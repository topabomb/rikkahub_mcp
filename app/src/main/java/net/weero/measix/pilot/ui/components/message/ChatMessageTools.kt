package net.weero.measix.pilot.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.service.runtime.ToolInteractionDecision
import net.weero.measix.pilot.service.runtime.isBusy
import net.weero.measix.pilot.service.runtime.resolveToolLivePhase
import net.weero.measix.pilot.ui.components.message.tools.ArchivedToolOutputDetails
import net.weero.measix.pilot.ui.components.message.tools.ToolOutputProjection
import net.weero.measix.pilot.ui.components.message.tools.ToolUIContext
import net.weero.measix.pilot.ui.components.message.tools.ToolUIRegistry
import net.weero.measix.pilot.ui.components.message.tools.busy
import net.weero.measix.pilot.ui.components.richtext.ZoomableAsyncImage
import net.weero.measix.pilot.ui.components.ui.ChainOfThoughtScope
import net.weero.measix.pilot.ui.components.ui.DotLoading
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.modifier.shimmer
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import net.weero.measix.pilot.utils.JsonInstant

private const val ASK_USER_TOOL_NAME = "ask_user"

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    locator: ToolCallLocator,
    phase: ToolLivePhase? = null,
    onToolDecision: ((locator: ToolCallLocator, decision: ToolInteractionDecision) -> Unit)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架。这是 UI Renderer 特化；
    // 交互门控由 typed phase 决定，不在此处解释审批语义。
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(
            tool = tool,
            phase = resolveToolLivePhase(tool, phase),
            onAnswer = onToolDecision?.let { callback ->
                { answer ->
                    callback(locator, ToolInteractionDecision.Answer(answer))
                    true
                }
            },
        )
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    // Tool.output is already a provider replay projection. Local media is resolved by the
    // conversation query projection, never by a UI-side ArtifactStore read.
    val displayTool = tool
    val resolvedPhase = resolveToolLivePhase(displayTool, phase)
    val parsedArguments = remember(displayTool.input) {
        runCatching { JsonInstant.parseToJsonElement(displayTool.input.ifBlank { "{}" }) }
    }
    // Archived 投影只读 Runtime metadata，不解析工具名，也不自动读取归档正文。
    val outputProjection = remember(displayTool) {
        displayTool.runtimeState.archive?.let { archive ->
            ToolOutputProjection.Archived(
                characters = archive.characters,
                lines = archive.lines,
                terminalStatus = displayTool.resultStatus?.wireName ?: "completed",
            )
        } ?: ToolOutputProjection.Inline(displayTool.output)
    }
    val context = remember(displayTool, resolvedPhase, parsedArguments, outputProjection) {
        ToolUIContext(
            tool = displayTool,
            arguments = parsedArguments.getOrElse { JsonObject(emptyMap()) },
            argumentsValid = parsedArguments.isSuccess,
            content = if (outputProjection is ToolOutputProjection.Inline && displayTool.hasReplayResult) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        displayTool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            phase = resolvedPhase,
            outputProjection = outputProjection,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isAwaitingApproval = resolvedPhase == ToolLivePhase.AWAITING_APPROVAL
    val isDenied = displayTool.interactionState is ToolInteractionState.Denied
    val images = displayTool.output.filterIsInstance<UIMessagePart.Image>()
    val isArchived = outputProjection is ToolOutputProjection.Archived

    // 归档结果在 UI 边界直接终止专用输出解析，只展示统一 durable 摘要。
    val hasExtraContent = isArchived || renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (context.busy) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Column {
                Text(
                    text = renderer.title(context),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = resolvedPhase == ToolLivePhase.CALL_STREAMING),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (resolvedPhase != ToolLivePhase.COMPLETED) {
                    Text(
                        text = stringResource(toolLivePhaseString(resolvedPhase)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        extra = if (isAwaitingApproval && onToolDecision != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolDecision(locator, ToolInteractionDecision.Approve) },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        // A Tool part is inspectable from its first streamed delta. Gating the sheet on output
        // made complete calls and long-running remote executions appear inert until completion.
        onClick = { showResult = true },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (outputProjection is ToolOutputProjection.Archived) {
                        ArchivedToolOutputDetails(outputProjection)
                    } else {
                        renderer.Summary(context)
                    }
                    if (!isArchived && images.isNotEmpty()) {
                        // 会话级时序相册: 稳定 provider, 点击期由 ZoomableAsyncImage 求值
                        val conversationAlbum = LocalConversationImages.current
                        val attachmentPreview = LocalAttachmentPreview.current
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                if (isImagePartLoading(image.url)) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .shimmer(isLoading = true)
                                    )
                                } else {
                                    resolveAttachmentImageUrl(image, attachmentPreview)?.let { imageUrl ->
                                        ZoomableAsyncImage(
                                            model = imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .height(64.dp)
                                                .wrapContentWidth(),
                                            albumProvider = conversationAlbum,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (displayTool.interactionState as ToolInteractionState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolDecision != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolDecision(locator, ToolInteractionDecision.Deny(reason))
            }
        )
    }

    if (showResult) {
        val modalToaster = rememberToasterState()
        AdaptiveModal(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                CompositionLocalProvider(LocalToaster provides modalToaster) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val projection = outputProjection
                        if (projection is ToolOutputProjection.Archived) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                ArchivedToolOutputDetails(projection)
                            }
                        } else {
                            renderer.Preview(
                                context = context,
                                onDismissRequest = { showResult = false },
                            )
                        }
                        Toaster(
                            state = modalToaster,
                            darkTheme = LocalDarkMode.current,
                            richColors = true,
                            alignment = Alignment.TopCenter,
                            showCloseButton = true,
                        )
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    phase: ToolLivePhase,
    onAnswer: ((answer: String) -> Boolean)?,
) {
    // 控件只服从严格投影出的 typed phase；持久化 payload 仅在 ANSWERED 阶段读取。
    val isPending = phase == ToolLivePhase.AWAITING_INPUT
    val answeredState = (tool.interactionState as? ToolInteractionState.Answered)
        ?.takeIf { phase == ToolLivePhase.ANSWERED }
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember(tool.localCallId, tool.input) { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember(tool.localCallId, tool.input) { mutableStateMapOf<String, Set<String>>() }
    var submitted by remember(tool.localCallId, tool.input) { mutableStateOf(false) }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (phase.isBusy) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = phase == ToolLivePhase.CALL_STREAMING),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    // Single select: chips only, no text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                "multi" -> {
                                    // Multi select: chips only, multiple can be selected
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                FilterChip(
                                                    selected = selectedSet.contains(option),
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // Text (default): optional option chips + free text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                            }
                        } else if (answeredState != null) {
                            // Show the user's answer
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onAnswer != null && questions.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = {
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(multiAnswers[q.id]?.joinToString(", ") ?: ""))
                                            else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                        }
                                    }
                                })
                            }
                            submitted = onAnswer(answerPayload.toString())
                        },
                        enabled = !submitted && questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

private fun toolLivePhaseString(phase: ToolLivePhase): Int = when (phase) {
    ToolLivePhase.CALL_STREAMING -> R.string.chat_message_tool_phase_call_streaming
    ToolLivePhase.READY -> R.string.chat_message_tool_phase_ready
    ToolLivePhase.AWAITING_APPROVAL -> R.string.chat_message_tool_phase_awaiting_approval
    ToolLivePhase.AWAITING_INPUT -> R.string.chat_message_tool_phase_awaiting_input
    ToolLivePhase.EXECUTING -> R.string.chat_message_tool_phase_executing
    ToolLivePhase.COMPLETED -> R.string.chat_message_tool_phase_completed
    ToolLivePhase.FAILED -> R.string.chat_message_tool_phase_failed
    ToolLivePhase.CANCELLED -> R.string.chat_message_tool_phase_cancelled
    ToolLivePhase.INTERRUPTED -> R.string.chat_message_tool_phase_interrupted
    ToolLivePhase.DENIED -> R.string.chat_message_tool_phase_denied
    ToolLivePhase.ANSWERED -> R.string.chat_message_tool_phase_answered
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
