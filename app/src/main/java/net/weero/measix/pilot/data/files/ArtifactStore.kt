package net.weero.measix.pilot.data.files

import android.net.Uri
import android.util.Log
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.mediaPersistenceFailurePart
import net.weero.measix.pilot.data.db.DatabaseTransactionRunner
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.dao.ArtifactReferenceDAO
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.MessagePayloadReadException
import net.weero.measix.pilot.data.db.dao.readMessagesPayload
import net.weero.measix.pilot.data.db.dao.SystemMetaDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.SystemMetaEntity
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.collectFileReferenceTokens
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private suspend inline fun <T> recoverArtifactPersistenceFailure(
    onFailure: (Exception) -> T,
    block: suspend () -> T,
): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    onFailure(error)
}

/** Artifact owner 的范围清理结果；application port 再投影为跨分类 UI 结果。 */
data class ArtifactCleanupResult(
    val deleted: Int,
    val cleanupPending: Int,
    val skippedInProgress: Int,
    val failed: Int,
)

/** 显式删除结果 */
sealed interface ArtifactDeleteResult {
    data class Completed(val artifactId: Long) : ArtifactDeleteResult
    /** 用户删除已被 DELETING 状态接受，剩余物理/元数据清理由启动恢复幂等续跑。 */
    data class CleanupPending(val artifactId: Long, val reason: String) : ArtifactDeleteResult
    data class Rejected(val artifactId: Long, val reason: RejectionReason) : ArtifactDeleteResult
    data class Failed(val artifactId: Long, val reason: String) : ArtifactDeleteResult

    enum class RejectionReason { IN_PROGRESS, ALREADY_DELETED }
}

/** Durable roots that must be detached before an artifact can be deleted. */
data class ArtifactDeleteImpact(
    val referencedByHistory: Boolean,
    val assistantBackgroundCount: Int,
    val assistantAvatarCount: Int,
)

/** 创建完成但尚未交给 durable message/Settings root 的 artifact 所有权令牌。 */
class OwnedArtifact internal constructor(
    val entity: ArtifactEntity,
    val uri: Uri,
    val localRef: LocalArtifactRef,
    internal val ownershipToken: String = Uuid.random().toString(),
)

/** Temporary GC/deletion pin for an application-level undo capability. */
class ArtifactRetentionLease internal constructor(
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }
}

data class PersistedMessageArtifacts(
    val message: UIMessage,
    val ownedArtifacts: List<OwnedArtifact>,
)

internal fun ArtifactDeleteResult.requireDiscarded(
    context: String,
    allowAlreadyPublished: Boolean = false,
) {
    when (this) {
        is ArtifactDeleteResult.Completed -> Unit
        is ArtifactDeleteResult.CleanupPending -> error("$context: deletion cleanup pending ($reason)")
        is ArtifactDeleteResult.Rejected -> check(reason == ArtifactDeleteResult.RejectionReason.ALREADY_DELETED) {
            "$context: deletion not acquired ($reason)"
        }
        is ArtifactDeleteResult.Failed -> check(allowAlreadyPublished && reason == "artifact_already_published") {
            "$context: deletion failed ($reason)"
        }
    }
}

internal data class ArtifactReferenceDelta(
    val replacedNodeIds: List<String>,
    val deletedNodeIds: List<String>,
    val references: List<ArtifactReferenceEntity>,
)

class ArtifactProjectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ArtifactDataIntegrityException(message: String) : IllegalStateException(message)

/**
 * 托管 artifact 元数据、引用与 payload 生命周期的唯一领域服务。
 *
 * 承载：引用投影（事务内 delta + 版本化全量校验）、影响检查（inspect）、生命周期协议
 * （CAS 幂等删除）、启动恢复（reconcileStartup）、孤儿回收。
 *
 * ArtifactDAO、创建、查询、引用、GC 与删除的唯一 owner。磁盘操作只委托无 DAO 的
 * [ArtifactPayloadStore]；Settings roots 只通过 [ArtifactSettingsCoordinator] 访问。
 */
class ArtifactStore(
    private val payloadStore: ArtifactPayloadStore,
    private val artifactDAO: ArtifactDAO,
    private val artifactReferenceDAO: ArtifactReferenceDAO,
    private val systemMetaDAO: SystemMetaDAO,
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsCoordinator: ArtifactSettingsCoordinator,
    private val transactionRunner: DatabaseTransactionRunner,
) {
    private val lifecycleMutex = Mutex()
    private val unpublishedPins = mutableMapOf<Long, String>()
    private val retentionPins = mutableMapOf<Long, Int>()

    internal suspend fun <T> withLifecycleLock(block: suspend () -> T): T =
        lifecycleMutex.withLock { block() }

    // ---- 创建与查询 ----

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ArtifactEntity>> =
        artifactDAO.listActiveByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ArtifactEntity> =
        artifactDAO.listActiveByFolder(folder).first()

    suspend fun get(id: Long): ArtifactEntity? = artifactDAO.getById(id)

    suspend fun getByRelativePath(relativePath: String): ArtifactEntity? =
        artifactDAO.getByPathAndState(relativePath, ArtifactState.ACTIVE.name)

    fun file(entity: ArtifactEntity): File = payloadStore.file(entity.relativePath)

    fun file(ref: LocalArtifactRef): File = payloadStore.file(ref.relativePath)

    /**
     * Resolves a local file only when its payload is an ACTIVE managed artifact. Callers that
     * clone or otherwise copy files must use this port instead of reading arbitrary file paths.
     */
    suspend fun resolveManagedReference(file: File): LocalArtifactRef? {
        val relativePath = payloadStore.relativePathForFile(file)?.replace('\\', '/') ?: return null
        val entity = getByRelativePath(relativePath) ?: return null
        return materialize(
            LocalArtifactRef(
                relativePath = entity.relativePath,
                mimeType = entity.mimeType,
            ),
        )
    }

    /**
     * Read-only image preview port for query projections.  The caller never receives a payload
     * path until the ACTIVE metadata, allowed root, declared MIME and image signature all agree.
     * This keeps previews on the same ArtifactStore lifecycle boundary as attachment execution.
     */
    suspend fun resolveImagePreviewForUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        val relativePath = payloadStore.relativePathForUri(uri)?.replace('\\', '/') ?: return@withContext null
        resolveActiveImagePreview(relativePath)
    }

    suspend fun resolveImagePreviewForFile(file: File): String? = withContext(Dispatchers.IO) {
        val relativePath = payloadStore.relativePathForFile(file)?.replace('\\', '/')
            ?: return@withContext null
        resolveActiveImagePreview(relativePath)
    }

    /** Resolves a managed image reference for query/UI display through the lifecycle owner. */
    suspend fun resolveImagePreviewForArtifact(ref: LocalArtifactRef): String? = withContext(Dispatchers.IO) {
        val materialized = materialize(ref) ?: return@withContext null
        resolveActiveImagePreview(materialized.relativePath)
    }

    /** Resolves any active managed media for query/UI sharing without exposing a raw path. */
    suspend fun resolveMediaPreviewForFile(file: File, expectedMime: String? = null): String? =
        withContext(Dispatchers.IO) {
            val relativePath = payloadStore.relativePathForFile(file)?.replace('\\', '/')
                ?: return@withContext null
            val entity = getByRelativePath(relativePath) ?: return@withContext null
            if (expectedMime != null && !entity.mimeType.equals(expectedMime, ignoreCase = true)) {
                return@withContext null
            }
            val materialized = materialize(
                LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
            ) ?: return@withContext null
            AttachmentRefs.fileToFileUrl(payloadStore.file(materialized.relativePath))
        }

    /** Resolves a non-image media artifact through the same ACTIVE/root/version owner. */
    suspend fun resolveMediaPreviewForArtifact(ref: LocalArtifactRef): String? =
        withContext(Dispatchers.IO) {
            val materialized = materialize(ref) ?: return@withContext null
            AttachmentRefs.fileToFileUrl(payloadStore.file(materialized.relativePath))
        }

    private suspend fun resolveActiveImagePreview(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/')
        if (!normalized.startsWith("${FileFolders.UPLOAD}/") &&
            !normalized.startsWith("images/")
        ) return null
        val entity = getByRelativePath(normalized) ?: return null
        if (!entity.mimeType.substringBefore(';').trim().lowercase().startsWith("image/")) return null
        val file = payloadStore.file(entity.relativePath)
        if (!file.isFile || file.length() > GeneratedMediaStore.MAX_IMAGE_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (ImageMime.isUnsupportedNonImage(bytes, entity.mimeType) || !ImageMime.isAcceptedImage(bytes)) {
            return null
        }
        return AttachmentRefs.fileToFileUrl(file)
    }

    fun displayName(uri: Uri): String? = payloadStore.displayName(uri)

    fun mimeType(uri: Uri): String? = payloadStore.mimeType(uri)

    fun isUploadUri(uri: Uri): Boolean =
        payloadStore.relativePathForUri(uri)?.replace('\\', '/')?.startsWith("${FileFolders.UPLOAD}/") == true

    suspend fun materialize(ref: LocalArtifactRef): LocalArtifactRef? {
        if (ref.version != LocalArtifactRef.CURRENT_VERSION) return null
        val entity = artifactDAO.getByPathAndState(ref.relativePath, ArtifactState.ACTIVE.name) ?: return null
        if (entity.mimeType != ref.mimeType) return null
        val entityFile = payloadStore.file(ref.relativePath)
        if (!entityFile.isFile) return null
        val normalized = ref.relativePath.replace('\\', '/')
        if (!normalized.startsWith("${FileFolders.UPLOAD}/") && !normalized.startsWith("images/")) return null
        return ref
    }

    suspend fun resolveToolPath(path: String): File? {
        val fileName = LocalToolPath.parseUploadToolPath(path) ?: return null
        val entity = getByRelativePath("${FileFolders.UPLOAD}/$fileName") ?: return null
        val candidate = payloadStore.file(entity.relativePath)
        val upload = payloadStore.file(FileFolders.UPLOAD)
        return candidate.takeIf { it.isFile && LocalToolPath.isInsideDirectory(it, upload) }
    }

    suspend fun copyFile(
        source: File,
        mimeType: String,
        displayName: String,
        folder: String = FileFolders.UPLOAD,
        origin: ArtifactOrigin,
    ): OwnedArtifact = createFromUri(source.toUri(), folder, displayName, mimeType, origin)

    suspend fun copyFilePreservingOrigin(
        source: File,
        mimeType: String,
        displayName: String,
        folder: String = FileFolders.UPLOAD,
    ): OwnedArtifact = copyFile(
        source = source,
        mimeType = mimeType,
        displayName = displayName,
        folder = folder,
        origin = resolveOrigin(source.toUri(), ArtifactOrigin.GENERATED),
    )

    /** Settings artifact 字段的唯一写入口；新增的本地 root 必须指向 ACTIVE artifact。 */
    suspend fun updateSettingsReferences(transform: (Settings) -> Settings): Settings = withLifecycleLock {
        // Once this owner begins the durable Settings write, commit and creation-pin transfer are
        // indivisible. Cancellation is observed by the caller after both facts agree.
        withContext(NonCancellable) {
            val active = artifactDAO.listByState(ArtifactState.ACTIVE.name)
            val activePaths = active.mapTo(hashSetOf(), ArtifactEntity::relativePath)
            val committed = settingsCoordinator.updateChecked(transform) { current, updated ->
                val addedManagedRoots = (ArtifactReferencePolicy.roots(updated) - ArtifactReferencePolicy.roots(current))
                    .mapNotNull { uri -> payloadStore.relativePathForUri(uri.toUri()) }
                addedManagedRoots.forEach { relativePath ->
                    check(relativePath in activePaths) {
                        "Settings reference targets a non-active artifact: $relativePath"
                    }
                }
            }
            val rootedPaths = ArtifactReferencePolicy.roots(committed)
                .mapNotNullTo(hashSetOf()) { uri -> payloadStore.relativePathForUri(uri.toUri()) }
            val rootedIds = active.filter { it.relativePath in rootedPaths }.mapTo(hashSetOf(), ArtifactEntity::id)
            synchronized(unpublishedPins) { unpublishedPins.keys.removeAll(rootedIds) }
            committed
        }
    }

    suspend fun createFromUri(
        uri: Uri,
        folder: String = FileFolders.UPLOAD,
        displayName: String? = null,
        mimeType: String? = null,
        origin: ArtifactOrigin = ArtifactOrigin.USER,
        maxBytes: Long? = null,
    ): OwnedArtifact {
        val resolvedName = displayName ?: payloadStore.displayName(uri) ?: "file"
        val resolvedMime = mimeType ?: payloadStore.mimeType(uri) ?: "application/octet-stream"
        val inheritedOrigin = resolveOrigin(uri, origin)
        val staged = payloadStore.stageFromUri(folder, uri, resolvedName, resolvedMime, maxBytes)
        return activateStaged(staged, resolvedName, resolvedMime, inheritedOrigin)
    }

    suspend fun createFromBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
        folder: String = FileFolders.UPLOAD,
        origin: ArtifactOrigin,
    ): OwnedArtifact {
        val staged = payloadStore.stageFromBytes(folder, bytes, displayName, mimeType)
        return activateStaged(staged, displayName, mimeType, origin)
    }

    suspend fun createText(
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
        folder: String = FileFolders.UPLOAD,
        origin: ArtifactOrigin = ArtifactOrigin.USER,
    ): OwnedArtifact {
        val staged = payloadStore.stageText(folder, text, displayName, mimeType)
        return activateStaged(staged, displayName, mimeType, origin)
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun persistBase64Images(message: UIMessage): PersistedMessageArtifacts {
        val ownedArtifacts = mutableListOf<OwnedArtifact>()
        return try {
            PersistedMessageArtifacts(
                message = message.copy(parts = persistBase64Parts(message.parts, ownedArtifacts)),
                ownedArtifacts = ownedArtifacts.toList(),
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                ownedArtifacts.forEach { owned ->
                    try {
                        discardUnpublished(owned).requireDiscarded("base64 transform rollback")
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
            }
            throw error
        }
    }

    private suspend fun persistBase64Parts(
        parts: List<UIMessagePart>,
        ownedArtifacts: MutableList<OwnedArtifact>,
    ): List<UIMessagePart> =
        parts.map { part ->
            when (part) {
                is UIMessagePart.Image -> persistBase64Image(part, ownedArtifacts)
                is UIMessagePart.Tool -> part.copy(output = persistBase64Parts(part.output, ownedArtifacts))
                else -> part
            }
        }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun persistBase64Image(
        part: UIMessagePart.Image,
        ownedArtifacts: MutableList<OwnedArtifact>,
    ): UIMessagePart {
        if (!part.url.startsWith("data:image")) return part
        return recoverArtifactPersistenceFailure(
            onFailure = { error ->
                Log.w(TAG, "base64 artifact persistence failed", error)
                mediaPersistenceFailurePart(part)
            },
        ) {
            val encoded = part.url.substringAfter("base64,", missingDelimiterValue = "")
            require(encoded.isNotEmpty() && encoded.length <= MAX_BASE64_IMAGE_CHARS) {
                "encoded image payload exceeds the size limit"
            }
            val source = Base64.decode(encoded.toByteArray())
            val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size)
                ?: error("incomplete image data")
            val pngBytes = try {
                FileUtils.compressBitmapToPng(bitmap)
            } finally {
                bitmap.recycle()
            }
            val owned = createFromBytes(
                bytes = pngBytes,
                displayName = "image.png",
                mimeType = "image/png",
                origin = ArtifactOrigin.SYSTEM,
            )
            try {
                AttachmentRefs.ensureAttachmentRef(part.copy(url = owned.uri.toString())).also {
                    ownedArtifacts += owned
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try {
                        discardUnpublished(owned).requireDiscarded("base64 projection rollback")
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                throw error
            }
        }
    }

    fun unpublishedLease(owned: OwnedArtifact): ToolResourceLease = ToolResourceLease(
        publish = { publishUnpublished(owned) },
        discard = { discardUnpublished(owned).requireDiscarded("unpublished tool resource rollback") },
    )

    private suspend fun activateStaged(
        staged: ArtifactPayloadStore.StagedPayload,
        displayName: String,
        mimeType: String,
        origin: ArtifactOrigin,
    ): OwnedArtifact {
        var insertedId: Long? = null
        return try {
            withLifecycleLock {
                val now = System.currentTimeMillis()
                val creating = ArtifactEntity(
                    folder = staged.folder,
                    relativePath = staged.relativePath,
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = staged.sizeBytes,
                    createdAt = now,
                    updatedAt = now,
                    state = ArtifactState.CREATING.name,
                    payloadToken = staged.stagingToken,
                    origin = origin.name,
                )
                val id = artifactDAO.insert(creating)
                check(id != -1L) { "Artifact metadata collision: ${staged.relativePath}" }
                insertedId = id
                payloadStore.promote(staged)
                check(artifactDAO.activateCreated(id, staged.stagingToken, System.currentTimeMillis()) == 1) {
                    "Artifact activation conflict: $id"
                }
                owned(creating.copy(id = id, state = ArtifactState.ACTIVE.name, payloadToken = null))
            }
        } catch (cancelled: CancellationException) {
            rollbackCreating(insertedId, staged)?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: Throwable) {
            rollbackCreating(insertedId, staged)?.let(error::addSuppressed)
            throw error
        }
    }

    /**
     * 只在 payload 已完全删除时删除 CREATING 事实行。若物理删除失败，
     * 保留事实行交给冷启动恢复，避免制造无法归因的最终路径文件。
     */
    private suspend fun rollbackCreating(
        id: Long?,
        staged: ArtifactPayloadStore.StagedPayload,
    ): Throwable? = withContext(NonCancellable) {
        try {
            if (id == null) {
                return@withContext if (payloadStore.deleteStaging(staged.stagingToken)) {
                    null
                } else {
                    IllegalStateException(
                        "Artifact rollback retained unregistered staging payload: ${staged.stagingToken}"
                    )
                }
            }
            withLifecycleLock {
                val stagingDeleted = payloadStore.deleteStaging(staged.stagingToken)
                val finalDeleted = payloadStore.deleteFinal(staged.relativePath)
                if (stagingDeleted && finalDeleted) {
                    check(artifactDAO.deleteById(id) == 1 || artifactDAO.getById(id) == null) {
                        "Failed to remove rolled-back artifact metadata: $id"
                    }
                }
                if (!stagingDeleted || !finalDeleted) {
                    IllegalStateException(
                        "Artifact rollback retained CREATING metadata for startup recovery: ${staged.relativePath}"
                    )
                } else {
                    null
                }
            }
        } catch (error: Throwable) {
            error
        }
    }

    private fun owned(entity: ArtifactEntity): OwnedArtifact {
        val file = payloadStore.file(entity.relativePath)
        val token = Uuid.random().toString()
        synchronized(unpublishedPins) {
            check(unpublishedPins.put(entity.id, token) == null) {
                "artifact already has an unpublished owner: ${entity.id}"
            }
        }
        return OwnedArtifact(
            entity = entity,
            uri = file.toUri(),
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
            ownershipToken = token,
        )
    }

    /**
     * Transfers a creation pin only after an executable durable root exists. Validation and pin
     * consumption are NonCancellable and share the lifecycle lock with GC/deletion, so a caller
     * cannot publish early and cancellation cannot split durable handoff from ownership release.
     * A consumed token is idempotent for retry after an uncertain caller return.
     */
    suspend fun publishUnpublished(owned: OwnedArtifact): Unit = publishAllUnpublished(listOf(owned))

    /** Validates every durable root before consuming any token, so batch handoff cannot split. */
    suspend fun publishAllUnpublished(artifacts: Collection<OwnedArtifact>): Unit = withContext(NonCancellable) {
        if (artifacts.isEmpty()) return@withContext
        require(artifacts.map { it.entity.id }.distinct().size == artifacts.size) {
            "duplicate artifact in ownership transfer"
        }
        withLifecycleLock {
            val pending = synchronized(unpublishedPins) {
                artifacts.filter { unpublishedPins[it.entity.id] != null }
            }
            val settingsRoots = settingsCoordinator.withRootsLock(ArtifactReferencePolicy::roots)
            pending.forEach { owned ->
                val current = synchronized(unpublishedPins) { unpublishedPins[owned.entity.id] }
                check(current == owned.ownershipToken) { "artifact ownership token mismatch: ${owned.entity.id}" }
                val entity = artifactDAO.getById(owned.entity.id)
                check(entity?.state == ArtifactState.ACTIVE.name) {
                    "artifact is not active during ownership transfer: ${owned.entity.id}"
                }
                val messageRooted = artifactReferenceDAO.existsByArtifactId(owned.entity.id)
                check(messageRooted || owned.uri.toString() in settingsRoots) {
                    "artifact has no durable root during ownership transfer: ${owned.entity.id}"
                }
            }
            synchronized(unpublishedPins) {
                pending.forEach { owned ->
                    check(unpublishedPins[owned.entity.id] == owned.ownershipToken) {
                        "artifact ownership changed during transfer: ${owned.entity.id}"
                    }
                }
                unpublishedPins.keys.removeAll(pending.mapTo(hashSetOf()) { it.entity.id })
            }
        }
    }

    /** Releases a creation pin without touching durable state; the unrooted artifact is then GC-owned. */
    fun abandonUnpublished(owned: OwnedArtifact) {
        synchronized(unpublishedPins) {
            val current = unpublishedPins[owned.entity.id]
            check(current == null || current == owned.ownershipToken) {
                "artifact ownership token mismatch: ${owned.entity.id}"
            }
            unpublishedPins.remove(owned.entity.id, owned.ownershipToken)
        }
    }

    suspend fun retainForUndo(conversations: List<Conversation>): ArtifactRetentionLease = withLifecycleLock {
        val ids = conversations.flatMap { conversation ->
            buildMutableReferencesForNodes(conversation.messageNodes).map(ArtifactReferenceEntity::artifactId)
        }.toSet()
        synchronized(retentionPins) {
            ids.forEach { id -> retentionPins[id] = retentionPins.getOrDefault(id, 0) + 1 }
        }
        ArtifactRetentionLease {
            synchronized(retentionPins) {
                ids.forEach { id ->
                    val remaining = retentionPins.getOrDefault(id, 0) - 1
                    if (remaining <= 0) retentionPins.remove(id) else retentionPins[id] = remaining
                }
            }
        }
    }

    // ---- 引用投影 ----

    /**
     * delta 同步，替换语义：
     *  - upsertedNodes：先 deleteByNodeIds 再 insertAll（node 变更后可能不再引用某文件）
     *  - deletedNodeIds：同事务显式清理投影，不依赖于延后重建
     */
    internal suspend fun prepareReferenceDelta(
        upsertedNodes: List<MessageNode>,
        deletedNodeIds: List<Uuid>,
    ): ArtifactReferenceDelta {
        require(upsertedNodes.all { node -> node.id.toString().isNotBlank() })
        return ArtifactReferenceDelta(
            replacedNodeIds = upsertedNodes.map { it.id.toString() },
            deletedNodeIds = deletedNodeIds.map { it.toString() },
            references = buildMutableReferencesForNodes(upsertedNodes),
        )
    }

    /** Must be called from the same Room transaction that writes the corresponding nodes. */
    internal suspend fun applyReferenceDeltaInTransaction(delta: ArtifactReferenceDelta) {
        if (delta.replacedNodeIds.isNotEmpty()) {
            artifactReferenceDAO.deleteByNodeIds(delta.replacedNodeIds)
        }
        if (delta.references.isNotEmpty()) {
            val artifactIds = delta.references.mapTo(linkedSetOf()) { it.artifactId }
            val activeIds = artifactDAO.getIdsByState(artifactIds.toList(), ArtifactState.ACTIVE.name).toSet()
            if (activeIds != artifactIds) {
                throw ArtifactProjectionException("artifact reference targets a non-active artifact")
            }
            artifactReferenceDAO.insertAll(delta.references)
        }
        if (delta.deletedNodeIds.isNotEmpty()) {
            artifactReferenceDAO.deleteByNodeIds(delta.deletedNodeIds)
        }
    }

    /** Ensures the versioned reference projection is complete before GC becomes available. */
    suspend fun ensureReferenceProjection() = withLifecycleLock {
        if (isReferenceProjectionCurrent()) return@withLifecycleLock
        val inserted = mutableListOf<ArtifactReferenceEntity>()
        try {
            conversationDAO.getAllConversations().forEach { conversationEntity ->
                messageNodeDAO.getNodeHeadersOfConversation(conversationEntity.id).forEach { header ->
                    val messagesJson = try {
                        messageNodeDAO.readMessagesPayload(header, "conversation=${conversationEntity.id}")
                    } catch (error: MessagePayloadReadException) {
                        throw ArtifactProjectionException(error.message ?: "invalid messages payload", error)
                    }
                    val messages = try {
                        JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                    } catch (error: Exception) {
                        throw ArtifactProjectionException(
                            "invalid messages payload: conversation=${conversationEntity.id}, node=${header.id}",
                            error,
                        )
                    }
                    inserted.addAll(resolveNodeReferenceEntities(header.id, messages))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ArtifactProjectionException) {
            throw error
        }
        transactionRunner.run {
            artifactReferenceDAO.deleteAll()
            if (inserted.isNotEmpty()) {
                val artifactIds = inserted.mapTo(linkedSetOf()) { it.artifactId }
                val activeIds = artifactDAO.getIdsByState(artifactIds.toList(), ArtifactState.ACTIVE.name).toSet()
                if (activeIds != artifactIds) {
                    throw ArtifactProjectionException("backfill resolved a non-active artifact")
                }
                artifactReferenceDAO.insertAll(inserted)
            }
            systemMetaDAO.put(SystemMetaEntity(REFERENCE_PROJECTION_VERSION_KEY, "true"))
        }
    }

    suspend fun isReferenceProjectionCurrent(): Boolean =
        systemMetaDAO.get(REFERENCE_PROJECTION_VERSION_KEY) == "true"

    // ---- 影响检查 ----

    suspend fun inspect(artifact: ArtifactEntity): ArtifactDeleteImpact =
        settingsCoordinator.withRootsLock { settings ->
            val fileUri = buildFileUri(artifact)
            val backgroundCount = settings.assistants.count { it.background == fileUri }
            val userAvatarHit = settings.displaySetting.userAvatar.let { avatar ->
                avatar is net.weero.measix.pilot.data.model.Avatar.Image && avatar.url == fileUri
            }
            val avatarCount = settings.assistants.count { assistant ->
                val avatar = assistant.avatar
                avatar is net.weero.measix.pilot.data.model.Avatar.Image && avatar.url == fileUri
            } + if (userAvatarHit) 1 else 0
            ArtifactDeleteImpact(
                // GC is closed until backfill is complete; inspect remains conservative too.
                referencedByHistory = !isReferenceProjectionCurrent() || artifactReferenceDAO.existsByArtifactId(artifact.id),
                assistantBackgroundCount = backgroundCount,
                assistantAvatarCount = avatarCount,
            )
        }

    // ---- 生命周期协议（CAS 幂等，无 operationId） ----

    suspend fun deleteUserRequested(artifactId: Long): ArtifactDeleteResult = withLifecycleLock {
        deleteUserRequestedLocked(artifactId)
    }

    private suspend fun deleteUserRequestedLocked(artifactId: Long): ArtifactDeleteResult {
        val artifact = artifactDAO.getById(artifactId)
            ?: return ArtifactDeleteResult.Rejected(
                artifactId,
                ArtifactDeleteResult.RejectionReason.ALREADY_DELETED,
            )
        if (isPinned(artifact.id)) {
            return ArtifactDeleteResult.Rejected(artifact.id, ArtifactDeleteResult.RejectionReason.IN_PROGRESS)
        }
        if (artifact.state == ArtifactState.DELETING.name) {
            return withContext(NonCancellable) { finishUserDeletion(artifact) }
        }
        if (artifact.state != ArtifactState.ACTIVE.name) {
            return ArtifactDeleteResult.Rejected(artifact.id, ArtifactDeleteResult.RejectionReason.IN_PROGRESS)
        }
        return withContext(NonCancellable) {
            val gained = artifactDAO.compareAndSetState(
                artifact.id,
                ArtifactState.ACTIVE.name,
                ArtifactState.DELETING.name,
                System.currentTimeMillis(),
            )
            if (gained != 1) return@withContext deletionConflict(artifact.id)
            finishUserDeletion(artifact.copy(state = ArtifactState.DELETING.name))
        }
    }

    /** 仅创建方持有的未发布令牌可走补偿删除；发布后存在任一 root 时明确拒绝。 */
    suspend fun discardUnpublished(owned: OwnedArtifact): ArtifactDeleteResult = withLifecycleLock {
        val current = artifactDAO.getById(owned.entity.id)
            ?: return@withLifecycleLock ArtifactDeleteResult.Rejected(
                owned.entity.id,
                ArtifactDeleteResult.RejectionReason.ALREADY_DELETED,
            )
        if (!isPinnedBy(owned)) {
            return@withLifecycleLock ArtifactDeleteResult.Failed(current.id, "artifact_ownership_already_transferred")
        }
        val rooted = artifactReferenceDAO.existsByArtifactId(current.id) ||
            settingsCoordinator.withRootsLock { settings ->
                buildFileUri(current) in ArtifactReferencePolicy.roots(settings)
            }
        if (rooted) return@withLifecycleLock ArtifactDeleteResult.Failed(current.id, "artifact_already_published")
        withContext(NonCancellable) {
            if (artifactDAO.compareAndSetState(
                    current.id,
                    ArtifactState.ACTIVE.name,
                    ArtifactState.DELETING.name,
                    System.currentTimeMillis(),
                ) != 1
            ) {
                return@withContext deletionConflict(current.id)
            }
            synchronized(unpublishedPins) { unpublishedPins.remove(current.id, owned.ownershipToken) }
            finishDeleting(current.copy(state = ArtifactState.DELETING.name))
        }
    }

    /**
     * 按时间范围清理整个 folder 的候选（含 CREATING/ACTIVE/DELETING），在同一 lifecycle lock 内
     * 逐项复用既有删除协议。返回结构化结果，部分成功不压成 Boolean。
     */
    suspend fun deleteUserRequestedFolderCreatedBefore(
        folder: String,
        createdBefore: Long,
    ): ArtifactCleanupResult = withLifecycleLock {
        val entities = artifactDAO.listByFolderCreatedBefore(folder, createdBefore)
        if (entities.isEmpty()) {
            // 与 deleteUserRequestedFolder 收敛：无候选时也尝试清空空目录
            payloadStore.deleteEmptyFolder(folder)
            return@withLifecycleLock ArtifactCleanupResult(0, 0, 0, 0)
        }
        var deleted = 0
        var cleanupPending = 0
        var skippedInProgress = 0
        var failed = 0
        entities.forEach { entity ->
            if (isPinned(entity.id)) {
                skippedInProgress++
                return@forEach
            }
            // 已取得所有权的终态（DELETING 续跑、CREATING 回滚）与单条路径一致地在 NonCancellable 内
            // 原子完成，取消只在单项边界传播，不把"已拥有的收口"交给恢复侧重试。
            val result = when (entity.state) {
                ArtifactState.ACTIVE.name -> deleteUserRequestedLocked(entity.id)
                ArtifactState.DELETING.name -> withContext(NonCancellable) { finishUserDeletion(entity) }
                ArtifactState.CREATING.name -> withContext(NonCancellable) { discardCreating(entity) }
                else -> ArtifactDeleteResult.Failed(entity.id, "unknown_artifact_state:${entity.state}")
            }
            when (result) {
                is ArtifactDeleteResult.Completed -> deleted++
                is ArtifactDeleteResult.CleanupPending -> cleanupPending++
                is ArtifactDeleteResult.Rejected -> skippedInProgress++
                is ArtifactDeleteResult.Failed -> failed++
            }
        }
        // 候选处理完后清理可能变空的目录；deleteEmptyFolder 只在目录确为空时删除，
        // 仍保留此时间范围之外（或失败未删）的 payload，与 deleteUserRequestedFolder 语义收敛。
        payloadStore.deleteEmptyFolder(folder)
        ArtifactCleanupResult(deleted = deleted, cleanupPending = cleanupPending, skippedInProgress = skippedInProgress, failed = failed)
    }

    /** 范围清理确认对话框的候选计数（只读提示，不锁定；真实清理在同一 lock 内重新快照）。 */
    suspend fun countFolderCreatedBefore(folder: String, createdBefore: Long): Int =
        artifactDAO.listByFolderCreatedBefore(folder, createdBefore).size

    suspend fun deleteUserRequestedFolder(folder: String): ArtifactDeleteResult = withLifecycleLock {
        val entities = artifactDAO.listAllStatesByFolder(folder).first()
        if (entities.isEmpty()) {
            payloadStore.deleteEmptyFolder(folder)
            return@withLifecycleLock ArtifactDeleteResult.Completed(0)
        }
        if (entities.any { isPinned(it.id) }) {
            return@withLifecycleLock ArtifactDeleteResult.Rejected(0, ArtifactDeleteResult.RejectionReason.IN_PROGRESS)
        }
        var pendingCleanup: ArtifactDeleteResult.CleanupPending? = null
        entities.forEach { entity ->
            val result = when (entity.state) {
                ArtifactState.ACTIVE.name -> deleteUserRequestedLocked(entity.id)
                ArtifactState.DELETING.name -> finishUserDeletion(entity)
                ArtifactState.CREATING.name -> discardCreating(entity)
                else -> ArtifactDeleteResult.Failed(entity.id, "unknown_artifact_state:${entity.state}")
            }
            when (result) {
                is ArtifactDeleteResult.Completed -> Unit
                is ArtifactDeleteResult.CleanupPending -> if (pendingCleanup == null) pendingCleanup = result
                is ArtifactDeleteResult.Rejected -> return@withLifecycleLock result
                is ArtifactDeleteResult.Failed -> return@withLifecycleLock result
            }
        }
        payloadStore.deleteEmptyFolder(folder)
        pendingCleanup ?: ArtifactDeleteResult.Completed(0)
    }

    // ---- 启动恢复 ----

    /** 冷启动回滚已失去进程内 owner 的 CREATING，续跑 DELETING，并持久化收口悬挂 Settings root。 */
    suspend fun reconcileStartup() = withLifecycleLock {
        val creating = artifactDAO.listByState(ArtifactState.CREATING.name)
        creating.forEach { entity ->
            when (val result = discardCreating(entity)) {
                is ArtifactDeleteResult.Completed -> Unit
                is ArtifactDeleteResult.Failed -> error(
                    "Failed to roll back interrupted artifact creation ${entity.id}: ${result.reason}"
                )
                else -> error("Unexpected CREATING rollback result for ${entity.id}: $result")
            }
        }
        payloadStore.listStagingTokens().forEach { orphanToken ->
            check(payloadStore.deleteStaging(orphanToken)) {
                "Failed to remove orphan artifact staging payload: $orphanToken"
            }
        }
        artifactDAO.listByState(ArtifactState.DELETING.name).forEach { entity ->
            when (val result = finishUserDeletion(entity)) {
                is ArtifactDeleteResult.CleanupPending -> error(
                    "Failed to resume artifact deletion ${entity.id}: ${result.reason}"
                )
                is ArtifactDeleteResult.Failed -> error(
                    "Failed to resume artifact deletion ${entity.id}: ${result.reason}"
                )
                else -> Unit
            }
        }
        val settingsOwnedRoots = settingsCoordinator.withRootsLock { settings ->
            ArtifactReferencePolicy.roots(settings).mapNotNull { root ->
                val uri = runCatching { Uri.parse(root) }.getOrNull() ?: return@mapNotNull null
                payloadStore.relativePathForUri(uri)?.let { relativePath -> root to relativePath }
            }
        }
        val settingsRootsToPersistAsDefaults = mutableSetOf<String>()
        settingsOwnedRoots.forEach { (root, relativePath) ->
            val hasActiveMetadata = artifactDAO.getByPathAndState(relativePath, ArtifactState.ACTIVE.name) != null
            when {
                !hasActiveMetadata -> {
                    // A Settings background/avatar is a mutable display preference, not an
                    // artifact owner. Without ACTIVE metadata its URI has no durable owner:
                    // persist the defined Settings default and never adopt a leftover payload.
                    settingsRootsToPersistAsDefaults += root
                }

                !payloadStore.finalExists(relativePath) -> {
                    // ACTIVE metadata without its payload is an unavailable display resource.
                    // Persist the Settings default before the recovery below removes that stale
                    // metadata, so config reads never depend on a missing external file.
                    settingsRootsToPersistAsDefaults += root
                }
            }
        }
        if (settingsRootsToPersistAsDefaults.isNotEmpty()) {
            check(settingsCoordinator.detach(settingsRootsToPersistAsDefaults)) {
                "Failed to persist fallback for Settings roots with unavailable artifacts"
            }
        }
        artifactDAO.listByState(ArtifactState.ACTIVE.name).forEach { entity ->
            if (!payloadStore.finalExists(entity.relativePath)) {
                val messageRooted = artifactReferenceDAO.existsByArtifactId(entity.id) ||
                    messageNodeDAO.existsMessagesJsonContaining(entity.relativePath) ||
                    messageNodeDAO.existsMessagesJsonContaining(buildFileUri(entity))
                val settingsRooted = settingsCoordinator.withRootsLock { settings ->
                    buildFileUri(entity) in ArtifactReferencePolicy.roots(settings)
                }
                if (messageRooted || settingsRooted) {
                    throw ArtifactDataIntegrityException(
                        "Active artifact payload is missing while a durable root still references it: ${entity.id}"
                    )
                }
                check(artifactDAO.compareAndSetState(
                    entity.id,
                    ArtifactState.ACTIVE.name,
                    ArtifactState.DELETING.name,
                    System.currentTimeMillis(),
                ) == 1) { "Artifact state changed during recovery: ${entity.id}" }
                check(artifactDAO.deleteById(entity.id) == 1) { "Failed to remove missing artifact ${entity.id}" }
                Log.w(TAG, "reconcileStartup: missing artifact ${entity.id} removed")
            }
        }
        logUntrackedFinalFiles(FileFolders.UPLOAD)
    }

    // ---- GC ----

    /**
     * GC：回收 state=ACTIVE 且无任何引用、created_at 超过保护窗口的 artifact。
     * 引用面有两个，缺一不可：
     *  - 消息历史引用（artifact_reference 投影）；
     *  - Settings 域可变当前引用（助手头像/背景 + 用户头像，统一见
     *    [ArtifactReferencePolicy.roots]）。此类文件虽不在消息历史中，但正被 UI
     *    消费，回收即制造"Settings 指向已删除文件"的悬挂引用（头像回退纯色/背景消失）。
     * 回填未完成时保守跳过（宁可保留文件）。
     */
    suspend fun collectGarbage(protectionWindowMillis: Long = 24 * 3600 * 1000L): List<ArtifactEntity> =
        withLifecycleLock {
            if (!isReferenceProjectionCurrent()) return@withLifecycleLock emptyList()
            val threshold = System.currentTimeMillis() - protectionWindowMillis
            val candidates = artifactDAO.listByStateCreatedBefore(ArtifactState.ACTIVE.name, threshold)
            settingsCoordinator.withRootsLock { settings ->
                val roots = ArtifactReferencePolicy.roots(settings)
                val claimed = mutableListOf<ArtifactEntity>()
                candidates.forEach { entity ->
                    // Recheck every root while holding both lifecycle domains, then claim by CAS.
                    if (!isPinned(entity.id) &&
                        !artifactReferenceDAO.existsByArtifactId(entity.id) &&
                        buildFileUri(entity) !in roots
                    ) {
                        if (artifactDAO.compareAndSetState(
                                entity.id,
                                ArtifactState.ACTIVE.name,
                                ArtifactState.DELETING.name,
                                System.currentTimeMillis(),
                            ) == 1
                        ) {
                            claimed += entity
                        }
                    }
                }
                claimed.forEach { entity ->
                    val result = finishDeleting(entity)
                    check(result is ArtifactDeleteResult.Completed) {
                        "Artifact GC deletion failed: id=${entity.id}, result=$result"
                    }
                }
                claimed
            }
        }

    // ---- 私有 ----

    private suspend fun buildMutableReferencesForNodes(nodes: List<MessageNode>): List<ArtifactReferenceEntity> {
        val refs = mutableListOf<ArtifactReferenceEntity>()
        nodes.forEach { node ->
            refs.addAll(resolveNodeReferenceEntities(node.id.toString(), node.messages))
        }
        return refs
    }

    private fun isPinnedBy(owned: OwnedArtifact): Boolean = synchronized(unpublishedPins) {
        unpublishedPins[owned.entity.id] == owned.ownershipToken
    }

    private fun isPinned(id: Long): Boolean =
        synchronized(unpublishedPins) { unpublishedPins.containsKey(id) } ||
            synchronized(retentionPins) { retentionPins.containsKey(id) }

    /**
     * 节点引用解析：引用 token = file:// URL + Tool.metadata 的 LocalArtifactRef
     * 相对路径（collectFileReferenceTokens）。URL 转 filesDir 相对路径，相对路径 token 直用；
     * metadata-only 引用（generate_image artifact 等）同样登记、阻止 GC 回收。
     */
    private suspend fun resolveNodeReferenceEntities(nodeId: String, messages: List<UIMessage>): List<ArtifactReferenceEntity> {
        val refs = mutableListOf<ArtifactReferenceEntity>()
        val seenArtifacts = mutableSetOf<Long>()
        messages.collectFileReferenceTokens().forEach { token ->
            val relativePath = if (token.startsWith("file:", ignoreCase = true)) {
                payloadStore.relativePathForUri(Uri.parse(token)) ?: return@forEach
            } else {
                token
            }
            artifactDAO.getByPathAndState(relativePath, ArtifactState.ACTIVE.name)?.let { artifact ->
                if (seenArtifacts.add(artifact.id)) {
                    refs.add(
                        ArtifactReferenceEntity(
                            artifactId = artifact.id,
                            nodeId = nodeId,
                            referenceType = ArtifactReferenceType.ATTACHMENT.name,
                        )
                    )
                }
            }
        }
        return refs
    }

    private fun buildFileUri(artifact: ArtifactEntity): String {
        return payloadStore.file(artifact.relativePath).toUri().toString()
    }

    private suspend fun finishDeleting(entity: ArtifactEntity): ArtifactDeleteResult = try {
        if (!payloadStore.deleteStaging(entity.payloadToken) || !payloadStore.deleteFinal(entity.relativePath)) {
            ArtifactDeleteResult.CleanupPending(entity.id, "payload_delete_failed")
        } else if (artifactDAO.deleteById(entity.id) == 1 || artifactDAO.getById(entity.id) == null) {
            ArtifactDeleteResult.Completed(entity.id)
        } else {
            ArtifactDeleteResult.CleanupPending(entity.id, "metadata_delete_failed")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(TAG, "artifact deletion failed: ${entity.id}", error)
        ArtifactDeleteResult.CleanupPending(entity.id, error.message ?: "unknown")
    }

    private suspend fun finishUserDeletion(entity: ArtifactEntity): ArtifactDeleteResult {
        val fileUri = buildFileUri(entity)
        if (!settingsCoordinator.detach(setOf(fileUri))) {
            return ArtifactDeleteResult.CleanupPending(entity.id, "settings_detach_failed")
        }
        return finishDeleting(entity)
    }

    private suspend fun discardCreating(entity: ArtifactEntity): ArtifactDeleteResult = try {
        if (!payloadStore.deleteStaging(entity.payloadToken) || !payloadStore.deleteFinal(entity.relativePath)) {
            ArtifactDeleteResult.Failed(entity.id, "creating_payload_delete_failed")
        } else if (artifactDAO.deleteById(entity.id) == 1 || artifactDAO.getById(entity.id) == null) {
            ArtifactDeleteResult.Completed(entity.id)
        } else {
            ArtifactDeleteResult.Failed(entity.id, "creating_metadata_delete_failed")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ArtifactDeleteResult.Failed(entity.id, error.message ?: "unknown")
    }

    private suspend fun deletionConflict(id: Long): ArtifactDeleteResult {
        val reason = if (artifactDAO.getById(id) == null) {
            ArtifactDeleteResult.RejectionReason.ALREADY_DELETED
        } else {
            ArtifactDeleteResult.RejectionReason.IN_PROGRESS
        }
        return ArtifactDeleteResult.Rejected(id, reason)
    }

    private suspend fun resolveOrigin(uri: Uri, externalOrigin: ArtifactOrigin): ArtifactOrigin {
        val relativePath = payloadStore.relativePathForUri(uri) ?: return externalOrigin
        val source = artifactDAO.getByPathAndState(relativePath, ArtifactState.ACTIVE.name) ?: return externalOrigin
        return ArtifactOrigin.valueOf(source.origin)
    }

    private suspend fun logUntrackedFinalFiles(folder: String) {
        val knownPaths = artifactDAO.listAllStatesByFolder(folder).first().mapTo(hashSetOf()) { it.relativePath }
        payloadStore.listFinalFiles(folder).forEach { file ->
            val relativePath = FileUtils.buildRelativePath(folder, file)
            if (relativePath !in knownPaths) {
                Log.w(TAG, "reconcileStartup: untracked payload requires operator review: $relativePath")
            }
        }
    }

    companion object {
        private const val TAG = "ArtifactStore"
        private const val MAX_BASE64_IMAGE_CHARS = 32 * 1024 * 1024
        const val REFERENCE_PROJECTION_VERSION_KEY = "artifact_reference_projection_transactional_v1"
    }
}
