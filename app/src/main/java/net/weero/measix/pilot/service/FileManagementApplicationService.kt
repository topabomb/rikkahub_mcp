package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
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
 * 设置文件页的唯一写端口：跨 `ArtifactUseCase`（上传）与 `GeneratedMediaStore`（生成媒体）
 * 两个 owner 编排范围清理，用注入 Clock 一次计算 cutoff，避免长任务里时间漂移。
 */
class FileManagementApplicationService(
    private val artifactUseCase: ArtifactUseCase,
    private val generatedMediaStore: GeneratedMediaStore,
    private val recoveryGate: ApplicationRecoveryGate,
    private val clock: Clock = Clock.System,
) {
    suspend fun cleanup(category: FileCleanupCategory, range: FileCleanupRange): FileCleanupResult {
        recoveryGate.awaitReady()
        val cutoff = cutoffFor(range, clock.now().toEpochMilliseconds())
        return when (category) {
            FileCleanupCategory.UPLOAD -> artifactUseCase.deleteUploadsCreatedBefore(cutoff).let { result ->
                FileCleanupResult(
                    deleted = result.deleted,
                    cleanupPending = result.cleanupPending,
                    skippedInProgress = result.skippedInProgress,
                    failed = result.failed,
                )
            }
            FileCleanupCategory.GENERATED_IMAGES -> generatedMediaStore.deleteCreatedBefore(cutoff).let { result ->
                FileCleanupResult(
                    deleted = result.deleted,
                    cleanupPending = result.cleanupPending,
                    skippedInProgress = 0,
                    failed = result.failed,
                )
            }
        }
    }

    suspend fun deleteUpload(key: ManagedFileKey.Upload): ArtifactDeleteOutcome {
        recoveryGate.awaitReady()
        return artifactUseCase.deleteUserRequested(key.artifactId)
    }

    suspend fun deleteGenerated(key: ManagedFileKey.Generated): Boolean {
        recoveryGate.awaitReady()
        return generatedMediaStore.delete(key.mediaId)
    }
}

internal fun cutoffFor(range: FileCleanupRange, nowMillis: Long): Long = when (range) {
    FileCleanupRange.All -> Long.MAX_VALUE
    is FileCleanupRange.OlderThanDays -> nowMillis - range.days.days.inWholeMilliseconds
}
