package net.weero.measix.pilot.data.imggen

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.service.ImageSource
import android.util.Log
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactStore
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
) {

    suspend fun replaceUserSelectedBackground(
        assistantId: ConfigurationReference,
        image: ImageSource,
    ): BackgroundUpdateResult = replaceBackground(assistantId) {
        val bytes = image.readBytes()
        val materialized = validatedBackground(bytes, image.displayName ?: "background") ?: return@replaceBackground null
        image.requireAccess()
        artifactStore.createFromBytes(ConfigurationScope.Personal, materialized.bytes, materialized.displayName,
            materialized.mimeType, origin = ArtifactOrigin.USER)
    }

    suspend fun replaceGeneratedBackground(
        assistantId: ConfigurationReference,
        source: File,
        mimeType: String,
    ): BackgroundUpdateResult = replaceBackground(assistantId) {
        artifactStore.copyFile(
            scope = ConfigurationScope.Personal,
            source = source,
            mimeType = mimeType,
            displayName = source.name,
            origin = ArtifactOrigin.GENERATED,
        )
    }

    /** 复制背景为设置域 artifact，并以同一 Settings 引用事务发布。 */
    private suspend fun replaceBackground(
        assistantId: ConfigurationReference,
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
