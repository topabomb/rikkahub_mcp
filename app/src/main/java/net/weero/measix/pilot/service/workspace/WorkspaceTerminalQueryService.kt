package net.weero.measix.pilot.service.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.rerere.workspace.WorkspaceShellStatus
import net.weero.measix.pilot.data.repository.WorkspaceRepository

/** Joins persisted Workspace readiness with the process-local terminal projection. */
class WorkspaceTerminalQueryService(
    repository: WorkspaceRepository,
    runtime: WorkspaceTerminalRuntime,
) {
    private val workspaces = repository.listFlow()
    private val terminals = runtime.workspaces

    fun observe(workspaceId: String): Flow<WorkspaceTerminalScreenUiModel> =
        combine(workspaces, terminals) { persisted, active ->
            val workspace = persisted.firstOrNull { it.id == workspaceId }
            val terminal = workspace?.root?.let(active::get) ?: WorkspaceTerminalWorkspaceState()
            val shellReady = workspace?.resolvedShellStatus() == WorkspaceShellStatus.READY
            WorkspaceTerminalScreenUiModel(
                workspaceId = workspaceId,
                name = workspace?.name,
                shellReady = shellReady,
                terminal = terminal,
                canCreateTerminal = shellReady &&
                    terminal.tabs.size < WorkspaceTerminalRuntime.MAX_TABS_PER_WORKSPACE,
            )
        }
}

data class WorkspaceTerminalScreenUiModel(
    val workspaceId: String,
    val name: String? = null,
    val shellReady: Boolean = false,
    val canCreateTerminal: Boolean = false,
    val terminal: WorkspaceTerminalWorkspaceState = WorkspaceTerminalWorkspaceState(),
)
