package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.portal.PortalCloseReason
import net.weero.measix.pilot.service.portal.PortalDocumentRegistry
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal sealed interface EnterpriseExitFailure {
    val reason: String
    data class Closing(val token: EnterpriseExitToken, override val reason: String) : EnterpriseExitFailure
    data class Invalidation(
        val access: RealmAccess.Enterprise,
        val exitReason: EnterpriseExitReason,
        override val reason: String,
    ) : EnterpriseExitFailure
}
internal data class EnterpriseExitResult(val maintenanceFailure: String? = null, val remoteLogoutFailure: String? = null)

/** Owns accepted exit work; the Session manifest remains the only source of enterprise state. */
internal class EnterpriseExitService(
    private val sessions: EnterpriseSessionController,
    private val synchronization: EnterpriseSynchronizationService,
    private val conversations: ConversationApplicationService,
    private val recoveryGate: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
    private val portals: PortalDocumentRegistry,
    private val images: net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator,
    private val terminals: net.weero.measix.pilot.service.workspace.WorkspaceTerminalRuntime,
    private val mcp: net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator,
    private val speech: SpeechApplicationService,
    private val identityData: EnterpriseIdentityDataDisposer,
    private val platformLogout: suspend (EnterpriseExitToken) -> Unit,
) {
    private val mutex = Mutex()
    private data class ExitTask(
        val reason: EnterpriseExitReason,
        val accepted: Deferred<Unit>,
        val result: Deferred<EnterpriseExitResult>,
    )
    private val active = mutableMapOf<RealmAccess.Enterprise, ExitTask>()
    private val _failure = MutableStateFlow<EnterpriseExitFailure?>(null)
    val failure = _failure.asStateFlow()
    private val _recoveryLogoutFailure = MutableStateFlow<String?>(null)
    val recoveryLogoutFailure = _recoveryLogoutFailure.asStateFlow()

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
        return enqueue(request.access, EnterpriseExitReason.USER_REQUEST) { sessions.beginExit(request) }.result.await()
    }

    suspend fun invalidate(access: RealmAccess.Enterprise, reason: EnterpriseExitReason): EnterpriseExitResult {
        recoveryGate.awaitReady()
        if (reason == EnterpriseExitReason.IDENTITY_DELETED) {
            val token = sessions.beginInvalidation(access, reason)
            return enqueueIdentityDeletion(token).result.await()
        }
        return enqueue(access, reason, invalidationReason = reason) { sessions.beginInvalidation(access, reason) }.result.await()
    }

    /** Returns once CLOSING is durable; cleanup remains owned by this service. */
    suspend fun acceptInvalidation(access: RealmAccess.Enterprise, reason: EnterpriseExitReason) {
        recoveryGate.awaitReady()
        if (reason == EnterpriseExitReason.IDENTITY_DELETED) {
            val token = sessions.beginInvalidation(access, reason)
            enqueueIdentityDeletion(token).accepted.await()
            return
        }
        enqueue(access, reason, invalidationReason = reason) {
            sessions.beginInvalidation(access, reason)
        }.accepted.await()
    }

    /** A deletion terminal state durably upgrades any weaker close and owns the eventual retry. */
    private fun enqueueIdentityDeletion(token: EnterpriseExitToken): ExitTask {
        val accepted = CompletableDeferred<Unit>().apply { complete(Unit) }
        val result = scope.async(start = CoroutineStart.LAZY) {
            val previous = mutex.withLock { active[token.access] }
            if (previous != null) {
                try {
                    previous.result.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The upgraded durable token intentionally makes weaker work stale.
                }
            }
            val pending = sessions.pendingExit()
            if (pending == null) return@async EnterpriseExitResult()
            check(pending == token) { "stale_enterprise_identity_deletion" }
            enqueue(token.access, token.reason) {
                sessions.withClosingSession(token) {}
                token
            }.result.await()
        }
        return ExitTask(token.reason, accepted, result).also { result.start() }
    }

    suspend fun retry(failure: EnterpriseExitFailure): EnterpriseExitResult = when (failure) {
        is EnterpriseExitFailure.Closing -> retry(failure.token)
        is EnterpriseExitFailure.Invalidation -> invalidate(failure.access, failure.exitReason)
    }

    suspend fun retry(token: EnterpriseExitToken): EnterpriseExitResult {
        recoveryGate.awaitReady()
        return enqueue(token.access, token.reason) {
            sessions.withClosingSession(token) {}
            token
        }.result.await()
    }

    private suspend fun resumeClosing(token: EnterpriseExitToken) {
        val pending = mutex.withLock {
            if ((_failure.value as? EnterpriseExitFailure.Closing)?.token == token || sessions.pendingExit() != token) null
            else enqueueLocked(token.access, token.reason, null) {
                sessions.withClosingSession(token) {}
                token
            }
        }
        pending?.result?.await()
    }

    private suspend fun enqueue(
        access: RealmAccess.Enterprise,
        requestedReason: EnterpriseExitReason,
        invalidationReason: EnterpriseExitReason? = null,
        admit: suspend () -> EnterpriseExitToken,
    ): ExitTask = mutex.withLock { enqueueLocked(access, requestedReason, invalidationReason, admit) }

    private fun enqueueLocked(
        access: RealmAccess.Enterprise,
        requestedReason: EnterpriseExitReason,
        invalidationReason: EnterpriseExitReason?,
        admit: suspend () -> EnterpriseExitToken,
    ): ExitTask {
        active[access]?.let { return it }
        val accepted = CompletableDeferred<Unit>()
        val result = scope.async(start = CoroutineStart.LAZY) {
            var token: EnterpriseExitToken? = null
            try {
                token = admit()
                accepted.complete(Unit)
                _failure.value = null
                finish(token, duringRecovery = false)
            } catch (error: Exception) {
                accepted.completeExceptionally(error)
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
        }
        return ExitTask(requestedReason, accepted, result).also {
            active[access] = it
            result.start()
        }
    }

    /** Runs after Child/Turn recovery and before gate.ready; it must never wait on that gate. */
    suspend fun completeDuringRecovery() {
        if (sessions.state.value !is EnterpriseState.Available) return
        val token = sessions.pendingExit() ?: return
        if (token.reason == EnterpriseExitReason.LOCAL_DATA_RESET) {
            throw EnterpriseConfigurationException("enterprise_reset_in_progress")
        }
        _recoveryLogoutFailure.value = finish(token, duringRecovery = true).remoteLogoutFailure
    }

    /** Shared stop barrier after Session admission is durably revoked. */
    internal suspend fun closeEnterpriseDomain(token: EnterpriseExitToken, duringRecovery: Boolean = false) {
        sessions.awaitPlatformOperations(token.access)
        supervisorScope {
            val cleanup = listOf(
                async { portals.closeAndAwait(token.access, PortalCloseReason.AUTHORIZATION_REVOKED) },
                async { synchronization.cancelAndAwait(token.access) },
                async { images.cancelAndAwait(token.access) },
                async { terminals.closeRealm(token.access) },
                async { mcp.closeRealm(token.access) },
                async { speech.closeRealm(token.access) },
                async {
                    if (duringRecovery) conversations.requireEnterpriseStopped(token) else conversations.stopEnterpriseWork(token)
                },
            )
            var failure: Exception? = null
            cleanup.forEach { pending ->
                try { pending.await() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (failure == null) failure = error else if (error !== failure) requireNotNull(failure).addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    private suspend fun finish(token: EnterpriseExitToken, duringRecovery: Boolean): EnterpriseExitResult {
        closeEnterpriseDomain(token, duringRecovery)
        val effectiveToken = sessions.pendingExit()?.takeIf { it.access == token.access } ?: token
        val logoutFailure = if (effectiveToken.reason == EnterpriseExitReason.USER_REQUEST) try {
            platformLogout(effectiveToken)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("EnterpriseExit", "Platform logout was not confirmed", error)
            error.userVisibleDiagnostic()
        } else null
        if (effectiveToken.reason == EnterpriseExitReason.IDENTITY_DELETED) {
            identityData.clear(effectiveToken.access.scope)
        }
        sessions.finishExit(effectiveToken)
        _failure.value = null
        return try {
            sessions.pruneUnusedRevisions()
            EnterpriseExitResult(logoutFailure, logoutFailure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EnterpriseExitResult(listOfNotNull(logoutFailure, reason(error)).joinToString("; "), logoutFailure)
        }
    }

    private fun reason(error: Exception): String = when (error) {
        is EnterpriseConfigurationException -> error.reason
        is EnterpriseStorageException -> error.reason
        else -> error.userVisibleDiagnostic()
    }
}
