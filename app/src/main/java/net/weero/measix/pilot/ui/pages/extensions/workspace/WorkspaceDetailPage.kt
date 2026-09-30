package net.weero.measix.pilot.ui.pages.extensions.workspace

import android.content.Intent
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import coil3.compose.AsyncImage
import net.weero.measix.pilot.service.ImageSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.service.workspace.WorkspaceExportRequest
import net.weero.measix.pilot.service.workspace.WorkspaceExportItem
import net.weero.measix.pilot.service.workspace.WorkspaceDocumentTreeDestination
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Share08
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.tools.resolveWorkspaceToolApproval
import net.weero.measix.pilot.service.workspace.WorkspaceUiModel
import androidx.compose.ui.res.stringResource
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.ConfirmDialog
import net.weero.measix.pilot.ui.components.ui.Tooltip
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDeleteAction
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDeleteResult
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.fileSizeToString
import net.weero.measix.pilot.utils.plus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val exportState by vm.exportState.collectAsStateWithLifecycle()
    // Returning from an editor or external document app must read the latest committed file metadata.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    var selectedFiles by remember(id, state.area, state.path) { mutableStateOf(emptySet<String>()) }
    var pendingBatch by remember(id) { mutableStateOf<WorkspaceExportRequest?>(null) }
    val pagerState = rememberPagerState { 2 }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 0) selectedFiles = emptySet()
    }
    val compactToolbar = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < 480.dp }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<WorkspaceImagePreview?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val imageDeleteFailed = stringResource(R.string.image_viewer_delete_failed)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember(id) { mutableStateOf<Pair<WorkspaceStorageArea, WorkspaceFileEntry>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val (area, entry) = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Document provider did not open output: $uri")
                    output.use { vm.exportFile(entry, area, it) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { vm.reportExportFailure(error) }
        }
    }
    val batchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request = pendingBatch.also { pendingBatch = null } ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            selectedFiles = emptySet()
            try { vm.exportFiles(request, WorkspaceDocumentTreeDestination(context.contentResolver, uri)) }
            catch (error: Exception) { vm.reportExportFailure(error) }
        }
    }

    BackHandler(enabled = pagerState.currentPage == 1 && (selectedFiles.isNotEmpty() || state.path.isNotBlank())) {
        if (selectedFiles.isNotEmpty()) selectedFiles = emptySet() else vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedFiles.isNotEmpty()) stringResource(R.string.workspace_export_selected, selectedFiles.size)
                            else state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selectedFiles.isNotEmpty()) IconButton(onClick = { selectedFiles = emptySet() }) {
                        Icon(HugeIcons.Cancel01, stringResource(R.string.common_cancel))
                    } else BackButton()
                },
                actions = {
                    if (exportState?.running == true) {
                        IconButton(onClick = vm::cancelExport) { Icon(HugeIcons.Cancel01, stringResource(R.string.common_cancel)) }
                    } else if (selectedFiles.isNotEmpty()) {
                        Tooltip(tooltip = { Text(stringResource(R.string.common_export)) }) {
                            IconButton(enabled = pendingBatch == null, onClick = {
                                pendingBatch = WorkspaceExportRequest(id, state.area, selectedFiles.toList())
                                batchLauncher.launch(null)
                            }) { Icon(HugeIcons.Download01, stringResource(R.string.common_export)) }
                        }
                    }
                    if (pagerState.currentPage == 1 && selectedFiles.isEmpty()) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.enterprise_budget_refresh))
                    }
                    if (selectedFiles.isEmpty() && state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = stringResource(R.string.workspace_terminal))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = if (compactToolbar) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
            )
        },
        bottomBar = {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage, modifier = Modifier.navigationBarsPadding()) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    text = { Text(stringResource(R.string.workspace_detail_tab_basic), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    text = { Text(stringResource(R.string.workspace_detail_tab_files), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> WorkspaceBasicPage(
                    workspace = state.workspace,
                    installProgress = installProgress,
                    onInstallRootfs = { showInstallDialog = true },
                    onToolApprovalChange = vm::setToolApproval,
                )

                1 -> WorkspaceFilesPage(
                    state = state,
                    selected = selectedFiles,
                    onToggleSelection = { entry ->
                        if (!entry.isDirectory) selectedFiles = if (entry.path in selectedFiles)
                            selectedFiles - entry.path else selectedFiles + entry.path
                    },
                    imageSource = vm::imageSource,
                    contentPadding = PaddingValues(),
                    onSelectArea = vm::selectArea,
                    onGoUp = vm::goUp,
                    onOpen = { entry ->
                        when {
                            entry.isDirectory -> vm.open(entry)

                            else -> when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> navController.navigate(
                                    Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                )

                                WorkspaceFileType.IMAGE -> {
                                    previewImage = WorkspaceImagePreview(entry, state.area, vm.imageSource(entry, state.area))
                                }

                                WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                        file.extension.lowercase()
                                    ) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(intent, null))
                                    }
                                }
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onExport = { entry ->
                        exportTarget = state.area to entry
                        exportLauncher.launch(entry.name)
                    },
                    onShare = { entry ->
                        vm.exportToCacheFile(entry, context.cacheDir) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    },
                )
            }
        }
    }

    exportState?.let { exporting ->
        AlertDialog(
            onDismissRequest = { if (!exporting.running) vm.dismissExportResult() },
            title = { Text(stringResource(R.string.common_export)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.workspace_export_progress, exporting.items.size, exporting.request.paths.size))
                    if (exporting.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else Text(stringResource(R.string.workspace_export_result,
                        exporting.items.count { it is WorkspaceExportItem.Exported },
                        exporting.items.count { it is WorkspaceExportItem.Failed }))
                    if (exporting.cancelled) Text(stringResource(R.string.workspace_export_cancelled))
                    SelectionContainer {
                        Text(exporting.items.joinToString("\n") { item -> when (item) {
                            is WorkspaceExportItem.Exported -> item.name
                            is WorkspaceExportItem.Failed -> "${item.path}: ${item.cause.userVisibleDiagnostic()}"
                        } } + exporting.cleanupDiagnostic?.let { "\n$it" }.orEmpty())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (exporting.running) vm.cancelExport() else vm.dismissExportResult() }) {
                    Text(stringResource(if (exporting.running) R.string.common_cancel else R.string.common_confirm))
                }
            },
        )
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                defaultUrl = vm.defaultRootfsUrl,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    if (installError != null) {
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = {
                Text(installError?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.workspace_detail_rootfs_install_failed))
            },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    previewImage?.let { preview ->
        ImagePreviewDialog(
            images = listOf(preview.image),
            onDismissRequest = {
                previewImage = null
            },
            deleteAction = ImagePreviewDeleteAction(
                confirmationText = {
                    resources.getString(R.string.workspace_detail_will_delete, preview.entry.path)
                },
                delete = {
                    if (vm.delete(preview.entry, preview.area)) {
                        ImagePreviewDeleteResult.Deleted
                    } else {
                        ImagePreviewDeleteResult.Failed(imageDeleteFailed)
                    }
                },
            ),
        )
    }

    deleteTarget?.let { entry ->
        ConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                scope.launch {
                    vm.delete(entry)
                    deleteTarget = null
                }
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

private data class WorkspaceImagePreview(
    val entry: WorkspaceFileEntry,
    val area: WorkspaceStorageArea,
    val image: net.weero.measix.pilot.service.ImageSource,
)

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceUiModel?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_workspace_info),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_name), workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    WorkspaceInfoRow(stringResource(R.string.workspace_detail_shell_status), workspace?.shellStatus?.toShellStatusLabel() ?: "-")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_detail_enable_shell_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(HugeIcons.Bash, contentDescription = null)
                        Text(
                            text = installButtonText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    installProgress?.let { progress ->
                        RootfsProgress(progress)
                    }
                }
            }
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceUiModel?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides.orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
    "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
    "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
    "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
)

@Composable
private fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceUiModel,
    defaultUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(defaultUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    selected: Set<String>,
    onToggleSelection: (WorkspaceFileEntry) -> Unit,
    imageSource: (WorkspaceFileEntry, WorkspaceStorageArea) -> ImageSource,
    contentPadding: PaddingValues,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                val wide = maxWidth >= 600.dp && LocalDensity.current.fontScale < 1.3f
                if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkspaceAreaSelector(state.area, onSelectArea, Modifier.width(280.dp))
                    WorkspacePathBar(state.path, state.path.isNotBlank(), onGoUp, Modifier.weight(1f))
                } else Column {
                    WorkspaceAreaSelector(state.area, onSelectArea)
                    WorkspacePathBar(state.path, state.path.isNotBlank(), onGoUp)
                }
            }
        }

        state.error?.let { error ->
            item {
                ErrorCard(state.diagnostic ?: workspaceErrorMessage(error))
            }
        }

        if (!state.loading && state.entries.isEmpty() && state.error == null) {
            item {
                EmptyDirectoryState()
            }
        }

        items(state.entries, key = { "${state.area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                selected = entry.path in selected,
                selecting = selected.isNotEmpty(),
                onSelect = { onToggleSelection(entry) },
                image = if (!entry.isDirectory && entry.detectFileType() == WorkspaceFileType.IMAGE) {
                    remember(entry, state.area, imageSource) { imageSource(entry, state.area) }
                } else null,
                onOpen = { onOpen(entry) },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = { onShare(entry) },
            )
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
    modifier: Modifier = Modifier,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = stringResource(R.string.file_browser_parent_folder))
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    selected: Boolean,
    selecting: Boolean,
    onSelect: () -> Unit,
    image: ImageSource?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val modified = remember(entry.updatedAt, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(entry.updatedAt).atZone(ZoneId.systemDefault()))
    }
    net.weero.measix.pilot.ui.components.files.FileRow(
        name = entry.name,
        detail = if (entry.isDirectory) modified else "${entry.sizeBytes.fileSizeToString()} · $modified",
        compact = true,
        directory = entry.isDirectory, selected = selected, selecting = selecting,
        onOpen = onOpen, onSelect = onSelect.takeUnless { entry.isDirectory },
        thumbnail = image?.let { source -> {
            val placeholder = rememberVectorPainter(HugeIcons.File02)
            AsyncImage(model = source, contentDescription = null, placeholder = placeholder, error = placeholder,
                contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small))
        } },
    ) { dismiss ->
        if (!entry.isDirectory) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_export)) },
                leadingIcon = { Icon(HugeIcons.FileImport, null) }, onClick = { dismiss(); onExport() })
            DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) },
                leadingIcon = { Icon(HugeIcons.Share08, null) }, onClick = { dismiss(); onShare() })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { dismiss(); onDelete() })
    }
}

@Composable
private fun EmptyDirectoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun workspaceErrorMessage(error: WorkspaceOperation): String {
    val fallback = when (error) {
        WorkspaceOperation.LOAD_WORKSPACE -> R.string.workspace_detail_load_failed
        WorkspaceOperation.LOAD_FILES -> R.string.workspace_detail_load_files_failed
        WorkspaceOperation.IMPORT -> R.string.workspace_detail_import_failed
        WorkspaceOperation.EXPORT -> R.string.workspace_detail_export_failed
        WorkspaceOperation.DELETE -> R.string.workspace_detail_delete_failed
    }
    return stringResource(fallback)
}

@Composable
internal fun WorkspaceShellStatus.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN -> stringResource(R.string.workspace_detail_shell_broken)
}
