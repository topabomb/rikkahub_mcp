package net.weero.measix.pilot.service.subassistant

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishInterruptedTools
import me.rerere.ai.ui.finishPendingTools
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.collectSubAssistantCallOutputs
import net.weero.measix.pilot.data.ai.subassistant.computeSubAssistantPreview
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtrasFromInput
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import kotlin.uuid.Uuid

/** Pure reconciliation for interrupted sub-assistant protocol state and lineage. */
internal fun UIMessage.finalizeSubAssistantToolsAfterInterruption(reason: String): UIMessage {
    fun markInterrupted(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"interrupted","reason":"$reason"}"""
            )
        ),
        resultStatus = me.rerere.ai.ui.ToolResultStatus.INTERRUPTED,
    )
    return finishPendingTools(::markInterrupted).finishInterruptedTools(::markInterrupted)
}
// ---- 子助手调用中断收口纯函数 ----

internal data class SubAssistantReconciliation(
    val masterNodes: List<MessageNode>,
    val referencedChildIds: Set<Uuid>,
    val childStopReasons: Map<Uuid, String>,
)

private data class RecoveryOccurrence(
    val nodeIndex: Int,
    val messageIndex: Int,
    val partIndex: Int,
    val tool: UIMessagePart.Tool,
    val metadata: SubAssistantCallMetadata,
)

internal fun reconcileMasterSubAssistantCalls(
    masterId: Uuid,
    masterNodes: List<MessageNode>,
    childrenById: Map<Uuid, net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot>,
    json: Json,
): SubAssistantReconciliation {
    val occurrences = buildList {
        masterNodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEachIndexed { messageIndex, message ->
                message.parts.forEachIndexed { partIndex, part ->
                    if (part is UIMessagePart.Tool && part.toolName == "assistant_call") {
                        part.getSubAssistantCallMetadata(json)?.let { metadata ->
                            add(RecoveryOccurrence(nodeIndex, messageIndex, partIndex, part, metadata))
                        }
                    }
                }
            }
        }
    }
    val runCounts = occurrences.groupingBy { it.metadata.runId }.eachCount()
    val referenced = mutableSetOf<Uuid>()
    val childReasons = mutableMapOf<Uuid, String>()
    val replacements = mutableMapOf<Triple<Int, Int, Int>, UIMessagePart.Tool>()

    occurrences.forEach { occurrence ->
        val metadata = occurrence.metadata
        val duplicateOrBlankRun = metadata.runId.isBlank() || runCounts[metadata.runId] != 1
        val validChild = if (duplicateOrBlankRun) {
            null
        } else {
            resolveValidChildSnapshotLineage(masterId, metadata, childrenById)
        }
        if (validChild != null) referenced += validChild.conversationId

        if (!metadata.state.isTerminal()) {
            // Recovery closes persisted work; current configuration cannot rewrite its historical authorization.
            val reason = if (validChild == null) "child_missing" else "app_restarted"
            if (validChild != null) childReasons[validChild.conversationId] = reason
            val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val childMessages = validChild?.currentMessages().orEmpty()
            val outputs = collectSubAssistantCallOutputs(
                messages = childMessages,
                childTaskNodeId = taskId,
                extras = parseAssistantCallExtrasFromInput(occurrence.tool.input),
            )
            val preview = if (validChild != null && taskId != null) {
                val rebuilt = computeSubAssistantPreview(childMessages, taskId)
                rebuilt.ifBlank { metadata.preview.orEmpty() }.takeIf { it.isNotBlank() }
            } else {
                metadata.preview
            }
            val stopped = metadata.copy(
                state = SubAssistantCallState.STOPPED,
                phase = null,
                activeToolName = null,
                preview = preview,
                reason = reason,
                userInteraction = null,
            )
            replacements[Triple(occurrence.nodeIndex, occurrence.messageIndex, occurrence.partIndex)] =
                occurrence.tool.mergeSubAssistantCallMetadata(json, stopped).copy(
                    resultStatus = me.rerere.ai.ui.ToolResultStatus.INTERRUPTED,
                    output = listOf(
                        UIMessagePart.Text(
                            buildSubAssistantCallResult(
                                json = json,
                                status = "stopped",
                                assistantName = metadata.targetNameSnapshot,
                                content = "",
                                reason = reason,
                                toolCalls = outputs.toolCalls,
                                ttsTexts = outputs.ttsTexts,
                                ttsStats = outputs.ttsStats,
                            )
                        )
                    )
                )
        }
    }

    if (replacements.isEmpty()) {
        return SubAssistantReconciliation(masterNodes, referenced, childReasons)
    }
    val recoveredNodes = masterNodes.mapIndexed { nodeIndex, node ->
        node.copy(
            messages = node.messages.mapIndexed { messageIndex, message ->
                message.copy(
                    parts = message.parts.mapIndexed { partIndex, part ->
                        replacements[Triple(nodeIndex, messageIndex, partIndex)] ?: part
                    }
                )
            }
        )
    }
    return SubAssistantReconciliation(
        masterNodes = recoveredNodes,
        referencedChildIds = referenced,
        childStopReasons = childReasons,
    )
}

internal fun resolveValidChildSnapshotLineage(
    masterId: Uuid,
    metadata: SubAssistantCallMetadata,
    childrenById: Map<Uuid, net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot>,
): net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot? {
    val childId = metadata.childConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val targetId = runCatching { ConfigurationReference.parse(metadata.targetAssistantId) }.getOrNull() ?: return null
    val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val child = childrenById[childId] ?: return null
    if (child.header.parentConversationId != masterId || child.header.assistantId != targetId) return null
    val hasSelectedTask = child.nodes.any { node ->
        node.selectIndex in node.messages.indices &&
            node.currentMessage.id == taskId &&
            node.currentMessage.role == MessageRole.USER
    }
    return child.takeIf { hasSelectedTask }
}

internal fun resolveValidChildLineage(
    masterId: Uuid,
    metadata: SubAssistantCallMetadata,
    childrenById: Map<Uuid, Conversation>,
): Conversation? {
    val childId = metadata.childConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val targetId = runCatching { ConfigurationReference.parse(metadata.targetAssistantId) }.getOrNull() ?: return null
    val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val child = childrenById[childId] ?: return null
    if (child.parentConversationId != masterId || child.assistantId != targetId) return null
    val hasSelectedTask = child.messageNodes.any { node ->
        node.selectIndex in node.messages.indices &&
            node.currentMessage.id == taskId &&
            node.currentMessage.role == MessageRole.USER
    }
    return child.takeIf { hasSelectedTask }
}
