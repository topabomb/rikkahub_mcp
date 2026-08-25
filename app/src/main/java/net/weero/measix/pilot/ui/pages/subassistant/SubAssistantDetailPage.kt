package net.weero.measix.pilot.ui.pages.subassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantErrorBody
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.ui.components.message.ChatMessage
import net.weero.measix.pilot.ui.components.message.LocalAttachmentPreview
import net.weero.measix.pilot.ui.components.message.LocalConversationImages
import net.weero.measix.pilot.ui.components.message.collectMessageImageUrls
import net.weero.measix.pilot.ui.components.ui.LocalImagePreviewActions
import net.weero.measix.pilot.ui.components.ui.LocalImagePreviewOverlay
import net.weero.measix.pilot.ui.components.ui.rememberImageBackgroundHost
import net.weero.measix.pilot.ui.components.message.localizeSubAssistantReason
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SubAssistantDetailPage(
    masterConversationId: String,
    runId: String,
) {
    val vm: SubAssistantDetailVM = koinViewModel(
        parameters = { parametersOf(masterConversationId, runId) }
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ready = uiState as? SubAssistantDetailUiState.Ready
    val metadata = ready?.link?.metadata
    val targetAssistant = ready?.link?.targetAssistantId?.let { targetId ->
        settings.assistants.find { it.id == targetId }
    }
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    if (metadata == null) {
                        Text(stringResource(R.string.sub_assistant_detail_title))
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UIAvatar(
                                name = metadata.targetNameSnapshot,
                                value = targetAssistant?.avatar ?: Avatar.Dummy,
                                modifier = Modifier.size(36.dp),
                                loading = metadata.state == SubAssistantCallState.STARTING ||
                                    metadata.state == SubAssistantCallState.RUNNING,
                                subAssistant = true,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = metadata.targetNameSnapshot,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = subAssistantStatusText(metadata.state),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = subAssistantStatusColor(metadata.state),
                                )
                            }
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (targetAssistant != null) {
                        TextButton(
                            onClick = {
                                navController.navigate(Screen.AssistantDetail(targetAssistant.id.toString()))
                            }
                        ) {
                            Text(stringResource(R.string.sub_assistant_detail_assistant_settings))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when (val state = uiState) {
            SubAssistantDetailUiState.Loading -> DetailLoading(Modifier.padding(innerPadding))
            SubAssistantDetailUiState.Unavailable -> DetailUnavailable(Modifier.padding(innerPadding))
            is SubAssistantDetailUiState.Ready -> DetailContent(
                state = state,
                targetAssistant = targetAssistant,
                attachmentPreviews = vm.attachmentPreviews(),
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun DetailContent(
    state: SubAssistantDetailUiState.Ready,
    targetAssistant: Assistant?,
    attachmentPreviews: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var requestExpanded by remember(state.link.metadata.runId) { mutableStateOf(false) }
    var requestOverflow by remember(state.link.metadata.runId) { mutableStateOf(false) }
    var followLatest by remember(state.link.metadata.runId) { mutableStateOf(true) }
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 8
        }
    }

    LaunchedEffect(isAtBottom, listState.isScrollInProgress) {
        if (isAtBottom) followLatest = true
        else if (listState.isScrollInProgress) followLatest = false
    }
    // Child.updateAt 在流式 updateCurrentMessages 时不会变。
    // 用时间线长度和最后一条可见文本长度作为跟随键，才能在最后一项长高时继续贴底。
    // 会话级时序相册: 稳定的点击期求值 lambda, 组合期零扫描
    val timelineState = rememberUpdatedState(state.timeline)
    val timelineAlbum = remember {
        {
            timelineState.value.flatMap { node ->
                collectMessageImageUrls(node.currentMessage.parts)
            }
        }
    }
    val backgroundHost = rememberImageBackgroundHost(LocalSettings.current, targetAssistant?.id)
    val previewActions = remember(backgroundHost.action, targetAssistant?.id) {
        if (targetAssistant?.id == null) emptyList() else listOf(backgroundHost.action)
    }
    val lastTimelineMessage = state.timeline.lastOrNull()?.let { node ->
        node.messages.getOrNull(node.selectIndex)
    }
    val lastOutputChars = lastTimelineMessage?.parts.orEmpty().sumOf { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.length
            is UIMessagePart.Reasoning -> part.reasoning.length
            else -> 0
        }
    }
    LaunchedEffect(followLatest, state.timeline.size, lastTimelineMessage?.id, lastOutputChars) {
        if (!followLatest) return@LaunchedEffect
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            listState.requestScrollToItem(count - 1)
        } else {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            listState.requestScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 相册 Provider 提升到列表外: 全部 item 共享同一稳定 lambda, 避免逐项 provider 节点
        CompositionLocalProvider(
            LocalConversationImages provides timelineAlbum,
            LocalAttachmentPreview provides remember(attachmentPreviews) {
                { ref: String -> attachmentPreviews[ref] }
            },
            LocalImagePreviewActions provides previewActions,
            LocalImagePreviewOverlay provides if (targetAssistant?.id == null) null else backgroundHost.overlay,
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "request") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sub_assistant_detail_request),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.link.request,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (requestExpanded) Int.MAX_VALUE else 6,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { requestOverflow = it.hasVisualOverflow },
                        )
                        if (requestOverflow || requestExpanded) {
                            TextButton(onClick = { requestExpanded = !requestExpanded }) {
                                Text(
                                    stringResource(
                                        if (requestExpanded) R.string.sub_assistant_detail_show_less
                                        else R.string.sub_assistant_detail_show_more
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (targetAssistant == null) {
                item(key = "target_deleted") {
                    Text(
                        text = stringResource(R.string.sub_assistant_detail_assistant_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "execution_header") {
                Text(
                    text = stringResource(R.string.sub_assistant_detail_execution),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.timeline.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.sub_assistant_detail_no_output),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                items(state.timeline, key = { it.id }) { node ->
                    ChatMessage(
                        node = node,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        loading = false,
                        model = null,
                        assistant = targetAssistant,
                        onFork = {},
                        onRegenerate = {},
                        onEdit = {},
                        onShare = {},
                        onDelete = {},
                        onUpdate = {},
                        readOnly = true,
                    )
                }
            }

            val metadata = state.link.metadata
            if (metadata.state == SubAssistantCallState.FAILED ||
                metadata.state == SubAssistantCallState.STOPPED ||
                metadata.state == SubAssistantCallState.UNAVAILABLE
            ) {
                item(key = "terminal") {
                    val errorBody = resolveSubAssistantErrorBody(
                        reason = metadata.reason,
                        detail = state.link.failureDetail,
                        localizedContentBlocked = stringResource(
                            R.string.sub_assistant_error_content_blocked_body,
                        ),
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = subAssistantStatusColor(metadata.state).copy(alpha = 0.12f),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = subAssistantStatusText(metadata.state),
                                style = MaterialTheme.typography.labelLarge,
                                color = subAssistantStatusColor(metadata.state),
                                fontWeight = FontWeight.SemiBold,
                            )
                            metadata.reason?.let { reason ->
                                Text(
                                    text = localizeSubAssistantReason(reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!errorBody.isNullOrBlank()) {
                                Text(
                                    text = errorBody,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            item(key = TimelineBottomKey) {
                Spacer(Modifier.height(8.dp))
            }
        }
        }

        if (!followLatest && state.timeline.isNotEmpty()) {
            TextButton(
                onClick = {
                    followLatest = true
                    val count = listState.layoutInfo.totalItemsCount
                    if (count > 0) listState.requestScrollToItem(count - 1)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                Text(stringResource(R.string.sub_assistant_card_scroll_to_latest))
            }
        }
    }
}

private const val TimelineBottomKey = "timeline_bottom"

@Composable
private fun DetailLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.sub_assistant_detail_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun DetailUnavailable(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.sub_assistant_detail_unavailable),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.sub_assistant_detail_unavailable_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun subAssistantStatusText(state: SubAssistantCallState): String = when (state) {
    SubAssistantCallState.STARTING -> stringResource(R.string.sub_assistant_card_starting)
    SubAssistantCallState.RUNNING -> stringResource(R.string.sub_assistant_card_running)
    SubAssistantCallState.COMPLETED -> stringResource(R.string.sub_assistant_card_completed)
    SubAssistantCallState.FAILED -> stringResource(R.string.sub_assistant_card_failed)
    SubAssistantCallState.STOPPED -> stringResource(R.string.sub_assistant_card_stopped)
    SubAssistantCallState.UNAVAILABLE -> stringResource(R.string.sub_assistant_card_unavailable)
}

@Composable
private fun subAssistantStatusColor(state: SubAssistantCallState) = when (state) {
    SubAssistantCallState.STARTING, SubAssistantCallState.RUNNING -> MaterialTheme.colorScheme.primary
    SubAssistantCallState.COMPLETED -> MaterialTheme.colorScheme.primary
    SubAssistantCallState.FAILED -> MaterialTheme.colorScheme.error
    SubAssistantCallState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    SubAssistantCallState.UNAVAILABLE -> MaterialTheme.colorScheme.tertiary
}
