package net.weero.measix.pilot.data.ai.subassistant

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * Lineage 决策结果
 */
sealed class LineageDecision {
    /**
     * 没有前序调用：创建新 Child
     */
    data object CreateNew : LineageDecision()

    /**
     * 前序调用指向的 run 正好是该 Child 的尾部：继续使用该 Child
     */
    data class ReuseChild(
        val childConversationId: Uuid,
        val previousRunId: String,
    ) : LineageDecision()

    /**
     * Child 在此前序 run 之后还有其他任务：从该 run 结束处克隆一个新 Child
     */
    data class CloneChild(
        val sourceChildConversationId: Uuid,
        val sourceRunId: String,
        val throughTaskMessageId: Uuid,
    ) : LineageDecision()

    /**
     * 前序 metadata 缺失、Child 已损坏或不属于当前 Master：创建新 Child
     */
    data object CreateNewDueToError : LineageDecision()
}

/**
 * 从 Master 当前选中的 currentMessages 中，从当前 tool 位置向前查找同一 Target 最近的 assistant_call。
 *
 * 返回该 call 的 metadata（如果有），以及 run_id。
 */
fun findPreviousCallMetadata(
    masterMessages: List<UIMessage>,
    currentMessageId: Uuid,
    currentToolOrdinal: Int,
    targetAssistantId: Uuid,
    json: kotlinx.serialization.json.Json,
): SubAssistantCallMetadata? {
    val targetIdStr = targetAssistantId.toString()
    val currentMessageIndex = masterMessages.indexOfFirst { it.id == currentMessageId }
    if (currentMessageIndex < 0) return null
    val currentTools = masterMessages[currentMessageIndex].parts.filterIsInstance<UIMessagePart.Tool>()
    if (currentTools.getOrNull(currentToolOrdinal)?.toolName != "assistant_call") return null

    // 从精确 locator 的前一个 Tool part 开始逆序查找。Provider toolCallId 可能为空、
    // 重复或跨 step 复用，不能用它判断“当前调用”。
    for (messageIndex in currentMessageIndex downTo 0) {
        val tools = masterMessages[messageIndex].parts.filterIsInstance<UIMessagePart.Tool>()
        val lastOrdinal = if (messageIndex == currentMessageIndex) {
            minOf(currentToolOrdinal - 1, tools.lastIndex)
        } else {
            tools.lastIndex
        }
        for (toolOrdinal in lastOrdinal downTo 0) {
            val part = tools[toolOrdinal]
            if (part.toolName != "assistant_call") continue
            val meta = part.getSubAssistantCallMetadata(json) ?: continue
            if (meta.targetAssistantId != targetIdStr) continue
            if (!meta.state.isTerminal()) continue
            return meta
        }
    }
    return null
}

/**
 * 根据 previous call metadata 和 Child 会话当前状态，决定 lineage 操作。
 *
 * @param previousMeta 前序调用 metadata（如果没有则为 null）
 * @param childConversation 前序调用指向的 Child 会话（如果存在且可加载）
 * @param previousRunChildTaskNodeId 前序调用在 Child 中的 task node ID
 */
fun resolveLineage(
    previousMeta: SubAssistantCallMetadata?,
    childConversation: Conversation?,
    expectedMasterConversationId: Uuid,
    expectedTargetAssistantId: Uuid,
): LineageDecision {
    // 没有前序调用：创建新 Child
    if (previousMeta == null) {
        return LineageDecision.CreateNew
    }

    val childConvId = previousMeta.childConversationId
    val previousRunId = previousMeta.runId

    // Child 不存在或损坏
    if (childConvId == null || childConversation == null) {
        return LineageDecision.CreateNewDueToError
    }

    val childConvUuid = runCatching { Uuid.parse(childConvId) }.getOrNull()
        ?: return LineageDecision.CreateNewDueToError

    // 检查 Child 是否属于当前 Master
    if (childConversation.parentConversationId != expectedMasterConversationId ||
        childConversation.assistantId != expectedTargetAssistantId
    ) {
        return LineageDecision.CreateNewDueToError
    }

    // 检查 previous run 的 task node 是否是 Child 的尾部 task
    val previousTaskNodeId = previousMeta.childTaskNodeId
    if (previousTaskNodeId == null) {
        return LineageDecision.CreateNewDueToError
    }

    val previousTaskUuid = runCatching { Uuid.parse(previousTaskNodeId) }.getOrNull()
        ?: return LineageDecision.CreateNewDueToError

    // 在 Child 的 messageNodes 中按 UIMessage.id 查找 task message 所属的 node
    val taskNodeIndex = childConversation.messageNodes.indexOfFirst { node ->
        node.selectIndex in node.messages.indices &&
            node.currentMessage.id == previousTaskUuid &&
            node.currentMessage.role == me.rerere.ai.core.MessageRole.USER
    }
    if (taskNodeIndex == -1) {
        return LineageDecision.CreateNewDueToError
    }

    // 检查该 task node 之后是否还有其他 USER task
    val hasSubsequentUserTasks = childConversation.messageNodes
        .subList(taskNodeIndex + 1, childConversation.messageNodes.size)
        .any { node ->
            node.selectIndex in node.messages.indices &&
                node.currentMessage.role == me.rerere.ai.core.MessageRole.USER
        }

    return if (hasSubsequentUserTasks) {
        // Child 在此前序 run 之后还有其他任务：需要 clone
        LineageDecision.CloneChild(
            sourceChildConversationId = childConvUuid,
            sourceRunId = previousRunId,
            throughTaskMessageId = previousTaskUuid,
        )
    } else {
        // 前序调用指向的 run 正好是该 Child 的尾部：继续使用
        LineageDecision.ReuseChild(
            childConversationId = childConvUuid,
            previousRunId = previousRunId,
        )
    }
}

/**
 * Returns the selected Child history prefix through one run, excluding the next USER task.
 * Message IDs and opaque metadata are preserved; callers regenerate MessageNode IDs.
 */
fun cloneLineagePrefix(
    sourceNodes: List<net.weero.measix.pilot.data.model.MessageNode>,
    throughTaskMessageId: Uuid,
): List<net.weero.measix.pilot.data.model.MessageNode>? {
    val startIndex = sourceNodes.indexOfFirst { node ->
        node.selectIndex in node.messages.indices &&
            node.currentMessage.id == throughTaskMessageId &&
            node.currentMessage.role == me.rerere.ai.core.MessageRole.USER
    }
    if (startIndex < 0) return null

    val nextTaskOffset = sourceNodes
        .drop(startIndex + 1)
        .indexOfFirst { node ->
            node.selectIndex in node.messages.indices &&
                node.currentMessage.role == me.rerere.ai.core.MessageRole.USER
        }
    val endExclusive = if (nextTaskOffset < 0) {
        sourceNodes.size
    } else {
        startIndex + 1 + nextTaskOffset
    }
    return sourceNodes.subList(0, endExclusive)
}
