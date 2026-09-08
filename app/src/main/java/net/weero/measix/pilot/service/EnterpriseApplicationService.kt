package net.weero.measix.pilot.service

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.portal.*

internal data class EnterpriseOverview(
    val selection: RealmSelection?,
    val phase: EnterpriseSessionPhase?,
    val enterpriseName: String?,
    val userName: String?,
    val access: RealmAccess.Enterprise?,
    val isLocal: Boolean,
    val generation: Long?,
    val lastSyncMillis: Long?,
    val failure: String?,
    val exitFailure: EnterpriseExitFailure?,
    val switching: Boolean,
)

/** Native enterprise UI commands share the existing source, Session, synchronization and exit owners. */
internal class EnterpriseApplicationService(
    private val sessions: EnterpriseSessionController,
    private val source: LocalEnterpriseSource,
    private val synchronization: EnterpriseSynchronizationService,
    private val exit: EnterpriseExitService,
    private val portals: PortalDocumentRegistry,
    private val recovery: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
) {
    private data class Switching(val request: RealmSwitchRequest, val result: Deferred<RealmSelection>)
    private val mutex = Mutex()
    private val switching = MutableStateFlow<Switching?>(null)

    private fun observePresentation(): Flow<EnterprisePresentation> =
        combine(sessions.state, sessions.selectionRevision) { _, _ -> Unit }
            .map { sessions.readPresentation() }
            .distinctUntilChanged()

    // Task progress must remain observable while Session admission waits for host teardown.
    fun observe(): Flow<EnterpriseOverview> = combine(observePresentation(), exit.failure, switching) {
            presentation, exitFailure, activeSwitch ->
            val available = presentation.state as? EnterpriseState.Available
            val manifest = available?.manifest
            val identity = manifest?.session?.identity ?: manifest?.lastIdentity
            EnterpriseOverview(
                selection = presentation.selection,
                phase = manifest?.phase,
                enterpriseName = identity?.enterpriseName,
                userName = identity?.userName,
                access = manifest?.session?.let { RealmAccess.Enterprise(it.identity.scope, it.id) },
                isLocal = identity?.authority?.isLocal == true,
                generation = manifest?.applied?.generation,
                lastSyncMillis = manifest?.lastConfigurationSyncMillis,
                failure = (presentation.state as? EnterpriseState.Failed)?.reason,
                exitFailure = exitFailure,
                switching = activeSwitch != null,
            )
        }.distinctUntilChanged()

    suspend fun joinExample() { recovery.awaitReady(); source.enrollExample() }
    suspend fun join(text: String) { recovery.awaitReady(); source.enroll(text) }
    suspend fun exampleEnrollmentText(): String { recovery.awaitReady(); return source.exampleEnrollmentText() }
    suspend fun synchronize(access: RealmAccess.Enterprise) { recovery.awaitReady(); synchronization.synchronize(access) }
    suspend fun captureExitRequest(): EnterpriseExitRequest? = exit.captureRequest()
    suspend fun exit(request: EnterpriseExitRequest) { exit.exit(request) }
    suspend fun retryExit(failure: EnterpriseExitFailure) { exit.retry(failure) }

    suspend fun openPortal(context: Context, selection: RealmSelection, onClosed: (PortalClosure) -> Unit): PortalWebView {
        recovery.awaitReady()
        return PortalWebView.open(context, selection, sessions, synchronization, scope, portals, onClosed)
    }

    suspend fun switchRealm(request: RealmSwitchRequest): RealmSelection {
        recovery.awaitReady()
        val pending = mutex.withLock {
            switching.value?.let { current ->
                if (current.request != request) throw EnterpriseConfigurationException("enterprise_switch_in_progress")
                return@withLock current.result
            }
            scope.coroutineContext.ensureActive()
            scope.async(start = CoroutineStart.LAZY) { performSwitch(request) }.also { pending ->
                switching.value = Switching(request, pending)
                pending.start()
            }
        }
        return pending.await()
    }

    private suspend fun performSwitch(request: RealmSwitchRequest): RealmSelection {
        var receipt: PortalCloseReceipt? = null
        var selected: RealmSelection? = null
        var failure: Exception? = null
        try {
            selected = sessions.switchRealm(request) { previous ->
                if (previous is RealmAccess.Enterprise) {
                    receipt = portals.capture(previous)
                    receipt!!.revoke(PortalCloseReason.AUTHORIZATION_REVOKED)
                    receipt!!.awaitHostsClosed()
                }
            }
        } catch (error: Exception) {
            failure = error
        }
        try {
            // The accepted switch owns this receipt even if its caller or application scope is cancelled.
            withContext(NonCancellable) { receipt?.awaitClosed() }
        } catch (cleanup: Exception) {
            if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup)
        } finally {
            withContext(NonCancellable) { mutex.withLock { switching.value = null } }
        }
        failure?.let { throw it }
        return requireNotNull(selected)
    }
}
