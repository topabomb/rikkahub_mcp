package net.weero.measix.pilot.data.imggen

import android.util.Log
import androidx.paging.PagingSource
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.ImageGenerationItem
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.AssetFileNames
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.repository.GenMediaRepository

enum class GeneratedMediaConsumer {
    CHAT_TOOL_RESULT,
}

/** Domain kind mapped to the existing stable Room string only inside the media owner. */
enum class GeneratedMediaKind(internal val persistedValue: String) {
    GENERATION(GenMediaEntity.TYPE_IMAGE_GENERATION),
    EDIT(GenMediaEntity.TYPE_IMAGE_EDIT),
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
    val chatArtifact: OwnedArtifact? = null,
)

data class GeneratedMediaStorageStats(
    val count: Int,
    val sizeBytes: Long,
)

/** GeneratedMedia owner 的范围清理结果；不与 Artifact lifecycle 结果互相依赖。 */
data class GeneratedMediaCleanupResult(
    val deleted: Int,
    val cleanupPending: Int,
    val failed: Int,
)

private sealed interface GeneratedMediaDeleteResult {
    data object Completed : GeneratedMediaDeleteResult
    data object CleanupPending : GeneratedMediaDeleteResult
    data object Failed : GeneratedMediaDeleteResult
}

class GeneratedMediaStore(
    private val filesDir: File,
    private val genMediaRepository: GenMediaRepository,
    private val artifactStore: ArtifactStore,
    private val deleteCommittedPayload: (File) -> Boolean = File::delete,
    private val fileNameCandidates: () -> List<String> = AssetFileNames::candidates,
) {
    private val persistMutex = Mutex()

    suspend fun <T> withPersistLock(block: suspend () -> T): T = persistMutex.withLock { block() }

    suspend fun commit(
        item: ImageGenerationItem,
        prompt: String,
        modelLabel: String,
        kind: GeneratedMediaKind = GeneratedMediaKind.GENERATION,
        sourcePaths: String? = null,
        consumerPlan: GeneratedMediaConsumerPlan = GeneratedMediaConsumerPlan.NONE,
    ): CommittedGeneratedMedia = withPersistLock {
        commitLocked(item, prompt, modelLabel, kind, sourcePaths, consumerPlan)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun commitLocked(
        item: ImageGenerationItem,
        prompt: String,
        modelLabel: String,
        kind: GeneratedMediaKind,
        sourcePaths: String?,
        consumerPlan: GeneratedMediaConsumerPlan,
    ): CommittedGeneratedMedia {
        val durableCommit = AtomicReference<CommittedGeneratedMedia?>()
        try {
            return withContext(Dispatchers.IO) {
                val bytes = decodeImageBytes(item.data)
                val inspected = inspectImagePayload(bytes, item.mimeType)
                val mimeType = inspected.mimeType
                val extension = inspected.extension
                val imagesDir = File(filesDir, IMAGES_DIR).apply { mkdirs() }
                val fileName = availableFileName(imagesDir, extension)
                val pending = File(imagesDir, "$fileName.pending")
                val finalFile = File(imagesDir, fileName)
                check(pending.createNewFile()) { "Generated media pending collision: $fileName" }
                var finalPublished = false
                var chatArtifact: OwnedArtifact? = null
                try {
                    pending.writeBytes(bytes)
                    check(!finalFile.exists()) { "Generated media target already exists: $fileName" }
                    check(pending.renameTo(finalFile)) { "Failed to atomically publish generated media: $fileName" }
                    finalPublished = true
                    if (GeneratedMediaConsumer.CHAT_TOOL_RESULT in consumerPlan.consumers) {
                        // 生成媒体在聊天域的副本——诞生方式为生成派生
                        chatArtifact = artifactStore.copyFile(
                            source = finalFile,
                            mimeType = mimeType,
                            displayName = fileName,
                            origin = ArtifactOrigin.GENERATED,
                        )
                    }
                    val relativePath = "$IMAGES_DIR/${finalFile.name}"
                    val mediaId = genMediaRepository.insertMedia(
                        GenMediaEntity(
                            path = relativePath,
                            modelId = modelLabel,
                            prompt = prompt,
                            createAt = System.currentTimeMillis(),
                            type = kind.persistedValue,
                            sourcePaths = sourcePaths,
                        )
                    )
                    CommittedGeneratedMedia(
                        mediaId = mediaId,
                        canonicalRelativePath = relativePath,
                        canonicalFile = finalFile,
                        mimeType = mimeType,
                        chatArtifact = chatArtifact,
                    ).also(durableCommit::set)
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        discardUnpublishedChatCopy(chatArtifact, "generated media rollback", error)
                        if (pending.exists() && !pending.delete()) {
                            error.addSuppressed(IllegalStateException("Failed to remove pending generated media: $pending"))
                        }
                        if (finalPublished && finalFile.exists() && !finalFile.delete()) {
                            error.addSuppressed(IllegalStateException("Failed to remove generated media: $finalFile"))
                        }
                    }
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                discardUnpublishedChatCopy(
                    durableCommit.get()?.chatArtifact,
                    "cancelled generated-media consumer",
                    cancelled,
                )
            }
            throw cancelled
        }
    }

    private suspend fun discardUnpublishedChatCopy(
        owned: OwnedArtifact?,
        operation: String,
        primary: Throwable,
    ) {
        if (owned == null) return
        try {
            artifactStore.discardUnpublished(owned).requireDiscarded(operation)
        } catch (cleanupFailure: Throwable) {
            primary.addSuppressed(cleanupFailure)
        }
    }

    private suspend fun availableFileName(imagesDir: File, extension: String): String {
        val stems = fileNameCandidates()
        require(stems.size == 4) { "Asset naming requires exactly four candidates" }
        suspend fun occupied(name: String): Boolean =
            genMediaRepository.existsByPath("$IMAGES_DIR/$name") ||
                File(imagesDir, name).exists() ||
                File(imagesDir, "$name$PENDING_SUFFIX").exists() ||
                File(imagesDir, "$name$DELETING_SUFFIX").exists()
        val names = stems.map { AssetFileNames.fileName(it, extension) }.distinct()
        for (name in names) {
            if (!occupied(name)) return name
        }
        var ordinal = 2
        var candidate = AssetFileNames.fileName(stems.first(), extension, ordinal)
        while (occupied(candidate)) {
            candidate = AssetFileNames.fileName(stems.first(), extension, ++ordinal)
        }
        return candidate
    }

    suspend fun delete(id: Int): Boolean = withPersistLock {
        withContext(Dispatchers.IO) {
            val entity = genMediaRepository.getMediaById(id) ?: return@withContext false
            deleteEntityLocked(entity) != GeneratedMediaDeleteResult.Failed
        }
    }

    /**
     * 按时间范围清理：在 persist lock 内按 create_at 取候选，逐项复用 [deleteEntityLocked]。
     * 结构化结果，部分成功不压成 Boolean；取消在项目边界传播，已取得单项所有权后的清理由
     * [deleteEntityLocked] 的既有协议收口。
     */
    suspend fun deleteCreatedBefore(cutoff: Long): GeneratedMediaCleanupResult = withPersistLock {
        withContext(Dispatchers.IO) {
            val entities = genMediaRepository.listCreatedBefore(cutoff)
            var deleted = 0
            var cleanupPending = 0
            var failed = 0
            entities.forEach { entity ->
                when (deleteEntityLocked(entity)) {
                    GeneratedMediaDeleteResult.Completed -> deleted++
                    GeneratedMediaDeleteResult.CleanupPending -> cleanupPending++
                    GeneratedMediaDeleteResult.Failed -> failed++
                }
            }
            GeneratedMediaCleanupResult(deleted, cleanupPending, failed)
        }
    }

    /** 全部清理 = 同一个范围删除协议；页面不再直调本方法，统一走 application command。 */
    suspend fun deleteAll(): Boolean = deleteCreatedBefore(Long.MAX_VALUE).let { result ->
        result.failed == 0 && result.cleanupPending == 0
    }

    /** 只读投影：设置页通过 query port 消费，不把实体当页面协议。 */
    fun observe(): Flow<List<GenMediaEntity>> = genMediaRepository.observeAllMedia()

    /** Gallery paging remains owned by this store; UI consumes only the query-port projection. */
    fun pagingSource(): PagingSource<Int, GenMediaEntity> = genMediaRepository.getAllMedia()

    /** 范围清理确认对话框的候选计数（只读提示；真实清理在 persist lock 内重新快照）。 */
    suspend fun candidateCount(cutoff: Long): Int =
        genMediaRepository.listCreatedBefore(cutoff).size

    private suspend fun deleteEntityLocked(entity: GenMediaEntity): GeneratedMediaDeleteResult {
        val original = canonicalFile(entity)
        if (original.name.endsWith(PENDING_SUFFIX) || original.name.endsWith(DELETING_SUFFIX)) {
            return GeneratedMediaDeleteResult.Failed
        }
        if (original.exists() && !original.isFile) return GeneratedMediaDeleteResult.Failed
        val deleting = File(original.parentFile, "${original.name}$DELETING_SUFFIX")
        if (deleting.exists()) return GeneratedMediaDeleteResult.Failed
        if (original.exists() && !original.renameTo(deleting)) return GeneratedMediaDeleteResult.Failed
        try {
            genMediaRepository.deleteMedia(entity.id)
        } catch (cancelled: CancellationException) {
            restoreDeletingFile(original, deleting)?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: Exception) {
            restoreDeletingFile(original, deleting)?.let(error::addSuppressed)
            Log.e(TAG, "failed to delete gallery row ${entity.id}", error)
            return GeneratedMediaDeleteResult.Failed
        }
        return if (deleting.exists() && !deleteCommittedPayload(deleting)) {
            Log.w(TAG, "gallery row deleted; payload cleanup deferred: $deleting")
            GeneratedMediaDeleteResult.CleanupPending
        } else {
            GeneratedMediaDeleteResult.Completed
        }
    }

    private fun restoreDeletingFile(original: File, deleting: File): Throwable? {
        if (!deleting.exists()) return null
        if (deleting.renameTo(original)) return null
        return IllegalStateException("Failed to restore generated media after row deletion failure: $deleting")
    }

    suspend fun countCommitted(): GeneratedMediaStorageStats = withContext(Dispatchers.IO) {
        val imagesDir = File(filesDir, IMAGES_DIR)
        val files = imagesDir.listFiles().orEmpty().filter { file ->
            file.isFile &&
                !file.name.endsWith(PENDING_SUFFIX) &&
                !file.name.endsWith(DELETING_SUFFIX)
        }
        GeneratedMediaStorageStats(
            count = files.size,
            sizeBytes = files.sumOf { it.length() },
        )
    }

    fun resolveCanonicalFile(entity: GenMediaEntity): File = canonicalFile(entity)

    fun isManagedFile(file: File): Boolean = runCatching {
        val imagesDir = File(filesDir, IMAGES_DIR).canonicalFile
        val target = file.canonicalFile
        target.path.startsWith(imagesDir.path + File.separator)
    }.getOrDefault(false)

    private fun canonicalFile(entity: GenMediaEntity): File {
        val normalized = entity.path.replace('\\', '/')
        require(normalized.startsWith("$IMAGES_DIR/")) {
            "Generated-media payload is outside its managed domain: ${entity.path}"
        }
        val imagesDir = File(filesDir, IMAGES_DIR).canonicalFile
        val target = File(imagesDir, normalized.removePrefix("$IMAGES_DIR/")).canonicalFile
        require(target.path.startsWith(imagesDir.path + File.separator)) {
            "Generated-media payload escapes its managed domain: ${entity.path}"
        }
        return target
    }

    suspend fun reconcile(nowMs: Long = System.currentTimeMillis()) = withPersistLock {
        withContext(Dispatchers.IO) {
            val imagesDir = File(filesDir, IMAGES_DIR)
            val records = genMediaRepository.getAllMediaList()
            val recordedNames = records.mapTo(mutableSetOf()) { entity ->
                canonicalFile(entity).relativeTo(imagesDir.canonicalFile).invariantSeparatorsPath
            }
            imagesDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val age = nowMs - file.lastModified()
                if (file.name.endsWith(PENDING_SUFFIX)) {
                    if (age >= PROTECTION_MS) {
                        check(file.delete()) { "Failed to remove stale generated-media staging file: $file" }
                    }
                    return@forEach
                }
                if (file.name.endsWith(DELETING_SUFFIX)) {
                    val originalName = file.name.removeSuffix(DELETING_SUFFIX)
                    if (originalName in recordedNames) {
                        val original = File(imagesDir, originalName)
                        check(original.exists() || file.renameTo(original)) {
                            "Failed to restore interrupted generated-media deletion: $file"
                        }
                        if (original.exists() && file.exists()) {
                            check(file.delete()) { "Failed to remove duplicate generated-media tombstone: $file" }
                        }
                    } else {
                        check(file.delete()) { "Failed to finish generated-media deletion: $file" }
                    }
                    return@forEach
                }
                if (file.name !in recordedNames && age >= PROTECTION_MS) {
                    check(file.delete()) { "Failed to remove untracked generated-media file: $file" }
                }
            }
            records.forEach { entity ->
                val file = canonicalFile(entity)
                if (!file.isFile) {
                    genMediaRepository.deleteMedia(entity.id)
                }
            }
        }
    }

    companion object {
        const val IMAGES_DIR = "images"
        const val PENDING_SUFFIX = ".pending"
        const val DELETING_SUFFIX = ".deleting"
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
            require(payload.isNotEmpty() && payload.length <= MAX_BASE64_IMAGE_CHARS) {
                "encoded image payload exceeds the size limit"
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
            return when (detectImageMimeBySignature(bytes)) {
                "image/png" -> "image/png".takeIf { isValidPngContainer(bytes) }
                "image/jpeg" -> "image/jpeg".takeIf { isValidJpegContainer(bytes) }
                "image/gif" -> "image/gif".takeIf { isValidGifContainer(bytes) }
                "image/webp" -> "image/webp".takeIf { isValidWebpContainer(bytes) }
                else -> null
            }
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

        private const val MAX_BASE64_IMAGE_CHARS = (MAX_IMAGE_BYTES * 4 / 3) + 16

        private fun isValidPngContainer(bytes: ByteArray): Boolean {
            if (!startsWith(bytes, PNG_SIGNATURE)) return false
            var offset = PNG_SIGNATURE.size
            var first = true
            var hasImageData = false
            while (offset + 12 <= bytes.size) {
                val length = readInt32(bytes, offset) ?: return false
                if (length < 0 || length > bytes.size - offset - 12) return false
                val typeOffset = offset + 4
                val dataOffset = typeOffset + 4
                val crcOffset = dataOffset + length
                val type = bytes.copyOfRange(typeOffset, dataOffset).toString(Charsets.US_ASCII)
                val crc = CRC32().apply { update(bytes, typeOffset, 4 + length) }.value
                if (crc != readUInt32(bytes, crcOffset)) return false
                if (first) {
                    if (type != "IHDR" || length != 13) return false
                    val width = readInt32(bytes, dataOffset) ?: return false
                    val height = readInt32(bytes, dataOffset + 4) ?: return false
                    if (width <= 0 || height <= 0) return false
                    first = false
                }
                if (type == "IDAT") hasImageData = true
                offset = crcOffset + 4
                if (type == "IEND") return length == 0 && hasImageData && offset == bytes.size
            }
            return false
        }

        private fun isValidJpegContainer(bytes: ByteArray): Boolean {
            if (!startsWith(bytes, JPEG_SIGNATURE) || bytes.size < 6) return false
            var offset = 2
            var hasDimensions = false
            while (offset + 1 < bytes.size) {
                if ((bytes[offset].toInt() and 0xFF) != 0xFF) return false
                while (offset < bytes.size && (bytes[offset].toInt() and 0xFF) == 0xFF) offset++
                if (offset >= bytes.size) return false
                val marker = bytes[offset++].toInt() and 0xFF
                if (marker == 0xD9) return hasDimensions
                if (marker == 0x01 || marker in 0xD0..0xD7) continue
                if (offset + 2 > bytes.size) return false
                val length = readUInt16(bytes, offset)
                if (length < 2 || offset + length > bytes.size) return false
                if (marker in JPEG_SOF_MARKERS) {
                    if (length < 7) return false
                    val height = readUInt16(bytes, offset + 3)
                    val width = readUInt16(bytes, offset + 5)
                    if (width <= 0 || height <= 0) return false
                    hasDimensions = true
                }
                offset += length
                if (marker == 0xDA) {
                    if (!hasDimensions) return false
                    return bytes.size >= 2 &&
                        (bytes[bytes.lastIndex - 1].toInt() and 0xFF) == 0xFF &&
                        (bytes.last().toInt() and 0xFF) == 0xD9
                }
            }
            return false
        }

        private fun isValidGifContainer(bytes: ByteArray): Boolean {
            if (bytes.size < 14 ||
                (!startsWith(bytes, GIF87_SIGNATURE) && !startsWith(bytes, GIF89_SIGNATURE))
            ) return false
            val width = readUInt16LittleEndian(bytes, 6)
            val height = readUInt16LittleEndian(bytes, 8)
            return width > 0 && height > 0 &&
                bytes.last() == 0x3B.toByte() &&
                (13 until bytes.lastIndex).any { bytes[it] == 0x2C.toByte() }
        }

        private fun isValidWebpContainer(bytes: ByteArray): Boolean {
            if (!isWebp(bytes) || bytes.size < 30) return false
            val declared = readUInt32LittleEndian(bytes, 4) ?: return false
            if (declared + 8L != bytes.size.toLong()) return false
            val chunks = parseWebpChunks(bytes, 12, bytes.size) ?: return false
            val first = chunks.firstOrNull() ?: return false
            return when (first.type) {
                "VP8 " -> chunks.size == 1 && isValidVp8Chunk(bytes, first)
                "VP8L" -> chunks.size == 1 && isValidVp8lChunk(bytes, first)
                "VP8X" -> isValidExtendedWebp(bytes, chunks)
                else -> false
            }
        }

        private data class WebpChunk(
            val type: String,
            val dataOffset: Int,
            val size: Int,
        )

        private fun parseWebpChunks(bytes: ByteArray, start: Int, end: Int): List<WebpChunk>? {
            if (start !in 0..end || end > bytes.size) return null
            val chunks = mutableListOf<WebpChunk>()
            var offset = start
            while (offset < end) {
                if (offset + 8 > end) return null
                val sizeLong = readUInt32LittleEndian(bytes, offset + 4) ?: return null
                if (sizeLong > Int.MAX_VALUE) return null
                val size = sizeLong.toInt()
                val dataOffset = offset + 8
                val dataEnd = dataOffset.toLong() + sizeLong
                val paddedEnd = dataEnd + (sizeLong and 1L)
                if (paddedEnd > end.toLong()) return null
                chunks += WebpChunk(
                    type = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII),
                    dataOffset = dataOffset,
                    size = size,
                )
                offset = paddedEnd.toInt()
            }
            return chunks.takeIf { offset == end }
        }

        private fun isValidVp8Chunk(bytes: ByteArray, chunk: WebpChunk): Boolean {
            val offset = chunk.dataOffset
            return chunk.size >= 10 &&
                bytes[offset + 3] == 0x9D.toByte() &&
                bytes[offset + 4] == 0x01.toByte() &&
                bytes[offset + 5] == 0x2A.toByte() &&
                (readUInt16LittleEndian(bytes, offset + 6) and 0x3FFF) > 0 &&
                (readUInt16LittleEndian(bytes, offset + 8) and 0x3FFF) > 0
        }

        private fun isValidVp8lChunk(bytes: ByteArray, chunk: WebpChunk): Boolean {
            if (chunk.size < 5 || bytes[chunk.dataOffset] != 0x2F.toByte()) return false
            val packed = readUInt32LittleEndian(bytes, chunk.dataOffset + 1) ?: return false
            val width = (packed and 0x3FFFL) + 1L
            val height = ((packed shr 14) and 0x3FFFL) + 1L
            return width > 0L && height > 0L
        }

        private fun isValidExtendedWebp(bytes: ByteArray, chunks: List<WebpChunk>): Boolean {
            val header = chunks.first()
            if (header.size != 10) return false
            val flags = bytes[header.dataOffset].toInt() and 0xFF
            if ((flags and 0xC1) != 0) return false
            val canvasWidth = readUInt24LittleEndian(bytes, header.dataOffset + 4) + 1
            val canvasHeight = readUInt24LittleEndian(bytes, header.dataOffset + 7) + 1
            if (canvasWidth <= 0 || canvasHeight <= 0) return false

            val payloadChunks = chunks.drop(1)
            val animated = (flags and 0x02) != 0
            return if (animated) {
                payloadChunks.any { it.type == "ANIM" && it.size == 6 } &&
                    payloadChunks.any { it.type == "ANMF" && isValidAnimatedWebpFrame(bytes, it) }
            } else {
                payloadChunks.none { it.type == "ANIM" || it.type == "ANMF" } &&
                    payloadChunks.any { chunk ->
                        when (chunk.type) {
                            "VP8 " -> isValidVp8Chunk(bytes, chunk)
                            "VP8L" -> isValidVp8lChunk(bytes, chunk)
                            else -> false
                        }
                    }
            }
        }

        private fun isValidAnimatedWebpFrame(bytes: ByteArray, frame: WebpChunk): Boolean {
            if (frame.size < 24) return false
            val frameWidth = readUInt24LittleEndian(bytes, frame.dataOffset + 6) + 1
            val frameHeight = readUInt24LittleEndian(bytes, frame.dataOffset + 9) + 1
            if (frameWidth <= 0 || frameHeight <= 0) return false
            val nestedStart = frame.dataOffset + 16
            val nestedEnd = frame.dataOffset + frame.size
            val nested = parseWebpChunks(bytes, nestedStart, nestedEnd) ?: return false
            return nested.any { chunk ->
                when (chunk.type) {
                    "VP8 " -> isValidVp8Chunk(bytes, chunk)
                    "VP8L" -> isValidVp8lChunk(bytes, chunk)
                    else -> false
                }
            }
        }

        private fun readInt32(bytes: ByteArray, offset: Int): Int? {
            if (offset < 0 || offset + 4 > bytes.size) return null
            return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }

        private fun readUInt32(bytes: ByteArray, offset: Int): Long =
            readInt32(bytes, offset)?.toLong()?.and(0xFFFF_FFFFL) ?: -1L

        private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long? {
            if (offset < 0 || offset + 4 > bytes.size) return null
            return (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
        }

        private fun readUInt16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readUInt24LittleEndian(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)

        private val JPEG_SOF_MARKERS = setOf(
            0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
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
