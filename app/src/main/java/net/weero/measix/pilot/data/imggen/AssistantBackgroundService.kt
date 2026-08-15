package net.weero.measix.pilot.data.imggen

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ManagedLocalArtifactStore
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.collectFileUrlStrings
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.GenMediaRepository

data class BackgroundUpdateResult(
    val requested: Boolean,
    val updated: Boolean,
    val reason: String? = null,
    val cleanupPending: Boolean = false,
)

class AssistantBackgroundService(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val artifactStore: ManagedLocalArtifactStore,
    private val conversationRepository: ConversationRepository,
    private val genMediaRepository: GenMediaRepository,
) {
    suspend fun replaceBackground(
        assistantId: Uuid,
        source: File,
        mimeType: String,
    ): BackgroundUpdateResult {
        val copy = try {
            artifactStore.copyFile(
                source = source,
                mimeType = mimeType,
                displayName = source.name,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "failed to copy background", error)
            return BackgroundUpdateResult(
                requested = true,
                updated = false,
                reason = "background_copy_failed",
            )
        }
        val newUri = copy.fileUri(context.filesDir)
        var previousBackground: String? = null
        val found = try {
            var updated = false
            settingsStore.updateAtomic { settings ->
                val index = settings.assistants.indexOfFirst { it.id == assistantId }
                if (index < 0) return@updateAtomic settings
                updated = true
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
            updated
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                discardUncommittedBackgroundCopy(copy, newUri)
            }
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "failed to write background settings", error)
            withContext(NonCancellable) {
                discardUncommittedBackgroundCopy(copy, newUri)
            }
            return BackgroundUpdateResult(
                requested = true,
                updated = false,
                reason = "settings_write_failed",
            )
        }
        if (!found) {
            runCatching { artifactStore.delete(copy) }
            return BackgroundUpdateResult(
                requested = true,
                updated = false,
                reason = "assistant_not_found",
            )
        }
        val cleanupPending = try {
            !cleanupUnreferencedLocalBackground(
                previousBackground,
                protectedUris = setOf(newUri),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "old background cleanup deferred", error)
            true
        }
        return BackgroundUpdateResult(
            requested = true,
            updated = true,
            cleanupPending = cleanupPending,
        )
    }

    private suspend fun discardUncommittedBackgroundCopy(
        copy: LocalArtifactRef,
        newUri: String,
    ) {
        val settings = settingsStore.settingsFlow.value
        val alreadyPublished = settings.assistants.any { it.background == newUri }
        if (!alreadyPublished) {
            runCatching { artifactStore.delete(copy) }
        }
    }

    suspend fun cleanupUnreferencedLocalBackground(
        backgroundUri: String?,
        protectedUris: Set<String> = emptySet(),
    ): Boolean {
        if (backgroundUri.isNullOrBlank()) return true
        if (backgroundUri in protectedUris) return true
        val file = localFileFromUri(backgroundUri) ?: return true
        val relative = FileUtils.getRelativePathInFilesDir(context.filesDir, file) ?: return true
        val fileUri = fileUri(file)
        val settings = settingsStore.settingsFlow.value
        if (isReferenced(relative, fileUri, settings)) return true
        return runCatching {
            artifactStore.delete(
                LocalArtifactRef(relativePath = relative, mimeType = "application/octet-stream"),
            )
            if (file.exists()) file.delete()
            true
        }.getOrElse {
            Log.w(TAG, "failed to cleanup old background $relative", it)
            false
        }
    }

    private suspend fun isReferenced(
        relativePath: String,
        fileUri: String,
        settings: Settings,
    ): Boolean {
        if (settings.assistants.any { assistant ->
                assistant.background == fileUri ||
                    (assistant.avatar is Avatar.Image && assistant.avatar.url == fileUri)
            }
        ) {
            return true
        }
        val media = genMediaRepository.getAllMediaList()
        if (media.any { entity ->
                entity.path == relativePath ||
                    entity.sourcePaths?.contains(fileUri) == true ||
                    entity.sourcePaths?.contains(relativePath) == true
            }
        ) {
            return true
        }
        val conversations = conversationRepository.getAllTopLevelConversationsSync() +
            conversationRepository.getAllChildConversationIds().mapNotNull { id ->
                conversationRepository.getConversationById(id)
            }
        return conversations.any { conversation ->
            conversation.messageNodes.flatMap { it.messages }.collectFileUrlStrings().any { it == fileUri }
        }
    }

    companion object {
        private const val TAG = "AssistantBackgroundService"
    }
}

internal fun localFileFromUri(value: String): File? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("file:")) return null
    val withoutScheme = trimmed.removePrefix("file:")
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
