package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.cloneLineagePrefix
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import kotlin.uuid.Uuid

internal data class SubAssistantRetentionPlan(
    val retainedChildren: List<Conversation>,
    val deletedChildren: List<Conversation>,
)

internal fun planSubAssistantRetention(
    master: Conversation,
    children: Map<Uuid, Conversation>,
    json: Json,
): SubAssistantRetentionPlan {
    val metadata = master.messageNodes
        .flatMap { it.messages }
        .flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName == "assistant_call" }
        .mapNotNull { it.getSubAssistantCallMetadata(json) }
    val runCounts = metadata.groupingBy { it.runId }.eachCount()
    val tasksByChild = metadata
        .filter { it.runId.isNotBlank() && runCounts[it.runId] == 1 }
        .mapNotNull { call ->
            val child = resolveValidRecoveryChild(master, call, children) ?: return@mapNotNull null
            val taskId = call.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            child.id to taskId
        }
        .groupBy({ it.first }, { it.second })

    val retained = tasksByChild.mapNotNull { (childId, taskIds) ->
        val child = children[childId] ?: return@mapNotNull null
        val longestPrefix = taskIds.mapNotNull { cloneLineagePrefix(child, it) }
            .maxByOrNull { it.size }
            ?: return@mapNotNull null
        child.copy(messageNodes = longestPrefix)
    }
    val retainedIds = retained.mapTo(mutableSetOf()) { it.id }
    return SubAssistantRetentionPlan(
        retainedChildren = retained,
        deletedChildren = children.filterKeys { it !in retainedIds }.values.toList(),
    )
}
