package net.weero.measix.pilot.service

import android.content.Context
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.portal.*
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal data class EnterpriseJoinConfirmation(val id: Uuid, val platformOrigin: String)

internal enum class EnterpriseResetPath { CONNECTED, STORAGE_FAILURE }

internal data class EnterpriseOverview(
    val selection: RealmSelection?,
    val phase: EnterpriseSessionPhase?,
    val enterpriseName: String?,
    val userName: String?,
    val access: RealmAccess.Enterprise?,
    val generation: Long?,
    val lastSyncMillis: Long?,
    val failure: String?,
    val exitFailure: EnterpriseExitFailure?,
    val switching: Boolean,
    val resetPath: EnterpriseResetPath? = null,
    val reset: EnterpriseDataResetProgress? = null,
    val enrollmentRecoveryFailure: String? = null,
    val recoveryLogoutFailure: String? = null,
    val exitReason: EnterpriseExitReason? = null,
    val platformOrigin: String? = null,
)

/** Native enterprise UI commands share the existing source, Session, synchronization and exit owners. */
internal class EnterpriseApplicationService(
    private val sessions: EnterpriseSessionController,
    private val synchronization: EnterpriseSynchronizationService,
    private val exit: EnterpriseExitService,
    private val dataReset: EnterpriseDataResetService,
    private val portals: PortalDocumentRegistry,
    private val recovery: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
    private val media: PortalMediaStore,
    private val terminals: net.weero.measix.pilot.service.workspace.WorkspaceTerminalRuntime,
    private val speech: SpeechApplicationService,
    private val platform: PlatformEnterpriseService,
) {
    private data class Switching(val request: RealmSwitchRequest, val result: Deferred<RealmSelection>)
    private val mutex = Mutex()
    private val switching = MutableStateFlow<Switching?>(null)
    private val joinMutex = Mutex()
    private var pendingJoin: Pair<EnterpriseJoinConfirmation, EnrollmentMaterial.Platform>? = null
    private val enrollmentRecoveryFailure = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            try {
                recovery.awaitReady()
                platform.recoverPlatformAccess()?.let { synchronization.synchronize(it) }
                enrollmentRecoveryFailure.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("EnterpriseEnrollment", "Platform session recovery could not synchronize", error)
                enrollmentRecoveryFailure.value = error.userVisibleDiagnostic()
            }
        }
    }

    private fun observePresentation(): Flow<EnterprisePresentation> =
        combine(sessions.state, sessions.selectionRevision) { _, _ -> Unit }
            .map { sessions.readPresentation() }
            .distinctUntilChanged()

    // Task progress must remain observable while Session admission waits for host teardown.
    fun observe(): Flow<EnterpriseOverview> = combine(observePresentation(), exit.failure, switching,
        combine(enrollmentRecoveryFailure, dataReset.progress) { resume, reset -> resume to reset },
        combine(exit.recoveryLogoutFailure, platform.pendingLogoutFailure) { exitFailure, pendingFailure ->
            exitFailure to pendingFailure
        }) {
            presentation, exitFailure, activeSwitch, resumeAndReset, logoutFailures ->
            val (resumeFailure, resetProgress) = resumeAndReset
            val available = presentation.state as? EnterpriseState.Available
            val manifest = available?.manifest
            val identity = manifest?.session?.identity ?: manifest?.lastIdentity
            EnterpriseOverview(
                selection = presentation.selection,
                phase = manifest?.phase,
                enterpriseName = identity?.enterpriseName,
                userName = identity?.userName,
                access = manifest?.session?.let { RealmAccess.Enterprise(it.identity.scope, it.id) },
                generation = manifest?.applied?.generation,
                lastSyncMillis = manifest?.lastConfigurationSyncMillis,
                failure = (presentation.state as? EnterpriseState.Failed)?.reason,
                exitFailure = exitFailure,
                switching = activeSwitch != null,
                resetPath = when {
                    presentation.state is EnterpriseState.Loading -> null
                    presentation.state is EnterpriseState.Failed -> EnterpriseResetPath.STORAGE_FAILURE
                    else -> EnterpriseResetPath.CONNECTED
                },
                reset = resetProgress,
                enrollmentRecoveryFailure = resumeFailure,
                recoveryLogoutFailure = logoutFailures.first.takeIf { manifest?.session == null } ?: logoutFailures.second,
                exitReason = manifest?.exitReason,
                platformOrigin = manifest?.session?.platform?.connection?.origin,
            )
        }.distinctUntilChanged()

    suspend fun join(text: String): EnterpriseJoinConfirmation? {
        recovery.awaitReady()
        val material = EnrollmentMaterialParser().parse(text)
        return joinMutex.withLock {
            EnterpriseJoinConfirmation(Uuid.random(), material.platformOrigin).also { pendingJoin = it to material }
        }
    }

    suspend fun dismissJoin(confirmation: EnterpriseJoinConfirmation) = joinMutex.withLock {
        if (pendingJoin?.first == confirmation) pendingJoin = null
    }

    suspend fun confirmJoin(confirmation: EnterpriseJoinConfirmation) {
        recovery.awaitReady()
        val material = joinMutex.withLock {
            val pending = pendingJoin?.takeIf { it.first == confirmation }
                ?: throw EnterpriseConfigurationException("enterprise_enrollment_replaced")
            pendingJoin = null
            pending.second
        }
        val access = platform.enroll(material, android.os.Build.MODEL, net.weero.measix.pilot.BuildConfig.VERSION_NAME)
        enrollmentRecoveryFailure.value = null
        val applied = synchronization.synchronize(access)
        if (applied.configuration != null) {
            val selection = requireNotNull(sessions.readPresentation().selection)
            switchRealm(RealmSwitchRequest(selection, access))
        }
    }
    suspend fun synchronize(access: RealmAccess.Enterprise) { recovery.awaitReady(); synchronization.synchronize(access) }
    suspend fun budgets(selection: RealmSelection, access: RealmAccess.Enterprise): PlatformUserBudgetView {
        recovery.awaitReady()
        if (sessions.readPresentation().selection != selection) {
            throw EnterpriseConfigurationException("enterprise_selection_revoked")
        }
        val result = platform.budgets(access)
        if (sessions.readPresentation().selection != selection) {
            throw EnterpriseConfigurationException("enterprise_selection_revoked")
        }
        return result
    }
    fun runtimeUsageChanges(): Flow<RealmAccess.Enterprise> = platform.runtimeUsageChanged
    suspend fun localDataReset(request: EnterpriseDataResetRequest) = dataReset.reset(request)
    suspend fun retryLocalDataReset() = dataReset.retryReset()

    suspend fun captureExitRequest(): EnterpriseExitRequest? = exit.captureRequest()
    suspend fun exit(request: EnterpriseExitRequest): EnterpriseExitResult = exit.exit(request)
    suspend fun retryExit(failure: EnterpriseExitFailure) { exit.retry(failure) }

    suspend fun openPortal(context: Context, selection: RealmSelection, onClosed: (PortalClosure) -> Unit): PortalWebView {
        return openPortal(context, selection, PortalDestination.HOME, onClosed)
    }

    suspend fun openPortal(context: Context, selection: RealmSelection, destination: PortalDestination,
        onClosed: (PortalClosure) -> Unit): PortalWebView {
        recovery.awaitReady()
        val access = selection.access as? RealmAccess.Enterprise
            ?: throw EnterpriseConfigurationException("enterprise_session_required")
        val source = PortalPageSource(platform.createPortalGrant(access), destination)
        return PortalWebView.open(context, selection, sessions, synchronization, scope, portals, source,
            { document -> PortalNativeActions(context, document, sessions, exit, media.open(document.id),
                AndroidPortalCaptureFactory(context, scope), scope) }, onClosed)
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
                speech.revoke(previous)
                terminals.revokeViewports(previous)
                if (previous is RealmAccess.Enterprise) {
                    val captured = portals.capture(previous)
                    receipt = captured
                    captured.revoke(PortalCloseReason.AUTHORIZATION_REVOKED)
                    captured.awaitHostsClosed()
                }
            }
        } catch (error: Exception) {
            failure = error
        }
        try {
            // The accepted switch owns this receipt even if its caller or application scope is cancelled.
            withContext(NonCancellable) {
                var portalFailure: Exception? = null
                try { receipt?.awaitClosed() } catch (error: Exception) { portalFailure = error }
                try { speech.closeRealm(request.selection.access) } catch (error: Exception) {
                    if (portalFailure == null) throw error else portalFailure.addSuppressed(error)
                }
                portalFailure?.let { throw it }
            }
        } catch (cleanup: Exception) {
            if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup)
        } finally {
            withContext(NonCancellable) { mutex.withLock { switching.value = null } }
        }
        failure?.let { throw it }
        return requireNotNull(selected)
    }
}
