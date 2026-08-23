package net.weero.measix.pilot.ui.components.message

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
        val toolOrdinal: Int,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class SubAssistantCallBlock(val tool: UIMessagePart.Tool, val toolOrdinal: Int) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

private const val TOOL_ASSISTANT_CALL = "assistant_call"

/**
 * 将 parts 分组成 ThinkingBlock、SubAssistantCallBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool（非 assistant_call）会被分组到一个 ThinkingBlock 中
 * assistant_call 工具被拆为独立的 SubAssistantCallBlock，由 SubAssistantCallCard 渲染
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()
    var toolOrdinal = 0

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                val currentToolOrdinal = toolOrdinal++
                if (part.toolName == TOOL_ASSISTANT_CALL) {
                    // assistant_call 从 COT 中拆出，独立渲染
                    flushThinkingSteps()
                    result.add(MessagePartBlock.SubAssistantCallBlock(part, currentToolOrdinal))
                } else {
                    currentThinkingSteps.add(ThinkingStep.ToolStep(part, currentToolOrdinal))
                }
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/** Pending tools stay interactive; [GENERATE_IMAGE_TOOL_NAME] stays on the collapsed timeline. */
fun ThinkingStep.shouldStayVisibleWhenCollapsed(): Boolean {
    val tool = (this as? ThinkingStep.ToolStep)?.tool ?: return false
    return tool.isPending || tool.toolName == GENERATE_IMAGE_TOOL_NAME
}

/**
 * 流式生成中的图片占位 url: base64 头后面没有任何数据
 */
private val LOADING_IMAGE_URL_REGEX = Regex("^data:image/[^;]*;base64,\\s*$")

internal fun isImagePartLoading(url: String): Boolean =
    url.isBlank() || url.matches(LOADING_IMAGE_URL_REGEX)

/**
 * 收集一条消息内的全部「明确图片」：顶层 Image part 与 Tool.output 中的 Image，
 * 按 part 位置顺序（过滤流式 loading 占位）。会话级时序相册按消息顺序展平本函数结果
 */
internal fun collectMessageImageUrls(parts: List<UIMessagePart>): List<String> = buildList {
    parts.fastForEachIndexed { _, part ->
        when (part) {
            is UIMessagePart.Image -> if (!isImagePartLoading(part.url)) add(part.url)

            is UIMessagePart.Tool -> part.output.forEach { output ->
                if (output is UIMessagePart.Image && !isImagePartLoading(output.url)) {
                    add(output.url)
                }
            }

            else -> {}
        }
    }
}

/**
 * 会话级时序相册：会话宿主（ChatList 等）提供的点击期求值函数，返回按消息顺序展平的
 * 明确图片 url 列表。宿主侧持有稳定 lambda 实例（组合期零重算、读者零失效），
 * 点击图片时才求值展开。默认空相册（共享单例，保证无宿主场景的参数稳定性），
 * 消费点回退单图模式
 */
private val EmptyConversationAlbum: () -> List<String> = { emptyList() }

val LocalConversationImages = compositionLocalOf<() -> List<String>> {
    EmptyConversationAlbum
}

/**
 * 附件缩略图只读解析：会话宿主按 stable `attachment:<uuid>`
 * 返回本地 `file:` url；不做远程下载、不触发识别，解析不到返回 null 由消费点显示占位。
 * UI 可显示缩略图不代表当前模型收到图片像素（presentation 与 projection 解耦）。
 */
private val NoAttachmentPreview: (String) -> String? = { null }

val LocalAttachmentPreview = compositionLocalOf<(String) -> String?> {
    NoAttachmentPreview
}

internal fun resolveAttachmentPreviewUrl(messages: List<UIMessage>, ref: String): String? {
    val normalized = AttachmentRefs.parse(ref)?.let { AttachmentRefs.format(it) } ?: return null
    return AttachmentRefs.walkMessageParts(messages)
        .firstOrNull { part ->
            AttachmentRefs.getRef(part)?.let { AttachmentRefs.parse(it) }?.let { AttachmentRefs.format(it) } == normalized
        }
        ?.let { it as? UIMessagePart.Image }
        ?.url
        ?.takeIf { it.startsWith("file:", ignoreCase = true) }
}
