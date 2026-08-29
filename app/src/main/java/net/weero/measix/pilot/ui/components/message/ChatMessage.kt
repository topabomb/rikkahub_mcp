package net.weero.measix.pilot.ui.components.message

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.ui.mediaFailureMetadataOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.service.runtime.ToolCallPhase
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.service.terminalMessagePresentation
import net.weero.measix.pilot.ui.components.richtext.MarkdownBlock
import net.weero.measix.pilot.ui.components.richtext.ZoomableAsyncImage
import net.weero.measix.pilot.ui.components.richtext.buildMarkdownPreviewHtml
import net.weero.measix.pilot.ui.components.webview.WebViewContentCache
import net.weero.measix.pilot.ui.components.ui.ChainOfThought
import net.weero.measix.pilot.ui.components.ui.Favicon
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.modifier.shimmer
import net.weero.measix.pilot.ui.theme.asChatChrome
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.theme.LocalChatFontFamily
import net.weero.measix.pilot.ui.theme.rememberChatFontFamily
import net.weero.measix.pilot.ui.theme.extendColors
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.openUrl
import net.weero.measix.pilot.utils.urlDecode

import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatMessage(
    node: MessageNode,
    masterConversationId: Uuid? = null,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onToolApproval: ((locator: ToolCallLocator, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((locator: ToolCallLocator, answer: String) -> Unit)? = null,
    onSubAssistantAnswer: ((runId: String, interactionId: String, answer: String) -> Boolean)? = null,
    toolCallPhases: Map<ToolCallLocator, ToolCallPhase> = emptyMap(),
    onShowTerminalError: ((UIMessage) -> Unit)? = null,
    readOnly: Boolean = false,
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val mediaFailureText = stringResource(R.string.chat_message_media_persistence_failed)
    val renderableParts = remember(message.parts, mediaFailureText) {
        message.parts.withMediaFailurePlaceholders(mediaFailureText)
    }
    if (shouldHideEmptyCancelledMessage(message, renderableParts)) {
        return
    }
    val hasVisibleMessage = !renderableParts.isEmptyUIMessage() || message.terminalStatus != null
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (hasVisibleMessage) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                masterConversationId = masterConversationId,
                assistant = assistant,
                messageId = message.id,
                role = message.role,
                parts = renderableParts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                onToolApproval = if (readOnly) null else onToolApproval,
                onToolAnswer = if (readOnly) null else onToolAnswer,
                onSubAssistantAnswer = if (readOnly) null else onSubAssistantAnswer,
                toolCallPhases = toolCallPhases,
                onUserMessageClick = if (!readOnly && message.role == MessageRole.USER) onEdit else null,
            )
        }

        message.terminalStatus?.let { terminalStatus ->
            MessageTerminalStatusNotice(
                message = message,
                status = terminalStatus,
                onShowTerminalError = onShowTerminalError,
            )
        }

        val showActions = if (readOnly) {
            false
        } else if (lastMessage) {
            !loading
        } else {
            hasVisibleMessage
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    }
                )
            }
        }

        EditedFilesList(
            parts = message.parts,
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

internal fun List<UIMessagePart>.withMediaFailurePlaceholders(
    placeholder: String,
): List<UIMessagePart> = map { part ->
    when (part) {
        is UIMessagePart.Tool -> part.copy(
            output = part.output.withMediaFailurePlaceholders(placeholder),
        )
        else -> if (part.mediaFailureMetadataOrNull() != null) UIMessagePart.Text(placeholder) else part
    }
}

internal fun shouldHideEmptyCancelledMessage(
    message: UIMessage,
    renderableParts: List<UIMessagePart> = message.parts,
): Boolean = message.terminalStatus == MessageTerminalStatus.CANCELLED &&
    renderableParts.isEmptyUIMessage()

internal fun terminalStatusTextResource(status: MessageTerminalStatus, reason: String? = null): Int =
    terminalMessagePresentation(status, reason).statusResource

@Composable
private fun MessageTerminalStatusNotice(
    message: UIMessage,
    status: MessageTerminalStatus,
    onShowTerminalError: ((UIMessage) -> Unit)?,
) {
    val canShowDetail = status == MessageTerminalStatus.FAILED || status == MessageTerminalStatus.INCOMPLETE
    val containerColor = when (status) {
        MessageTerminalStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        MessageTerminalStatus.INCOMPLETE -> MaterialTheme.colorScheme.tertiaryContainer
        MessageTerminalStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
        MessageTerminalStatus.INTERRUPTED -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (status) {
        MessageTerminalStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        MessageTerminalStatus.INCOMPLETE -> MaterialTheme.colorScheme.onTertiaryContainer
        MessageTerminalStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageTerminalStatus.INTERRUPTED -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.clickable(
            enabled = canShowDetail && onShowTerminalError != null,
            onClick = { onShowTerminalError?.invoke(message) },
        ),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
    ) {
        Text(
            text = stringResource(terminalStatusTextResource(status, message.terminalReason)),
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    masterConversationId: Uuid?,
    assistant: Assistant?,
    messageId: kotlin.uuid.Uuid,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((locator: ToolCallLocator, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((locator: ToolCallLocator, answer: String) -> Unit)? = null,
    onSubAssistantAnswer: ((runId: String, interactionId: String, answer: String) -> Boolean)? = null,
    toolCallPhases: Map<ToolCallLocator, ToolCallPhase>,
    onUserMessageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val attachmentPreview = LocalAttachmentPreview.current

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.hasReplayResult) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    // 会话级时序相册: 稳定 provider, 点击期由 ZoomableAsyncImage 求值
    val conversationAlbum = LocalConversationImages.current
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        keepVisibleWhenCollapsed = { step ->
                            step.shouldStayVisibleWhenCollapsed()
                        },
                        cardColors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.asChatChrome(
                                settings.displaySetting.bubbleOpacity,
                            ),
                        ),
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(messageId, step.toolOrdinal) {
                                    val locator = ToolCallLocator(messageId, step.toolOrdinal)
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        locator = locator,
                                        phase = toolCallPhases[locator],
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.SubAssistantCallBlock -> key(messageId, block.toolOrdinal) {
                val locator = ToolCallLocator(messageId, block.toolOrdinal)
                val phase = toolCallPhases[locator]
                if (
                    block.tool.getSubAssistantCallMetadata(JsonInstant) == null &&
                    phase?.isPreExecutionOrRunning == true
                ) {
                    ChainOfThought(steps = listOf(block.tool)) { streamedTool ->
                        ChatMessageToolStep(
                            tool = streamedTool,
                            locator = locator,
                            phase = phase,
                            onToolApproval = onToolApproval,
                        )
                    }
                } else {
                    SubAssistantCallCard(
                        tool = block.tool,
                        masterConversationId = masterConversationId,
                        onAnswer = onSubAssistantAnswer,
                        modifier = Modifier.animateContentSize(),
                    )
                }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val textContent = @Composable {
                            if (role == MessageRole.USER) {
                                Surface(
                                    modifier = Modifier.animateContentSize(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.asChatChrome(
                                        settings.displaySetting.bubbleOpacity,
                                    ),
                                    onClick = { onUserMessageClick?.invoke() },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        MarkdownBlock(
                                            content = part.text.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation
                                        )
                                    }
                                }
                            } else {
                                if (settings.displaySetting.showAssistantBubble) {
                                    Surface(
                                        modifier = Modifier.animateContentSize(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh.asChatChrome(
                                            settings.displaySetting.bubbleOpacity,
                                        ),
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = part.text.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation,
                                            )
                                        }
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = part.text.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        modifier = Modifier
                                            .animateContentSize()
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val file = resolveManagedMediaFile(part, attachmentPreview) ?: return@Surface
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val file = resolveManagedMediaFile(part, attachmentPreview) ?: return@Surface
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        if (isImagePartLoading(part.url)) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            val imageUrl = resolveRenderableImageUrl(part, attachmentPreview)
                            if (imageUrl == null) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            } else {
                                ZoomableAsyncImage(
                                    model = imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.medium)
                                        .height(72.dp),
                                    albumProvider = conversationAlbum,
                                )
                            }
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val file = resolveManagedMediaFile(part, attachmentPreview) ?: return@Surface
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }
}

/** UI media must use the query-projected, ArtifactStore-validated local URL. */
private fun resolveRenderableImageUrl(
    part: UIMessagePart.Image,
    attachmentPreview: (String) -> String?,
): String? {
    return resolveAttachmentImageUrl(part, attachmentPreview)
}

private fun resolveManagedMediaFile(
    part: UIMessagePart,
    attachmentPreview: (String) -> String?,
): java.io.File? {
    val rawUrl = when (part) {
        is UIMessagePart.Document -> part.url
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Video -> part.url
        else -> return null
    }
    if (!rawUrl.startsWith("file:", ignoreCase = true)) return null
    val ref = AttachmentRefs.getStableRef(part) ?: return null
    return attachmentPreview(ref)?.let(AttachmentRefs::parseFileUrl)
}

private val ToolCallPhase.isPreExecutionOrRunning: Boolean
    get() = this == ToolCallPhase.CALL_STREAMING ||
        this == ToolCallPhase.READY ||
        this == ToolCallPhase.AWAITING_APPROVAL ||
        this == ToolCallPhase.EXECUTING
