package net.weero.measix.pilot.data.imggen

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.ai.attachments.RemoteMediaFetchResult
import net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded

data class BackgroundUpdateResult(
    val requested: Boolean,
    val updated: Boolean,
    val reason: String? = null,
    val cleanupPending: Boolean = false,
)

class AssistantBackgroundService(
    private val artifactStore: ArtifactStore,
    context: Context,
    private val remoteMediaFetcher: SafeRemoteMediaFetcher,
) {
    private val appContext = context.applicationContext

    suspend fun replaceUserSelectedBackground(
        assistantId: Uuid,
        imageUrl: String,
    ): BackgroundUpdateResult = replaceBackground(assistantId) {
        createUserSelectedCopy(imageUrl)
    }

    suspend fun replaceGeneratedBackground(
        assistantId: Uuid,
        source: File,
        mimeType: String,
    ): BackgroundUpdateResult = replaceBackground(assistantId) {
        artifactStore.copyFile(
            source = source,
            mimeType = mimeType,
            displayName = source.name,
            origin = ArtifactOrigin.GENERATED,
        )
    }

    /** 复制背景为设置域 artifact，并以同一 Settings 引用事务发布。 */
    private suspend fun replaceBackground(
        assistantId: Uuid,
        createCopy: suspend () -> OwnedArtifact?,
    ): BackgroundUpdateResult {
        val copy = try {
            createCopy()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "failed to copy background", error)
            null
        }
        if (copy == null) {
            return BackgroundUpdateResult(
                requested = true,
                updated = false,
                reason = "background_copy_failed",
            )
        }
        val newUri = copy.uri.toString()
        var previousBackground: String? = null
        var assistantFound = false
        val committed = try {
            artifactStore.updateSettingsReferences { settings ->
                val index = settings.assistants.indexOfFirst { it.id == assistantId }
                if (index < 0) return@updateSettingsReferences settings
                assistantFound = true
                val current = settings.assistants[index]
                previousBackground = current.background
                settings.copy(
                    assistants = settings.assistants.toMutableList().also { list ->
                        list[index] = current.copy(
                            background = newUri,
                            useGradientBackground = false,
                        )
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                discardUncommittedBackgroundCopy(copy, newUri)?.let(cancelled::addSuppressed)
            }
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "failed to write background settings", error)
            val cleanupFailure =
            withContext(NonCancellable) {
                discardUncommittedBackgroundCopy(copy, newUri)
            }
            return BackgroundUpdateResult(
                requested = true,
                updated = false,
                reason = "settings_write_failed",
                cleanupPending = cleanupFailure != null,
            )
        }
        val backgroundCommitted = committed.assistants
            .find { it.id == assistantId }
            ?.background == newUri
        if (!assistantFound || !backgroundCommitted) {
            val cleanupFailure = discardUncommittedBackgroundCopy(copy, newUri)
            return BackgroundUpdateResult(
                requested = true,
                updated = false,
                reason = if (assistantFound) "settings_write_rejected" else "assistant_not_found",
                cleanupPending = cleanupFailure != null,
            )
        }
        val cleanupPending = try {
            !cleanupUnreferencedLocalBackground(
                previousBackground,
                protectedUris = setOf(newUri),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "old background cleanup deferred", error)
            true
        }
        return BackgroundUpdateResult(
            requested = true,
            updated = true,
            cleanupPending = cleanupPending,
        )
    }

    private suspend fun createUserSelectedCopy(imageUrl: String): OwnedArtifact? {
        val source = imageUrl.trim()
        val materialized = when {
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> fetchRemote(source)

            source.startsWith("data:", ignoreCase = true) -> decodeDataImage(source)
            else -> readLocalImage(source)
        } ?: return null
        return artifactStore.createFromBytes(
            bytes = materialized.bytes,
            displayName = materialized.displayName,
            mimeType = materialized.mimeType,
            origin = ArtifactOrigin.USER,
        )
    }

    private suspend fun fetchRemote(url: String): MaterializedBackground? = withContext(Dispatchers.IO) {
        when (val result = remoteMediaFetcher.fetch(url)) {
            is RemoteMediaFetchResult.Success -> MaterializedBackground(
                bytes = result.bytes,
                mimeType = result.mimeType,
                displayName = result.fileName,
            )

            is RemoteMediaFetchResult.Failure -> null
        }
    }

    private suspend fun decodeDataImage(value: String): MaterializedBackground? = withContext(Dispatchers.IO) {
        val header = value.substringBefore(',')
        if (!header.contains(";base64", ignoreCase = true)) return@withContext null
        val payload = value.substringAfter(',', missingDelimiterValue = "")
        if (payload.isEmpty() || payload.length > MAX_BASE64_IMAGE_CHARS) return@withContext null
        val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return@withContext null
        validatedBackground(bytes, "background")
    }

    private suspend fun readLocalImage(value: String): MaterializedBackground? = withContext(Dispatchers.IO) {
        val uri = localImageUri(value) ?: return@withContext null
        val input = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext null
        val bytes = input.use(::readImageBytes) ?: return@withContext null
        val declaredMime = FileUtils.getFileMimeType(appContext, uri)
        val displayName = FileUtils.getFileNameFromUri(appContext, uri)
            ?.takeIf(String::isNotBlank)
            ?: "background.${extensionForMime(declaredMime.orEmpty())}"
        validatedBackground(bytes, displayName)
    }

    private fun localImageUri(value: String): Uri? = when {
        value.startsWith("content:", ignoreCase = true) || value.startsWith("file:", ignoreCase = true) ->
            runCatching { Uri.parse(value) }.getOrNull()

        else -> File(value).takeIf(File::isFile)?.toUri()
    }

    private fun readImageBytes(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > GeneratedMediaStore.MAX_IMAGE_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf(ByteArray::isNotEmpty)
    }

    private fun validatedBackground(
        bytes: ByteArray,
        displayName: String,
    ): MaterializedBackground? {
        if (bytes.size > GeneratedMediaStore.MAX_IMAGE_BYTES || !ImageMime.isAcceptedImage(bytes)) {
            return null
        }
        val mimeType = ImageMime.sniff(bytes) ?: return null
        val baseName = displayName.substringBeforeLast('.').ifBlank { "background" }
        return MaterializedBackground(bytes, mimeType, "$baseName.${extensionForMime(mimeType)}")
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "png"
    }

    private suspend fun discardUncommittedBackgroundCopy(
        copy: OwnedArtifact,
        newUri: String,
    ): Throwable? = try {
        artifactStore.discardUnpublished(copy).requireDiscarded(
            context = "uncommitted background $newUri",
            allowAlreadyPublished = true,
        )
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "failed to discard uncommitted background $newUri", error)
        error
    }

    suspend fun cleanupUnreferencedLocalBackground(
        backgroundUri: String?,
        protectedUris: Set<String> = emptySet(),
    ): Boolean {
        if (backgroundUri.isNullOrBlank()) return true
        if (backgroundUri in protectedUris) return true
        val file = localFileFromUri(backgroundUri) ?: return true
        return try {
            artifactStore.collectGarbage(protectionWindowMillis = 0)
            !file.exists()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "failed to cleanup old background $backgroundUri", error)
            false
        }
    }

    companion object {
        private const val TAG = "AssistantBackgroundService"
        private const val MAX_BASE64_IMAGE_CHARS =
            (GeneratedMediaStore.MAX_IMAGE_BYTES * 4 / 3) + 16
    }
}

private data class MaterializedBackground(
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
)

internal fun localFileFromUri(value: String): File? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("file:", ignoreCase = true)) return null
    // The scheme check is case-insensitive; strip exactly five characters so FILE://
    // follows the same path normalization as the canonical lowercase form.
    val withoutScheme = trimmed.substring("file:".length)
    val path = when {
        withoutScheme.startsWith("///") -> {
            val rest = withoutScheme.removePrefix("//")
            if (rest.length >= 3 && rest[2] == ':') rest.removePrefix("/") else rest
        }
        withoutScheme.startsWith("//") -> withoutScheme.removePrefix("//")
        else -> withoutScheme
    }.replace('/', File.separatorChar)
    return path.takeIf { it.isNotBlank() }?.let(::File)
}

internal fun fileUri(file: File): String {
    val path = file.absolutePath.replace('\\', '/')
    return if (path.startsWith("/")) "file://$path" else "file:///$path"
}
