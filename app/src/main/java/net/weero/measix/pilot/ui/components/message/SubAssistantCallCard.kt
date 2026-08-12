package net.weero.measix.pilot.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.ui.components.ui.UIAvatar
import net.weero.measix.pilot.ui.components.ui.ChainOfThought
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.theme.extendColors
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 渲染子助手调用卡片。
 *
 * 视觉设计（设计文档 §10.1）：
 * - 卡片左侧有一条彩色侧条，标识子代理调用（区别于主代理消息）
 * - 头像始终显示：Target 已删除或解析失败时使用默认头像
 * - 头像带角标标识（子代理标记），与主代理消息头像视觉区分
 * - request 摘要一行预览
 * - 滚动 preview 窗口（固定高度）
 * - 运行中显示 phase 或 active tool display name
 * - 终态显示对应状态和原因
 * - 整张 Card 是单一详情点击目标
 *
 * 从 Tool.metadata 解析 SubAssistantCallMetadata。
 * 如果 metadata 缺失，回退到通用 Tool renderer。
 */
@Composable
fun SubAssistantCallCard(
    tool: UIMessagePart.Tool,
    masterConversationId: Uuid?,
    modifier: Modifier = Modifier,
    onAnswer: ((runId: String, interactionId: String, answer: String) -> Unit)? = null,
) {
    val json = JsonInstant
    val metadata = remember(tool) {
        tool.getSubAssistantCallMetadata(json)
    }

    if (metadata == null) {
        SubAssistantCallCardFallback(tool = tool, modifier = modifier)
        return
    }

    // 实时解析 Target 头像：失败或已删除时使用默认头像（文档 §10.1）
    val settings = LocalSettings.current
    val targetAvatar = remember(metadata.targetAssistantId, settings) {
        runCatching {
            settings.assistants.find { it.id.toString() == metadata.targetAssistantId }?.avatar
        }.getOrNull() ?: Avatar.Dummy
    }

    val navController = LocalNavController.current
    val isRunning = metadata.state == SubAssistantCallState.STARTING ||
        metadata.state == SubAssistantCallState.RUNNING

    // 整张 Card 是单一详情点击目标：只有存在有效 Child link 时才可点击
    val detailRoute = masterConversationId?.takeIf {
        !metadata.childConversationId.isNullOrBlank() && !metadata.childTaskNodeId.isNullOrBlank()
    }?.let { masterId ->
        Screen.SubAssistantDetail(
            masterConversationId = masterId.toString(),
            runId = metadata.runId,
        )
    }
    val canNavigate = detailRoute != null
    val navigateToDetail: () -> Unit = {
        detailRoute?.let(navController::navigate)
    }

    val statusText = when (metadata.state) {
        SubAssistantCallState.STARTING -> stringResource(R.string.sub_assistant_card_starting)
        SubAssistantCallState.RUNNING -> stringResource(R.string.sub_assistant_card_running)
        SubAssistantCallState.COMPLETED -> stringResource(R.string.sub_assistant_card_completed)
        SubAssistantCallState.FAILED -> stringResource(R.string.sub_assistant_card_failed)
        SubAssistantCallState.STOPPED -> stringResource(R.string.sub_assistant_card_stopped)
        SubAssistantCallState.UNAVAILABLE -> stringResource(R.string.sub_assistant_card_unavailable)
    }

    val statusColor = when (metadata.state) {
        SubAssistantCallState.STARTING, SubAssistantCallState.RUNNING ->
            MaterialTheme.colorScheme.primary
        SubAssistantCallState.COMPLETED ->
            MaterialTheme.extendColors.green8
        SubAssistantCallState.FAILED ->
            MaterialTheme.colorScheme.error
        SubAssistantCallState.STOPPED ->
            MaterialTheme.colorScheme.onSurfaceVariant
        SubAssistantCallState.UNAVAILABLE ->
            MaterialTheme.extendColors.orange8
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (canNavigate) Modifier.clickable(onClick = navigateToDetail) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp),
        // 左侧侧条：标识子代理调用，颜色随状态变化
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // 左侧彩色侧条：宽度 4dp，用状态色标识子代理
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(statusColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 头像 + 子代理角标 + 名称 + 状态
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 头像容器：带子代理角标，与主代理消息头像视觉区分
                    Box {
                        UIAvatar(
                            name = metadata.targetNameSnapshot,
                            value = targetAvatar,
                            modifier = Modifier.size(36.dp)
                        )
                        // 右下角子代理标识角标
                        SubAssistantBadge()
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = metadata.targetNameSnapshot,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            // 状态指示
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = statusColor
                                )
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )

                // request 预览（一行）
                val requestText = remember(tool.input) {
                    runCatching {
                        val inputJson = json.parseToJsonElement(tool.input).jsonObject
                        inputJson["request"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                }
                if (!requestText.isNullOrBlank()) {
                    Text(
                        text = requestText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 滚动 preview 窗口
                val previewText = metadata.preview
                if (!previewText.isNullOrBlank()) {
                    PreviewWindow(
                        text = previewText,
                        isRunning = isRunning,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 终态原因：统一处理 UNAVAILABLE/FAILED/STOPPED
                val reasonColor = when (metadata.state) {
                    SubAssistantCallState.UNAVAILABLE -> MaterialTheme.extendColors.orange8
                    SubAssistantCallState.FAILED -> MaterialTheme.colorScheme.error
                    SubAssistantCallState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> null
                }
                if (reasonColor != null && metadata.reason != null) {
                    Text(
                        text = localizeSubAssistantReason(metadata.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = reasonColor
                    )
                }

                // 运行中：phase 或 active tool
                if (isRunning) {
                    val phaseText = metadata.activeToolName?.let { toolName ->
                        stringResource(
                            R.string.sub_assistant_card_using_tool,
                            localizeActiveToolName(toolName),
                        )
                    } ?: metadata.phase?.let { localizePhase(it) }
                    if (phaseText != null) {
                        Text(
                            text = phaseText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val interaction = metadata.userInteraction
                if (isRunning && interaction?.toolName == "ask_user") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.sub_assistant_card_waiting_for_answer),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                            val askTool = UIMessagePart.Tool(
                                toolCallId = interaction.interactionId,
                                toolName = interaction.toolName,
                                input = interaction.input,
                                approvalState = ToolApprovalState.Pending,
                            )
                            ChainOfThought(steps = listOf(askTool)) { pendingTool ->
                                AskUserToolStep(
                                    tool = pendingTool,
                                    loading = false,
                                    onAnswer = onAnswer?.let { callback ->
                                        { answer ->
                                            callback(metadata.runId, interaction.interactionId, answer)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // 详情入口提示（整张 Card 已是点击目标，这里只作为视觉提示）
                if (canNavigate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.sub_assistant_card_view_detail) + " ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 子代理头像角标。
 * 在头像右下角显示一个小圆点，标识这是子代理而非主代理。
 * 颜色使用 tertiary，与主代理消息头像区分。
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SubAssistantBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(12.dp)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(50)
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "S",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.6f
                ),
                color = MaterialTheme.colorScheme.onTertiary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 固定高度的滚动 preview 窗口。
 * 运行中自动跟随底部；用户上滚后暂停自动跟随。
 */
@Composable
private fun PreviewWindow(
    text: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var userScrolledAway by remember { mutableStateOf(false) }
    var displayedText by remember { mutableStateOf(text) }

    val isAtBottom by remember {
        derivedStateOf {
            scrollState.value >= scrollState.maxValue - 4
        }
    }

    LaunchedEffect(scrollState.isScrollInProgress, isAtBottom) {
        if (scrollState.isScrollInProgress && !isAtBottom) {
            userScrolledAway = true
        }
        if (isAtBottom) {
            userScrolledAway = false
            displayedText = text
        }
    }

    // 运行中且未手动上滚时，更新 snapshot 并自动跟随底部；上滚时冻结当前文本。
    LaunchedEffect(text, isRunning) {
        if (isRunning && !userScrolledAway) {
            displayedText = text
            scrollState.animateScrollTo(scrollState.maxValue)
        } else if (!isRunning) {
            displayedText = text
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            Text(
                text = displayedText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(8.dp),
                overflow = TextOverflow.Visible
            )
        }

    }
}

/**
 * metadata 缺失时的回退渲染。
 */
@Composable
private fun SubAssistantCallCardFallback(
    tool: UIMessagePart.Tool,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "assistant_call",
                style = MaterialTheme.typography.titleSmall,
            )
            if (tool.isExecuted) {
                tool.output.firstOrNull { it is UIMessagePart.Text }?.let {
                    Text(
                        text = (it as UIMessagePart.Text).text.take(200),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

internal fun sanitizeToolNameForDisplay(name: String): String {
    val cleaned = name
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isBlank()) return "tool"
    val codePoints = cleaned.codePoints().limit(64).toArray()
    return buildString {
        codePoints.forEach(::appendCodePoint)
        if (cleaned.codePointCount(0, cleaned.length) > codePoints.size) append('…')
    }
}

@Composable
private fun localizeActiveToolName(name: String): String = when (name) {
    "get_time_info" -> stringResource(R.string.chat_message_tool_get_time)
    "text_to_speech" -> stringResource(R.string.assistant_page_local_tools_tts_title)
    "ask_user" -> stringResource(R.string.assistant_page_local_tools_ask_user_title)
    "get_screen_time" -> stringResource(R.string.chat_message_tool_screen_time)
    "clipboard_tool" -> stringResource(R.string.assistant_page_local_tools_clipboard_title)
    "calendar_query", "calendar_create" -> stringResource(R.string.assistant_page_local_tools_calendar_title)
    "eval_javascript" -> stringResource(R.string.assistant_page_local_tools_javascript_engine_title)
    else -> sanitizeToolNameForDisplay(name)
}

// ---- 本地化辅助 ----

@Composable
internal fun localizeSubAssistantReason(reason: String): String {
    return when (reason) {
        "tool_not_permitted" -> stringResource(R.string.sub_assistant_reason_tool_not_permitted)
        "context_required" -> stringResource(R.string.sub_assistant_reason_context_required)
        "assistant_not_found" -> stringResource(R.string.sub_assistant_reason_assistant_not_found)
        "target_not_allowed" -> stringResource(R.string.sub_assistant_reason_target_not_allowed)
        "target_description_missing" -> stringResource(R.string.sub_assistant_reason_target_description_missing)
        "target_model_unavailable" -> stringResource(R.string.sub_assistant_reason_target_model_unavailable)
        "caller_model_unavailable" -> stringResource(R.string.sub_assistant_reason_caller_model_unavailable)
        "target_busy" -> stringResource(R.string.sub_assistant_reason_target_busy)
        "provider_error" -> stringResource(R.string.sub_assistant_reason_provider_error)
        "runtime_error" -> stringResource(R.string.sub_assistant_reason_runtime_error)
        "step_limit_reached" -> stringResource(R.string.sub_assistant_reason_step_limit_reached)
        "approval_blocked" -> stringResource(R.string.sub_assistant_reason_approval_blocked)
        "user_cancelled" -> stringResource(R.string.sub_assistant_reason_user_cancelled)
        "app_restarted" -> stringResource(R.string.sub_assistant_reason_app_restarted)
        "target_removed" -> stringResource(R.string.sub_assistant_reason_target_removed)
        "target_disabled" -> stringResource(R.string.sub_assistant_reason_target_disabled)
        "target_access_revoked" -> stringResource(R.string.sub_assistant_reason_target_not_allowed)
        "child_missing" -> stringResource(R.string.sub_assistant_reason_child_missing)
        else -> reason
    }
}

@Composable
private fun localizePhase(phase: SubAssistantCallPhase): String {
    return when (phase) {
        SubAssistantCallPhase.PREPARING -> stringResource(R.string.sub_assistant_phase_preparing)
        SubAssistantCallPhase.MODEL_WAITING -> stringResource(R.string.sub_assistant_phase_model_waiting)
        SubAssistantCallPhase.REASONING_STREAMING -> stringResource(R.string.sub_assistant_phase_reasoning_streaming)
        SubAssistantCallPhase.ANSWER_STREAMING -> stringResource(R.string.sub_assistant_phase_answer_streaming)
        SubAssistantCallPhase.TOOL_EXECUTING -> stringResource(R.string.sub_assistant_phase_tool_executing)
        SubAssistantCallPhase.BETWEEN_STEPS -> stringResource(R.string.sub_assistant_phase_between_steps)
        SubAssistantCallPhase.AWAITING_USER -> stringResource(R.string.sub_assistant_phase_awaiting_user)
    }
}
