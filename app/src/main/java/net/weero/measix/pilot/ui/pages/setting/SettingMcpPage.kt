package net.weero.measix.pilot.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MessageBlocked
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Clock02
import kotlin.uuid.Uuid
import android.content.Intent
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import net.weero.measix.pilot.ui.components.ui.Switch
import net.weero.measix.pilot.ui.components.ui.SwitchSize
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.hugeicons.stroke.McpServer
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.mcp.McpParseResult
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.ai.mcp.McpTool
import net.weero.measix.pilot.data.ai.mcp.parseMcpServersFromJson
import net.weero.measix.pilot.data.ai.mcp.encodeForShare
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.utils.ImageUtils
import net.weero.measix.pilot.ui.components.ui.FormItem
import net.weero.measix.pilot.ui.components.ui.QRCode
import net.weero.measix.pilot.ui.components.ui.Tag
import net.weero.measix.pilot.ui.components.ui.TagType
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.hooks.EditState
import net.weero.measix.pilot.ui.hooks.EditStateContent
import net.weero.measix.pilot.ui.hooks.useEditState
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.ui.theme.extendColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val creationState = useEditState<McpServerConfig> {
        vm.updateSettings(
            settings.copy(
                mcpServers = mcpConfigs + it
            )
        )
    }
    val editState = useEditState<McpServerConfig> { newConfig ->
        vm.updateSettings(
            settings.copy(
                mcpServers = mcpConfigs.map {
                    if (it.id == newConfig.id) {
                        newConfig
                    } else {
                        it
                    }
                }
            ))
    }
    var showImportDialog by remember { mutableStateOf(false) }
    var showImportMethodDialog by remember { mutableStateOf(false) }
    var pendingConflicts by remember { mutableStateOf<List<Pair<McpServerConfig, McpServerConfig>>?>(null) }
    var shareConfig by remember { mutableStateOf<McpServerConfig?>(null) }
    val toaster = LocalToaster.current
    val context = LocalContext.current

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> handleMcpImport(result.content.rawValue ?: "", vm, toaster, context) { conflicts ->
                pendingConflicts = conflicts
            }
            is QRResult.QRError -> toaster.show(context.getString(R.string.setting_provider_page_scan_error, result), type = ToastType.Error)
            QRResult.QRMissingPermission -> toaster.show(context.getString(R.string.setting_provider_page_no_permission), type = ToastType.Error)
            QRResult.QRUserCanceled -> {}
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val qrContent = ImageUtils.decodeQRCodeFromUri(context, it)
            if (qrContent.isNullOrEmpty()) {
                toaster.show(context.getString(R.string.setting_provider_page_no_qr_found), type = ToastType.Error)
            } else {
                handleMcpImport(qrContent, vm, toaster, context) { conflicts ->
                    pendingConflicts = conflicts
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_mcp_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            showImportMethodDialog = true
                        }
                    ) {
                        Icon(HugeIcons.FileImport, null)
                    }
                    IconButton(
                        onClick = {
                            creationState.open(McpServerConfig.StreamableHTTPServer())
                        }
                    ) {
                        Icon(HugeIcons.Add01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status: Map<Uuid, McpStatus> by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val state = rememberPullToRefreshState()
        val loading = mcpConfigs.any { it.commonOptions.enable } &&
            status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting || it is McpStatus.Dormant }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(mcpConfigs, key = { it.id }) { mcpConfig ->
                    McpServerItem(
                        item = mcpConfig,
                        onEdit = {
                            editState.open(mcpConfig)
                        },
                        onDelete = {
                            vm.updateSettings(
                                settings.copy(
                                    mcpServers = mcpConfigs.filter { it.id != mcpConfig.id }
                                )
                            )
                        },
                        onShare = {
                            shareConfig = mcpConfig
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    McpServerConfigModal(creationState)
    McpServerConfigModal(editState)

    shareConfig?.let { config ->
        McpShareSheet(
            config = config,
            onDismiss = { shareConfig = null }
        )
    }

    if (showImportMethodDialog) {
        McpImportMethodDialog(
            onDismiss = { showImportMethodDialog = false },
            onScanQr = {
                showImportMethodDialog = false
                scanQrCodeLauncher.launch(null)
            },
            onPickImage = {
                showImportMethodDialog = false
                pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPasteJson = {
                showImportMethodDialog = false
                showImportDialog = true
            }
        )
    }

    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            onImport = { jsonText ->
                showImportDialog = false
                handleMcpImport(jsonText, vm, toaster, context) { conflicts ->
                    pendingConflicts = conflicts
                }
            }
        )
    }

    pendingConflicts?.let { conflicts ->
        McpConflictDialog(
            conflicts = conflicts,
            onConfirm = {
                vm.confirmOverwriteMcpServers(conflicts.map { it.first })
                toaster.show(context.getString(R.string.setting_mcp_page_conflict_overwrite), type = ToastType.Success)
                pendingConflicts = null
            },
            onDismiss = { pendingConflicts = null }
        )
    }
}

@Composable
private fun McpServerItem(
    item: McpServerConfig,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onShare: () -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val status by mcpManager.getStatus(item.id).collectAsStateWithLifecycle(McpStatus.Idle)
    val dismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = dismissBoxState,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch { dismissBoxState.reset() }
                    }
                ) {
                    Icon(HugeIcons.Cancel01, null)
                }
                FilledTonalIconButton(
                    onClick = {
                        onDelete()
                    }
                ) {
                    Icon(HugeIcons.Delete01, null)
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        modifier = modifier
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = CustomColors.listItemColors.containerColor
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    McpStatus.Idle -> Icon(HugeIcons.MessageBlocked, null)
                    McpStatus.Connecting -> CircularProgressIndicator(
                        modifier = Modifier.size(
                            24.dp
                        )
                    )

                    McpStatus.Connected -> Icon(HugeIcons.McpServer, null)
                    is McpStatus.Reconnecting -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    is McpStatus.Dormant -> Icon(HugeIcons.Clock02, null)
                    is McpStatus.Error -> Icon(HugeIcons.AlertCircle, null)
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = item.commonOptions.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val dotColor =
                            if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .drawWithContent {
                                    drawCircle(
                                        color = dotColor
                                    )
                                }
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Tag(type = TagType.SUCCESS) {
                            when (item) {
                                is McpServerConfig.SseTransportServer -> Text("SSE")
                                is McpServerConfig.StreamableHTTPServer -> Text("Streamable HTTP")
                            }
                        }
                    }
                    if (status is McpStatus.Error) {
                        Text(
                            text = (status as McpStatus.Error).message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (status is McpStatus.Reconnecting) {
                        Text(
                            text = stringResource(R.string.mcp_status_reconnecting, (status as McpStatus.Reconnecting).attempt, (status as McpStatus.Reconnecting).maxAttempts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (status is McpStatus.Dormant) {
                        Text(
                            text = stringResource(R.string.mcp_status_dormant, ((status as McpStatus.Dormant).nextRetryInMs / 1000).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(
                    onClick = onShare
                ) {
                    Icon(HugeIcons.Share01, null)
                }

                IconButton(
                    onClick = {
                        onEdit(item)
                    }
                ) {
                    Icon(HugeIcons.Settings03, null)
                }
            }
        }
    }
}

@Composable
private fun McpServerConfigModal(state: EditState<McpServerConfig>) {
    state.EditStateContent { config, updateValue ->
        val pagerState = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_basic_settings))
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_tools))
                        }
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> {
                            McpCommonOptionsConfigure(
                                config = config,
                                update = updateValue
                            )
                        }

                        1 -> {
                            McpToolsConfigure(
                                config = config,
                                update = updateValue,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            if (config.commonOptions.name.isNotBlank() && isValidMcpName(config.commonOptions.name)) {
                                state.confirm()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.setting_mcp_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun McpCommonOptionsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 启用/禁用开关
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_enable))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_enable_desc))
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.setting_mcp_page_enable))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = config.commonOptions.enable,
                    onCheckedChange = { enabled ->
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(enable = enabled)
                                )

                                is McpServerConfig.StreamableHTTPServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(enable = enabled)
                                )
                            }
                        )
                    }
                )
            }
        }

        HorizontalDivider()

        // 名称输入框
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_name))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_name_desc))
            }
        ) {
            val nameInvalid = !isValidMcpName(config.commonOptions.name)
            OutlinedTextField(
                value = config.commonOptions.name,
                onValueChange = { name ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )

                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )
                        }
                    )
                },
                label = { Text(stringResource(R.string.setting_mcp_page_name)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_mcp_page_name_placeholder)) },
                isError = nameInvalid,
                supportingText = if (nameInvalid) {
                    { Text(stringResource(R.string.setting_mcp_page_name_invalid)) }
                } else null
            )
        }

        HorizontalDivider()

        // 传输类型选择
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_transport_type))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_transport_type_desc))
            }
        ) {
            val transportTypes = listOf(
                "Streamable HTTP",
                "SSE"
            )
            val currentTypeIndex = when (config) {
                is McpServerConfig.StreamableHTTPServer -> 0
                is McpServerConfig.SseTransportServer -> 1
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                transportTypes.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, transportTypes.size),
                        onClick = {
                            if (index != currentTypeIndex) {
                                val newConfig = when (index) {
                                    0 -> McpServerConfig.StreamableHTTPServer(
                                        id = config.id,
                                        commonOptions = config.commonOptions,
                                        url = when (config) {
                                            is McpServerConfig.SseTransportServer -> config.url
                                            is McpServerConfig.StreamableHTTPServer -> config.url
                                        }
                                    )

                                    1 -> McpServerConfig.SseTransportServer(
                                        id = config.id,
                                        commonOptions = config.commonOptions,
                                        url = when (config) {
                                            is McpServerConfig.SseTransportServer -> config.url
                                            is McpServerConfig.StreamableHTTPServer -> config.url
                                        }
                                    )

                                    else -> config
                                }
                                update(newConfig)
                            }
                        },
                        selected = index == currentTypeIndex
                    ) {
                        Text(type)
                    }
                }
            }
        }

        HorizontalDivider()

        // 服务器地址配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_server_url))
            },
            description = {
                Text(
                    when (config) {
                        is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_desc)
                        is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_desc)
                    }
                )
            }
        ) {
            OutlinedTextField(
                value = when (config) {
                    is McpServerConfig.SseTransportServer -> config.url
                    is McpServerConfig.StreamableHTTPServer -> config.url
                },
                onValueChange = { url ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(url = url)
                            is McpServerConfig.StreamableHTTPServer -> config.copy(url = url)
                        }
                    )
                },
                label = { Text(stringResource(R.string.setting_mcp_page_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_placeholder)
                            is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_placeholder)
                        }
                    )
                }
            )
        }

        HorizontalDivider()

        // 请求头配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers_desc))
            }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                config.commonOptions.headers.forEachIndexed { index, header ->
                    var headerName by remember(header.first) { mutableStateOf(header.first) }
                    var headerValue by remember(header.second) { mutableStateOf(header.second) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = headerName,
                                onValueChange = {
                                    headerName = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] =
                                        it.trim() to updatedHeaders[index].second
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                label = { Text(stringResource(R.string.setting_mcp_page_header_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.setting_mcp_page_header_name_placeholder)) }
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].first to it.trim()
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                label = { Text(stringResource(R.string.setting_mcp_page_header_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.setting_mcp_page_header_value_placeholder)) }
                            )
                        }
                        IconButton(onClick = {
                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            update(
                                when (config) {
                                    is McpServerConfig.SseTransportServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )

                                    is McpServerConfig.StreamableHTTPServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )
                                }
                            )
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.setting_mcp_page_delete_header)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val updatedHeaders = config.commonOptions.headers.toMutableList()
                        val hasAuth = updatedHeaders.any { it.first.equals("Authorization", ignoreCase = true) }
                        val newHeader = if (!hasAuth) {
                            "Authorization" to "Bearer api_key"
                        } else {
                            "" to ""
                        }
                        updatedHeaders.add(newHeader)
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )

                                is McpServerConfig.StreamableHTTPServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        HugeIcons.Add01,
                        contentDescription = stringResource(R.string.setting_mcp_page_add_header)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.setting_mcp_page_add_header))
                }
            }
        }
    }
}

@Composable
private fun McpToolsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mcpManager.getClient(config.id) == null) {
            item {
                Text(stringResource(R.string.setting_mcp_page_tools_unavailable_message))
            }
        }
        items(config.commonOptions.tools) { tool ->
            McpToolCard(
                tool = tool,
                onEnableChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(enable = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                },
                onNeedsApprovalChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(needsApproval = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun McpToolCard(
    tool: McpTool,
    onEnableChange: (Boolean) -> Unit,
    onNeedsApprovalChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第一行：工具名字和3个按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 需要审批开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_mcp_page_needs_approval),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Switch(
                        checked = tool.needsApproval,
                        onCheckedChange = onNeedsApprovalChange,
                        size = SwitchSize.Small
                    )
                }
                // 启用开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "启用",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Switch(
                        checked = tool.enable,
                        onCheckedChange = onEnableChange,
                        size = SwitchSize.Small
                    )
                }
                // 展开/收起按钮
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // 展开后显示描述和参数
            if (expanded) {
                // 描述
                if (!tool.description.isNullOrBlank()) {
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                // 参数标签
                tool.inputSchema?.let { it as? InputSchema.Obj }?.let { schema ->
                    if (schema.properties.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            schema.properties.forEach { (key, _) ->
                                Tag(
                                    type = if (schema.required?.contains(key) == true) TagType.INFO else TagType.DEFAULT
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isValidMcpName(name: String): Boolean {
    return name.isEmpty() || name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
}

@Composable
private fun McpImportModal(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.setting_mcp_page_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setting_mcp_page_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonText,
                onValueChange = { jsonText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("{ \"mcpServers\": { ... } }") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onImport(jsonText.trim()) }
                ) {
                    Text(stringResource(R.string.setting_mcp_page_import_confirm))
                }
            }
        }
    }
}

/**
 * 处理 MCP JSON 导入：解析 → 导入合并 → 反馈结果 → 回调冲突
 */
private fun handleMcpImport(
    json: String,
    vm: SettingVM,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context,
    onConflicts: (List<Pair<McpServerConfig, McpServerConfig>>) -> Unit,
) {
    runCatching {
        val result = parseMcpServersFromJson(json)
        if (result.servers.isEmpty() && result.unsupportedNames.isEmpty()) {
            toaster.show(context.getString(R.string.setting_mcp_page_import_no_valid_config), type = ToastType.Error)
            return
        }

        val importResult = vm.importMcpServers(result.servers)

        if (result.unsupportedNames.isNotEmpty()) {
            toaster.show(context.getString(R.string.setting_mcp_page_import_unsupported, result.unsupportedNames.joinToString()), type = ToastType.Error)
        }

        if (importResult.added.isNotEmpty()) {
            toaster.show(context.getString(R.string.setting_mcp_page_import_added, importResult.added.size), type = ToastType.Success)
        }

        if (importResult.conflicts.isNotEmpty()) {
            onConflicts(importResult.conflicts)
        }
    }.onFailure { e ->
        toaster.show(
            context.getString(R.string.setting_mcp_page_import_parse_error, e.message ?: ""),
            type = ToastType.Error
        )
    }
}

@Composable
private fun McpImportMethodDialog(
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onPickImage: () -> Unit,
    onPasteJson: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_provider_page_import_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.setting_provider_page_import_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(HugeIcons.Camera01, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.setting_provider_page_scan_qr_code), style = MaterialTheme.typography.labelLarge)
                    }
                }
                OutlinedButton(
                    onClick = onPickImage,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(HugeIcons.Image02, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.setting_mcp_page_import_pick_image), style = MaterialTheme.typography.labelLarge)
                    }
                }
                OutlinedButton(
                    onClick = onPasteJson,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(HugeIcons.FileImport, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.setting_mcp_page_import_paste_json), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun McpConflictDialog(
    conflicts: List<Pair<McpServerConfig, McpServerConfig>>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_mcp_page_conflict_title)) },
        text = {
            Text(stringResource(R.string.setting_mcp_page_conflict_message, conflicts.size, conflicts.joinToString { it.first.commonOptions.name }))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.setting_mcp_page_conflict_overwrite)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun McpShareSheet(
    config: McpServerConfig,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shareJson = remember(config) { config.encodeForShare() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.setting_mcp_page_share_title),
                    style = MaterialTheme.typography.titleLarge
                )

                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.type = "text/plain"
                        intent.putExtra(Intent.EXTRA_TEXT, shareJson)
                        try {
                            context.startActivity(Intent.createChooser(intent, null))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                ) {
                    Icon(HugeIcons.Share01, null)
                }
            }

            QRCode(
                value = shareJson,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
        }
    }
}

