package net.weero.measix.pilot.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 子助手 run 门禁（并发控制域）。
 *
 * 一个 run 的两类挂起资源归同一所有者：
 *  - **运行 lease**（Master+Target 原子占用：target_busy / 结构化取消 / 删除等待）
 *  - **pending ask_user**（Child 运行中等待主聊天 UI 应答的交互）
 *
 * 被 DelegationCoordinator（执行）与 TurnRecovery（启动恢复收口）共同消费，
 * 恢复入口得以不依赖编排器即可取消全部运行中资源。
 */
class SubAssistantRunGate {
    private data class PendingUserInteraction(
        val interactionId: String,
        val answer: CompletableDeferred<String> = CompletableDeferred(),
    )

    private val runLeases = SubAssistantRunLeaseRegistry()
    private val pendingUserInteractions = ConcurrentHashMap<String, PendingUserInteraction>()

    // ---- 运行 lease ----

    internal fun isBusy(key: SubAssistantRunKey): Boolean = runLeases.isBusy(key)

    internal fun tryAcquire(
        key: SubAssistantRunKey,
        runId: String,
        callerAssistantId: Uuid,
        parentJob: Job?,
    ): SubAssistantRunLease? = runLeases.tryAcquire(key, runId, callerAssistantId, parentJob)

    internal fun release(key: SubAssistantRunKey, lease: SubAssistantRunLease) {
        runLeases.release(key, lease)
    }

    suspend fun cancelRunsForAssistant(assistantId: Uuid) {
        runLeases.cancelForAssistant(assistantId)
    }

    suspend fun cancelAllRuns(reason: String) {
        runLeases.cancelAll(reason)
    }

    // ---- pending ask_user ----

    /**
     * 登记一个待应答交互；同一 run 已存在 pending 时抛出（防重复桥接）。
     * 返回的 deferred 由 [completeAnswer] 完成或 [cancelPendingInteractions] 取消。
     */
    fun registerPendingInteraction(runId: String, interactionId: String): CompletableDeferred<String> {
        val pending = PendingUserInteraction(interactionId)
        check(pendingUserInteractions.putIfAbsent(runId, pending) == null) {
            "Run $runId already has a pending user interaction"
        }
        return pending.answer
    }

    /** 应答/取消路径的 finally 清理：移除该 run 的挂起登记。 */
    fun unregisterPendingInteraction(runId: String) {
        pendingUserInteractions.remove(runId)
    }

    /** 主聊天 UI 应答当前 ask_user；过期或重复 interaction 被拒绝。 */
    fun completeAnswer(runId: String, interactionId: String, answer: String): Boolean {
        val pending = pendingUserInteractions[runId] ?: return false
        if (pending.interactionId != interactionId) return false
        return pending.answer.complete(answer)
    }

    /** 启动恢复：取消全部挂起交互（awaiter 收到 CancellationException）。 */
    fun cancelPendingInteractions() {
        pendingUserInteractions.values.forEach { it.answer.cancel() }
        pendingUserInteractions.clear()
    }
}
