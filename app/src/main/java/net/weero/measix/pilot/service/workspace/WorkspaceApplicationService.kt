package net.weero.measix.pilot.service.workspace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import java.io.InputStream
import java.io.OutputStream

class WorkspaceApplicationService(
    private val repository: WorkspaceRepository,
    private val terminals: WorkspaceTerminalRuntime,
) {
    private val catalogGate = Mutex()
    private val mutationGates = Array(GATE_STRIPES) { Mutex() }

    suspend fun createWorkspace(name: String): WorkspaceCreated = catalogGate.withLock {
        WorkspaceCreated(repository.create(name).id)
    }

    suspend fun renameWorkspace(workspaceId: String, name: String) = catalogGate.withLock {
        gated(workspaceId) { repository.rename(workspaceId, name) }
    }

    suspend fun setToolApproval(workspaceId: String, toolName: String, needsApproval: Boolean) =
        gated(workspaceId) { repository.setToolApproval(workspaceId, toolName, needsApproval) }

    suspend fun deleteFile(
        workspaceId: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ) = gated(workspaceId) { repository.deleteFile(workspaceId, area, path, recursive) }

    suspend fun importFile(
        workspaceId: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        input: InputStream,
    ): WorkspaceFileEntry = gated(workspaceId) {
        repository.importFile(workspaceId, area, destinationPath, fileName, input)
    }

    suspend fun exportFile(
        workspaceId: String,
        area: WorkspaceStorageArea,
        path: String,
        output: OutputStream,
    ) = gated(workspaceId) { repository.exportFile(workspaceId, area, path, output) }

    suspend fun writeText(workspaceId: String, path: String, text: String) =
        gated(workspaceId) { repository.writeText(workspaceId, path, text, overwrite = true) }

    suspend fun createTerminal(workspaceId: String): WorkspaceTerminalCreateResult = gated(workspaceId) {
        val workspace = requireWorkspace(workspaceId)
        if (workspace.resolvedShellStatus() != WorkspaceShellStatus.READY) WorkspaceTerminalCreateResult.NotReady
        else terminals.create(workspace.root) { preparation ->
            gated(workspaceId) {
                val current = requireWorkspace(workspaceId)
                if (
                    current.root != workspace.root ||
                    current.resolvedShellStatus() != WorkspaceShellStatus.READY
                ) {
                    false
                } else {
                    preparation()
                }
            }
        }
    }

    suspend fun selectTerminal(workspaceId: String, tabId: String) = gated(workspaceId) {
        terminals.select(requireWorkspace(workspaceId).root, tabId)
    }

    suspend fun renameTerminal(workspaceId: String, tabId: String, title: String) = gated(workspaceId) {
        terminals.rename(requireWorkspace(workspaceId).root, tabId, title)
    }

    suspend fun reorderTerminals(workspaceId: String, orderedIds: List<String>) = gated(workspaceId) {
        terminals.reorder(requireWorkspace(workspaceId).root, orderedIds)
    }

    suspend fun closeTerminal(workspaceId: String, tabId: String) = gated(workspaceId) {
        terminals.close(requireWorkspace(workspaceId).root, tabId)
    }

    suspend fun installRootfs(
        workspaceId: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit,
    ): Boolean = gated(workspaceId) {
        val workspace = requireWorkspace(workspaceId)
        terminals.closeWorkspace(workspace.root)
        repository.installRootfs(workspaceId, url, onProgress)
    }

    suspend fun deleteWorkspace(workspaceId: String): Boolean = catalogGate.withLock {
        gated(workspaceId) {
            val workspace = requireWorkspace(workspaceId)
            terminals.closeWorkspace(workspace.root)
            repository.delete(workspaceId)
        }
    }

    /**
     * Executes one model-visible Rootfs operation under the same per-workspace gate as UI commands.
     * Compound operations such as read/replace/write stay atomic relative to install and deletion.
     */
    suspend fun <T> executeTool(
        workspaceId: String,
        operation: suspend WorkspaceToolSession.() -> T,
    ): T = gated(workspaceId) {
        val workspace = requireWorkspace(workspaceId)
        check(workspace.resolvedShellStatus() == WorkspaceShellStatus.READY) {
            "Workspace shell is not ready: $workspaceId"
        }
        RepositoryWorkspaceToolSession(repository, workspaceId).operation()
    }

    fun bindViewport(tabId: String, viewport: WorkspaceTerminalViewport): Boolean =
        terminals.bind(tabId, viewport.view)

    fun unbindViewport(tabId: String, viewport: WorkspaceTerminalViewport) =
        terminals.unbind(tabId, viewport.view)

    fun writeTerminal(tabId: String, text: String) = terminals.write(tabId, text)

    private suspend fun requireWorkspace(id: String) =
        requireNotNull(repository.getById(id)) { "Workspace not found: $id" }

    private suspend fun <T> gated(workspaceId: String, block: suspend () -> T): T =
        mutationGates[(workspaceId.hashCode() and Int.MAX_VALUE) % mutationGates.size].withLock { block() }

    private companion object {
        const val GATE_STRIPES = 32
    }
}

data class WorkspaceCreated(val workspaceId: String)

interface WorkspaceToolSession {
    suspend fun rootfsFileSize(path: String): Long

    suspend fun exportRootfsFile(path: String, output: OutputStream)

    suspend fun executeCommand(
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult
}

private class RepositoryWorkspaceToolSession(
    private val repository: WorkspaceRepository,
    private val workspaceId: String,
) : WorkspaceToolSession {
    override suspend fun rootfsFileSize(path: String): Long =
        repository.rootfsFileSize(workspaceId, path)

    override suspend fun exportRootfsFile(path: String, output: OutputStream) =
        repository.exportRootfsFile(workspaceId, path, output)

    override suspend fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult = repository.executeCommand(
        id = workspaceId,
        command = command,
        cwd = cwd,
        timeoutMillis = timeoutMillis,
        stdin = stdin,
    )
}
