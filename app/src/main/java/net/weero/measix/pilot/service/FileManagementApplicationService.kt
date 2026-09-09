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
    private val writeTemporaryImage: (File, ByteArray) -> Unit = { file, bytes ->
        file.writeBytes(bytes)
    },
    private val remoteMediaFetcher: net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher = net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher(),
) {
    suspend fun requireContentAccess(source: RenderedContentSource) = withContentAccess(source) { }

    internal suspend fun <T> withContentAccess(source: RenderedContentSource, action: suspend () -> T): T {
        recoveryGate.awaitReady()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return when (source) {
            is RenderedContentSource.Conversation -> sessions.withSelectedRealmSelection(
                RealmSelection(source.view.access, source.view.selectionRevision),
            ) { source.view.requireOpen(); action() }
            RenderedContentSource.UserConfiguration, RenderedContentSource.Static -> action()
        }
    }

    suspend fun resolveContentImage(source: RenderedContentSource, url: String): ImageSource? = when (source) {
        is RenderedContentSource.Conversation -> resolveConversationImage(source.view, url)
        RenderedContentSource.UserConfiguration -> resolveConfigurationImage(url)
        RenderedContentSource.Static -> externalImageSource(url)
    }

    suspend fun readRenderedImage(source: RenderedContentSource, url: String): Pair<String, ByteArray>? {
        val image = resolveContentImage(source, url) ?: return null
        val bytes = image.readBytes()
        val mime = net.weero.measix.pilot.data.ai.attachments.ImageMime.sniff(bytes) ?: return null
        return mime to bytes
    }

    suspend fun resolveContentAttachment(source: RenderedContentSource, url: String): AttachmentPreview? {
        val preview = withContentAccess(source) {
            val file = when {
                url.startsWith("file:", ignoreCase = true) -> net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.parseFileUrl(url)
                net.weero.measix.pilot.data.files.LocalToolPath.parseUploadToolPath(url) != null -> artifactStore.resolveToolPath(url)
                else -> null
            } ?: return@withContentAccess null
            when (source) {
                is RenderedContentSource.Conversation -> artifactStore.resolveMediaPreviewForFile(source.view.access.scope, file, owner = source.view.ownedArtifact(file))
                RenderedContentSource.UserConfiguration -> artifactStore.resolveConfigurationMedia(file)
                RenderedContentSource.Static -> null
            }
        }
        requireContentAccess(source)
        return preview?.let { AttachmentPreview(it.uri, null, AttachmentPreview.FileTarget(source, it.artifactId, it.displayName)) }
    }

    internal suspend fun copyAttachmentTo(preview: AttachmentPreview, output: java.io.OutputStream): String {
        val target = requireNotNull(preview.fileTarget) { "attachment_unavailable" }
        val mime = withContentAccess(target.source) {
            when (val source = target.source) {
                is RenderedContentSource.Conversation -> artifactStore.copyMediaTo(source.view.access.scope, target.artifactId, output, source.view.ownedArtifact(target.artifactId))
                RenderedContentSource.UserConfiguration -> artifactStore.copyConfigurationMediaTo(target.artifactId, output)
                RenderedContentSource.Static -> error("attachment_unavailable")
            }
        }
        withAttachmentAccess(preview) { }
        return mime
    }

    /** The final system handoff is accepted under the original selection, outside the artifact lock. */
    internal suspend fun <T> withAttachmentAccess(preview: AttachmentPreview, action: () -> T): T {
        val target = requireNotNull(preview.fileTarget) { "attachment_unavailable" }
        withContentAccess(target.source) {
            when (val source = target.source) {
                is RenderedContentSource.Conversation -> artifactStore.requireMediaAccess(source.view.access.scope, target.artifactId, source.view.ownedArtifact(target.artifactId))
                RenderedContentSource.UserConfiguration -> artifactStore.requireConfigurationMediaAccess(target.artifactId)
                RenderedContentSource.Static -> error("attachment_unavailable")
            }
        }
        return withContentAccess(target.source) { action() }
    }

    fun imageSource(key: ManagedFileKey, displayName: String? = null, modifiedAtMillis: Long? = null): ImageSource =
        createImageSource("managed", key, displayName, modifiedAtMillis) { }

    internal fun conversationImageSource(view: ConversationViewLease, artifactId: Long, displayName: String? = null, modifiedAtMillis: Long? = null): ImageSource = createImageSource(
        "conversation:${view.imageReadIdentity}",
        ManagedFileKey.Artifact(artifactId, RealmSelection(view.access, view.selectionRevision)),
        displayName = displayName,
        modifiedAtMillis = modifiedAtMillis,
        requireOwner = view::requireOpen,
        artifactOwner = view::ownedArtifact,
    )

    private fun createImageSource(contextIdentity: String, key: ManagedFileKey, displayName: String? = null, modifiedAtMillis: Long? = null, artifactOwner: (Long) -> net.weero.measix.pilot.data.files.OwnedArtifact? = { null }, requireOwner: () -> Unit): ImageSource = ImageSource(
        cacheIdentity = "$contextIdentity:$key",
        origin = if (key is ManagedFileKey.Generated) ImageOrigin.GENERATED else ImageOrigin.UPLOAD,
        displayName = displayName,
        modifiedAtMillis = modifiedAtMillis,
        verifyAccess = { withImageAccess(key, requireOwner) {
            when (key) {
                is ManagedFileKey.Artifact -> artifactStore.requireImageAccess(key.selection.access.scope, key.artifactId, artifactOwner(key.artifactId))
                is ManagedFileKey.Generated -> generatedMediaStore.requireImageAccess(key.selection.access.scope, key.mediaId)
            }
        } },
        readPayload = { withImageAccess(key, requireOwner) {
            when (key) {
                is ManagedFileKey.Artifact -> artifactStore.readImage(key.selection.access.scope, key.artifactId, artifactOwner(key.artifactId))
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
        val file = net.weero.measix.pilot.data.ai.attachments.AttachmentRefs.parseFileUrl(url)
            ?: if (net.weero.measix.pilot.data.files.LocalToolPath.parseUploadToolPath(url) != null) artifactStore.resolveToolPath(url) else null
        val preview = artifactStore.resolveConfigurationImage(file ?: return null) ?: return null
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
            artifactStore.resolveImagePreviewForFile(view.access.scope, file, view.ownedArtifact(file))
        }
        requireImagePage(view)
        return preview?.let { conversationImageSource(view, it.artifactId, it.displayName, it.modifiedAtMillis) }
    }

    private fun externalImageSource(url: String, contextIdentity: String, verify: suspend () -> Unit): ImageSource? {
        val inline = url.startsWith("data:image/", ignoreCase = true)
        if (!inline && !url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
        val inlinePayload = if (inline) url.substringAfter(',', "").also { payload ->
            require(url.substringBefore(',').endsWith(";base64", ignoreCase = true) && payload.isNotEmpty() &&
                payload.length <= GeneratedMediaStore.MAX_IMAGE_BYTES * 4 / 3 + 16) { "image_payload_invalid" }
        } else null
        return ImageSource(
            cacheIdentity = "$contextIdentity:external:$url",
            origin = if (inline) ImageOrigin.INLINE else ImageOrigin.NETWORK,
            displayName = if (inline) null else url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() },
            verifyAccess = verify,
            readPayload = {
                verify()
                val bytes = if (inline) {
                    java.util.Base64.getDecoder().decode(requireNotNull(inlinePayload))
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

    internal suspend fun createGeneratedPreview(item: ImageGenerationItem, tempDirectory: File, selection: RealmSelection, owner: Job): TemporaryImage =
        createTemporaryImage(tempDirectory, selection, owner, ImageOrigin.GENERATED,
            requireDestination = { sessions.withRealmAccess(selection.access) { owner.ensureActive() } },
            readBytes = { GeneratedMediaStore.decodeImageBytes(item.data) })

    internal suspend fun importImageReference(context: android.content.Context, uri: android.net.Uri,
        tempDirectory: File, selection: RealmSelection, owner: Job): TemporaryImage =
        createTemporaryImage(tempDirectory, selection, owner, ImageOrigin.UPLOAD,
            requireDestination = { sessions.withSelectedRealmSelection(selection) { owner.ensureActive() } },
            readBytes = {
                val bitmap = net.weero.measix.pilot.utils.ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                    ?: error("reference_image_decode_failed")
                try { net.weero.measix.pilot.data.files.FileUtils.compressBitmapToPng(bitmap) }
                finally { bitmap.recycle() }
            })

    private suspend fun createTemporaryImage(tempDirectory: File, selection: RealmSelection, owner: Job,
        origin: ImageOrigin, requireDestination: suspend () -> Unit, readBytes: suspend () -> ByteArray): TemporaryImage {
        recoveryGate.awaitReady()
        requireDestination()
        var candidate: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val bytes = readBytes()
                val inspected = GeneratedMediaStore.inspectImagePayload(bytes, "application/octet-stream")
                val preview = File(
                    tempDirectory,
                    "imggen_${Uuid.random()}.${inspected.extension}",
                )
                candidate = preview
                writeTemporaryImage(preview, bytes)
                suspend fun verify() {
                    recoveryGate.awaitReady()
                    sessions.withSelectedRealmSelection(selection) {
                        owner.ensureActive()
                        check(preview.isFile) { "temporary_image_unavailable" }
                    }
                }
                requireDestination()
                TemporaryImage(preview, selection, ImageSource(
                    cacheIdentity = "temporary-image:${preview.name}:$selection",
                    origin = origin,
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
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    candidate?.let { preview ->
                        if (preview.exists() && !preview.delete()) throw IOException("failed to remove incomplete temporary image")
                    }
                }
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
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
internal data class TemporaryImage(val file: File, val selection: RealmSelection, val image: ImageSource)
