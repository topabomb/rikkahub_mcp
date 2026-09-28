package net.weero.measix.pilot.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceUiModel
import net.weero.measix.pilot.data.datastore.SettingsLockedException

class WorkspaceVM(
    private val workspaceApplicationService: WorkspaceApplicationService,
    workspaceQueryService: WorkspaceQueryService,
) : ViewModel() {
    val workspaces = workspaceQueryService.observeWorkspaces()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun create(name: String, onResult: (WorkspaceMutationResult) -> Unit) {
        viewModelScope.launch {
            try {
                workspaceApplicationService.createWorkspace(name)
                onResult(WorkspaceMutationResult.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("WorkspaceVM", "Create failed", error)
                onResult(WorkspaceMutationResult.Failure(WorkspaceMutationOperation.CREATE, error))
            }
        }
    }

    fun rename(workspace: WorkspaceUiModel, name: String, onResult: (WorkspaceMutationResult) -> Unit) {
        viewModelScope.launch {
            try {
                val renamed = workspaceApplicationService.renameWorkspace(workspace.id, name)
                onResult(
                    if (renamed) WorkspaceMutationResult.Success
                    else WorkspaceMutationResult.Failure(WorkspaceMutationOperation.RENAME)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("WorkspaceVM", "Rename failed", error)
                onResult(WorkspaceMutationResult.Failure(WorkspaceMutationOperation.RENAME, error))
            }
        }
    }

    fun delete(workspace: WorkspaceUiModel, onResult: (WorkspaceMutationResult) -> Unit) {
        viewModelScope.launch {
            try {
                val deleted = workspaceApplicationService.deleteWorkspace(workspace.id)
                onResult(
                    if (deleted) WorkspaceMutationResult.Success
                    else WorkspaceMutationResult.Failure(WorkspaceMutationOperation.DELETE)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SettingsLockedException) {
                onResult(WorkspaceMutationResult.Locked(error.reason))
            } catch (error: Exception) {
                android.util.Log.e("WorkspaceVM", "Delete failed", error)
                onResult(WorkspaceMutationResult.Failure(WorkspaceMutationOperation.DELETE, error))
            }
        }
    }
}

enum class WorkspaceMutationOperation { CREATE, RENAME, DELETE }

sealed interface WorkspaceMutationResult {
    data object Success : WorkspaceMutationResult
    data class Failure(val operation: WorkspaceMutationOperation, val cause: Exception? = null) : WorkspaceMutationResult
    data class Locked(val reason: String) : WorkspaceMutationResult
}
