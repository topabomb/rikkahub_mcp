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
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalViewport
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalScreenUiModel
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalCreateResult
import net.weero.measix.pilot.service.workspace.WorkspaceTerminalFailureReason

class WorkspaceTerminalVM(
    private val workspaceId: String,
    private val applicationService: WorkspaceApplicationService,
    queryService: WorkspaceQueryService,
) : ViewModel() {
    val state = queryService.observeTerminal(workspaceId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WorkspaceTerminalScreenUiModel(workspaceId),
    )
    private val mutableCommandError = MutableStateFlow<WorkspaceTerminalCommandError?>(null)
    val commandError = mutableCommandError.asStateFlow()

    private var initialTerminalSelection: RealmSelection? = null
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
                if (current.selection != null && initialTerminalSelection != current.selection && current.shellReady && current.terminal.tabs.isEmpty()) {
                    initialTerminalSelection = current.selection
                    requestTerminal(current.selection)
                }
            }
        }
    }

    fun create(selection: RealmSelection) {
        viewModelScope.launch {
            requestTerminal(selection)
        }
    }
    fun select(selection: RealmSelection, tabId: String) = launchCommand(selection) { applicationService.selectTerminal(workspaceId, selection, tabId) }
    fun close(selection: RealmSelection, tabId: String) = launchCommand(selection) { applicationService.closeTerminal(workspaceId, selection, tabId) }
    fun rename(selection: RealmSelection, tabId: String, title: String) = launchCommand(selection) {
        applicationService.renameTerminal(workspaceId, selection, tabId, title)
    }
    fun reorder(selection: RealmSelection, orderedIds: List<String>) = launchCommand(selection) {
        applicationService.reorderTerminals(workspaceId, selection, orderedIds)
    }

    suspend fun bindViewport(selection: RealmSelection, tabId: String, viewport: WorkspaceTerminalViewport): Boolean = try {
        applicationService.bindViewport(selection, tabId, viewport)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: EnterpriseConfigurationException) { false }

    fun unbindViewport(tabId: String, viewport: WorkspaceTerminalViewport) =
        applicationService.unbindViewport(tabId, viewport)

    fun write(selection: RealmSelection, tabId: String, text: String) = applicationService.writeTerminal(selection, tabId, text)

    fun consumeCommandError() {
        mutableCommandError.value = null
    }

    private fun launchCommand(selection: RealmSelection, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                if (state.value.selection == selection) mutableCommandError.value = null
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Terminal command failed for workspace=$workspaceId", error)
                if (state.value.selection == selection) mutableCommandError.value = WorkspaceTerminalCommandError.Unexpected
            }
        }
    }

    private suspend fun requestTerminal(selection: RealmSelection) {
        if (state.value.selection == selection) mutableCommandError.value = null
        try {
            val result = applicationService.createTerminal(workspaceId, selection)
            if (state.value.selection != selection) return
            when (result) {
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
            if (state.value.selection == selection) mutableCommandError.value = WorkspaceTerminalCommandError.Unexpected
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
