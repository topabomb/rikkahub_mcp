package net.weero.measix.pilot.service

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.ImageGenerationItem
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Application port 的跨分类结果；各 data owner 的内部结果不泄漏到 UI。 */
data class FileCleanupResult(
    val deleted: Int,
    val cleanupPending: Int,
    val skippedInProgress: Int,
    val failed: Int,
)

/** 托管文件的分类：上传与生成媒体走各自唯一 owner，页面只通过本 port 发命令。 */
enum class FileCleanupCategory {
    UPLOAD,
    GENERATED_IMAGES,
}

/** 范围：只允许 7/14/30 天或全部，其余值在构造时失败。 */
sealed interface FileCleanupRange {
    data object All : FileCleanupRange
    data class OlderThanDays(val days: Int) : FileCleanupRange {
        init {
            require(days == 7 || days == 14 || days == 30) {
                "unsupported cleanup range: $days days"
            }
        }
    }
}

/**
 * Combines authorized payload reads and file commands across the existing Artifact and GeneratedMedia owners.
 * Metadata projection remains in FileManagementQueryService; this port owns no durable files or rows.
 */
class FileManagementApplicationService internal constructor(
    private val artifactStore: ArtifactStore,
    private val generatedMediaStore: GeneratedMediaStore,
    private val recoveryGate: ApplicationRecoveryGate,
    private val sessions: EnterpriseSessionController,
    private val clock: Clock = Clock.System,
    private val writeGeneratedPreview: (File, ByteArray) -> Unit = { file, bytes ->
        file.writeBytes(bytes)
    },
) {
    fun imageSource(key: ManagedFileKey): ImageSource = createImageSource("managed", key) { }

    internal fun conversationImageSource(view: ConversationViewLease, artifactId: Long): ImageSource = createImageSource(
        "conversation:${view.imageReadIdentity}",
        ManagedFileKey.Artifact(artifactId, RealmSelection(view.access, view.selectionRevision)),
        view::requireOpen,
    )

    private fun createImageSource(contextIdentity: String, key: ManagedFileKey, requireOwner: () -> Unit): ImageSource = ImageSource(
        cacheIdentity = "$contextIdentity:$key",
        verifyAccess = { withImageAccess(key, requireOwner) {
            when (key) {
                is ManagedFileKey.Artifact -> artifactStore.requireImageAccess(key.selection.access.scope, key.artifactId)
                is ManagedFileKey.Generated -> generatedMediaStore.requireImageAccess(key.selection.access.scope, key.mediaId)
            }
        } },
        readPayload = { withImageAccess(key, requireOwner) {
            when (key) {
                is ManagedFileKey.Artifact -> artifactStore.readImage(key.selection.access.scope, key.artifactId)
                is ManagedFileKey.Generated -> generatedMediaStore.readImage(key.selection.access.scope, key.mediaId)
            }
        } },
    )

    private suspend fun <T> withImageAccess(key: ManagedFileKey, requireOwner: () -> Unit, action: suspend () -> T): T {
        recoveryGate.awaitReady()
        val result = sessions.withSelectedRealmSelection(key.selection) { requireOwner(); action() }
        return sessions.withSelectedRealmSelection(key.selection) { requireOwner(); result }
    }

    suspend fun cleanup(selection: RealmSelection, category: FileCleanupCategory, range: FileCleanupRange): FileCleanupResult {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(selection) {
            val cutoff = cutoffFor(range, clock.now().toEpochMilliseconds())
            when (category) {
                FileCleanupCategory.UPLOAD -> artifactStore.deleteUserRequestedFolderCreatedBefore(selection.access.scope, FileFolders.UPLOAD, cutoff).let { result ->
                    FileCleanupResult(
                        deleted = result.deleted,
                        cleanupPending = result.cleanupPending,
                        skippedInProgress = result.skippedInProgress,
                        failed = result.failed,
                    )
                }
                FileCleanupCategory.GENERATED_IMAGES -> generatedMediaStore.deleteCreatedBefore(selection.access.scope, cutoff).let { result ->
                    FileCleanupResult(
                        deleted = result.deleted,
                        cleanupPending = result.cleanupPending,
                        skippedInProgress = 0,
                        failed = result.failed,
                    )
                }
            }
        }
    }

    suspend fun deleteArtifact(key: ManagedFileKey.Artifact): ArtifactDeleteOutcome {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(key.selection) {
            artifactStore.deleteUserRequested(key.selection.access.scope, key.artifactId).toOutcome()
        }
    }

    suspend fun deleteGenerated(key: ManagedFileKey.Generated): Boolean {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(key.selection) {
            generatedMediaStore.delete(key.selection.access.scope, key.mediaId)
        }
    }

    suspend fun createGeneratedPreview(item: ImageGenerationItem, tempDirectory: File): File {
        var candidate: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val bytes = GeneratedMediaStore.decodeImageBytes(item.data)
                val inspected = GeneratedMediaStore.inspectImagePayload(bytes, item.mimeType)
                val preview = File(
                    tempDirectory,
                    "imggen_preview_${Uuid.random()}.${inspected.extension}",
                )
                candidate = preview
                writeGeneratedPreview(preview, bytes)
                preview
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                candidate?.let { preview ->
                    try {
                        if (preview.exists() && !preview.delete()) {
                            error.addSuppressed(IOException("failed to remove incomplete generated preview"))
                        }
                    } catch (cleanupError: Throwable) {
                        error.addSuppressed(cleanupError)
                    }
                }
            }
            throw error
        }
    }
}

internal fun cutoffFor(range: FileCleanupRange, nowMillis: Long): Long = when (range) {
    FileCleanupRange.All -> Long.MAX_VALUE
    is FileCleanupRange.OlderThanDays -> nowMillis - range.days.days.inWholeMilliseconds
}
