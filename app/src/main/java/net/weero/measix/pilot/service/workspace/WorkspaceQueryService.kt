package net.weero.measix.pilot.service.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.repository.FileTooLargeException
import net.weero.measix.pilot.data.repository.WorkspaceRepository

/** Read-only projection boundary for persisted Workspace state and files. */
class WorkspaceQueryService(
    private val repository: WorkspaceRepository,
) {
    fun observeWorkspaces(): Flow<List<WorkspaceUiModel>> = repository.listFlow().map { workspaces ->
        workspaces.map { it.toUiModel() }
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
