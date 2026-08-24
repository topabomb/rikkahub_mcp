package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.cloneLineagePrefix
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import kotlin.uuid.Uuid

internal data class ForkedSubAssistantTree(
    val masterNodes: List<MessageNode>,
    val children: List<Conversation>,
)

private data class ForkOccurrence(
    val nodeIndex: Int,
    val messageIndex: Int,
    val partIndex: Int,
    val metadata: SubAssistantCallMetadata,
)

private data class ForkedChild(
    val conversation: Conversation,
    val messageIdMap: Map<Uuid, Uuid>,
)

internal fun forkSubAssistantTree(
    sourceMasterId: Uuid,
    copiedMasterNodes: List<MessageNode>,
    newMasterId: Uuid,
    sourceChildren: Map<Uuid, Conversation>,
    json: Json,
): ForkedSubAssistantTree {
    val occurrences = buildList {
        copiedMasterNodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEachIndexed { messageIndex, message ->
                message.parts.forEachIndexed { partIndex, part ->
                    if (part is UIMessagePart.Tool && part.toolName == "assistant_call") {
                        part.getSubAssistantCallMetadata(json)?.let { metadata ->
                            add(ForkOccurrence(nodeIndex, messageIndex, partIndex, metadata))
                        }
                    }
                }
            }
        }
    }
    val oldRunCounts = occurrences.groupingBy { it.metadata.runId }.eachCount()
    val validChildByOccurrence = occurrences.associateWith { occurrence ->
        if (occurrence.metadata.runId.isBlank() || oldRunCounts[occurrence.metadata.runId] != 1) {
            null
        } else {
            resolveValidChildLineage(sourceMasterId, occurrence.metadata, sourceChildren)
        }
    }
    val forkedChildren = validChildByOccurrence
        .filterValues { it != null }
        .entries
        .groupBy { it.value!!.id }
        .mapNotNull { (sourceChildId, entries) ->
            val sourceChild = sourceChildren[sourceChildId] ?: return@mapNotNull null
            val longestPrefix = entries.mapNotNull { entry ->
                val taskId = entry.key.metadata.childTaskNodeId
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@mapNotNull null
                cloneLineagePrefix(sourceChild, taskId)
            }.maxByOrNull { it.size } ?: return@mapNotNull null

            val messageIdMap = longestPrefix
                .flatMap { it.messages }
                .associate { it.id to Uuid.random() }
            val clonedNodes = longestPrefix.map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(id = messageIdMap.getValue(message.id))
                    },
                )
            }
            sourceChildId to ForkedChild(
                conversation = sourceChild.copy(
                    id = Uuid.random(),
                    messageNodes = clonedNodes,
                    parentConversationId = newMasterId,
                    isPinned = false,
                    folderId = null,
                    chatSuggestions = emptyList(),
                    customSystemPrompt = null,
                    modeInjectionIds = emptySet(),
                    workspaceCwd = null,
                ),
                messageIdMap = messageIdMap,
            )
        }.toMap()

    val newRunIds = occurrences.associateWith { Uuid.random().toString() }
    val uniqueOldRunMap = occurrences
        .filter { oldRunCounts[it.metadata.runId] == 1 }
        .associate { it.metadata.runId to newRunIds.getValue(it) }
    val replacements = occurrences.associate { occurrence ->
        val old = occurrence.metadata
        val sourceChild = validChildByOccurrence[occurrence]
        val forkedChild = sourceChild?.let { forkedChildren[it.id] }
        val oldTaskId = old.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val updated = old.copy(
            runId = newRunIds.getValue(occurrence),
            previousRunId = old.previousRunId?.let(uniqueOldRunMap::get),
            childConversationId = forkedChild?.conversation?.id?.toString(),
            childTaskNodeId = oldTaskId?.let { forkedChild?.messageIdMap?.get(it) }?.toString(),
        )
        Triple(occurrence.nodeIndex, occurrence.messageIndex, occurrence.partIndex) to updated
    }
    val remappedNodes = copiedMasterNodes.mapIndexed { nodeIndex, node ->
        node.copy(
            messages = node.messages.mapIndexed { messageIndex, message ->
                message.copy(
                    parts = message.parts.mapIndexed { partIndex, part ->
                        val metadata = replacements[Triple(nodeIndex, messageIndex, partIndex)]
                        if (metadata != null && part is UIMessagePart.Tool) {
                            part.mergeSubAssistantCallMetadata(json, metadata)
                        } else {
                            part
                        }
                    }
                )
            }
        )
    }
    return ForkedSubAssistantTree(
        masterNodes = remappedNodes,
        children = forkedChildren.values.map { it.conversation },
    )
}
