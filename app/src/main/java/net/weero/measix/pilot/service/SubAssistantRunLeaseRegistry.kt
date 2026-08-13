package net.weero.measix.pilot.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal data class SubAssistantRunKey(
    val masterConversationId: Uuid,
    val targetAssistantId: Uuid,
)

internal data class SubAssistantRunLease(
    val runId: String,
    val callerAssistantId: Uuid,
    val targetAssistantId: Uuid,
    val job: Job,
    val completion: CompletableDeferred<Unit> = CompletableDeferred(),
)

/**
 * Master + Target 的原子运行 lease。
 *
 * 同一个 lease 同时承担 target_busy、结构化取消和删除等待，避免多张索引表发生残留或错配。
 */
internal class SubAssistantRunLeaseRegistry {
    private val leases = ConcurrentHashMap<SubAssistantRunKey, SubAssistantRunLease>()

    fun isBusy(key: SubAssistantRunKey): Boolean = leases.containsKey(key)

    fun tryAcquire(
        key: SubAssistantRunKey,
        runId: String,
        callerAssistantId: Uuid,
        parentJob: Job?,
    ): SubAssistantRunLease? {
        val job = Job(parentJob)
        val lease = SubAssistantRunLease(runId, callerAssistantId, key.targetAssistantId, job)
        if (leases.putIfAbsent(key, lease) == null) return lease

        job.cancel()
        lease.completion.complete(Unit)
        return null
    }

    fun release(key: SubAssistantRunKey, lease: SubAssistantRunLease) {
        leases.remove(key, lease)
        lease.job.cancel()
        lease.completion.complete(Unit)
    }

    suspend fun cancelForAssistant(assistantId: Uuid) {
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

    suspend fun cancelAll(reason: String) {
        val active = leases.values.distinct()
        active.forEach { lease ->
            lease.job.cancel(reason)
        }
        active.forEach { it.completion.await() }
    }
}
