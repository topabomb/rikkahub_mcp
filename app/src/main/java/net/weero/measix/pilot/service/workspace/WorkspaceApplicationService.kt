package net.weero.measix.pilot.service.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.workspace.ProotLaunchSpec
import me.rerere.workspace.RootfsPath
import me.rerere.workspace.WorkspaceBindMount
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.weero.measix.pilot.service.ImageSource
import net.weero.measix.pilot.service.ImageOrigin
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.files.FilePayloadTooLargeException
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

internal const val MAX_WORKSPACE_UPLOADS = 32
internal const val MAX_WORKSPACE_UPLOAD_BYTES = 64L * 1024 * 1024

class WorkspaceApplicationService internal constructor(
    private val repository: WorkspaceRepository,
    private val terminals: WorkspaceTerminalRuntime,
    private val artifacts: ArtifactStore,
    private val sessions: EnterpriseSessionController,
    private val tempRoot: File,
    private val recovery: ApplicationRecoveryGate,
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

    fun imageSource(workspaceId: String, area: WorkspaceStorageArea, entry: WorkspaceFileEntry): ImageSource {
        suspend fun requireEntry() {
            requireWorkspace(workspaceId)
            check(!entry.isDirectory) { "workspace_image_unavailable" }
            if (entry.sizeBytes > GeneratedMediaStore.MAX_IMAGE_BYTES) throw FilePayloadTooLargeException()
            check(repository.statFile(workspaceId, area, entry.path) == entry) { "workspace_image_changed" }
        }
        return ImageSource(
            cacheIdentity = "workspace:$workspaceId:$area:$entry",
            origin = ImageOrigin.LOCAL,
            displayName = entry.name,
            modifiedAtMillis = entry.updatedAt,
            verifyAccess = { gated(workspaceId) { requireEntry() } },
            readPayload = { gated(workspaceId) {
                requireEntry()
                val reading = currentCoroutineContext()
                val output = object : java.io.ByteArrayOutputStream() {
                    override fun write(value: Int) {
                        reading.ensureActive()
                        if (size() >= GeneratedMediaStore.MAX_IMAGE_BYTES) throw FilePayloadTooLargeException()
                        super.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        reading.ensureActive()
                        if (length > GeneratedMediaStore.MAX_IMAGE_BYTES - size()) throw FilePayloadTooLargeException()
                        super.write(bytes, offset, length)
                    }
                }
                repository.exportFile(workspaceId, area, entry.path, output)
                requireEntry()
                output.toByteArray().also { check(ImageMime.isAcceptedImage(it)) { "workspace_image_invalid" } }
            } },
        )
    }

    suspend fun writeText(workspaceId: String, path: String, text: String) =
        gated(workspaceId) { repository.writeText(workspaceId, path, text, overwrite = true) }

    suspend fun createTerminal(workspaceId: String, selection: RealmSelection): WorkspaceTerminalCreateResult = gated(workspaceId) {
        val workspace = requireWorkspace(workspaceId)
        if (workspace.resolvedShellStatus() != WorkspaceShellStatus.READY) WorkspaceTerminalCreateResult.NotReady
        else terminals.create(workspace.root, selection) { preparation ->
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

    suspend fun selectTerminal(workspaceId: String, selection: RealmSelection, tabId: String) = gated(workspaceId) {
        terminals.select(requireWorkspace(workspaceId).root, selection, tabId)
    }

    suspend fun renameTerminal(workspaceId: String, selection: RealmSelection, tabId: String, title: String) = gated(workspaceId) {
        terminals.rename(requireWorkspace(workspaceId).root, selection, tabId, title)
    }

    suspend fun reorderTerminals(workspaceId: String, selection: RealmSelection, orderedIds: List<String>) = gated(workspaceId) {
        terminals.reorder(requireWorkspace(workspaceId).root, selection, orderedIds)
    }

    suspend fun closeTerminal(workspaceId: String, selection: RealmSelection, tabId: String) = gated(workspaceId) {
        terminals.close(requireWorkspace(workspaceId).root, selection, tabId)
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
        access: RealmAccess,
        operation: suspend WorkspaceToolSession.() -> T,
    ): T = gated(workspaceId) {
        recovery.awaitReady()
        sessions.withRealmAccess(access) { currentCoroutineContext().ensureActive() }
        val workspace = requireWorkspace(workspaceId)
        check(workspace.resolvedShellStatus() == WorkspaceShellStatus.READY) {
            "Workspace shell is not ready: $workspaceId"
        }
        val tool = ScopedWorkspaceToolSession(workspaceId, access)
        try {
            val result = tool.operation()
            sessions.withRealmAccess(access) { currentCoroutineContext().ensureActive() }
            result
        } finally { tool.open = false }
    }

    suspend fun bindViewport(selection: RealmSelection, tabId: String, viewport: WorkspaceTerminalViewport): Boolean =
        terminals.bind(selection, tabId, viewport.view)

    fun unbindViewport(tabId: String, viewport: WorkspaceTerminalViewport) =
        terminals.unbind(tabId, viewport.view)

    fun writeTerminal(selection: RealmSelection, tabId: String, text: String) = terminals.write(selection, tabId, text)

    private suspend fun requireWorkspace(id: String) =
        requireNotNull(repository.getById(id)) { "Workspace not found: $id" }

    private suspend fun <T> gated(workspaceId: String, block: suspend () -> T): T =
        mutationGates[(workspaceId.hashCode() and Int.MAX_VALUE) % mutationGates.size].withLock { block() }

    private inner class ScopedWorkspaceToolSession(
        private val workspaceId: String,
        private val access: RealmAccess,
    ) : WorkspaceToolSession {
        var open = true
        private fun requireOpen() { check(open) { "workspace_tool_session_closed" } }

        override suspend fun readRootfsBytes(path: String, maxBytes: Long): ByteArray = sessions.withRealmAccess(access) {
            requireOpen()
            val normalized = RootfsPath.parse(path)
            if (normalized.isUpload) artifacts.readUpload(access.scope, normalized.value, maxBytes)
            else repository.readRootfsBytes(workspaceId, normalized.value, maxBytes)
        }

        override suspend fun writeRootfsText(path: String, text: String, overwrite: Boolean, approvedByUser: Boolean): WorkspaceFileEntry =
            sessions.withRealmAccess(access) {
                requireOpen()
                repository.writeRootfsText(workspaceId, path, text, overwrite, approvedByUser)
            }

        override suspend fun updateRootfsText(path: String, maxBytes: Long, approvedByUser: Boolean, transform: (String) -> String): WorkspaceFileEntry =
            sessions.withRealmAccess(access) {
                requireOpen()
                repository.updateRootfsText(workspaceId, path, maxBytes, approvedByUser, transform)
            }

        override suspend fun executeCommand(command: String, cwd: String, timeoutMillis: Long, stdin: ByteArray?, uploads: List<String>): WorkspaceCommandResult {
            requireOpen()
            require(uploads.size <= MAX_WORKSPACE_UPLOADS) { "too_many_uploads" }
            var directory: File? = null
            var failure: Throwable? = null
            try {
                val inputs = withContext(Dispatchers.IO) {
                    check(tempRoot.isDirectory || tempRoot.mkdirs()) { "workspace_input_directory_unavailable" }
                    Files.createTempDirectory(tempRoot.toPath(), "workspace-input-").toFile().also { directory = it }
                }
                sessions.withRealmAccess(access) { artifacts.copyUploads(access.scope, uploads, inputs, MAX_WORKSPACE_UPLOAD_BYTES) }
                currentCoroutineContext().ensureActive()
                return repository.executeCommand(workspaceId, command, cwd, timeoutMillis, stdin,
                    listOf(WorkspaceBindMount(inputs, ProotLaunchSpec.UPLOAD_DIR)))
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    withContext(NonCancellable) {
                        directory?.let { FileUtils.deleteOwnedTree(it) }
                    }
                } catch (cleanup: Throwable) {
                    failure?.let { if (cleanup !== it) it.addSuppressed(cleanup) } ?: throw cleanup
                }
            }
        }
    }

    private companion object {
        const val GATE_STRIPES = 32
    }
}

data class WorkspaceCreated(val workspaceId: String)

interface WorkspaceToolSession {
    suspend fun readRootfsBytes(path: String, maxBytes: Long): ByteArray

    suspend fun writeRootfsText(path: String, text: String, overwrite: Boolean, approvedByUser: Boolean): WorkspaceFileEntry

    suspend fun updateRootfsText(
        path: String,
        maxBytes: Long,
        approvedByUser: Boolean,
        transform: (String) -> String,
    ): WorkspaceFileEntry

    suspend fun executeCommand(
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        uploads: List<String> = emptyList(),
    ): WorkspaceCommandResult
}
