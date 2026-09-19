package net.weero.measix.pilot.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseState

/** Native and Portal callers share one synchronization. Cancelling a waiter does not replay or undo the work. */
internal class EnterpriseSynchronizationService(
    private val sessions: EnterpriseSessionController,
    private val scope: CoroutineScope,
    private val platform: PlatformEnterpriseService,
) {
    private val mutex = Mutex()
    private val active = mutableMapOf<RealmAccess.Enterprise, Deferred<EnterpriseState.Available>>()

    suspend fun prepareExecution(access: RealmAccess.Enterprise): net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion {
        return platform.prepareExecution(access) { synchronize(access) }
    }

    suspend fun cancelAndAwait(access: RealmAccess.Enterprise) {
        val pending = mutex.withLock { active[access]?.also { it.cancel() } }
        pending?.join()
    }

    suspend fun synchronize(access: RealmAccess.Enterprise): EnterpriseState.Available {
        val pending = sessions.withRealmAccess(access) { mutex.withLock {
            active[access] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    sessions.withRealmAccess(access) { Unit }
                    platform.synchronize(access)
                } finally {
                    withContext(NonCancellable) { mutex.withLock { active.remove(access) } }
                }
            }.also { active[access] = it; it.start() }
        } }
        return pending.await()
    }
}
