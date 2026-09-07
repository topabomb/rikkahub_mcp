package net.weero.measix.pilot.service.subassistant

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import net.weero.measix.pilot.data.enterprise.RealmAccess
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

internal data class SubAssistantRunKey(
    val masterConversationId: Uuid,
    val targetAssistantId: ConfigurationReference,
)

private data class SubAssistantRunLease(
    val runId: String,
    val callerAssistantId: ConfigurationReference,
    val targetAssistantId: ConfigurationReference,
    val job: Job,
    val completion: CompletableDeferred<Unit> = CompletableDeferred(),
)

/**
 * 子助手 run 门禁（并发控制域）。
 *
 * 一个 run 的两类挂起资源归同一所有者：
 *  - **运行 lease**（Master+Target 原子占用：target_busy / 结构化取消 / 删除等待）
 *  - **pending ask_user**（Child 运行中等待主聊天 UI 应答的交互）
 *
 * 被 SubAssistantRunCoordinator（执行）与 TurnRecovery（启动恢复收口）共同消费，
 * 恢复入口得以不依赖编排器即可取消全部运行中资源。
 */
class SubAssistantRunGate {
    internal class LeaseHandle internal constructor(
        val job: Job,
        private val closeAction: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) closeAction()
        }
    }

    private data class PendingUserInteraction(
        val masterConversationId: Uuid,
        val realmAccess: RealmAccess,
        val interactionId: String,
        val answer: CompletableDeferred<String>,
    )

    private val leases = ConcurrentHashMap<SubAssistantRunKey, SubAssistantRunLease>()
    private val interactionLock = Any()
    private val pendingUserInteractions = mutableMapOf<String, PendingUserInteraction>()

    internal fun isBusy(key: SubAssistantRunKey): Boolean = leases.containsKey(key)

    internal fun acquireLease(
        key: SubAssistantRunKey,
        runId: String,
        callerAssistantId: ConfigurationReference,
        parentJob: Job?,
    ): LeaseHandle? {
        val job = Job(parentJob)
        val lease = SubAssistantRunLease(runId, callerAssistantId, key.targetAssistantId, job)
        if (leases.putIfAbsent(key, lease) != null) {
            job.cancel()
            lease.completion.complete(Unit)
            return null
        }
        return LeaseHandle(job = lease.job) {
            leases.remove(key, lease)
            lease.job.cancel()
            lease.completion.complete(Unit)
        }
    }

    suspend fun cancelRunsForAssistant(assistantId: ConfigurationReference) {
        val matching = leases.values
            .filter { it.targetAssistantId == assistantId || it.callerAssistantId == assistantId }
            .distinct()
        matching.forEach { lease ->
            val reason = if (lease.targetAssistantId == assistantId) {
                "target_removed"
            } else {
                "target_access_revoked"
            }
            lease.job.cancel(reason)
        }
        matching.forEach { it.completion.await() }
    }

    suspend fun cancelAllRuns(reason: String) {
        val active = leases.values.distinct()
        active.forEach { lease -> lease.job.cancel(reason) }
        active.forEach { it.completion.await() }
    }

    // ---- pending ask_user ----

    /**
     * 登记一个待应答交互；同一 run 已存在 pending 时抛出（防重复桥接）。
     * 返回的 deferred 由 [completeAnswer] 完成或 [cancelPendingInteractions] 取消。
     */
    internal fun registerPendingInteraction(
        masterConversationId: Uuid,
        realmAccess: RealmAccess,
        runId: String,
        interactionId: String,
        owner: Job,
    ): CompletableDeferred<String> = synchronized(interactionLock) {
        check(runId !in pendingUserInteractions) { "Run $runId already has a pending user interaction" }
        val pending = PendingUserInteraction(masterConversationId, realmAccess, interactionId, CompletableDeferred(owner))
        pendingUserInteractions[runId] = pending
        pending.answer
    }

    /** Cleanup belongs to this exact registration; stale cleanup cannot remove its replacement. */
    internal fun unregisterPendingInteraction(runId: String, answer: CompletableDeferred<String>) = synchronized(interactionLock) {
        if (pendingUserInteractions[runId]?.answer === answer) {
            pendingUserInteractions.remove(runId)
            answer.cancel()
        }
    }

    /** Complete only the original root, Session and pending interaction, at most once. */
    internal fun completeAnswer(
        masterConversationId: Uuid,
        realmAccess: RealmAccess,
        runId: String,
        interactionId: String,
        answer: String,
    ): Boolean = synchronized(interactionLock) {
        val pending = pendingUserInteractions[runId] ?: return false
        if (pending.masterConversationId != masterConversationId || pending.realmAccess != realmAccess ||
            pending.interactionId != interactionId) return false
        pending.answer.complete(answer)
    }

    /** Startup recovery cancels every registered waiter before dropping its ownership. */
    fun cancelPendingInteractions() = synchronized(interactionLock) {
        val pending = pendingUserInteractions.values.toList()
        pendingUserInteractions.clear()
        pending.forEach { it.answer.cancel() }
    }
}
