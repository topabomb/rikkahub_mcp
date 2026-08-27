package net.weero.measix.pilot.service.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.repository.FileTooLargeException
import net.weero.measix.pilot.data.repository.WorkspaceRepository

/** Read-only projection of persisted Workspace state, files, and process-local terminals. */
class WorkspaceQueryService(
    private val repository: WorkspaceRepository,
    private val terminalRuntime: WorkspaceTerminalRuntime,
) {
    fun observeWorkspaces(): Flow<List<WorkspaceUiModel>> = repository.listFlow().map { workspaces ->
        workspaces.map { it.toUiModel() }
    }

    fun observeTerminal(workspaceId: String): Flow<WorkspaceTerminalScreenUiModel> =
        combine(repository.listFlow(), terminalRuntime.workspaces) { persisted, active ->
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

    suspend fun getWorkspace(workspaceId: String): WorkspaceUiModel? =
        repository.getById(workspaceId)?.toUiModel()

    suspend fun listFiles(workspaceId: String, area: WorkspaceStorageArea, path: String) =
        repository.listFiles(workspaceId, area, path)

    suspend fun readTextForPreview(
        workspaceId: String,
        area: WorkspaceStorageArea,
        path: String,
    ): WorkspaceTextPreviewResult = try {
        WorkspaceTextPreviewResult.Success(repository.readTextForPreview(workspaceId, area, path))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (tooLarge: FileTooLargeException) {
        WorkspaceTextPreviewResult.TooLarge(tooLarge.size)
    } catch (_: Exception) {
        WorkspaceTextPreviewResult.Unavailable
    }

    private fun WorkspaceEntity.toUiModel() = WorkspaceUiModel(
        id = id,
        name = name,
        shellStatus = resolvedShellStatus(),
        toolApprovalOverrides = toolApprovalOverrides(),
    )
}

data class WorkspaceUiModel(
    val id: String,
    val name: String,
    val shellStatus: WorkspaceShellStatus,
    val toolApprovalOverrides: Map<String, Boolean> = emptyMap(),
)

sealed interface WorkspaceTextPreviewResult {
    data class Success(val content: String) : WorkspaceTextPreviewResult
    data class TooLarge(val sizeBytes: Long) : WorkspaceTextPreviewResult
    data object Unavailable : WorkspaceTextPreviewResult
}

data class WorkspaceTerminalScreenUiModel(
    val workspaceId: String,
    val name: String? = null,
    val shellReady: Boolean = false,
    val canCreateTerminal: Boolean = false,
    val terminal: WorkspaceTerminalWorkspaceState = WorkspaceTerminalWorkspaceState(),
)
