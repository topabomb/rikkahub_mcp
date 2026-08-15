package net.weero.measix.pilot.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart
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
