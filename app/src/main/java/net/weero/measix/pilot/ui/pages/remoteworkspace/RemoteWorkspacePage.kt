package net.weero.measix.pilot.ui.pages.remoteworkspace

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.SquareArrowUpRight
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Pdf01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.WorkspaceFileRules
import net.weero.measix.pilot.service.remoteworkspace.*
import net.weero.measix.pilot.ui.components.files.FileRow
import net.weero.measix.pilot.ui.components.files.PdfPreview
import net.weero.measix.pilot.ui.components.richtext.RestrictedMarkdown
import net.weero.measix.pilot.ui.components.ui.FileTextEditor
import net.weero.measix.pilot.ui.components.ui.Tooltip
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.utils.fileSizeToString
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun remoteWorkspaceStatusText(status: RemoteWorkspaceStatus): String = stringResource(when (status) {
    RemoteWorkspaceStatus.CHECKING -> R.string.remote_workspace_checking
    RemoteWorkspaceStatus.FAILED -> R.string.remote_workspace_failed
    RemoteWorkspaceStatus.AVAILABLE -> R.string.remote_workspace_available
    RemoteWorkspaceStatus.UNPROVISIONED -> R.string.remote_workspace_unprovisioned
    RemoteWorkspaceStatus.CONNECTING -> R.string.remote_workspace_connecting
    RemoteWorkspaceStatus.RESTORING -> R.string.remote_workspace_restoring
    RemoteWorkspaceStatus.DISCONNECTING -> R.string.remote_workspace_disconnecting
    RemoteWorkspaceStatus.DISCONNECTED -> R.string.remote_workspace_disconnected
    RemoteWorkspaceStatus.DISABLED -> R.string.remote_workspace_disabled
    RemoteWorkspaceStatus.DELETING -> R.string.remote_workspace_deleting
    RemoteWorkspaceStatus.DELETED -> R.string.remote_workspace_deleted
    RemoteWorkspaceStatus.NEEDS_ATTENTION -> R.string.remote_workspace_attention
    RemoteWorkspaceStatus.FILES_UNAVAILABLE -> R.string.remote_workspace_unavailable
    RemoteWorkspaceStatus.READ_FAILED -> R.string.remote_workspace_read_failed
})

@Composable
internal fun RemoteWorkspaceCard(summary: RemoteWorkspaceSummary, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("remote-workspace-card")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.remote_workspace_title), style = MaterialTheme.typography.titleSmall)
                Text(if (summary.canOpenFiles) stringResource(R.string.remote_workspace_description) else remoteWorkspaceStatusText(summary.status),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (summary.canOpenFiles) Text(remoteWorkspaceStatusText(summary.status), style = MaterialTheme.typography.labelSmall)
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(20.dp))
        }
    }
}

private data class NamePrompt(val title: Int, val handle: RemoteWorkspaceHandle, val initial: String = "", val submit: (String) -> Unit)
private data class FolderPrompt(val title: Int, val handle: RemoteWorkspaceHandle, val files: List<RemoteFile>, val action: RemoteFileAction)

internal fun EntryProviderScope<NavKey>.remoteWorkspaceEntry() {
    entry<Screen.RemoteWorkspace>(clazzContentKey = { it.id }) { key -> RemoteWorkspacePage(key.selection) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteWorkspacePage(selection: RealmSelection?, vm: RemoteWorkspaceVM = koinViewModel { parametersOf(selection) }) {
    val state by vm.state.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var filter by remember { mutableStateOf("") }
    var filtering by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var sort by remember { mutableIntStateOf(0) }
    val fileListState = rememberLazyListState()
    LaunchedEffect(filter, hidden, sort, state.path) { fileListState.scrollToItem(0) }
    var menu by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var detailFile by remember { mutableStateOf<RemoteFile?>(null) }
    var name by remember { mutableStateOf<NamePrompt?>(null) }
    var folder by remember { mutableStateOf<FolderPrompt?>(null) }
    var delete by remember { mutableStateOf<Pair<RemoteWorkspaceHandle, List<RemoteFile>>?>(null) }
    var verification by remember { mutableStateOf<Pair<RemoteWorkspaceHandle, String>?>(null) }
    var leave by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(false) }
    val visible = remember(state.directory, hidden) { state.directory?.files.orEmpty().filter { hidden || !it.name.startsWith('.') } }
    val entries = remember(visible, filter, sort) { visible.filter { it.name.contains(filter, true) }
        .sortedWith(compareBy<RemoteFile> { !it.directory }.thenComparator { a, b ->
            val order = when (sort) {
                1 -> compareValues(b.modifiedAt, a.modifiedAt)
                2 -> compareValues(b.size, a.size)
                else -> 0
            }
            if (order != 0) order else a.name.compareTo(b.name, ignoreCase = true)
        }) }
    val selectedFiles = state.directory?.files.orEmpty().filter { it.path in selected }
    LaunchedEffect(state.directory) {
        state.directory?.let { selected = selected.intersect(it.files.map { file -> file.path }.toSet()) }
    }

    fun exit() { if (state.running) leave = true else scope.launch { vm.leave(); nav.popBackStack() } }
    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            filtering -> { filter = ""; filtering = false; focus.clearFocus(); keyboard?.hide() }
            state.running -> exit()
            state.path.isNotEmpty() -> vm.browse(state.path.substringBeforeLast('/', ""))
            else -> exit()
        }
    }
    BackHandler { back() }
    LaunchedEffect(state.path, state.revoked) { filter = ""; filtering = false }
    LaunchedEffect(state.path, state.handle) {
        focus.clearFocus()
        keyboard?.hide()
        selected = emptySet()
        if (name?.handle !== state.handle) name = null
        if (folder?.handle !== state.handle) folder = null
        if (delete?.first !== state.handle) delete = null
        if (verification?.first !== state.handle) verification = null
        if (state.revoked) { detailFile = null; details = false; results = false }
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            val originalSelection = vm.selection
            if (event == Lifecycle.Event.ON_RESUME && originalSelection != null) scope.launch {
                try { vm.service.refresh(originalSelection) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { vm.report(error) }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.downloadResult(uri, false)
    }
    val downloadTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.downloadResult(uri, true)
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.uploadResult(uris)
    }
    fun export(files: List<RemoteFile>) {
        val handle = state.handle ?: return
        if (files.isEmpty() || files.any { it.directory }) return
        vm.prepareDownload(handle, files, files.size > 1)
        try { if (files.size == 1) download.launch(files.single().name) else downloadTree.launch(null) }
        catch (error: Exception) { vm.cancelDownloadPicker(); vm.report(error) }
    }
    fun share(file: RemoteFile, external: Boolean) {
        val original = state.handle ?: return
        vm.execute(listOf(file.path), original) { _, handle ->
            vm.service.share(handle, file) { copy ->
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copy, file.name)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val intent = Intent(if (external) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
                    if (external) setDataAndType(uri, mime) else { type = mime; putExtra(Intent.EXTRA_STREAM, uri) }
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, file.name))
            }
            RemoteOperationResult(file.path, RemoteOutcome.SUCCEEDED)
        }
    }

    state.preview?.let { file ->
        val handle = state.handle
        if (handle != null && !state.revoked) {
            RemoteFilePreview(file, handle, vm, onClose = vm::closePreview, onDownload = { export(listOf(file)) }, onShare = { share(file, it) })
            return
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            if (filtering && selected.isEmpty()) {
                val label = stringResource(R.string.remote_workspace_filter)
                val inputFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { inputFocus.requestFocus() }
                BasicTextField(filter, { filter = it },
                    Modifier.fillMaxWidth().focusRequester(inputFocus).semantics { contentDescription = label },
                    singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                    decorationBox = { input ->
                        Box { if (filter.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            input() }
                    })
            } else Text(if (selected.isEmpty()) stringResource(R.string.remote_workspace_title)
                else stringResource(R.string.remote_workspace_selected, selected.size), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
            navigationIcon = { IconButton({ back() }) {
                Icon(if (selected.isEmpty()) HugeIcons.ArrowLeft01 else HugeIcons.Cancel01,
                    stringResource(if (selected.isEmpty()) R.string.back else R.string.common_cancel)) } },
            actions = {
                if (selected.isEmpty() && !filtering) IconButton(vm::retry, enabled = !state.loading && !state.running) { Icon(HugeIcons.Refresh, stringResource(R.string.enterprise_budget_refresh)) }
                if (selected.isEmpty() && filtering) IconButton({ filter = ""; filtering = false; focus.clearFocus(); keyboard?.hide() }) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.common_cancel))
                }
                if (selected.isNotEmpty()) Tooltip(tooltip = { Text(stringResource(R.string.remote_workspace_download)) }) {
                    IconButton({ export(selectedFiles) }, enabled = !state.running && !state.loading &&
                        selectedFiles.isNotEmpty() && selectedFiles.none { it.directory }) {
                        Icon(HugeIcons.Download01, stringResource(R.string.remote_workspace_download))
                    }
                }
                Box {
                    IconButton({ menu = true }, modifier = Modifier.testTag("remote-browser-menu")) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (selected.isNotEmpty()) {
                            for ((label, action) in listOf(R.string.remote_workspace_move to RemoteFileAction.MOVE, R.string.remote_workspace_copy to RemoteFileAction.COPY)) {
                                DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = !state.running && selectedFiles.isNotEmpty() && selectedFiles.all { it.directory || it.versioned },
                                    onClick = { menu = false; state.handle?.let { folder = FolderPrompt(label, it, selectedFiles, action) } })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, enabled = !state.running && selectedFiles.isNotEmpty() && selectedFiles.all { it.directory || it.versioned },
                                onClick = { menu = false; state.handle?.let { delete = it to selectedFiles } })
                            HorizontalDivider()
                        }
                        listOf(R.string.remote_workspace_sort_name, R.string.remote_workspace_sort_time, R.string.remote_workspace_sort_size).forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, modifier = Modifier.semantics { this.selected = sort == index }, trailingIcon = {
                                if (sort == index) Icon(HugeIcons.Tick02, null)
                            }, onClick = { sort = index; menu = false })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_hidden)) }, trailingIcon = { Checkbox(hidden, null) }, onClick = { hidden = !hidden; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_select_all)) }, enabled = state.directory != null && !state.loading && !state.running,
                            onClick = { selected = entries.map { it.path }.toSet(); menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_details)) }, onClick = { detailFile = null; details = true; menu = false })
                    }
                }
            })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).imePadding()) {
            // A short IME viewport cannot fit another touch target below the title-bar filter.
            if (filtering && maxHeight < 48.dp) return@BoxWithConstraints
            val wide = maxWidth >= 600.dp && LocalDensity.current.fontScale < 1.3f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.revoked) Text(stringResource(R.string.remote_workspace_revoked))
                else if (state.handle == null) {
                    summary?.takeIf { it.selection == vm.selection }?.let { Text(remoteWorkspaceStatusText(it.status)) }
                    Button(vm::retry, enabled = !state.loading) { Text(stringResource(R.string.enterprise_budget_refresh)) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemotePathBar(state.path, enabled = !state.running, modifier = Modifier.weight(1f), onPath = vm::browse)
                        if (selected.isEmpty()) {
                            if (!filtering) IconButton({ filtering = true },
                                enabled = state.directory != null && !state.loading) { Icon(HugeIcons.Search01, stringResource(R.string.remote_workspace_filter)) }
                            Box {
                                val addLabel = stringResource(R.string.add)
                                IconButton({ add = true }, enabled = !state.running,
                                    modifier = Modifier.semantics { contentDescription = addLabel }) { Icon(HugeIcons.Add01, null) }
                                DropdownMenu(add, { add = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_upload)) }, onClick = {
                                        add = false; state.handle?.let {
                                            vm.prepareUpload(it, state.path)
                                            try { upload.launch(arrayOf("*/*")) }
                                            catch (error: Exception) { vm.cancelUploadPicker(); vm.report(error) }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_new_folder)) }, onClick = {
                                        add = false; val path = state.path; val original = state.handle ?: return@DropdownMenuItem
                                        name = NamePrompt(R.string.remote_workspace_new_folder, original) { value ->
                                            val target = WorkspaceFileRules.child(path, value)
                                            vm.execute(listOf(target), original) { _, handle -> vm.service.createDirectory(handle, target) }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_new_text)) }, onClick = {
                                        add = false; val path = state.path; val original = state.handle ?: return@DropdownMenuItem
                                        name = NamePrompt(R.string.remote_workspace_new_text, original, "untitled.txt") { value ->
                                            val target = WorkspaceFileRules.child(path, value)
                                            vm.execute(listOf(target), original) { _, handle -> vm.service.upload(handle, target, null, 0, { byteArrayOf().inputStream() }) }
                                        }
                                    })
                                }
                            }
                        }
                    }
                    state.directory?.let { directory ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(if (filter.isEmpty()) stringResource(R.string.remote_workspace_item_count, entries.size)
                                else stringResource(R.string.remote_workspace_filtered_count, entries.size, visible.size), style = MaterialTheme.typography.labelSmall)
                            directory.usedBytes?.let { Text(stringResource(R.string.remote_workspace_used, it.fileSizeToString()), style = MaterialTheme.typography.labelSmall) }
                            directory.availableBytes?.let { Text(stringResource(R.string.remote_workspace_free, it.fileSizeToString()), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                state.error?.let { Text(stringResource(R.string.remote_workspace_operation_failed), color = MaterialTheme.colorScheme.error); Diagnostic(it) }
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.remote_workspace_loading), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(vm::cancel) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
                if (state.running) TransferStatus(state, vm::cancel)
                if (state.results.isNotEmpty() && !state.running) {
                    val completed = state.results.count { it.outcome == RemoteOutcome.SUCCEEDED }
                    val unknown = state.results.count { it.outcome == RemoteOutcome.UNKNOWN }
                    val problems = state.results.count { it.outcome == RemoteOutcome.FAILED || it.outcome == RemoteOutcome.PARTIAL }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when {
                                unknown > 0 -> stringResource(R.string.remote_workspace_verification_count, unknown)
                                problems > 0 -> stringResource(R.string.remote_workspace_problem_count, problems)
                                state.results.any { it.outcome == RemoteOutcome.CANCELLED } -> stringResource(R.string.remote_workspace_cancelled)
                                state.results.any { it.outcome == RemoteOutcome.NOT_STARTED } -> stringResource(R.string.remote_workspace_not_started)
                                else -> stringResource(R.string.remote_workspace_completed_count, completed)
                            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = if (unknown > 0 || problems > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            TextButton({ results = true }) { Text(stringResource(R.string.remote_workspace_details)) }
                            IconButton(vm::clearResults, enabled = unknown == 0) { Icon(HugeIcons.Cancel01, stringResource(R.string.common_confirm)) }
                        }
                    }
                }
                if (wide && entries.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(if (selected.isEmpty()) 22.dp else 48.dp))
                    Text(stringResource(R.string.image_viewer_info_filename), Modifier.weight(1f).padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RemoteFileColumns(null)
                    Spacer(Modifier.width(48.dp))
                }
                LazyColumn(Modifier.weight(1f).testTag("remote-file-list"), state = fileListState, contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (!state.loading && state.directory != null && entries.isEmpty()) item {
                        Text(stringResource(if (filter.isNotBlank()) R.string.search_page_no_results else R.string.remote_workspace_empty), Modifier.padding(vertical = 32.dp))
                    }
                    items(entries, key = { it.path }) { file ->
                        FileRow(file.name, if (wide) "" else listOfNotNull(file.size?.takeUnless { file.directory }?.fileSizeToString(), file.modifiedAt?.let { formatFileTime(it) }).joinToString(" · "),
                            file.directory, file.path in selected, selected.isNotEmpty(),
                            onOpen = { if (!state.running) { if (file.directory) vm.browse(file.path) else vm.openPreview(file) } },
                            onSelect = { if (!state.running) selected = if (file.path in selected) selected - file.path else selected + file.path },
                            compact = true,
                            metadata = if (wide) { { RemoteFileColumns(file) } } else null,
                            thumbnail = if (file.directory) null else { {
                                Icon(when {
                                    file.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> HugeIcons.Image01
                                    file.name.endsWith(".pdf", ignoreCase = true) -> HugeIcons.Pdf01
                                    isTextFile(file) -> HugeIcons.FileEdit
                                    else -> HugeIcons.File02
                                }, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            } },
                        ) { dismiss ->
                            if (!file.directory) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_download)) }, onClick = { dismiss(); export(listOf(file)) }, enabled = !state.running)
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, onClick = { dismiss(); share(file, false) }, enabled = !state.running)
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_rename)) }, enabled = !state.running && (file.directory || file.versioned), onClick = {
                                dismiss(); val original = state.handle ?: return@DropdownMenuItem
                                name = NamePrompt(R.string.remote_workspace_rename, original, file.name) { value ->
                                    val target = WorkspaceFileRules.child(file.path.substringBeforeLast('/', ""), value)
                                    vm.rename(original, file, target)
                                }
                            })
                            for ((label, action) in listOf(R.string.remote_workspace_move to RemoteFileAction.MOVE, R.string.remote_workspace_copy to RemoteFileAction.COPY)) {
                                DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = !state.running && (file.directory || file.versioned), onClick = { dismiss(); state.handle?.let { folder = FolderPrompt(label, it, listOf(file), action) } })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, enabled = !state.running && (file.directory || file.versioned), onClick = { dismiss(); state.handle?.let { delete = it to listOf(file) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_details)) }, onClick = { dismiss(); detailFile = file; details = true })
                            if (!file.directory && !file.versioned) Text(stringResource(R.string.remote_workspace_no_version), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    name?.let { prompt -> NameDialog(prompt, { name = null }, vm::report) }
    delete?.let { (handle, files) -> ConfirmDialog(stringResource(R.string.common_delete),
        stringResource(R.string.remote_workspace_delete_confirm) + "\n\n" + files.joinToString("\n") { it.name }, { delete = null }) {
        vm.mutate(RemoteFileAction.DELETE, files, original = handle); selected = emptySet(); delete = null
    } }
    if (leave) ConfirmDialog(stringResource(R.string.stop), stringResource(R.string.remote_workspace_stop_leave), { leave = false }) {
        scope.launch { vm.leave(); nav.popBackStack() }
    }
    folder?.let { prompt ->
        FolderDialog(prompt.handle, vm.service, prompt, { folder = null }) { path, _ ->
            vm.mutate(prompt.action, prompt.files, path, original = prompt.handle)
            folder = null; selected = emptySet()
        }
    }
    state.overwrite?.let { prompt -> ConfirmDialog(stringResource(R.string.remote_workspace_overwrite),
        listOfNotNull(prompt.target.path, prompt.target.modifiedAt?.let { formatFileTime(it, full = true) },
            prompt.target.size?.fileSizeToString()).joinToString("\n"),
        { vm.answerOverwrite(prompt, false) }) {
        vm.answerOverwrite(prompt, true)
    } }
    verification?.let { (handle, path) -> VerifyResultDialog(handle, path, vm.service, { verification = null }) {
        verification = null; vm.dismissVerifiedResult(handle, path); vm.retry()
    } }
    if (results) AlertDialog(onDismissRequest = { results = false }, title = { Text(stringResource(R.string.remote_workspace_results)) },
        confirmButton = { TextButton({ results = false }) { Text(stringResource(R.string.common_confirm)) } }, text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.results) { result ->
                    Column {
                        Text(result.path, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(when (result.outcome) {
                            RemoteOutcome.SUCCEEDED -> R.string.remote_workspace_completed
                            RemoteOutcome.FAILED -> R.string.remote_workspace_operation_failed
                            RemoteOutcome.PARTIAL -> R.string.remote_workspace_partial
                            RemoteOutcome.UNKNOWN -> R.string.remote_workspace_unknown
                            RemoteOutcome.NOT_STARTED -> R.string.remote_workspace_not_started
                            RemoteOutcome.CANCELLED -> R.string.remote_workspace_cancelled
                        }), style = MaterialTheme.typography.bodySmall)
                        result.diagnostic?.let { Diagnostic(it) }
                        if (result.outcome == RemoteOutcome.UNKNOWN) TextButton({
                            results = false; state.handle?.let { verification = it to result.path }
                        }, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_read_verify)) }
                    }
                }
            }
        })
    if (details) AlertDialog(onDismissRequest = { details = false }, confirmButton = { TextButton({ details = false }) { Text(stringResource(R.string.common_confirm)) } },
        title = { Text(stringResource(R.string.remote_workspace_details)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                detailFile?.let { FileMetadata(it) }
                if (detailFile == null) {
                    Text(if (state.path.isEmpty()) stringResource(R.string.remote_workspace_root) else state.path, style = MaterialTheme.typography.titleSmall)
                    state.handle?.let { Text(stringResource(R.string.remote_workspace_owner, it.connection.origin)) }
                    summary?.let { Text(remoteWorkspaceStatusText(it.status)) }
                    state.directory?.let { if (it.usedBytes != null || it.availableBytes != null) Text(stringResource(R.string.remote_workspace_capacity,
                        it.usedBytes?.fileSizeToString() ?: "—", it.availableBytes?.fileSizeToString() ?: "—")) }
                    Diagnostic(listOfNotNull(summary?.reason, summary?.let { "MCP: ${it.mcpAvailable}; ${it.mcpReason.orEmpty()}" },
                        summary?.diagnostic, state.handle?.space).joinToString("\n"))
                }
            }
        })
}

/** Fixed metadata widths keep both the header and selected rows aligned. Large text uses stacked rows. */
@Composable
private fun RemoteFileColumns(file: RemoteFile?) {
    val style = if (file == null) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    Text(if (file == null) stringResource(R.string.image_viewer_info_modified) else file.modifiedAt?.let { formatFileTime(it) } ?: "—",
        Modifier.width(152.dp), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(if (file == null) stringResource(R.string.image_viewer_info_size) else file.size?.takeUnless { file.directory }?.fileSizeToString() ?: "—",
        Modifier.width(80.dp).padding(end = 12.dp), textAlign = TextAlign.End, style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun RemotePathBar(path: String, enabled: Boolean = true, modifier: Modifier = Modifier, onPath: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ onPath("") }, enabled = enabled) { Text(stringResource(R.string.remote_workspace_root)) }
        var current = ""
        path.split('/').filter(String::isNotEmpty).forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            val target = current
            Text("›"); TextButton({ onPath(target) }, enabled = enabled) { Text(segment, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Diagnostic(value: String, label: String = stringResource(R.string.remote_workspace_details)) {
    var expanded by remember(value) { mutableStateOf(false) }
    TextButton({ expanded = !expanded }) { Text(label) }
    if (expanded) SelectionContainer { Text(value, Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ConfirmDialog(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = if (body.isBlank()) null else ({
        SelectionContainer { Text(body, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) }
    }),
        confirmButton = { TextButton(confirm) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun NameDialog(prompt: NamePrompt, dismiss: () -> Unit, report: (Throwable) -> Unit) {
    var value by remember(prompt) { mutableStateOf(TextFieldValue(prompt.initial, TextRange(0, prompt.initial.length))) }
    val focus = remember { FocusRequester() }
    var error by remember(prompt) { mutableStateOf<String?>(null) }
    var hint by remember(prompt) { mutableStateOf<String?>(null) }
    val invalidName = stringResource(R.string.remote_workspace_invalid_name)
    val newName = stringResource(R.string.remote_workspace_save_as_new_name)
    val enabled = value.text.isNotBlank() && (prompt.title != R.string.remote_workspace_rename || value.text != prompt.initial)
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        fun submit() {
            try { WorkspaceFileRules.child("", value.text); prompt.submit(value.text); dismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.userVisibleDiagnostic()
                hint = if (failure is IllegalArgumentException) when (failure.message) {
                    "invalid_workspace_name", "invalid_workspace_path" -> invalidName
                    "workspace_save_as_requires_new_name" -> newName
                    else -> null
                } else null
                keyboard?.hide(); focusManager.clearFocus()
                if (hint == null) report(failure)
            }
        }
        val window = LocalWindowInfo.current
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxWidth().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 280.dp
            LaunchedEffect(prompt, window.isWindowFocused) {
                if (window.isWindowFocused) {
                    withFrameNanos { }
                    focus.requestFocus()
                }
            }
            val errorHeight = (maxHeight - 112.dp).coerceAtLeast(24.dp)
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 12.dp),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                val buttons: @Composable RowScope.() -> Unit = {
                    TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton({ submit() }, enabled = enabled) { Text(stringResource(R.string.common_confirm)) }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!compact) {
                        Text(stringResource(prompt.title), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    // Only the buttons move: the field keeps its composition position and IME connection.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = stringResource(R.string.remote_workspace_name)
                        OutlinedTextField(value, { value = it; error = null; hint = null },
                            Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = label },
                            singleLine = true, label = { Text(stringResource(if (compact) prompt.title else R.string.remote_workspace_name),
                                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focusManager.clearFocus() }))
                        if (compact) buttons()
                    }
                    error?.let { diagnostic -> Column(Modifier.weight(1f, fill = false).heightIn(max = errorHeight).verticalScroll(rememberScrollState())) {
                        hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Diagnostic(diagnostic)
                    } }
                    if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = buttons)
                }
            }
        }
    }
}

@Composable
private fun FolderDialog(handle: RemoteWorkspaceHandle, service: RemoteWorkspaceService, prompt: FolderPrompt,
    dismiss: () -> Unit, choose: (String, List<RemoteFile>) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf<RemoteDirectory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        directory = null; error = null
        try { directory = service.list(handle, path) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(prompt.title)) }, text = {
        Column {
            Text(prompt.files.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RemotePathBar(path) { path = it }
            error?.let { Diagnostic(it) }
            if (directory == null && error == null) CircularProgressIndicator()
            val folders = directory?.files.orEmpty().filter { it.directory && prompt.files.none { source -> source.directory && source.path == it.path } }.sortedBy { it.name.lowercase() }
            if (directory != null && folders.isEmpty()) Text(stringResource(R.string.remote_workspace_no_subfolders), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(folders) { file ->
                ListItem(headlineContent = { Text(file.name) }, leadingContent = { Icon(HugeIcons.Folder01, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { path = file.path })
            } }
        }
    }, confirmButton = { TextButton({
        try {
            prompt.files.forEach { WorkspaceFileRules.child(path, it.name) }
            choose(path, requireNotNull(directory).files)
        } catch (failure: IllegalArgumentException) { error = failure.userVisibleDiagnostic() }
    }, enabled = directory != null && prompt.files.none {
        it.path.substringBeforeLast('/', "") == path || it.path == path || (it.directory && path.startsWith(it.path + "/"))
    }) { Text(stringResource(R.string.remote_workspace_select_folder)) } }, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun FileMetadata(file: RemoteFile) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(file.path, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (file.directory) R.string.remote_workspace_kind_folder else R.string.remote_workspace_kind_file))
            file.size?.let { Text(stringResource(R.string.remote_workspace_file_size, it.fileSizeToString())) }
            file.modifiedAt?.let { Text(stringResource(R.string.remote_workspace_modified, formatFileTime(it, full = true))) }
        }
    }
    file.etag?.let { Diagnostic(it, "ETag") }
}

/** A fresh read is shown before the user releases the unknown-write guard. It never repeats a write. */
@Composable
private fun VerifyResultDialog(handle: RemoteWorkspaceHandle, path: String, service: RemoteWorkspaceService,
    dismiss: () -> Unit, acknowledged: () -> Unit,
) {
    var file by remember(handle, path) { mutableStateOf<RemoteFile?>(null) }
    var complete by remember(handle, path) { mutableStateOf(false) }
    var loading by remember(handle, path) { mutableStateOf(true) }
    var error by remember(handle, path) { mutableStateOf<String?>(null) }
    var text by remember(handle, path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(handle, path) {
        try {
            file = service.verifyUnknown(handle, path)
            val current = file
            if (current != null && !current.directory && isTextFile(current)) {
                text = try { service.readText(handle, current).content.text }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.userVisibleDiagnostic(); null }
            }
            complete = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.remote_workspace_read_verify)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(path)
            if (loading) CircularProgressIndicator()
            else if (complete) {
                file?.let { FileMetadata(it) } ?: Text(stringResource(R.string.remote_workspace_target_absent))
                text?.let {
                    if (it.length > 8192) Text(stringResource(R.string.remote_workspace_verify_excerpt))
                    SelectionContainer { Text(it.take(8192), style = MaterialTheme.typography.bodySmall) }
                }
                Text(stringResource(R.string.remote_workspace_verify_explanation))
            }
            error?.let { Text(stringResource(R.string.remote_workspace_operation_failed)); Diagnostic(it) }
        }
    }, confirmButton = { TextButton({
        loading = true
        scope.launch {
            try { service.acknowledgeVerified(handle, path); acknowledged() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
            finally { loading = false }
        }
    }, enabled = complete && !loading) { Text(stringResource(R.string.remote_workspace_verify)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

private fun isTextFile(file: RemoteFile) = file.name.substringAfterLast('.', "").lowercase() in setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "xml", "csv", "log", "kt", "java", "py", "js", "ts", "css",
    "sh", "toml", "ini", "conf", "sql", "rs", "go", "c", "h", "cpp", "",
)

private fun formatFileTime(value: String, full: Boolean = false): String = try {
    DateTimeFormatter.ofLocalizedDateTime(if (full) FormatStyle.MEDIUM else FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).format(Instant.parse(value).atZone(ZoneId.systemDefault()))
} catch (_: java.time.DateTimeException) { value }

@Composable
private fun TransferStatus(state: RemoteBrowserState, cancel: () -> Unit) {
    val total = state.total
    if (total != null && total > 0 && state.progress < total) {
        LinearProgressIndicator(progress = { (state.progress.toDouble() / total).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
    } else LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            state.activeIndex?.let { index ->
                state.results.getOrNull(index)?.let { current ->
                    Text(stringResource(R.string.remote_workspace_active_item, index + 1, state.batchSize, current.path.substringAfterLast('/')),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (total != null || state.progress > 0) Text(
                if (total != null && state.progress >= total) stringResource(R.string.remote_workspace_waiting)
                else "${state.progress.fileSizeToString()} / ${total?.fileSizeToString() ?: "—"}", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(cancel) { Text(stringResource(R.string.common_cancel)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFilePreview(file: RemoteFile, handle: RemoteWorkspaceHandle, vm: RemoteWorkspaceVM,
    onClose: () -> Unit, onDownload: () -> Unit, onShare: (Boolean) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editorSession = state.editorSession?.takeIf { it.handle === handle && it.path == file.path } ?: return
    val submission = state.save?.takeIf { it.handle === handle && it.document.file.path == file.path }
    val saving = submission != null && submission.result == null
    val result = submission?.result
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document = editorSession.document
    val density = LocalDensity.current
    val compactToolbar = with(density) { LocalWindowInfo.current.containerSize.height.toDp() < 480.dp } ||
        WindowInsets.ime.getBottom(density) > 0
    var pdf by remember(file, handle) { mutableStateOf<File?>(null) }
    var error by remember(file, handle) { mutableStateOf<String?>(null) }
    var loading by remember(file, handle) { mutableStateOf(document == null) }
    val editing = editorSession.editing
    var discard by remember { mutableStateOf(false) }
    var reread by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var saveAs by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf<String?>(null) }
    var actions by remember { mutableStateOf(false) }
    val editor = editorSession.editor
    val dirty by remember(editorSession) { derivedStateOf {
        editor.revision
        editorSession.document?.let { editor.snapshot() != it.content.text } == true
    } }
    val extension = file.name.substringAfterLast('.', "").lowercase()
    val image = extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    val markdown = extension in setOf("md", "markdown")
    fun close() { if (saving) return else if (dirty) discard = true else onClose() }
    BackHandler { close() }
    LaunchedEffect(file, handle, reload) {
        if (reload == 0 && editorSession.document != null) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try {
            if (reload > 0) vm.service.refresh(handle.selection)
            when {
                image -> Unit
                extension == "pdf" -> pdf = vm.service.previewCopy(handle, file)
                isTextFile(file) -> {
                    val fresh = vm.service.readText(handle, file)
                    editorSession.accept(fresh)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
        finally { loading = false }
    }
    LaunchedEffect(result) {
        if (result?.outcome == RemoteOutcome.SUCCEEDED) onClose()
    }
    DisposableEffect(pdf) {
        val copy = pdf
        onDispose { copy?.let { vm.service.releaseCopyLater(it) } }
    }
    val images = remember(handle, file) { mutableSetOf<String>() }
    val imageResolver: suspend (String) -> net.weero.measix.pilot.service.ImageSource? = remember(handle, file) { { reference ->
        val path = WorkspaceFileRules.relativeImage(file.path, reference)
        synchronized(images) { require(path in images || images.size < 20) { "workspace_markdown_image_limit" }; images += path }
        vm.service.imageSource(handle, RemoteFile(path, false, null, null, null), 4L * 1024 * 1024)
    } }
    fun save(destination: String) {
        val original = document ?: return
        if (saving || state.running || loading) return
        vm.save(handle, original, editor.snapshot(), destination)
    }
    if (image) {
        val source = remember(file, handle) { vm.service.imageSource(handle, file) }
        ImagePreviewDialog(listOf(source), onDismissRequest = onClose,
            onInfoRetry = { vm.service.refresh(handle.selection) }, extraActions = listOf(
            net.weero.measix.pilot.ui.components.ui.ImagePreviewAction(HugeIcons.SquareArrowUpRight,
                stringResource(R.string.remote_workspace_open_external)) { _, _ -> if (!state.running) onShare(true) },
        ), overlay = {
            if (state.running) Surface {
                Column(Modifier.padding(8.dp)) {
                    LinearProgressIndicator()
                    TextButton(vm::cancel) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        })
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton({ close() }, enabled = !saving) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) } },
        actions = {
            if (document != null && !editing) Tooltip(tooltip = { Text(stringResource(R.string.edit)) }) {
                IconButton({ editorSession.editing = true }, enabled = !state.running && !loading) {
                    Icon(HugeIcons.FileEdit, stringResource(R.string.edit))
                }
            }
            if (editing) Tooltip(tooltip = { Text(stringResource(R.string.remote_workspace_save_close)) }) {
                IconButton({ save(file.path) }, enabled = !state.running && !loading && document?.file?.versioned == true && result?.outcome != RemoteOutcome.UNKNOWN) {
                    Icon(HugeIcons.FloppyDisk, stringResource(R.string.remote_workspace_save_close))
                }
            }
            Box {
            IconButton({ actions = true }, enabled = !state.running) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
            DropdownMenu(actions, { actions = false }) {
                if (editing) DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_save_as)) }, enabled = !loading,
                    onClick = { actions = false; saveAs = true })
                if (editing) DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_reread)) }, enabled = !loading,
                    onClick = { actions = false; if (dirty) reread = true else { vm.clearSave(); reload++ } })
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_download)) }, onClick = { actions = false; onDownload() })
                DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, onClick = { actions = false; onShare(false) })
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_open_external)) }, onClick = { actions = false; onShare(true) })
            }
        } }, expandedHeight = if (compactToolbar) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)
            .padding(horizontal = if (document != null && (editing || !markdown)) 0.dp else 12.dp, vertical = 4.dp)
            .imePadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.running) TransferStatus(state, vm::cancel)
            val unavailable = !loading && document == null && pdf == null
            if (!unavailable) {
                error?.let { Text(stringResource(R.string.remote_workspace_operation_failed)); Diagnostic(it) }
                state.error?.let { Text(stringResource(R.string.remote_workspace_operation_failed)); Diagnostic(it) }
            }
            result?.takeIf { it.outcome != RemoteOutcome.SUCCEEDED }?.let {
                Text(stringResource(when (it.outcome) {
                    RemoteOutcome.UNKNOWN -> R.string.remote_workspace_unknown
                    RemoteOutcome.CANCELLED -> R.string.remote_workspace_cancelled
                    else -> R.string.remote_workspace_operation_failed
                }))
                it.diagnostic?.let { detail -> Diagnostic(detail) }
                if (it.outcome == RemoteOutcome.UNKNOWN) TextButton({ verification = it.path }, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_read_verify)) }
            }
            if (editing && document?.file?.versioned == false) Text(stringResource(R.string.remote_workspace_no_version))
            when {
                document != null && markdown && !editing -> RestrictedMarkdown(requireNotNull(document).content.text,
                    Modifier.weight(1f).verticalScroll(rememberScrollState()), imageResolver) { uri ->
                    scope.launch { try { if (vm.service.isValid(handle)) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.userVisibleDiagnostic() } }
                }
                document != null -> FileTextEditor(editor, Modifier.weight(1f).fillMaxWidth(), enabled = !saving && !loading, readOnly = !editing, fillViewport = true)
                pdf != null -> PdfPreview(requireNotNull(pdf), Modifier.weight(1f)) {
                    error = it.userVisibleDiagnostic()
                    pdf = null
                }
                !loading -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(stringResource(R.string.remote_workspace_operation_failed)); Diagnostic(it) }
                    state.error?.let { Text(stringResource(R.string.remote_workspace_operation_failed)); Diagnostic(it) }
                    if (error == null) Text(stringResource(R.string.remote_workspace_preview_unsupported))
                    FlowRow {
                        if (error != null) TextButton({ reload++ }, enabled = !state.running) { Text(stringResource(R.string.enterprise_budget_refresh)) }
                        TextButton(onDownload, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_download)) }
                        TextButton({ onShare(true) }, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_open_external)) }
                    }
                    FileMetadata(file)
                }
            }
        }
    }
    if (discard) ConfirmDialog(stringResource(R.string.remote_workspace_unsaved), "", { discard = false }) { onClose() }
    if (reread) ConfirmDialog(stringResource(R.string.remote_workspace_reread), stringResource(R.string.remote_workspace_reread_confirm), { reread = false }) {
        reread = false; vm.clearSave(); reload++
    }
    verification?.let { path -> VerifyResultDialog(handle, path, vm.service, { verification = null }) {
        verification = null; vm.clearSave(); vm.dismissVerifiedResult(handle, path)
    } }
    if (saveAs) NameDialog(NamePrompt(R.string.remote_workspace_save_as, handle, "copy-${file.name}") { value ->
        val target = WorkspaceFileRules.child(file.path.substringBeforeLast('/', ""), value)
        require(target != file.path) { "workspace_save_as_requires_new_name" }
        save(target)
    }, { saveAs = false }, vm::report)
}
