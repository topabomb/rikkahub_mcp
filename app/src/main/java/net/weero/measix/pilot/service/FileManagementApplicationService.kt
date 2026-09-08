package net.weero.measix.pilot.service

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    private val remoteMediaFetcher: net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher = net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher(),
) {
    fun imageSource(key: ManagedFileKey, displayName: String? = null, modifiedAtMillis: Long? = null): ImageSource =
        createImageSource("managed", key, displayName, modifiedAtMillis) { }

    internal fun conversationImageSource(view: ConversationViewLease, artifactId: Long, displayName: String? = null, modifiedAtMillis: Long? = null): ImageSource = createImageSource(
        "conversation:${view.imageReadIdentity}",
        ManagedFileKey.Artifact(artifactId, RealmSelection(view.access, view.selectionRevision)),
        displayName = displayName,
        modifiedAtMillis = modifiedAtMillis,
        requireOwner = view::requireOpen,
    )

    private fun createImageSource(contextIdentity: String, key: ManagedFileKey, displayName: String? = null, modifiedAtMillis: Long? = null, requireOwner: () -> Unit): ImageSource = ImageSource(
        cacheIdentity = "$contextIdentity:$key",
        origin = if (key is ManagedFileKey.Generated) ImageOrigin.GENERATED else ImageOrigin.UPLOAD,
        displayName = displayName,
        modifiedAtMillis = modifiedAtMillis,
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

    suspend fun resolveConfigurationImage(url: String): ImageSource? {
        recoveryGate.awaitReady()
        externalImageSource(url)?.let { return it }
        val file = net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.parseFileUrl(url) ?: return null
        val preview = artifactStore.resolveConfigurationImage(file) ?: return null
        return ImageSource(
            cacheIdentity = "configuration:${preview.artifactId}",
            origin = ImageOrigin.UPLOAD,
            displayName = preview.displayName,
            modifiedAtMillis = preview.modifiedAtMillis,
            verifyAccess = { recoveryGate.awaitReady(); artifactStore.requireConfigurationImageAccess(preview.artifactId) },
            readPayload = { recoveryGate.awaitReady(); artifactStore.readConfigurationImage(preview.artifactId) },
        )
    }

    fun externalImageSource(url: String): ImageSource? = externalImageSource(url, "shared") { }

    internal fun externalImageSource(view: ConversationViewLease, url: String): ImageSource? =
        externalImageSource(url, "conversation:${view.imageReadIdentity}") { requireImagePage(view) }

    private suspend fun requireImagePage(view: ConversationViewLease) {
        recoveryGate.awaitReady()
        sessions.withSelectedRealmSelection(RealmSelection(view.access, view.selectionRevision)) { view.requireOpen() }
    }

    suspend fun resolveConversationImage(view: ConversationViewLease, url: String): ImageSource? {
        requireImagePage(view)
        externalImageSource(view, url)?.let { return it }
        val preview = sessions.withSelectedRealmSelection(RealmSelection(view.access, view.selectionRevision)) {
            view.requireOpen()
            val file = when {
                url.startsWith("file:", ignoreCase = true) -> net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.parseFileUrl(url)
                net.weero.measix.pilot.data.files.LocalToolPath.parseUploadToolPath(url) != null -> artifactStore.resolveToolPath(url)
                else -> null
            } ?: return@withSelectedRealmSelection null
            artifactStore.resolveImagePreviewForFile(view.access.scope, file)
        }
        requireImagePage(view)
        return preview?.let { conversationImageSource(view, it.artifactId, it.displayName, it.modifiedAtMillis) }
    }

    private fun externalImageSource(url: String, contextIdentity: String, verify: suspend () -> Unit): ImageSource? {
        val inline = url.startsWith("data:image/", ignoreCase = true)
        if (!inline && !url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
        return ImageSource(
            cacheIdentity = "$contextIdentity:external:$url",
            origin = if (inline) ImageOrigin.INLINE else ImageOrigin.NETWORK,
            displayName = if (inline) null else url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() },
            verifyAccess = verify,
            readPayload = {
                verify()
                val bytes = if (inline) {
                    val payload = url.substringAfter(',', "")
                    require(url.substringBefore(',').endsWith(";base64", ignoreCase = true) && payload.isNotEmpty() &&
                        payload.length <= GeneratedMediaStore.MAX_IMAGE_BYTES * 4 / 3 + 16) { "image_payload_invalid" }
                    java.util.Base64.getDecoder().decode(payload)
                } else when (val result = remoteMediaFetcher.fetch(url)) {
                    is net.weero.measix.pilot.data.ai.attachments.RemoteMediaFetchResult.Success -> result.bytes
                    is net.weero.measix.pilot.data.ai.attachments.RemoteMediaFetchResult.Failure -> error(result.reason)
                }
                require(bytes.size <= GeneratedMediaStore.MAX_IMAGE_BYTES && net.weero.measix.pilot.data.ai.attachments.ImageMime.isAcceptedImage(bytes)) { "image_payload_invalid" }
                verify()
                bytes
            },
        )
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

    suspend fun createGeneratedPreview(item: ImageGenerationItem, tempDirectory: File, selection: RealmSelection, owner: Job): GeneratedPreview {
        recoveryGate.awaitReady()
        sessions.withRealmAccess(selection.access) { owner.ensureActive() }
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
                suspend fun verify() {
                    recoveryGate.awaitReady()
                    sessions.withSelectedRealmSelection(selection) {
                        owner.ensureActive()
                        check(preview.isFile) { "generated_preview_unavailable" }
                    }
                }
                sessions.withRealmAccess(selection.access) { owner.ensureActive() }
                GeneratedPreview(preview, ImageSource(
                    cacheIdentity = "generated-preview:${preview.name}:$selection",
                    origin = ImageOrigin.GENERATED,
                    displayName = preview.name,
                    verifyAccess = { verify() },
                    readPayload = {
                        verify()
                        val payload = net.weero.measix.pilot.data.files.FileUtils.readBoundedBytes(preview, GeneratedMediaStore.MAX_IMAGE_BYTES.toLong())
                        verify()
                        payload
                    },
                ))
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

/** The producer retains temporary-file cleanup; the source only borrows read access. */
data class GeneratedPreview(val file: File, val image: ImageSource)
