package net.weero.measix.pilot.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseSnapshotCompatibilityException
import net.weero.measix.pilot.data.enterprise.PlatformHttpException
import net.weero.measix.pilot.data.enterprise.EnterpriseSnapshotContentException
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal enum class EnterpriseSynchronizationIssue { UPDATE_APP, UPDATE_PLATFORM, UNSUPPORTED_VERSION, INVALID_CONFIGURATION, NETWORK, FAILED }

internal data class EnterpriseSynchronizationFailure(
    val issue: EnterpriseSynchronizationIssue,
    val diagnostic: String,
) {
    companion object {
        fun from(error: Exception): EnterpriseSynchronizationFailure {
            val issue = when (error) {
                is EnterpriseSnapshotCompatibilityException -> when {
                    error.receivedSchemas.isEmpty() -> EnterpriseSynchronizationIssue.UNSUPPORTED_VERSION
                    error.receivedSchemas.all { it > error.supportedSchemas.max() } -> EnterpriseSynchronizationIssue.UPDATE_APP
                    error.receivedSchemas.all { it < error.supportedSchemas.min() } -> EnterpriseSynchronizationIssue.UPDATE_PLATFORM
                    else -> EnterpriseSynchronizationIssue.UNSUPPORTED_VERSION
                }
                is EnterpriseSnapshotContentException -> EnterpriseSynchronizationIssue.INVALID_CONFIGURATION
                is PlatformHttpException -> EnterpriseSynchronizationIssue.FAILED
                is java.io.IOException -> EnterpriseSynchronizationIssue.NETWORK
                else -> EnterpriseSynchronizationIssue.FAILED
            }
            return EnterpriseSynchronizationFailure(issue, error.userVisibleDiagnostic())
        }
    }
}

/** A synchronization observation is not an execution permit or a second persisted configuration. */
internal data class EnterpriseSynchronizationStatus(
    val access: RealmAccess.Enterprise,
    val syncing: Boolean,
    val failure: EnterpriseSynchronizationFailure? = null,
)

/** UI command receipts refer to this attempt; a replaced Session receives neither its notice nor its failure. */
internal enum class EnterpriseSynchronizationCommandResult { COMPLETED, FAILURE_PRESENTED, SUPERSEDED }

/** Native and Portal callers share one synchronization. Cancelling a waiter does not replay or undo the work. */
internal class EnterpriseSynchronizationService(
    private val sessions: EnterpriseSessionController,
    private val scope: CoroutineScope,
    private val platform: PlatformEnterpriseService,
) {
    private val mutex = Mutex()
    private data class Attempt(val result: Result<EnterpriseState.Available>, val observationPublished: Boolean)
    private val active = mutableMapOf<RealmAccess.Enterprise, Deferred<Attempt>>()
    private val _status = MutableStateFlow<EnterpriseSynchronizationStatus?>(null)
    val status = _status.asStateFlow()

    private fun publish(status: EnterpriseSynchronizationStatus): Boolean {
        var published = false
        _status.update { previous ->
            val session = (sessions.state.value as? EnterpriseState.Available)?.manifest?.session
            published = session?.id == status.access.sessionId && session.identity.scope == status.access.scope
            if (published) status else previous
        }
        return published
    }

    suspend fun prepareExecution(access: RealmAccess.Enterprise): net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion {
        // Failed synchronization remains an explicit recovery boundary, never an implicit retry on send.
        _status.value?.takeIf { it.access == access }?.failure?.let { failure ->
            sessions.withRealmAccess(access) { Unit }
            throw EnterpriseConfigurationException("platform_runtime_synchronization_required",
                "Synchronize enterprise configuration manually before starting another operation. ${failure.diagnostic}")
        }
        return platform.prepareExecution(access)
    }

    suspend fun cancelAndAwait(access: RealmAccess.Enterprise) {
        val pending = mutex.withLock { active[access]?.also { it.cancel() } }
        pending?.join()
    }

    suspend fun synchronize(access: RealmAccess.Enterprise): EnterpriseState.Available =
        synchronizeAttempt(access).result.getOrThrow()

    /** Native commands consume the attempt receipt; they never infer failure ownership from exception types. */
    suspend fun synchronizeForPresentation(access: RealmAccess.Enterprise): EnterpriseSynchronizationCommandResult {
        val attempt = synchronizeAttempt(access)
        return when {
            !attempt.observationPublished -> EnterpriseSynchronizationCommandResult.SUPERSEDED
            attempt.result.isSuccess -> EnterpriseSynchronizationCommandResult.COMPLETED
            else -> EnterpriseSynchronizationCommandResult.FAILURE_PRESENTED
        }
    }

    private suspend fun synchronizeAttempt(access: RealmAccess.Enterprise): Attempt {
        val pending = sessions.withRealmAccess(access) { mutex.withLock {
            active[access] ?: scope.async(start = CoroutineStart.LAZY) {
                val previous = _status.value?.takeIf { it.access == access }?.failure
                publish(EnterpriseSynchronizationStatus(access, syncing = true, failure = previous))
                try {
                    sessions.withRealmAccess(access) { Unit }
                    val applied = platform.synchronize(access)
                    Attempt(Result.success(applied), publish(EnterpriseSynchronizationStatus(access, syncing = false)))
                } catch (cancelled: CancellationException) {
                    publish(EnterpriseSynchronizationStatus(access, syncing = false, failure = previous))
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.e("EnterpriseSynchronization", "Enterprise configuration synchronization failed", error)
                    Attempt(Result.failure(error), publish(EnterpriseSynchronizationStatus(access, syncing = false,
                        failure = EnterpriseSynchronizationFailure.from(error))))
                } finally {
                    withContext(NonCancellable) { mutex.withLock { active.remove(access) } }
                }
            }.also { active[access] = it; it.start() }
        } }
        return pending.await()
    }
}
