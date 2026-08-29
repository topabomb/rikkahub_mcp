package net.weero.measix.pilot.service

import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore

/** UI 只持有 typed identity，不解析 `artifact:<id>` 一类字符串协议。 */
sealed interface ManagedFileKey {
    data class Upload(val artifactId: Long) : ManagedFileKey
    data class Generated(val mediaId: Int) : ManagedFileKey
}

/** 设置文件页统一的托管文件投影：上传与生成媒体共用同一形状。 */
data class ManagedFileUiModel(
    val key: ManagedFileKey,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val origin: ArtifactUiOrigin?,
    val createdAt: Long,
    val prompt: String?,
    val modelId: String?,
)

data class ManagedStorageUiModel(
    val count: Int,
    val sizeBytes: Long,
)

/**
 * 设置域唯一文件读端口。所有 read 在全局 recovery gate 后开始，避免页面观察到尚未 reconcile 的
 * `.pending` / `.deleting` 或缺 payload row；本类只组合既有 owner 的只读投影。
 */
class FileManagementQueryService(
    private val artifactUseCase: ArtifactUseCase,
    private val generatedMediaStore: GeneratedMediaStore,
    private val recoveryGate: ApplicationRecoveryGate,
    private val clock: Clock = Clock.System,
) {
    fun observeUploads(): Flow<List<ManagedFileUiModel>> = flow {
        recoveryGate.awaitReady()
        emitAll(artifactUseCase.observeUploads().map { artifacts -> artifacts.map(ArtifactUiModel::toManaged) })
    }

    fun observeGenerated(): Flow<List<ManagedFileUiModel>> = flow {
        recoveryGate.awaitReady()
        emitAll(generatedMediaStore.observe().map { entities ->
            entities.map { it.toManaged(generatedMediaStore) }
        })
    }

    suspend fun candidateCount(category: FileCleanupCategory, range: FileCleanupRange): Int {
        recoveryGate.awaitReady()
        val cutoff = cutoffFor(range, clock.now().toEpochMilliseconds())
        return when (category) {
            FileCleanupCategory.UPLOAD -> artifactUseCase.uploadCandidateCount(cutoff)
            FileCleanupCategory.GENERATED_IMAGES -> generatedMediaStore.candidateCount(cutoff)
        }
    }

    suspend fun inspectUpload(key: ManagedFileKey.Upload): ArtifactDeleteImpactUiModel? {
        recoveryGate.awaitReady()
        return artifactUseCase.inspect(key.artifactId)
    }

    suspend fun storageStats(): ManagedStorageUiModel {
        recoveryGate.awaitReady()
        val (uploadCount, uploadSize) = artifactUseCase.uploadStats()
        val generated = generatedMediaStore.countCommitted()
        return ManagedStorageUiModel(
            count = uploadCount + generated.count,
            sizeBytes = uploadSize + generated.sizeBytes,
        )
    }
}

private fun ArtifactUiModel.toManaged(): ManagedFileUiModel = ManagedFileUiModel(
    key = ManagedFileKey.Upload(id),
    contentUri = contentUri,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    origin = origin,
    createdAt = createdAt,
    prompt = null,
    modelId = null,
)

private fun GenMediaEntity.toManaged(store: GeneratedMediaStore): ManagedFileUiModel {
    val file = store.resolveCanonicalFile(this)
    return ManagedFileUiModel(
        key = ManagedFileKey.Generated(id),
        contentUri = "file://${file.absolutePath}",
        displayName = file.name,
        mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/png"
        },
        sizeBytes = file.takeIf { it.isFile }?.length() ?: 0L,
        origin = null,
        createdAt = createAt,
        prompt = prompt,
        modelId = modelId,
    )
}
