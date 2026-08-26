package net.weero.measix.pilot.ui.pages.extensions.workspace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalViewport
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalScreenUiModel
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalCreateResult
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalFailureReason

class WorkspaceTerminalVM(
    private val workspaceId: String,
    private val applicationService: WorkspaceApplicationService,
    queryService: WorkspaceTerminalQueryService,
) : ViewModel() {
    val state = queryService.observe(workspaceId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WorkspaceTerminalScreenUiModel(workspaceId),
    )
    private val mutableCommandError = MutableStateFlow<WorkspaceTerminalCommandError?>(null)
    val commandError = mutableCommandError.asStateFlow()

    private var initialTerminalRequested = false
    private var observedFailureId: String? = null

    init {
        viewModelScope.launch {
            state.collect { current ->
                current.terminal.lastFailure?.let { failure ->
                    if (failure.id != observedFailureId) {
                        observedFailureId = failure.id
                        mutableCommandError.value = when (val reason = failure.reason) {
                            WorkspaceTerminalFailureReason.NotReady -> WorkspaceTerminalCommandError.NotReady
                            WorkspaceTerminalFailureReason.Unexpected -> WorkspaceTerminalCommandError.Unexpected
                        }
                    }
                }
                if (!initialTerminalRequested && current.shellReady && current.terminal.tabs.isEmpty()) {
                    initialTerminalRequested = true
                    requestTerminal()
                }
            }
        }
    }

    fun create() {
        viewModelScope.launch {
            requestTerminal()
        }
    }
    fun select(tabId: String) = launchCommand { applicationService.selectTerminal(workspaceId, tabId) }
    fun close(tabId: String) = launchCommand { applicationService.closeTerminal(workspaceId, tabId) }
    fun rename(tabId: String, title: String) = launchCommand {
        applicationService.renameTerminal(workspaceId, tabId, title)
    }
    fun reorder(orderedIds: List<String>) = launchCommand {
        applicationService.reorderTerminals(workspaceId, orderedIds)
    }

    fun bindViewport(tabId: String, viewport: WorkspaceTerminalViewport): Boolean =
        applicationService.bindViewport(tabId, viewport)

    fun unbindViewport(tabId: String, viewport: WorkspaceTerminalViewport) =
        applicationService.unbindViewport(tabId, viewport)

    fun write(tabId: String, text: String) = applicationService.writeTerminal(tabId, text)

    fun consumeCommandError() {
        mutableCommandError.value = null
    }

    private fun launchCommand(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                mutableCommandError.value = null
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Terminal command failed for workspace=$workspaceId", error)
                mutableCommandError.value = WorkspaceTerminalCommandError.Unexpected
            }
        }
    }

    private suspend fun requestTerminal() {
        mutableCommandError.value = null
        try {
            when (val result = applicationService.createTerminal(workspaceId)) {
                is WorkspaceTerminalCreateResult.Created -> Unit
                is WorkspaceTerminalCreateResult.LimitReached -> {
                    mutableCommandError.value = WorkspaceTerminalCommandError.LimitReached(result.maximum)
                }
                WorkspaceTerminalCreateResult.NotReady -> {
                    mutableCommandError.value = WorkspaceTerminalCommandError.NotReady
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Terminal creation failed for workspace=$workspaceId", error)
            mutableCommandError.value = WorkspaceTerminalCommandError.Unexpected
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalVM"
    }
}

sealed interface WorkspaceTerminalCommandError {
    data class LimitReached(val maximum: Int) : WorkspaceTerminalCommandError
    data object NotReady : WorkspaceTerminalCommandError
    data object Unexpected : WorkspaceTerminalCommandError
}
