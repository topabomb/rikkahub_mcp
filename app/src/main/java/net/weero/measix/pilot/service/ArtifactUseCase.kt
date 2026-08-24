package net.weero.measix.pilot.service

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactDeleteImpact
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore

enum class ArtifactUiOrigin {
    USER,
    GENERATED,
    SYSTEM,
}

data class ArtifactUiModel(
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val origin: ArtifactUiOrigin,
)

data class ArtifactDeleteImpactUiModel(
    val referencedByHistory: Boolean,
    val assistantBackgroundCount: Int,
    val assistantAvatarCount: Int,
)

data class ArtifactStorageStats(
    val count: Int,
    val sizeBytes: Long,
)

/** Draft-owned import descriptor; callers never have to rediscover metadata from a managed file URI. */
data class ArtifactDraftItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
)

sealed interface ArtifactDeleteOutcome {
    data object Deleted : ArtifactDeleteOutcome
    data object CleanupPending : ArtifactDeleteOutcome
    data object InProgress : ArtifactDeleteOutcome
    data object AlreadyDeleted : ArtifactDeleteOutcome
    data class Failed(val reason: String) : ArtifactDeleteOutcome
}

/** UI/Application 的 artifact 端口；调用方看不到 DAO、payload 或删除状态机。 */
class ArtifactUseCase(
    private val store: ArtifactStore,
    private val recoveryGate: ApplicationRecoveryGate,
) {
    fun openDraftScope(): ArtifactDraftScope = ArtifactDraftScope(store, recoveryGate)

    fun observeUploads(): Flow<List<ArtifactUiModel>> =
        store.observe(FileFolders.UPLOAD).map { artifacts -> artifacts.map(::toUiModel) }

    /** 验证并创建 Settings 图像 artifact，由同一挂起所有者提交 durable root。 */
    suspend fun importSettingsImage(uri: Uri, transform: (Settings, Uri) -> Settings): Uri {
        recoveryGate.awaitReady()
        val owned = store.createFromUri(uri, maxBytes = GeneratedMediaStore.MAX_IMAGE_BYTES.toLong())
        var ownershipTransferred = false
        return try {
            withContext(Dispatchers.IO) {
                val file = store.file(owned.entity)
                require(file.isFile && file.length() in 1..GeneratedMediaStore.MAX_IMAGE_BYTES.toLong()) {
                    "Settings image payload exceeds the size limit"
                }
                require(ImageMime.isAcceptedImage(file.readBytes())) { "Settings image payload is invalid" }
            }
            withContext(NonCancellable) {
                store.updateSettingsReferences { settings -> transform(settings, owned.uri) }
                // The transform must actually root the new artifact. A no-op/missing owner is a
                // failed import, not a published orphan.
                store.publishUnpublished(owned)
                ownershipTransferred = true
                owned.uri
            }
        } catch (error: Throwable) {
            if (!ownershipTransferred) {
                withContext(NonCancellable) {
                    try {
                        store.discardUnpublished(owned).requireDiscarded("settings artifact import rollback")
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
            }
            throw error
        }
    }

    suspend fun uploadStats(): ArtifactStorageStats {
        recoveryGate.awaitReady()
        val artifacts = store.list(FileFolders.UPLOAD)
        return ArtifactStorageStats(
            count = artifacts.size,
            sizeBytes = artifacts.sumOf(ArtifactEntity::sizeBytes),
        )
    }

    suspend fun inspect(id: Long): ArtifactDeleteImpactUiModel? {
        recoveryGate.awaitReady()
        val entity = store.get(id) ?: return null
        return store.inspect(entity).toUiModel()
    }

    fun displayName(uri: Uri): String? = store.displayName(uri)

    fun mimeType(uri: Uri): String? = store.mimeType(uri)

    fun isManagedUploadUrl(url: String): Boolean = store.isUploadUri(Uri.parse(url))

    suspend fun deleteUserRequested(id: Long): ArtifactDeleteOutcome {
        recoveryGate.awaitReady()
        return store.deleteUserRequested(id).toOutcome()
    }

    suspend fun deleteAllUploads(): ArtifactDeleteOutcome {
        recoveryGate.awaitReady()
        return store.deleteUserRequestedFolder(FileFolders.UPLOAD).toOutcome()
    }

    suspend fun updateSettingsReferences(transform: (Settings) -> Settings): Settings {
        recoveryGate.awaitReady()
        return store.updateSettingsReferences(transform)
    }

    /** Runs non-authoritative storage maintenance without turning a committed UI command into failure. */
    suspend fun maintainStorage(protectionWindowMillis: Long = 0) {
        recoveryGate.awaitReady()
        try {
            store.collectGarbage(protectionWindowMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "artifact storage maintenance failed", error)
        }
    }

    private fun toUiModel(entity: ArtifactEntity): ArtifactUiModel = ArtifactUiModel(
        id = entity.id,
        contentUri = store.file(entity).toUri().toString(),
        displayName = entity.displayName,
        mimeType = entity.mimeType,
        sizeBytes = entity.sizeBytes,
        origin = when (ArtifactOrigin.valueOf(entity.origin)) {
            ArtifactOrigin.USER -> ArtifactUiOrigin.USER
            ArtifactOrigin.GENERATED -> ArtifactUiOrigin.GENERATED
            ArtifactOrigin.SYSTEM -> ArtifactUiOrigin.SYSTEM
        },
    )

    private companion object {
        const val TAG = "ArtifactUseCase"
    }
}

private fun ArtifactDeleteImpact.toUiModel() = ArtifactDeleteImpactUiModel(
    referencedByHistory = referencedByHistory,
    assistantBackgroundCount = assistantBackgroundCount,
    assistantAvatarCount = assistantAvatarCount,
)

private fun ArtifactDeleteResult.toOutcome(): ArtifactDeleteOutcome = when (this) {
    is ArtifactDeleteResult.Completed -> ArtifactDeleteOutcome.Deleted
    is ArtifactDeleteResult.CleanupPending -> ArtifactDeleteOutcome.CleanupPending
    is ArtifactDeleteResult.Rejected -> when (reason) {
        ArtifactDeleteResult.RejectionReason.IN_PROGRESS -> ArtifactDeleteOutcome.InProgress
        ArtifactDeleteResult.RejectionReason.ALREADY_DELETED -> ArtifactDeleteOutcome.AlreadyDeleted
    }
    is ArtifactDeleteResult.Failed -> ArtifactDeleteOutcome.Failed(reason)
}

/** 单个输入编辑器拥有的 draft lease；创建、发布、丢弃和关闭在同一 Mutex 下串行。 */
class ArtifactDraftScope internal constructor(
    private val store: ArtifactStore,
    private val recoveryGate: ApplicationRecoveryGate,
) : AutoCloseable {
    private val closeRequested = AtomicBoolean(false)
    private val mutex = Mutex()
    private val owned = linkedMapOf<String, OwnedArtifact>()

    suspend fun importUrisOrThrow(uris: List<Uri>): List<ArtifactDraftItem> = withOwnershipLock {
        if (uris.isEmpty()) return@withOwnershipLock emptyList()
        val created = mutableListOf<OwnedArtifact>()
        try {
            uris.forEach { uri ->
                val artifact = try {
                    store.createFromUri(uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    throw ArtifactImportException(uri.toString(), error)
                }
                created += artifact
            }
            created.forEach { artifact -> owned[artifact.uri.toString()] = artifact }
            created.map { artifact ->
                ArtifactDraftItem(
                    uri = artifact.uri,
                    displayName = artifact.entity.displayName,
                    mimeType = artifact.entity.mimeType,
                )
            }
        } catch (error: Throwable) {
            rollback(created, "artifact import rollback", error)
            throw error
        }
    }

    suspend fun createTextDocument(text: String): UIMessagePart.Document = withOwnershipLock {
        val artifact = store.createText(text)
        owned[artifact.uri.toString()] = artifact
        UIMessagePart.Document(
            url = artifact.uri.toString(),
            fileName = artifact.entity.displayName,
            mime = artifact.entity.mimeType,
        )
    }

    suspend fun discard(uri: Uri) = withOwnershipLock {
        val key = uri.toString()
        val artifact = owned[key] ?: return@withOwnershipLock
        val result = store.discardUnpublished(artifact)
        if (result.isDiscardTerminal()) owned.remove(key)
    }

    /**
     * Publishes editor-owned artifacts only after the message/reference transaction has committed.
     * Batch validation happens before any creation pin is consumed.
     */
    suspend fun publishCommittedReferences(parts: List<UIMessagePart>) = withContext(NonCancellable) {
        mutex.withLock {
            val committed = parts.collectArtifactUris().mapNotNull(owned::get)
            if (committed.isNotEmpty()) store.publishAllUnpublished(committed)
            committed.forEach { owned.remove(it.uri.toString()) }
            if (closeRequested.get()) releasePinsLocked()
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        if (mutex.tryLock()) {
            try {
                releasePinsLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun ensureOpen() = check(!closeRequested.get()) { "artifact draft scope is closed" }

    private suspend inline fun <T> withOwnershipLock(crossinline action: suspend () -> T): T = mutex.withLock {
        try {
            ensureOpen()
            recoveryGate.awaitReady()
            action()
        } finally {
            if (closeRequested.get()) releasePinsLocked()
        }
    }

    /**
     * ViewModel disposal cannot suspend. Closing therefore releases only the in-process creation
     * pin; it never starts an unowned database deletion. Unrooted ACTIVE artifacts become ordinary
     * GC candidates, while an operation already holding [mutex] performs this handoff on exit.
     */
    private fun releasePinsLocked() {
        owned.values.forEach(store::abandonUnpublished)
        owned.clear()
    }

    private suspend fun rollback(created: List<OwnedArtifact>, reason: String, cause: Throwable) =
        withContext(NonCancellable) {
            created.asReversed().forEach { artifact ->
                try {
                    store.discardUnpublished(artifact).requireDiscarded(reason)
                } catch (cleanup: Throwable) {
                    cause.addSuppressed(cleanup)
                }
            }
        }
}

class ArtifactImportException(source: String, cause: Throwable) :
    IllegalStateException("Failed to import artifact: $source", cause)

private fun List<UIMessagePart>.collectArtifactUris(): Set<String> = buildSet {
    fun collect(parts: List<UIMessagePart>) {
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Image -> add(part.url)
                is UIMessagePart.Document -> add(part.url)
                is UIMessagePart.Audio -> add(part.url)
                is UIMessagePart.Video -> add(part.url)
                is UIMessagePart.Tool -> collect(part.output)
                else -> Unit
            }
        }
    }
    collect(this@collectArtifactUris)
}

private fun ArtifactDeleteResult.isDiscardTerminal(): Boolean =
    this is ArtifactDeleteResult.Completed ||
        this is ArtifactDeleteResult.CleanupPending ||
        this is ArtifactDeleteResult.Rejected && reason == ArtifactDeleteResult.RejectionReason.ALREADY_DELETED
