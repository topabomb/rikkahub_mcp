package net.weero.measix.pilot.ui.pages.remoteworkspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.WorkspaceFileRules
import net.weero.measix.pilot.service.remoteworkspace.*
import net.weero.measix.pilot.ui.components.files.FileEditorState
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal data class RemoteSaveSubmission(
    val handle: RemoteWorkspaceHandle, val document: RemoteTextDocument, val text: String,
    val destination: String, val result: RemoteOperationResult? = null,
)
internal data class RemoteOverwritePrompt(val handle: RemoteWorkspaceHandle, val target: RemoteFile)
internal data class RemoteDownloadPicker(val handle: RemoteWorkspaceHandle, val files: List<RemoteFile>, val tree: Boolean)
/** One transient native buffer survives view recreation only within its original management session. */
internal class RemoteEditorSession(val handle: RemoteWorkspaceHandle, val path: String) {
    val editor = FileEditorState()
    var document by mutableStateOf<RemoteTextDocument?>(null)
    var editing by mutableStateOf(false)

    fun accept(document: RemoteTextDocument) {
        require(document.file.path == path)
        this.document = document
        editor.replaceText(document.content.text)
    }
}
internal data class RemoteBrowserState(
    val handle: RemoteWorkspaceHandle? = null,
    val path: String = "",
    val directory: RemoteDirectory? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val revoked: Boolean = false,
    val running: Boolean = false,
    val progress: Long = 0,
    val total: Long? = null,
    val activeIndex: Int? = null,
    val batchSize: Int = 0,
    val results: List<RemoteOperationResult> = emptyList(),
    val preview: RemoteFile? = null,
    val editorSession: RemoteEditorSession? = null,
    val save: RemoteSaveSubmission? = null,
    val overwrite: RemoteOverwritePrompt? = null,
)

internal class RemoteWorkspaceVM(
    val selection: RealmSelection?,
    val service: RemoteWorkspaceService,
    private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteBrowserState(revoked = selection == null))
    val state = _state.asStateFlow()
    val summary = service.summary
    private var readJob: Job? = null
    private var readRevision = 0L
    private var readPath: String? = null
    private var work: Job? = null
    private var overwriteAnswer: CompletableDeferred<Boolean>? = null
    // Picker requests survive Activity recreation, but are never serialized or rebound to another handle.
    private var pendingDownload: RemoteDownloadPicker? = null
    private var pendingUpload: Pair<RemoteWorkspaceHandle, String>? = null

    init {
        retry()
        viewModelScope.launch {
            service.summary.collect { summary ->
                val handle = _state.value.handle
                if (handle != null && (summary?.selection != selection || !service.isValid(handle))) {
                    overwriteAnswer?.cancel()
                    _state.update { it.copy(handle = null, directory = null, revoked = true, error = null,
                        preview = null, editorSession = null, save = null, overwrite = null, results = emptyList(), progress = 0, total = null, activeIndex = null) }
                    service.close(handle)
                }
            }
        }
    }

    fun retry() = loadDirectory(force = false)

    private fun loadDirectory(force: Boolean) {
        if (selection == null || _state.value.running) return
        val path = _state.value.path
        if (!force && readJob?.isActive == true && readPath == path) return
        readJob?.cancel()
        readPath = path
        val revision = ++readRevision
        readJob = viewModelScope.launch {
            var acquired: RemoteWorkspaceHandle? = null
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (_state.value.handle != null) service.refresh(selection)
                val handle = _state.value.handle ?: service.open(selection).also { acquired = it }
                ensureActive()
                if (revision != readRevision) return@launch
                _state.update { it.copy(handle = handle, revoked = false) }
                acquired = null
                val directory = service.list(handle, path)
                if (revision == readRevision && _state.value.handle === handle) _state.update { it.copy(directory = directory) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (revision == readRevision) report(error) }
            finally {
                acquired?.let(service::closeLater)
                if (revision == readRevision) _state.update { it.copy(loading = false) }
            }
        }
    }

    fun browse(path: String) {
        if (_state.value.running) return
        WorkspaceFileRules.path(path, rootAllowed = true)
        _state.update { it.copy(path = path, directory = null) }
        retry()
    }

    fun openPreview(file: RemoteFile) {
        val handle = _state.value.handle ?: return
        if (!_state.value.running) _state.update {
            it.copy(preview = file, editorSession = RemoteEditorSession(handle, file.path), save = null)
        }
    }

    fun closePreview() {
        if (_state.value.save?.result == null && _state.value.save != null) return
        _state.update { it.copy(preview = null, editorSession = null, save = null) }
    }

    fun clearSave() { if (!_state.value.running) _state.update { it.copy(save = null) } }
    fun cancel() { readJob?.cancel(); work?.cancel(); overwriteAnswer?.cancel() }
    fun clearResults() { if (!_state.value.running) _state.update { state -> state.copy(results = state.results.filter { it.outcome == RemoteOutcome.UNKNOWN }) } }
    fun dismissVerifiedResult(handle: RemoteWorkspaceHandle, path: String) {
        _state.update { if (it.running || it.handle !== handle) it else it.copy(results = it.results.filterNot { result -> result.path == path }) }
    }

    fun report(error: Throwable) {
        android.util.Log.e("RemoteWorkspacePage", "Remote file operation failed", error)
        _state.update { it.copy(error = error.userVisibleDiagnostic()) }
    }

    /** All write closures capture their original handle before a dialog or picker opens. */
    fun execute(paths: List<String>, original: RemoteWorkspaceHandle? = _state.value.handle,
        action: suspend (Int, RemoteWorkspaceHandle) -> RemoteOperationResult,
    ) {
        if (work?.isActive == true || original == null || _state.value.handle !== original) return
        val unverified = _state.value.results.filter { it.outcome == RemoteOutcome.UNKNOWN }.distinctBy { it.path }
        val results = paths.map { RemoteOperationResult(it, RemoteOutcome.NOT_STARTED) }
        _state.update { it.copy(running = true, results = retainUnverified(results, unverified), error = null,
            progress = 0, total = null, activeIndex = null, batchSize = paths.size) }
        work = viewModelScope.launch {
            try {
                paths.indices.forEach { index ->
                    ensureActive()
                    _state.update { it.copy(progress = 0, total = null, activeIndex = index) }
                    val result = try { action(index, original) }
                    catch (cancelled: CancellationException) {
                        _state.update { state -> if (state.handle !== original) state else state.copy(results = state.results.toMutableList().also {
                            it[index] = RemoteOperationResult(it[index].path, service.outcomeAfterCancellation(original, it[index].path),
                                cancelled.takeIf { it.suppressed.isNotEmpty() }?.userVisibleDiagnostic())
                        }) }
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.e("RemoteWorkspacePage", "Batch item failed", error)
                        RemoteOperationResult(paths[index], RemoteOutcome.FAILED, error.userVisibleDiagnostic())
                    }
                    _state.update { state -> if (state.handle !== original) state else state.copy(results = state.results.toMutableList().also { it[index] = result }) }
                    if (!service.isValid(original)) return@launch
                }
            } finally {
                overwriteAnswer?.cancel(); overwriteAnswer = null
                // A completed or cancelled batch may have changed rows; retire them before allowing new actions.
                _state.update { if (it.handle !== original) it.copy(running = false, overwrite = null, activeIndex = null)
                    else it.copy(running = false, overwrite = null, activeIndex = null, directory = null,
                        results = retainUnverified(it.results.take(paths.size), unverified)) }
                if (isActive && _state.value.handle === original) loadDirectory(force = true)
            }
        }
    }

    /** Current batch indexes stay at the front; earlier uncertain results remain reviewable until acknowledged. */
    private fun retainUnverified(batch: List<RemoteOperationResult>, unverified: List<RemoteOperationResult>) =
        batch + unverified.filter { previous -> batch.none { it.path == previous.path && it.outcome == RemoteOutcome.UNKNOWN } }

    fun save(handle: RemoteWorkspaceHandle, document: RemoteTextDocument, text: String, destination: String) {
        if (_state.value.running || _state.value.handle !== handle) return
        val editor = _state.value.editorSession?.takeIf { it.handle === handle && it.path == document.file.path } ?: return
        if (editor.document == null) editor.accept(document)
        if (editor.editor.snapshot() != text) editor.editor.replaceText(text)
        editor.editing = true
        val submission = RemoteSaveSubmission(handle, document, text, destination)
        _state.update { it.copy(save = submission) }
        execute(listOf(destination), handle) { _, _ ->
            val result = try { service.save(handle, document, text, destination) }
            catch (cancelled: CancellationException) {
                _state.update { if (it.save === submission) it.copy(save = submission.copy(
                    result = RemoteOperationResult(destination, service.outcomeAfterCancellation(handle, destination)))) else it }
                throw cancelled
            } catch (error: Exception) {
                RemoteOperationResult(destination, RemoteOutcome.FAILED, error.userVisibleDiagnostic())
            }
            _state.update { if (it.save === submission) it.copy(save = submission.copy(result = result)) else it }
            result
        }
    }

    private suspend fun confirmOverwrite(handle: RemoteWorkspaceHandle, target: RemoteFile): Boolean {
        check(!target.directory && target.versioned) { "workspace_strong_etag_required" }
        check(service.isValid(handle)) { "workspace_access_revoked" }
        val answer = CompletableDeferred<Boolean>()
        overwriteAnswer = answer
        _state.update { it.copy(overwrite = RemoteOverwritePrompt(handle, target)) }
        return try { answer.await().also { check(service.isValid(handle)) { "workspace_access_revoked" } } }
        finally {
            if (overwriteAnswer === answer) {
                overwriteAnswer = null
                _state.update { it.copy(overwrite = null) }
            }
        }
    }

    fun answerOverwrite(prompt: RemoteOverwritePrompt, confirmed: Boolean) {
        if (_state.value.overwrite === prompt && _state.value.handle === prompt.handle) overwriteAnswer?.complete(confirmed)
    }

    private suspend fun mutateOne(handle: RemoteWorkspaceHandle, action: RemoteFileAction, file: RemoteFile, target: String?): RemoteOperationResult {
        if (action == RemoteFileAction.DELETE) return service.mutate(handle, action, file, recursiveConfirmed = true)
        requireNotNull(target)
        require(target != file.path && (!file.directory || !target.startsWith(file.path + "/"))) { "workspace_invalid_destination" }
        val existing = service.list(handle, target.substringBeforeLast('/', "")).files.find { it.path == target }
        if (existing != null) {
            require(!file.directory && !existing.directory) { "workspace_directory_overwrite_forbidden" }
            if (!confirmOverwrite(handle, existing)) return RemoteOperationResult(target, RemoteOutcome.NOT_STARTED)
        }
        return service.mutate(handle, action, file, target, existing)
    }

    fun rename(handle: RemoteWorkspaceHandle, file: RemoteFile, target: String) {
        execute(listOf(target), handle) { _, _ -> mutateOne(handle, RemoteFileAction.MOVE, file, target) }
    }

    fun mutate(action: RemoteFileAction, files: List<RemoteFile>, destination: String? = null,
        original: RemoteWorkspaceHandle? = _state.value.handle,
    ) {
        val roots = files.filter { child -> files.none { parent -> parent.directory && child.path.startsWith(parent.path + "/") } }
        val paths = try { roots.map { destination?.let { path -> WorkspaceFileRules.child(path, it.name) } ?: it.path } }
        catch (error: IllegalArgumentException) { report(error); return }
        execute(paths, original) { index, handle ->
            val file = roots[index]
            mutateOne(handle, action, file, destination?.let { WorkspaceFileRules.child(it, file.name) })
        }
    }

    fun prepareUpload(handle: RemoteWorkspaceHandle, directory: String) { pendingUpload = handle to directory }
    fun uploadResult(uris: List<Uri>) {
        val request = pendingUpload; pendingUpload = null
        if (uris.isNotEmpty() && request != null) upload(uris, request.first, request.second)
    }
    fun cancelUploadPicker() { pendingUpload = null }

    private fun upload(uris: List<Uri>, original: RemoteWorkspaceHandle, directory: String) {
        if (_state.value.handle !== original) return
        execute(uris.map(Uri::toString), original) { index, handle ->
            val uri = uris[index]
            val metadata = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    check(it.moveToFirst()) { "workspace_upload_metadata_unavailable" }
                    it.getString(0) to if (it.isNull(1)) null else it.getLong(1)
                } ?: error("workspace_upload_metadata_unavailable")
            }
            val path = WorkspaceFileRules.child(directory, metadata.first)
            _state.update { state -> if (state.handle !== handle) state else state.copy(results = state.results.toMutableList().also {
                it[index] = RemoteOperationResult(path, RemoteOutcome.NOT_STARTED)
            }) }
            // Resolve every queued destination when it starts, then freeze its confirmed version.
            val existing = service.list(handle, directory).files.find { it.path == path }
            if (existing != null) {
                require(!existing.directory) { "workspace_directory_overwrite_forbidden" }
                if (!confirmOverwrite(handle, existing)) return@execute RemoteOperationResult(path, RemoteOutcome.NOT_STARTED)
            }
            service.upload(handle, path, existing?.etag, metadata.second,
                { requireNotNull(context.contentResolver.openInputStream(uri)) { "workspace_upload_open_failed" } }, ::progress)
        }
    }

    fun prepareDownload(handle: RemoteWorkspaceHandle, files: List<RemoteFile>, tree: Boolean) {
        pendingDownload = RemoteDownloadPicker(handle, files.toList(), tree)
    }
    fun cancelDownloadPicker() { pendingDownload = null }
    fun downloadResult(destination: Uri?, tree: Boolean) {
        val request = pendingDownload; pendingDownload = null
        if (destination == null) return
        if (request == null || request.tree != tree || _state.value.handle !== request.handle || _state.value.running) {
            if (!tree) cleanupCreatedDocument(destination)
            return
        }
        if (tree) download(request.files, request.handle, destination)
        else downloadSingle(request.files.single(), request.handle, destination)
    }

    private fun destination(uri: Uri) = object : RemoteExportDestination {
        override val description = uri.toString()
        override fun open() = requireNotNull(context.contentResolver.openOutputStream(uri, "w"))
        override fun delete() = DocumentsContract.deleteDocument(context.contentResolver, uri)
    }

    private fun cleanupCreatedDocument(uri: Uri) = service.discardExport(destination(uri), ::report)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun downloadSingle(file: RemoteFile, original: RemoteWorkspaceHandle, uri: Uri) {
        val transfer = service.acceptExport(original, file, destination(uri), ::progress)
        val unverified = _state.value.results.filter { it.outcome == RemoteOutcome.UNKNOWN }.distinctBy { it.path }
        _state.update { it.copy(running = true, results = retainUnverified(listOf(RemoteOperationResult(file.path, RemoteOutcome.NOT_STARTED)), unverified), error = null,
            progress = 0, total = null, activeIndex = 0, batchSize = 1) }
        work = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            var result = RemoteOperationResult(file.path, RemoteOutcome.CANCELLED)
            try {
                ensureActive(); transfer.await()
                result = RemoteOperationResult(file.path, RemoteOutcome.SUCCEEDED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                report(error); result = RemoteOperationResult(file.path, RemoteOutcome.FAILED, error.userVisibleDiagnostic())
            } finally {
                withContext(NonCancellable) { if (!transfer.isCompleted) transfer.cancelAndJoin() }
                val cleanup = transfer.getCompletionExceptionOrNull()?.takeIf { it.suppressed.isNotEmpty() }
                if (cleanup != null) { report(cleanup); result = result.copy(diagnostic = cleanup.userVisibleDiagnostic()) }
                _state.update { if (it.handle !== original) it.copy(running = false, activeIndex = null) else it.copy(running = false, activeIndex = null,
                    results = retainUnverified(listOf(result), unverified)) }
            }
        }
    }

    private fun download(files: List<RemoteFile>, original: RemoteWorkspaceHandle, tree: Uri) {
        execute(files.map { it.path }, original) { index, handle ->
            val file = files[index]
            service.exportCreated(handle, file, {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                destination(requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", file.name)))
            }, ::progress)
            RemoteOperationResult(file.path, RemoteOutcome.SUCCEEDED)
        }
    }

    private fun progress(bytes: Long, total: Long?) { _state.update { it.copy(progress = bytes, total = total) } }

    suspend fun leave() {
        readJob?.cancelAndJoin(); work?.cancelAndJoin()
        _state.value.handle?.let { service.close(it) }
        _state.update { it.copy(handle = null, directory = null, preview = null, editorSession = null, save = null, overwrite = null) }
    }

    override fun onCleared() {
        _state.value.handle?.let(service::closeLater)
        _state.update { it.copy(preview = null, editorSession = null, save = null) }
    }
}
