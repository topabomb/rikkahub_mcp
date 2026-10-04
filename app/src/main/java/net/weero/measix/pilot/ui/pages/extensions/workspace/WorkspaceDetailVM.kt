package net.weero.measix.pilot.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceUiModel
import net.weero.measix.pilot.service.workspace.WorkspaceExportRequest
import net.weero.measix.pilot.service.workspace.WorkspaceExportDestination
import net.weero.measix.pilot.service.workspace.WorkspaceExportItem
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val workspaceApplicationService: WorkspaceApplicationService,
    private val workspaceQueryService: WorkspaceQueryService,
) : ViewModel() {
    val defaultRootfsUrl: String get() = workspaceQueryService.defaultRootfsUrl

    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    private val _exportState = MutableStateFlow<WorkspaceExportState?>(null)
    val exportState = _exportState.asStateFlow()
    private var exportJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshTarget: Pair<WorkspaceStorageArea, String>? = null
    private var refreshRevision = 0L

    fun exportFiles(request: WorkspaceExportRequest, destination: WorkspaceExportDestination) {
        if (exportJob?.isActive == true) return
        require(request.workspaceId == id) { "Workspace export target changed" }
        _exportState.value = WorkspaceExportState(request, running = true)
        exportJob = viewModelScope.launch {
            try {
                workspaceApplicationService.exportFiles(request, destination) { item ->
                    _exportState.update { it?.copy(items = it.items + item) }
                }
            } catch (cancelled: CancellationException) {
                cancelled.suppressed.forEach { cleanup ->
                    android.util.Log.e("WorkspaceExport", "Cancellation cleanup failed", cleanup)
                }
                _exportState.update { it?.copy(cancelled = true, cleanupDiagnostic = cancelled.suppressed
                    .takeIf { failures -> failures.isNotEmpty() }?.joinToString("\n") { failure -> failure.userVisibleDiagnostic() }) }
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("WorkspaceExport", "Export request failed", error)
                _exportState.update { it?.copy(cleanupDiagnostic = error.userVisibleDiagnostic()) }
            } finally { _exportState.update { it?.copy(running = false) } }
        }
    }

    fun cancelExport() { exportJob?.cancel() }
    fun dismissExportResult() { if (exportJob?.isActive != true) _exportState.value = null }

    fun reportExportFailure(error: Exception) {
        android.util.Log.e("WorkspaceExport", "Export failed", error)
        _state.update { it.copy(error = WorkspaceOperation.EXPORT, diagnostic = error.userVisibleDiagnostic()) }
    }

    init {
        viewModelScope.launch {
            if (loadWorkspaceNow()) refresh()
        }
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
                diagnostic = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null, diagnostic = null) }
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
                diagnostic = null,
            )
        }
        refresh()
    }

    fun refresh() = refreshFiles(force = false)

    private fun refreshFiles(force: Boolean) {
        val target = state.value.area to state.value.path
        if (!force && refreshJob?.isActive == true && refreshTarget == target) return
        refreshJob?.cancel()
        refreshTarget = target
        val revision = ++refreshRevision
        refreshJob = viewModelScope.launch {
            refreshNow(revision)
        }
    }

    suspend fun delete(entry: WorkspaceFileEntry, area: WorkspaceStorageArea = state.value.area): Boolean = try {
        val deleted = workspaceApplicationService.deleteFile(
            workspaceId = id,
            area = area,
            path = entry.path,
            recursive = entry.isDirectory,
        )
        // 删除已经提交后，列表刷新是独立的读模型同步；不要让刷新取消把已提交删除误报为失败。
        refreshFiles(force = true)
        if (!deleted) {
            _state.update { it.copy(error = WorkspaceOperation.DELETE) }
        }
        deleted
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        _state.update { it.copy(error = WorkspaceOperation.DELETE) }
        false
    }

    private suspend fun refreshNow(revision: Long) {
        val target = state.value
        _state.update { it.copy(loading = true, error = null, diagnostic = null) }
        try {
            val entries = workspaceQueryService.listFiles(id, target.area, target.path)
            _state.update { if (revision == refreshRevision && it.area == target.area && it.path == target.path) it.copy(entries = entries) else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.e("WorkspaceDetail", "Unable to list files", error)
            _state.update { if (revision == refreshRevision && it.area == target.area && it.path == target.path)
                it.copy(error = WorkspaceOperation.LOAD_FILES, diagnostic = error.userVisibleDiagnostic()) else it }
        } finally {
            _state.update { if (revision == refreshRevision && it.area == target.area && it.path == target.path) it.copy(loading = false) else it }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            try {
                inputStream.use { input ->
                    workspaceApplicationService.importFile(
                        workspaceId = id,
                        area = state.value.area,
                        destinationPath = state.value.path,
                        fileName = fileName,
                        input = input,
                    )
                }
                refreshFiles(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = WorkspaceOperation.IMPORT) }
            }
        }
    }

    fun imageSource(entry: WorkspaceFileEntry, area: WorkspaceStorageArea): net.weero.measix.pilot.service.ImageSource =
        workspaceApplicationService.imageSource(id, area, entry)

    suspend fun exportFile(entry: WorkspaceFileEntry, area: WorkspaceStorageArea, outputStream: OutputStream) =
        workspaceApplicationService.exportFile(id, area, entry.path, outputStream)

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(entry: WorkspaceFileEntry, onReady: (File) -> Unit) {
        val area = state.value.area
        viewModelScope.launch {
            try {
                workspaceApplicationService.shareFile(id, area, entry.path, entry.name, onReady)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportExportFailure(error) }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            try {
                workspaceApplicationService.setToolApproval(workspace.id, toolName, needsApproval)
                loadWorkspaceNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = WorkspaceOperation.LOAD_WORKSPACE) }
            }
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                workspaceApplicationService.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                if (loadWorkspaceNow()) refreshFiles(force = true)
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
            val workspace = workspaceQueryService.getWorkspace(id)
            _state.update { it.copy(workspace = workspace) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(error = WorkspaceOperation.LOAD_WORKSPACE) }
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

data class WorkspaceDetailState(
    val workspace: WorkspaceUiModel? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: WorkspaceOperation? = null,
    val diagnostic: String? = null,
)

data class WorkspaceExportState(
    val request: WorkspaceExportRequest,
    val running: Boolean,
    val items: List<WorkspaceExportItem> = emptyList(),
    val cleanupDiagnostic: String? = null,
    val cancelled: Boolean = false,
)
