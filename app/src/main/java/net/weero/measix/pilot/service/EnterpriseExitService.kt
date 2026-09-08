package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.*

internal sealed interface EnterpriseExitFailure {
    val reason: String
    data class Closing(val token: EnterpriseExitToken, override val reason: String) : EnterpriseExitFailure
    data class Invalidation(
        val access: RealmAccess.Enterprise,
        val exitReason: EnterpriseExitReason,
        override val reason: String,
    ) : EnterpriseExitFailure
}
internal data class EnterpriseExitResult(val maintenanceFailure: String? = null)

/** Owns accepted exit work; the Session manifest remains the only source of enterprise state. */
internal class EnterpriseExitService(
    private val sessions: EnterpriseSessionController,
    private val synchronization: EnterpriseSynchronizationService,
    private val conversations: ConversationApplicationService,
    private val recoveryGate: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val active = mutableMapOf<RealmAccess.Enterprise, Deferred<EnterpriseExitResult>>()
    private val _failure = MutableStateFlow<EnterpriseExitFailure?>(null)
    val failure = _failure.asStateFlow()

    init {
        scope.launch {
            recoveryGate.state.flatMapLatest { state ->
                if (state == ApplicationRecoveryState.Ready) sessions.observeExitSignals() else emptyFlow()
            }.collect { signal ->
                try {
                    when (signal) {
                        is EnterpriseExitSignal.Closing -> resumeClosing(signal.token)
                        is EnterpriseExitSignal.Expired -> invalidate(signal.access, EnterpriseExitReason.AUTHORIZATION_EXPIRED)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Admission and cleanup failures remain observable against their original Session.
                }
            }
        }
    }

    suspend fun captureRequest(): EnterpriseExitRequest? {
        recoveryGate.awaitReady()
        return sessions.captureExitRequest()
    }

    suspend fun exit(request: EnterpriseExitRequest): EnterpriseExitResult {
        recoveryGate.awaitReady()
        return enqueue(request.access) { sessions.beginExit(request) }.await()
    }

    suspend fun invalidate(access: RealmAccess.Enterprise, reason: EnterpriseExitReason): EnterpriseExitResult {
        recoveryGate.awaitReady()
        return enqueue(access, invalidationReason = reason) { sessions.beginInvalidation(access, reason) }.await()
    }

    suspend fun retry(failure: EnterpriseExitFailure): EnterpriseExitResult = when (failure) {
        is EnterpriseExitFailure.Closing -> retry(failure.token)
        is EnterpriseExitFailure.Invalidation -> invalidate(failure.access, failure.exitReason)
    }

    suspend fun retry(token: EnterpriseExitToken): EnterpriseExitResult {
        recoveryGate.awaitReady()
        return enqueue(token.access) {
            sessions.withClosingSession(token) { Unit }
            token
        }.await()
    }

    private suspend fun resumeClosing(token: EnterpriseExitToken) {
        val pending = mutex.withLock {
            if ((_failure.value as? EnterpriseExitFailure.Closing)?.token == token || sessions.pendingExit() != token) null
            else enqueueLocked(token.access, null) {
                sessions.withClosingSession(token) { Unit }
                token
            }
        }
        pending?.await()
    }

    private suspend fun enqueue(
        access: RealmAccess.Enterprise,
        invalidationReason: EnterpriseExitReason? = null,
        admit: suspend () -> EnterpriseExitToken,
    ): Deferred<EnterpriseExitResult> = mutex.withLock { enqueueLocked(access, invalidationReason, admit) }

    private fun enqueueLocked(
        access: RealmAccess.Enterprise,
        invalidationReason: EnterpriseExitReason?,
        admit: suspend () -> EnterpriseExitToken,
    ): Deferred<EnterpriseExitResult> =
        active[access] ?: scope.async(start = CoroutineStart.LAZY) {
            var token: EnterpriseExitToken? = null
            try {
                token = admit()
                _failure.value = null
                finish(token, duringRecovery = false)
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    val manifest = (sessions.state.value as? EnterpriseState.Available)?.manifest
                    if (manifest?.session?.id == access.sessionId && manifest.session.identity.scope == access.scope) {
                        if (token != null && sessions.pendingExit() == token) {
                            _failure.value = EnterpriseExitFailure.Closing(token, reason(error))
                        } else if (token == null && invalidationReason != null) {
                            _failure.value = EnterpriseExitFailure.Invalidation(access, invalidationReason, reason(error))
                        }
                    }
                }
                throw error
            } finally {
                withContext(NonCancellable) { mutex.withLock { active.remove(access) } }
            }
        }.also { active[access] = it; it.start() }

    /** Runs after Child/Turn recovery and before gate.ready; it must never wait on that gate. */
    suspend fun completeDuringRecovery() {
        if (sessions.state.value !is EnterpriseState.Available) return
        val token = sessions.pendingExit() ?: return
        finish(token, duringRecovery = true)
    }

    private suspend fun finish(token: EnterpriseExitToken, duringRecovery: Boolean): EnterpriseExitResult {
        synchronization.cancelAndAwait(token.access)
        if (duringRecovery) conversations.requireEnterpriseStopped(token) else conversations.stopEnterpriseWork(token)
        sessions.finishExit(token)
        _failure.value = null
        return try {
            sessions.pruneUnusedRevisions()
            EnterpriseExitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EnterpriseExitResult(reason(error))
        }
    }

    private fun reason(error: Exception): String = when (error) {
        is EnterpriseConfigurationException -> error.reason
        is EnterpriseStorageException -> error.reason
        else -> "enterprise_exit_failed"
    }
}
