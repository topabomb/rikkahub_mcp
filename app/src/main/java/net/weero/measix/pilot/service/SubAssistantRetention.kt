package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.cloneLineagePrefix
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import kotlin.uuid.Uuid

internal data class TruncatedChildTree(
    val conversationId: Uuid,
    val nodes: List<MessageNode>,
)

internal data class SubAssistantRetentionPlan(
    val truncatedChildren: List<TruncatedChildTree>,
    val deletedChildIds: List<Uuid>,
)

internal fun planSubAssistantRetention(
    masterId: Uuid,
    masterNodes: List<MessageNode>,
    children: Map<Uuid, ConversationAggregateSnapshot>,
    json: Json,
): SubAssistantRetentionPlan {
    val metadata = masterNodes
        .flatMap { it.messages }
        .flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName == "assistant_call" }
        .mapNotNull { it.getSubAssistantCallMetadata(json) }
    val runCounts = metadata.groupingBy { it.runId }.eachCount()
    val tasksByChild = metadata
        .filter { it.runId.isNotBlank() && runCounts[it.runId] == 1 }
        .mapNotNull { call ->
            val child = resolveValidChildSnapshotLineage(masterId, call, children) ?: return@mapNotNull null
            val taskId = call.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            child.conversationId to taskId
        }
        .groupBy({ it.first }, { it.second })

    val retained = tasksByChild.mapNotNull { (childId, taskIds) ->
        val child = children[childId] ?: return@mapNotNull null
        val longestPrefix = taskIds.mapNotNull { cloneLineagePrefix(child.nodes, it) }
            .maxByOrNull { it.size }
            ?: return@mapNotNull null
        TruncatedChildTree(child.conversationId, longestPrefix)
    }
    val retainedIds = retained.mapTo(mutableSetOf()) { it.conversationId }
    return SubAssistantRetentionPlan(
        truncatedChildren = retained.filter { retainedChild ->
            retainedChild.nodes != children[retainedChild.conversationId]?.nodes
        },
        deletedChildIds = children.keys.filter { it !in retainedIds },
    )
}
