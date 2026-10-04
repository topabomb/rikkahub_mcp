package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.service.runtime.isBusy
import net.weero.measix.pilot.ui.components.message.LocalAttachmentPreview
import net.weero.measix.pilot.ui.components.message.AttachmentPreviewDiagnostic
import net.weero.measix.pilot.ui.components.message.resolveAttachmentPreview
import net.weero.measix.pilot.ui.components.message.LocalConversationImages
import net.weero.measix.pilot.ui.components.message.resolveAttachmentImageSource
import net.weero.measix.pilot.ui.components.message.isImagePartLoading
import net.weero.measix.pilot.ui.components.richtext.HighlightCodeBlock
import net.weero.measix.pilot.ui.components.richtext.ZoomableAsyncImage
import net.weero.measix.pilot.ui.components.ui.FormItem
import net.weero.measix.pilot.ui.modifier.shimmer
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.JsonInstantPretty
import net.weero.measix.pilot.utils.jsonPrimitiveOrNull

/**
 * 工具调用的渲染上下文, 预解析好工具入参与输出, 避免各渲染器重复解析
 */
data class ToolUIContext(
    val tool: UIMessagePart.Tool,
    /** 工具入参 ([UIMessagePart.Tool.input] 的 JSON 解析结果) */
    val arguments: JsonElement,
    /** False while a provider is still streaming an incomplete JSON argument document. */
    val argumentsValid: Boolean,
    /** 输出文本部件解析出的 JSON；Provider 回放结果尚未形成时为 null。 */
    val content: JsonElement?,
    /** Call assembly, approval and execution are deliberately distinct. */
    val phase: ToolLivePhase,
    /** Inline 保持现有 renderer；Archived 只展示 durable 裁剪摘要。 */
    val outputProjection: ToolOutputProjection = ToolOutputProjection.Inline(tool.output),
    val conversationId: kotlin.uuid.Uuid? = null,
    val locator: me.rerere.ai.core.ToolCallLocator? = null,
)

/** Standalone message previews have no authority to mutate a conversation's tool result. */
val LocalToolConversationId = androidx.compose.runtime.staticCompositionLocalOf<kotlin.uuid.Uuid?> { null }

/** Tool Result 的展示投影：完整内联，或已归档为 durable 摘要。 */
sealed interface ToolOutputProjection {
    data class Inline(val parts: List<UIMessagePart>) : ToolOutputProjection
    data class Archived(
        val characters: Long,
        val lines: Int,
        val terminalStatus: String,
    ) : ToolOutputProjection
}


internal val ToolUIContext.busy: Boolean
    get() = phase.isBusy

/**
 * 单个工具的 UI 渲染器
 *
 * 在 [ToolUIRegistry] 注册后, 聊天消息中对应的工具调用将使用该渲染器展示;
 * 未注册的工具 fallback 到接口的默认实现 (通用标题/图标 + JSON 详情)
 */
interface ToolUIRenderer {
    /** 渲染器对应的工具名 */
    val toolName: String

    /** 用户可见的工具身份；协议名仍由 [toolName] 和 Tool part 持有。 */
    @Composable
    fun displayName(context: ToolUIContext): String = context.tool.displayToolName

    /** 折叠步骤的图标 */
    fun icon(context: ToolUIContext): ImageVector = HugeIcons.Tools

    /** 折叠步骤的标题 */
    @Composable
    fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_call_generic, localizedToolName(displayName(context)))

    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }

    /** 点击步骤后的详情, 渲染在 BottomSheet 内 */
    @Composable
    fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(
            context = context,
            toolDisplayName = displayName(context),
        )
    }
}

/** 未注册工具使用的默认渲染器, 全部行为来自 [ToolUIRenderer] 的默认实现 */
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""
}

/**
 * 工具 UI 渲染器注册表, 为新工具定制渲染时在 [renderers] 中注册即可
 */
object ToolUIRegistry {
    private val renderers: Map<String, ToolUIRenderer> = listOf(
        MemoryToolUI,
        SearchWebToolUI,
        ScrapeWebToolUI,
        GetTimeInfoToolUI,
        ClipboardToolUI,
        TextToSpeechToolUI,
        UseSkillToolUI,
        RecentChatsToolUI,
        ConversationSearchToolUI,
        GetScreenTimeToolUI,
        CalendarQueryToolUI,
        CalendarCreateToolUI,
        EditFileToolUI,
        ReadFileToolUI,
        WriteFileToolUI,
        ShellToolUI,
        ImageGenerationToolUI,
        AttachmentInspectionToolUI,
        ReadToolOutputUI,
        GrepToolOutputUI,
    ).associateBy { it.toolName }

    /** 查找工具对应的渲染器, 未注册时返回默认渲染器 */
    fun resolve(toolName: String): ToolUIRenderer = renderers[toolName] ?: DefaultToolUIRenderer
}

@Composable
internal fun localizedToolName(name: String): String = builtinToolNameResource(name)?.let { stringResource(it) } ?: name

internal fun builtinToolNameResource(name: String): Int? = when (name) {
    "memory_tool" -> R.string.assistant_page_manage_memory_title
    "search_web" -> R.string.built_in_search_title
    "scrape_web" -> R.string.chat_message_tool_scrape_web
    "get_time_info" -> R.string.chat_message_tool_get_time
    "clipboard_tool" -> R.string.assistant_page_local_tools_clipboard_title
    "text_to_speech" -> R.string.assistant_page_local_tools_tts_title
    "use_skill" -> R.string.skills_page_title
    "recent_chats" -> R.string.chat_message_tool_recent_chats
    "conversation_search" -> R.string.search_page_title
    "get_screen_time" -> R.string.chat_message_tool_screen_time
    "calendar_query" -> R.string.chat_message_tool_calendar_query
    "calendar_create" -> R.string.assistant_page_local_tools_calendar_title
    "workspace_read_file" -> R.string.tool_ui_read_file_default
    "workspace_write_file" -> R.string.tool_ui_write_file_default
    "workspace_edit_file" -> R.string.tool_ui_edit_file_default
    "workspace_shell" -> R.string.workspace_detail_tool_shell
    "generate_image" -> R.string.assistant_page_local_tools_text_to_image_title
    "inspect_attachments" -> R.string.chat_message_tool_inspect_attachments_title
    "ask_user" -> R.string.assistant_page_local_tools_ask_user_title
    "eval_javascript" -> R.string.assistant_page_local_tools_javascript_engine_title
    "assistant_manage" -> R.string.assistant_page_local_tools_assistant_management_title
    "assistant_inspect" -> R.string.chat_message_tool_inspect_assistant
    "assistant_call" -> R.string.assistant_page_local_tools_assistant_delegation_title
    "read_tool_output" -> R.string.chat_message_tool_read_trimmed_result
    "grep_tool_output" -> R.string.chat_message_tool_search_trimmed_result
    else -> null
}

/** Business identity comes only from committed client-safe metadata, including after output archiving. */
internal val UIMessagePart.Tool.gatewayAction: JsonObject?
    get() = (metadata?.get("com.measix/resolvedTool") as? JsonObject)?.let { action ->
        JsonObject(action.filterKeys { it in setOf("gatewayToolId", "name", "status", "requestId") })
    }

internal val UIMessagePart.Tool.displayToolName: String
    get() = gatewayAction.getStringContent("name")?.takeIf { it.isNotBlank() } ?: toolName

internal fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

@Composable
internal fun GatewayActionDetails(tool: UIMessagePart.Tool) {
    tool.gatewayAction?.let { action ->
        FormItem(label = { Text(tool.displayToolName) }) {
            HighlightCodeBlock(code = JsonInstantPretty.encodeToString(action), language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp))
        }
    }
}

/**
 * 默认工具详情: 入参与输出的 JSON 高亮展示
 *
 * @param headerActions 标题栏右侧的附加操作区
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
    toolDisplayName: String = context.tool.displayToolName,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            headerActions?.invoke()
        }
        ToolCallJsonDetails(
            context = context,
            includeImages = true,
            toolDisplayName = toolDisplayName,
        )
    }
}

/**
 * 工具调用的入参 / 结果 JSON, 与默认详情及文生图「调用详情」共用同一套样式。
 */
@Composable
fun ToolCallJsonDetails(
    context: ToolUIContext,
    includeImages: Boolean = true,
    toolDisplayName: String = context.tool.displayToolName,
) {
    GatewayActionDetails(context.tool)
    FormItem(
        label = {
            Text(stringResource(R.string.chat_message_tool_call_label, toolDisplayName))
        }
    ) {
        HighlightCodeBlock(
            code = if (context.argumentsValid) {
                JsonInstantPretty.encodeToString(context.arguments)
            } else {
                context.tool.input
            },
            language = "json",
            style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
        )
    }
    val projection = context.outputProjection
    if (projection is ToolOutputProjection.Archived) {
        ArchivedToolOutputDetails(projection)
        return
    }
    val resultParts = context.tool.output.filter { part ->
        part is UIMessagePart.Text || (includeImages && part is UIMessagePart.Image)
    }
    if (resultParts.isNotEmpty()) {
        val conversationAlbum = LocalConversationImages.current
        val attachmentPreview = LocalAttachmentPreview.current
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_result))
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                resultParts.fastForEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> HighlightCodeBlock(
                            code = runCatching {
                                JsonInstantPretty.encodeToString(
                                    JsonInstant.parseToJsonElement(part.text)
                                )
                            }.getOrElse { part.text },
                            language = "json",
                            style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                        )

                        is UIMessagePart.Image -> {
                            if (isImagePartLoading(part.url)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .shimmer(isLoading = true)
                                )
                            } else {
                                AttachmentPreviewDiagnostic(resolveAttachmentPreview(part, attachmentPreview))
                                resolveAttachmentImageSource(part, attachmentPreview)?.let { imageUrl ->
                                    ZoomableAsyncImage(
                                        model = imageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxWidth(),
                                        albumProvider = conversationAlbum,
                                    )
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }
}

/** 归档输出详情只呈现 durable 摘要；精确正文仅由模型通过 scoped tools 按需回查。 */
@Composable
fun ArchivedToolOutputDetails(projection: ToolOutputProjection.Archived) {
    Text(
        text = stringResource(
            R.string.chat_message_tool_output_archived_summary,
            projection.lines,
            projection.characters,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
