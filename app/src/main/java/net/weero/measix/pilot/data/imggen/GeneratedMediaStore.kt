package net.weero.measix.pilot.data.imggen

import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.ImageGenerationItem
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ManagedLocalArtifactStore
import net.weero.measix.pilot.data.repository.GenMediaRepository

enum class GeneratedMediaConsumer {
    CHAT_TOOL_RESULT,
}

data class GeneratedMediaConsumerPlan(
    val consumers: Set<GeneratedMediaConsumer> = emptySet(),
) {
    companion object {
        val NONE = GeneratedMediaConsumerPlan()
        val CHAT_TOOL_RESULT = GeneratedMediaConsumerPlan(setOf(GeneratedMediaConsumer.CHAT_TOOL_RESULT))
    }
}

data class CommittedGeneratedMedia(
    val mediaId: Long,
    val canonicalRelativePath: String,
    val canonicalFile: File,
    val mimeType: String,
    val chatArtifact: LocalArtifactRef? = null,
)

data class GeneratedMediaStorageStats(
    val count: Int,
    val sizeBytes: Long,
)

class GeneratedMediaStore(
    private val filesDir: File,
    private val genMediaRepository: GenMediaRepository,
    private val artifactStore: ManagedLocalArtifactStore,
) {
    private val persistMutex = Mutex()

    suspend fun <T> withPersistLock(block: suspend () -> T): T = persistMutex.withLock { block() }

    suspend fun commit(
        item: ImageGenerationItem,
        prompt: String,
        modelLabel: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
        consumerPlan: GeneratedMediaConsumerPlan = GeneratedMediaConsumerPlan.NONE,
    ): CommittedGeneratedMedia = withPersistLock {
        commitLocked(item, prompt, modelLabel, type, sourcePaths, consumerPlan)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun commitLocked(
        item: ImageGenerationItem,
        prompt: String,
        modelLabel: String,
        type: String,
        sourcePaths: String?,
        consumerPlan: GeneratedMediaConsumerPlan,
    ): CommittedGeneratedMedia = withContext(Dispatchers.IO) {
        val bytes = decodeImageBytes(item.data)
        val inspected = inspectImagePayload(bytes, item.mimeType)
        val mimeType = inspected.mimeType
        val extension = inspected.extension
        val imagesDir = File(filesDir, IMAGES_DIR).apply { mkdirs() }
        val fileName = "${Uuid.random()}.$extension"
        val pending = File(imagesDir, "$fileName.pending")
        val finalFile = File(imagesDir, fileName)
        var chatArtifact: LocalArtifactRef? = null
        var insertedId: Long? = null
        try {
            pending.writeBytes(bytes)
            if (!pending.renameTo(finalFile)) {
                pending.copyTo(finalFile, overwrite = true)
                pending.delete()
            }
            if (GeneratedMediaConsumer.CHAT_TOOL_RESULT in consumerPlan.consumers) {
                chatArtifact = artifactStore.copyFile(
                    source = finalFile,
                    mimeType = mimeType,
                    displayName = fileName,
                )
            }
            val relativePath = "$IMAGES_DIR/${finalFile.name}"
            val mediaId = genMediaRepository.insertMedia(
                GenMediaEntity(
                    path = relativePath,
                    modelId = modelLabel,
                    prompt = prompt,
                    createAt = System.currentTimeMillis(),
                    type = type,
                    sourcePaths = sourcePaths,
                )
            )
            insertedId = mediaId
            CommittedGeneratedMedia(
                mediaId = mediaId,
                canonicalRelativePath = relativePath,
                canonicalFile = finalFile,
                mimeType = mimeType,
                chatArtifact = chatArtifact,
            )
        } catch (error: Throwable) {
            if (error is CancellationException && insertedId != null) {
                throw error
            }
            withContext(NonCancellable) {
                chatArtifact?.let { runCatching { artifactStore.delete(it) } }
                runCatching { pending.delete() }
                runCatching { finalFile.delete() }
            }
            throw error
        }
    }

    suspend fun delete(id: Int): Boolean = withPersistLock {
        withContext(Dispatchers.IO) {
            val entity = genMediaRepository.getMediaById(id) ?: return@withContext false
            val file = canonicalFile(entity)
            if (file.name.endsWith(PENDING_SUFFIX)) return@withContext false
            if (file.exists() && !file.delete()) return@withContext false
            try {
                genMediaRepository.deleteMedia(entity.id)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "failed to delete gallery row ${entity.id}", error)
                false
            }
        }
    }

    suspend fun deleteAll(): Boolean = withPersistLock {
        withContext(Dispatchers.IO) {
            val imagesDir = File(filesDir, IMAGES_DIR)
            var allDeleted = true
            genMediaRepository.getAllMediaList().forEach { entity ->
                val file = canonicalFile(entity)
                if (file.name.endsWith(PENDING_SUFFIX)) return@forEach
                if (file.exists() && !file.delete()) {
                    allDeleted = false
                    return@forEach
                }
                try {
                    genMediaRepository.deleteMedia(entity.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(TAG, "failed to delete gallery row ${entity.id}", error)
                    allDeleted = false
                }
            }
            imagesDir.listFiles()?.forEach { file ->
                if (!file.isFile || file.name.endsWith(PENDING_SUFFIX)) return@forEach
                if (!file.delete()) allDeleted = false
            }
            allDeleted
        }
    }

    suspend fun countCommitted(): GeneratedMediaStorageStats = withContext(Dispatchers.IO) {
        val imagesDir = File(filesDir, IMAGES_DIR)
        val files = imagesDir.listFiles().orEmpty().filter { file ->
            file.isFile && !file.name.endsWith(PENDING_SUFFIX)
        }
        GeneratedMediaStorageStats(
            count = files.size,
            sizeBytes = files.sumOf { it.length() },
        )
    }

    fun resolveCanonicalFile(entity: GenMediaEntity): File = canonicalFile(entity)

    private fun canonicalFile(entity: GenMediaEntity): File {
        val imagesDir = File(filesDir, IMAGES_DIR)
        return File(imagesDir, entity.path.removePrefix("$IMAGES_DIR/"))
    }

    suspend fun reconcile(nowMs: Long = System.currentTimeMillis()) = withPersistLock {
        withContext(Dispatchers.IO) {
            val imagesDir = File(filesDir, IMAGES_DIR)
            val records = genMediaRepository.getAllMediaList()
            val recordedNames = records.mapTo(mutableSetOf()) { entity ->
                entity.path.removePrefix("$IMAGES_DIR/")
            }
            imagesDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val age = nowMs - file.lastModified()
                if (file.name.endsWith(PENDING_SUFFIX)) {
                    if (age >= PROTECTION_MS) {
                        file.delete()
                    }
                    return@forEach
                }
                if (file.name !in recordedNames && age >= PROTECTION_MS) {
                    file.delete()
                }
            }
            records.forEach { entity ->
                val file = File(imagesDir, entity.path.removePrefix("$IMAGES_DIR/"))
                if (!file.isFile) {
                    runCatching { genMediaRepository.deleteMedia(entity.id) }
                }
            }
        }
    }

    companion object {
        const val IMAGES_DIR = "images"
        const val PENDING_SUFFIX = ".pending"
        const val PROTECTION_MS = 10 * 60 * 1000L
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val TAG = "GeneratedMediaStore"

        data class InspectedImage(
            val mimeType: String,
            val extension: String,
        )

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val GIF87_SIGNATURE = "GIF87a".toByteArray()
        private val GIF89_SIGNATURE = "GIF89a".toByteArray()
        private val WEBP_RIFF = "RIFF".toByteArray()
        private val WEBP_WEBP = "WEBP".toByteArray()

        @OptIn(ExperimentalEncodingApi::class)
        fun decodeImageBytes(data: String): ByteArray {
            val payload = if (data.startsWith("data:image")) {
                data.substringAfter("base64,")
            } else {
                data
            }
            return Base64.decode(payload)
        }

        fun inspectImagePayload(bytes: ByteArray, declaredMime: String): InspectedImage {
            if (bytes.isEmpty()) error("empty image payload")
            if (bytes.size > MAX_IMAGE_BYTES) error("image payload exceeds $MAX_IMAGE_BYTES bytes")
            val detected = detectImageMime(bytes)
                ?: error("payload is not a supported image")
            val declared = normalizeMimeType(declaredMime)
            val mimeType = when {
                declared == detected -> detected
                declared in SUPPORTED_IMAGE_MIMES && declared != detected -> detected
                declared !in SUPPORTED_IMAGE_MIMES -> detected
                else -> detected
            }
            return InspectedImage(
                mimeType = mimeType,
                extension = extensionForMime(mimeType),
            )
        }

        fun detectImageMime(bytes: ByteArray): String? {
            val signature = detectImageMimeBySignature(bytes)
            if (signature != null) return signature
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val outMime = bounds.outMimeType?.substringBefore(';')?.trim()?.lowercase()
            return outMime?.takeIf { it in SUPPORTED_IMAGE_MIMES && bounds.outWidth > 0 && bounds.outHeight > 0 }
        }

        fun detectImageMimeBySignature(bytes: ByteArray): String? = when {
            startsWith(bytes, PNG_SIGNATURE) -> "image/png"
            startsWith(bytes, JPEG_SIGNATURE) -> "image/jpeg"
            startsWith(bytes, GIF87_SIGNATURE) || startsWith(bytes, GIF89_SIGNATURE) -> "image/gif"
            isWebp(bytes) -> "image/webp"
            else -> null
        }

        fun normalizeMimeType(raw: String): String {
            val mime = raw.substringBefore(';').trim().lowercase()
            return when (mime) {
                "image/jpg" -> "image/jpeg"
                else -> mime
            }
        }

        fun extensionForMime(mimeType: String): String = when (normalizeMimeType(mimeType)) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/png" -> "png"
            else -> error("unsupported image mime $mimeType")
        }

        private val SUPPORTED_IMAGE_MIMES = setOf(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
        )

        private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
            if (bytes.size < prefix.size) return false
            return prefix.indices.all { bytes[it] == prefix[it] }
        }

        private fun isWebp(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            return startsWith(bytes, WEBP_RIFF) &&
                bytes[8] == WEBP_WEBP[0] &&
                bytes[9] == WEBP_WEBP[1] &&
                bytes[10] == WEBP_WEBP[2] &&
                bytes[11] == WEBP_WEBP[3]
        }
    }
}
