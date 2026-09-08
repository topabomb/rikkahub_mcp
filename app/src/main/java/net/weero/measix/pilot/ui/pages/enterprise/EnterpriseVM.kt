package net.weero.measix.pilot.ui.pages.enterprise

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.EnterpriseApplicationService
import net.weero.measix.pilot.service.portal.PortalClosure
import kotlin.uuid.Uuid

internal data class PortalPresentation(val id: Uuid = Uuid.random(), val selection: RealmSelection)
internal data class EnterpriseExitConfirmation(val request: EnterpriseExitRequest, val enterpriseName: String?)

internal class EnterpriseVM(private val service: EnterpriseApplicationService) : ViewModel() {
    val overview = service.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<Int?>(null)
    val error = _error.asStateFlow()
    private val _exitRequest = MutableStateFlow<EnterpriseExitConfirmation?>(null)
    val exitRequest = _exitRequest.asStateFlow()
    private val _exampleCode = MutableStateFlow<String?>(null)
    val exampleCode = _exampleCode.asStateFlow()
    private val _portal = MutableStateFlow<PortalPresentation?>(null)
    val portal = _portal.asStateFlow()

    fun joinExample() = command(enrollment = true) { service.joinExample() }
    fun join(text: String) = command(enrollment = true) { service.join(text) }
    fun showExampleCode() = command { _exampleCode.value = service.exampleEnrollmentText() }
    fun dismissExampleCode() { _exampleCode.value = null }
    fun scanFailed() { _error.value = R.string.enterprise_scan_failure }
    fun synchronize() { overview.value?.access?.let { access -> command { service.synchronize(access) } } }
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
        if (_exitRequest.value == null) _error.value = R.string.enterprise_failure
    }
    fun dismissExit() { _exitRequest.value = null }
    fun confirmExit(onExited: () -> Unit) {
        val original = _exitRequest.value ?: return
        _exitRequest.value = null
        command { service.exit(original.request); onExited() }
    }
    fun retryExit() { overview.value?.exitFailure?.let { failure -> command { service.retryExit(failure) } } }
    fun showPortal() { overview.value?.selection?.let { _portal.value = PortalPresentation(selection = it) } }
    fun dismissPortal(original: PortalPresentation) { if (_portal.value == original) _portal.value = null }
    suspend fun openPortal(context: Context, original: PortalPresentation, onClosed: (PortalClosure) -> Unit) =
        service.openPortal(context, original.selection, onClosed)
    fun portalFailed(original: PortalPresentation) {
        if (_portal.value != original) return
        dismissPortal(original)
        _error.value = R.string.enterprise_failure
    }

    private fun command(enrollment: Boolean = false, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _error.value = when {
                    error is EnterpriseConfigurationException && error.reason == "platform_enrollment_not_supported" -> R.string.enterprise_platform_unavailable
                    enrollment -> R.string.enterprise_invalid_enrollment
                    else -> R.string.enterprise_failure
                }
            } finally { _busy.value = false }
        }
    }
}
