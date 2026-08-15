package net.weero.measix.pilot.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.ui.adaptive.AdaptiveWidthClass
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.fallbackSubAssistantOutputText
import net.weero.measix.pilot.data.ai.subassistant.parseRuntimeErrorDetailFromToolOutput
import net.weero.measix.pilot.data.ai.subassistant.parseSubAssistantToolResultFields
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantErrorBody
import net.weero.measix.pilot.data.ai.subassistant.takeTailByCodePoints
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
 * 紧凑布局：
 * - 左侧状态色侧条；运行中头像正圆+圆边扫光；右下角小叠圆标识子助手
 * - 顶部右侧合并状态（phase/原因/终态），不再单独占底行
 * - preview 按屏高显示 2 或 3 行尾部文本，不内嵌滚动
 * - 整张 Card 是唯一详情点击目标
 */
@Composable
fun SubAssistantCallCard(
    tool: UIMessagePart.Tool,
    masterConversationId: Uuid?,
    modifier: Modifier = Modifier,
    onAnswer: ((runId: String, interactionId: String, answer: String) -> Boolean)? = null,
) {
    val json = JsonInstant
    val metadata = remember(tool.metadata) {
        tool.getSubAssistantCallMetadata(json)
    }

    if (metadata == null) {
        SubAssistantCallCardFallback(tool = tool, modifier = modifier)
        return
    }

    // 实时解析 Target 头像：失败或已删除时使用默认头像（文档 §10.1）
    val settings = LocalSettings.current
    val targetAvatar = remember(metadata.targetAssistantId, settings.assistants) {
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
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        // 用 Box+drawBehind 替代 Row+IntrinsicSize.Min+fillMaxHeight 的侧条布局。
        // IntrinsicSize.Min 会将高度约束到最小固有高度，当内容包含可变高度组件
        //（OutlinedTextField maxLines=3、ChainOfThought animateContentSize）时，
        // 实际渲染高度超过约束，底部内容（如 ask_user 提交按钮）被裁剪。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // 左侧彩色侧条：宽度 4dp，用状态色标识子代理
                    drawRect(
                        color = statusColor,
                        topLeft = Offset.Zero,
                        size = Size(width = 4.dp.toPx(), height = size.height),
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UIAvatar(
                        name = metadata.targetNameSnapshot,
                        value = targetAvatar,
                        modifier = Modifier.size(28.dp),
                        loading = isRunning,
                        subAssistant = true,
                    )

                    Text(
                        text = metadata.targetNameSnapshot,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = statusColor
                        )
                    }
                    val phaseText = if (isRunning) {
                        metadata.activeToolName?.let { toolName ->
                            stringResource(
                                R.string.sub_assistant_card_using_tool,
                                localizeActiveToolName(toolName),
                            )
                        } ?: metadata.phase?.let { localizePhase(it) }
                    } else {
                        null
                    }
                    val reasonText = if (!isRunning && metadata.reason != null) {
                        localizeSubAssistantReason(metadata.reason)
                    } else {
                        null
                    }
                    Text(
                        text = resolveCardStatusLabel(
                            isRunning = isRunning,
                            phaseText = phaseText,
                            reasonText = reasonText,
                            stateText = statusText,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

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

                val previewText = metadata.preview.takeUnless { it.isNullOrBlank() } ?: if (
                    shouldShowNonTextOutputPlaceholder(
                        state = metadata.state,
                        preview = metadata.preview,
                        hasNonTextOutput = metadata.hasNonTextOutput,
                    )
                ) {
                    stringResource(R.string.sub_assistant_card_non_text_output)
                } else {
                    null
                }
                val errorBody = if (
                    metadata.state == SubAssistantCallState.FAILED ||
                    metadata.state == SubAssistantCallState.UNAVAILABLE
                ) {
                    resolveSubAssistantErrorBody(
                        reason = metadata.reason,
                        detail = parseRuntimeErrorDetailFromToolOutput(tool, json),
                        localizedContentBlocked = stringResource(
                            R.string.sub_assistant_error_content_blocked_body,
                        ),
                    )
                } else {
                    null
                }
                if (!errorBody.isNullOrBlank()) {
                    Text(
                        text = errorBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!previewText.isNullOrBlank()) {
                    val layout = LocalAdaptiveLayoutInfo.current
                    val previewLines = subAssistantCardPreviewLines(
                        compactHeight = layout.useCompactChatInput,
                    )
                    Text(
                        text = clipPreviewForCard(
                            text = previewText,
                            maxLines = previewLines,
                            maxChars = subAssistantCardPreviewMaxChars(
                                compactHeight = layout.useCompactChatInput,
                                compactWidth = layout.widthClass == AdaptiveWidthClass.Compact,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val interaction = metadata.userInteraction
                if (isRunning && interaction?.toolName == "ask_user") {
                    // ask_user 交互区必须消费点击事件，防止冒泡到 Card 的 navigateToDetail。
                    // OutlinedTextField 不像 FilterChip 那样通过 Modifier.clickable 消费事件，
                    // 不加此消费层时点击文本输入框会触发详情导航。
                    val interactionSource = remember { MutableInteractionSource() }
                    Surface(
                        modifier = Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {},
                        ),
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
            }
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
                text = stringResource(R.string.sub_assistant_call_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (tool.isExecuted) {
                val fields = parseSubAssistantToolResultFields(tool, JsonInstant)
                val rawOutput = tool.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                val fallbackText = fallbackSubAssistantOutputText(
                    fields = fields,
                    localizedReason = fields.reason?.let { localizeSubAssistantReason(it) },
                    localizedContentBlocked = stringResource(
                        R.string.sub_assistant_error_content_blocked_body,
                    ),
                    rawOutput = rawOutput,
                )
                Text(
                    text = fallbackText
                        ?: stringResource(R.string.sub_assistant_reason_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

internal fun subAssistantCardPreviewLines(compactHeight: Boolean): Int =
    if (compactHeight) 2 else 3

internal fun shouldShowNonTextOutputPlaceholder(
    state: SubAssistantCallState,
    preview: String?,
    hasNonTextOutput: Boolean,
): Boolean = state == SubAssistantCallState.COMPLETED && preview.isNullOrBlank() && hasNonTextOutput

/** 窄屏每行约 36 字，宽屏约 72 字，再乘行数，避免长段落整段进排版。 */
internal fun subAssistantCardPreviewMaxChars(compactHeight: Boolean, compactWidth: Boolean): Int {
    val lines = subAssistantCardPreviewLines(compactHeight)
    val charsPerLine = if (compactWidth) 36 else 72
    return lines * charsPerLine
}

/** 只保留尾部若干行和有界字符，避免为 2000 code point 的 preview 做完整排版。 */
internal fun clipPreviewForCard(text: String, maxLines: Int, maxChars: Int = maxLines * 72): String {
    val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
    if (cleaned.isEmpty() || maxLines <= 0 || maxChars <= 0) return ""
    val lines = cleaned.split('\n')
    val lineClipped = if (lines.size <= maxLines) {
        cleaned
    } else {
        val tail = lines.takeLast(maxLines).toMutableList()
        tail[0] = "…" + tail[0].trimStart().removePrefix("…")
        tail.joinToString("\n")
    }
    val cpCount = lineClipped.codePointCount(0, lineClipped.length)
    if (cpCount <= maxChars) return lineClipped
    return "…" + takeTailByCodePoints(lineClipped, maxChars).trimStart().removePrefix("…")
}

internal fun resolveCardStatusLabel(
    isRunning: Boolean,
    phaseText: String?,
    reasonText: String?,
    stateText: String,
): String = when {
    isRunning -> phaseText?.takeIf { it.isNotBlank() } ?: stateText
    !reasonText.isNullOrBlank() -> reasonText
    else -> stateText
}

internal fun sanitizeToolNameForDisplay(name: String, fallback: String): String {
    val cleaned = name
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isBlank()) return fallback
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
    else -> sanitizeToolNameForDisplay(
        name = name,
        fallback = stringResource(R.string.sub_assistant_tool_unknown),
    )
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
        "content_blocked" -> stringResource(R.string.sub_assistant_reason_content_blocked)
        "runtime_error" -> stringResource(R.string.sub_assistant_reason_runtime_error)
        "step_limit_reached" -> stringResource(R.string.sub_assistant_reason_step_limit_reached)
        "interaction_limit_reached" -> stringResource(
            R.string.sub_assistant_reason_interaction_limit_reached
        )
        "approval_blocked" -> stringResource(R.string.sub_assistant_reason_approval_blocked)
        "user_cancelled" -> stringResource(R.string.sub_assistant_reason_user_cancelled)
        "app_restarted" -> stringResource(R.string.sub_assistant_reason_app_restarted)
        "target_removed" -> stringResource(R.string.sub_assistant_reason_target_removed)
        "target_disabled" -> stringResource(R.string.sub_assistant_reason_target_disabled)
        "target_access_revoked" -> stringResource(R.string.sub_assistant_reason_target_not_allowed)
        "child_missing" -> stringResource(R.string.sub_assistant_reason_child_missing)
        else -> stringResource(R.string.sub_assistant_reason_unknown)
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
