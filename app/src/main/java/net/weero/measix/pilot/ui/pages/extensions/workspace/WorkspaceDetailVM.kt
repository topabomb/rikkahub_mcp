package net.weero.measix.pilot.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.io.InputStream
import java.io.OutputStream
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    init {
        viewModelScope.launch {
            if (loadWorkspaceNow()) refreshNow()
        }
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshNow()
        }
    }

    suspend fun delete(entry: WorkspaceFileEntry): Boolean = try {
        val deleted = repository.deleteFile(
            id = id,
            area = state.value.area,
            path = entry.path,
            recursive = entry.isDirectory,
        )
        // 删除已经提交后，列表刷新是独立的读模型同步；不要让刷新取消把已提交删除误报为失败。
        refresh()
        if (!deleted) {
            _state.update { it.copy(error = WorkspaceOperationError(WorkspaceOperation.DELETE)) }
        }
        deleted
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        _state.update {
            it.copy(error = WorkspaceOperationError(WorkspaceOperation.DELETE, error.message))
        }
        false
    }

    private suspend fun refreshNow() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val entries = repository.listFiles(
                id = id,
                area = state.value.area,
                path = state.value.path,
            )
            _state.update { it.copy(entries = entries, loading = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    entries = emptyList(),
                    loading = false,
                    error = WorkspaceOperationError(WorkspaceOperation.LOAD_FILES, error.message),
                )
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            try {
                inputStream.use { input ->
                    repository.importFile(
                        id = id,
                        area = state.value.area,
                        destinationPath = state.value.path,
                        fileName = fileName,
                        inputStream = input,
                    )
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = WorkspaceOperationError(WorkspaceOperation.IMPORT, error.message))
                }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            try {
                outputStream.use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = WorkspaceOperationError(WorkspaceOperation.EXPORT, error.message))
                }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, cacheDir: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            var file: File? = null
            try {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val safeName = File(entry.name).name.ifBlank { "workspace-file" }
                val exported = File(dir, "${UUID.randomUUID()}_$safeName")
                file = exported
                exported.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = state.value.area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                onReady(exported)
                file = null
            } catch (cancelled: CancellationException) {
                file?.delete()
                throw cancelled
            } catch (error: Exception) {
                file?.delete()
                _state.update {
                    it.copy(error = WorkspaceOperationError(WorkspaceOperation.EXPORT, error.message))
                }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            try {
                repository.setToolApproval(workspace.id, toolName, needsApproval)
                loadWorkspaceNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = WorkspaceOperationError(WorkspaceOperation.LOAD_WORKSPACE, error.message))
                }
            }
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                if (loadWorkspaceNow()) refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                _installError.value = error.message.orEmpty()
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    private suspend fun loadWorkspaceNow(): Boolean = try {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update {
                it.copy(error = WorkspaceOperationError(WorkspaceOperation.LOAD_WORKSPACE, error.message))
            }
            false
        }
}

enum class WorkspaceOperation {
    LOAD_WORKSPACE,
    LOAD_FILES,
    IMPORT,
    EXPORT,
    DELETE,
}

data class WorkspaceOperationError(
    val operation: WorkspaceOperation,
    val detail: String? = null,
)

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: WorkspaceOperationError? = null,
)
