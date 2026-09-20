package net.weero.measix.pilot.ui.pages.enterprise

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.EnterpriseApplicationService
import net.weero.measix.pilot.service.ENROLLMENT_EXPIRED
import net.weero.measix.pilot.service.portal.PortalClosure
import net.weero.measix.pilot.service.portal.PortalCloseReason
import net.weero.measix.pilot.service.portal.PortalFailure
import net.weero.measix.pilot.service.portal.PortalHostUnavailable
import net.weero.measix.pilot.service.portal.PortalDestination
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import kotlin.uuid.Uuid

internal data class PortalPresentation(
    val id: Uuid = Uuid.random(),
    val selection: RealmSelection,
    val destination: PortalDestination = PortalDestination.HOME,
)
internal data class EnterpriseExitConfirmation(val request: EnterpriseExitRequest, val enterpriseName: String?)
internal data class EnterpriseAddressEditor(val selection: RealmSelection, val origin: String)
internal data class EnterpriseResetConfirmation(
    val request: EnterpriseDataResetRequest,
    val path: net.weero.measix.pilot.service.EnterpriseResetPath,
)

internal data class EnterpriseBudgetPresentation(
    val selection: RealmSelection,
    val access: RealmAccess.Enterprise,
    val loading: Boolean,
    val value: PlatformUserBudgetView? = null,
    val failure: String? = null,
    val stale: Boolean = false,
)

internal class EnterpriseVM(private val service: EnterpriseApplicationService) : ViewModel() {
    val overview = service.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    data class Failure(
        val resource: Int,
        val selection: RealmSelection?,
        val arguments: List<String> = emptyList(),
        val detail: String? = null,
    )
    private val _error = MutableStateFlow<Failure?>(null)
    val error = combine(_error, overview) { value, state ->
        value?.takeIf { it.selection == null || it.selection == state?.selection }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private data class Notice(val resource: Int, val selection: RealmSelection? = null)
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice = combine(_notice, overview) { value, state ->
        value?.takeIf { it.selection == null || it.selection == state?.selection }?.resource
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _exitRequest = MutableStateFlow<EnterpriseExitConfirmation?>(null)
    val exitRequest = _exitRequest.asStateFlow()
    private val _portal = MutableStateFlow<PortalPresentation?>(null)
    val portal = _portal.asStateFlow()
    private val _joinConfirmation = MutableStateFlow<net.weero.measix.pilot.service.EnterpriseJoinConfirmation?>(null)
    val joinConfirmation = _joinConfirmation.asStateFlow()
    private val _addressEditor = MutableStateFlow<EnterpriseAddressEditor?>(null)
    val addressEditor = _addressEditor.asStateFlow()
    private val _resetChoice = MutableStateFlow<net.weero.measix.pilot.service.EnterpriseResetPath?>(null)
    val resetChoice = _resetChoice.asStateFlow()
    private val _resetConfirmation = MutableStateFlow<EnterpriseResetConfirmation?>(null)
    val resetConfirmation = _resetConfirmation.asStateFlow()
    private val _budgets = MutableStateFlow<EnterpriseBudgetPresentation?>(null)
    val budgets = combine(_budgets, overview) { value, state ->
        value?.takeIf { it.selection == state?.selection && it.access == state.access }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var budgetListenerStarted = false
    private var budgetRefreshPending = false

    fun refreshBudgets() {
        if (!budgetListenerStarted) {
            budgetListenerStarted = true
            viewModelScope.launch {
                service.runtimeUsageChanges().collect { access ->
                    if (overview.value?.access == access) refreshBudgets()
                }
            }
        }
        val state = overview.value ?: return
        val selection = state.selection ?: return
        val access = state.access ?: return
        val current = _budgets.value?.takeIf { it.selection == selection && it.access == access }
        if (current?.loading == true) {
            budgetRefreshPending = true
            return
        }
        budgetRefreshPending = false
        _budgets.value = EnterpriseBudgetPresentation(selection, access, loading = true,
            value = current?.value, stale = current?.value != null)
        viewModelScope.launch {
            try {
                val value = service.budgets(selection, access)
                if (overview.value?.selection == selection && overview.value?.access == access) {
                    _budgets.value = EnterpriseBudgetPresentation(selection, access, loading = false, value = value)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (overview.value?.selection == selection && overview.value?.access == access) {
                    _budgets.value = EnterpriseBudgetPresentation(selection, access, loading = false,
                        value = current?.value, failure = error.userVisibleDiagnostic(), stale = current?.value != null)
                }
            } finally {
                if (budgetRefreshPending && overview.value?.selection == selection && overview.value?.access == access) {
                    budgetRefreshPending = false
                    refreshBudgets()
                }
            }
        }
    }

    fun join(text: String) = command(enrollment = true) { _joinConfirmation.value = service.join(text) }
    fun dismissJoin() {
        val original = _joinConfirmation.value ?: return
        _joinConfirmation.value = null
        viewModelScope.launch { service.dismissJoin(original) }
    }
    fun confirmJoin() {
        val original = _joinConfirmation.value ?: return
        command(enrollment = true) {
            _joinConfirmation.value = null
            service.confirmJoin(original)
        }
    }
    fun scanFailed() { _error.value = Failure(R.string.enterprise_scan_failure, overview.value?.selection) }
    fun synchronize() {
        val selection = overview.value?.selection ?: return
        overview.value?.access?.let { access -> command(isCurrent = { overview.value?.selection == selection }) {
            service.synchronize(access)
            if (overview.value?.access == access) _notice.value = Notice(R.string.enterprise_sync_completed, selection)
        } }
    }
    fun editAddress() {
        val state = overview.value ?: return
        val selection = state.selection ?: return
        val origin = state.platformOrigin ?: return
        if (selection.access !is RealmAccess.Enterprise) return
        _addressEditor.value = EnterpriseAddressEditor(selection, origin)
    }
    fun dismissAddressEditor() { _addressEditor.value = null }
    fun changeAddress(origin: String) {
        val editor = _addressEditor.value ?: return
        _addressEditor.value = null
        command(
            failureMessage = R.string.enterprise_address_change_failed,
            isCurrent = { overview.value?.selection == editor.selection },
        ) {
            service.changeAddress(editor.selection, origin)
            if (overview.value?.selection == editor.selection) {
                _notice.value = Notice(R.string.enterprise_address_changed, editor.selection)
            }
        }
    }
    fun switchSpace(onSelected: () -> Unit) {
        val state = overview.value ?: return
        val selected = state.selection ?: return
        val target = if (selected.access is RealmAccess.Enterprise) RealmAccess.Personal else state.access ?: return
        command { service.switchRealm(RealmSwitchRequest(selected, target)); onSelected() }
    }
    fun requestExit() = command {
        _exitRequest.value = service.captureExitRequest()?.let { request ->
            EnterpriseExitConfirmation(request, overview.value?.takeIf { it.access == request.access }?.enterpriseName)
        }
        if (_exitRequest.value == null) _error.value = Failure(R.string.enterprise_failure, overview.value?.selection)
    }
    fun dismissExit() { _exitRequest.value = null }
    fun confirmExit(onExited: () -> Unit) {
        val original = _exitRequest.value ?: return
        _exitRequest.value = null
        command {
            val result = service.exit(original.request)
            onExited()
            result.maintenanceFailure?.let {
                _error.value = Failure(R.string.enterprise_logout_unconfirmed, null, detail = it)
            }
        }
    }
    fun retryExit() { overview.value?.exitFailure?.let { failure -> command { service.retryExit(failure) } } }
    fun showReset() {
        if (_busy.value || overview.value?.reset != null) return
        _resetChoice.value = overview.value?.resetPath
    }
    fun dismissReset() { _resetChoice.value = null; _resetConfirmation.value = null }
    /** Reset formats every enterprise realm on this device, so both branches are offered in every state. */
    fun requestReset(mode: EnterpriseDataResetMode) {
        val path = _resetChoice.value ?: return
        _resetChoice.value = null
        _resetConfirmation.value = EnterpriseResetConfirmation(
            EnterpriseDataResetRequest(Uuid.random(), mode), path)
    }
    fun confirmReset(onReset: () -> Unit) {
        val original = _resetConfirmation.value ?: return
        _resetConfirmation.value = null
        command { service.localDataReset(original.request); onReset() }
    }
    fun retryReset() { if (overview.value?.reset?.failure != null) command { service.retryLocalDataReset() } }
    fun showPortal() { overview.value?.selection?.let {
        _error.value = null
        _portal.value = PortalPresentation(selection = it)
    } }
    fun showUsagePortal() { overview.value?.selection?.let {
        _error.value = null
        _portal.value = PortalPresentation(selection = it, destination = PortalDestination.USAGE)
    } }
    fun dismissPortal(original: PortalPresentation) { if (_portal.value == original) _portal.value = null }
    fun portalClosed(original: PortalPresentation, closure: PortalClosure) {
        if (_portal.value != original || overview.value?.selection != original.selection) return
        dismissPortal(original)
        val reason = when (closure.reason) {
            PortalCloseReason.USER_REQUEST, PortalCloseReason.HOST_DISPOSED,
            PortalCloseReason.CONNECTION_CHANGED -> null
            PortalCloseReason.DOCUMENT_REPLACED -> "document_replaced"
            PortalCloseReason.DOCUMENT_EXPIRED -> "session_expired"
            PortalCloseReason.AUTHORIZATION_REVOKED -> "authorization_revoked"
            PortalCloseReason.HOST_FAILURE -> "host_failure"
        }
        if (reason != null) {
            android.util.Log.e("EnterprisePortal", "Portal closed unexpectedly: $reason")
            _error.value = Failure(R.string.enterprise_portal_open_failed, original.selection, listOf(reason))
        }
    }
    suspend fun openPortal(context: Context, original: PortalPresentation, onClosed: (PortalClosure) -> Unit) =
        when (original.destination) {
            PortalDestination.HOME -> service.openPortal(context, original.selection, onClosed)
            PortalDestination.USAGE -> service.openPortal(context, original.selection, original.destination, onClosed)
        }
    fun portalFailed(original: PortalPresentation, failure: Exception) {
        if (_portal.value != original || overview.value?.selection != original.selection) return
        dismissPortal(original)
        _error.value = if (failure is PortalHostUnavailable) {
            Failure(R.string.enterprise_portal_unavailable, original.selection,
                listOf(failure.provider, failure.missing.joinToString()))
        } else {
            android.util.Log.e("EnterprisePortal", "Portal opening failed", failure)
            val reason = (failure as? PortalFailure)?.code ?: (failure as? EnterpriseConfigurationException)?.reason
                ?: failure.javaClass.simpleName
            Failure(R.string.enterprise_portal_open_failed, original.selection, listOf(reason))
        }
    }

    private fun command(enrollment: Boolean = false, failureMessage: Int = R.string.enterprise_failure, isCurrent: () -> Boolean = { true }, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _notice.value = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("EnterpriseCommand", "Enterprise command failed", error)
                val enrollmentCode = if (enrollment) error.enrollmentCode() else null
                if (isCurrent()) _error.value = Failure(when {
                    error is EnterpriseConfigurationException && error.reason == "enterprise_selection_revoked" -> R.string.enterprise_selection_changed
                    enrollmentCode == ENROLLMENT_EXPIRED -> R.string.enterprise_enrollment_expired
                    enrollmentCode == "enrollment_already_used" ->
                        R.string.enterprise_enrollment_already_used
                    enrollmentCode == "installation_user_conflict" ->
                        R.string.enterprise_installation_user_conflict
                    enrollmentCode == EnterpriseRuntimeProblemCodes.IDENTITY_DELETED ->
                        R.string.enterprise_identity_deleted
                    enrollment -> R.string.enterprise_invalid_enrollment
                    else -> failureMessage
                }, overview.value?.selection, detail = when (error) {
                    is EnterpriseConfigurationException -> error.reason
                    is PlatformHttpException -> error.message
                    else -> error.userVisibleDiagnostic()
                })
            } finally { _busy.value = false }
        }
    }
}

private fun Exception.enrollmentCode(): String? = when (this) {
    is EnterpriseConfigurationException -> reason
    is PlatformHttpException -> problem?.code
    else -> null
}
