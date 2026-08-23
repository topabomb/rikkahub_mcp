package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.computeSubAssistantPreview
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtrasFromInput
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.runtime.collectSubAssistantCallOutputs
import kotlin.uuid.Uuid

internal data class SubAssistantRecoveryResult(
    val master: Conversation,
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

internal fun recoverMasterSubAssistantCalls(
    master: Conversation,
    settings: Settings,
    childrenById: Map<Uuid, Conversation>,
    json: Json,
): SubAssistantRecoveryResult {
    require(master.parentConversationId == null)
    val occurrences = buildList {
        master.messageNodes.forEachIndexed { nodeIndex, node ->
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
            resolveValidRecoveryChild(master, metadata, childrenById)
        }
        if (validChild != null) referenced += validChild.id

        if (!metadata.state.isTerminal()) {
            val reason = if (duplicateOrBlankRun) {
                "child_missing"
            } else {
                resolveRecoveryStopReason(master, metadata, settings, validChild)
            }
            if (validChild != null) {
                childReasons[validChild.id] = chooseMoreSpecificStopReason(
                    childReasons[validChild.id],
                    reason,
                )
            }
            val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val childMessages = validChild?.currentMessages.orEmpty()
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
        return SubAssistantRecoveryResult(master, referenced, childReasons)
    }
    val recoveredNodes = master.messageNodes.mapIndexed { nodeIndex, node ->
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
    return SubAssistantRecoveryResult(
        master = master.copy(messageNodes = recoveredNodes),
        referencedChildIds = referenced,
        childStopReasons = childReasons,
    )
}

internal fun resolveValidRecoveryChild(
    master: Conversation,
    metadata: SubAssistantCallMetadata,
    childrenById: Map<Uuid, Conversation>,
): Conversation? {
    val childId = metadata.childConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val targetId = runCatching { Uuid.parse(metadata.targetAssistantId) }.getOrNull() ?: return null
    val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val child = childrenById[childId] ?: return null
    if (child.parentConversationId != master.id || child.assistantId != targetId) return null
    val hasSelectedTask = child.messageNodes.any { node ->
        node.selectIndex in node.messages.indices &&
            node.currentMessage.id == taskId &&
            node.currentMessage.role == MessageRole.USER
    }
    return child.takeIf { hasSelectedTask }
}

internal fun resolveRecoveryStopReason(
    master: Conversation,
    metadata: SubAssistantCallMetadata,
    settings: Settings,
    validChild: Conversation?,
): String {
    val targetId = runCatching { Uuid.parse(metadata.targetAssistantId) }.getOrNull()
        ?: return "target_removed"
    val pendingDeletionIds = settings.pendingAssistantDeletions.mapTo(mutableSetOf()) { it.assistantId }
    val target = settings.assistants.find { it.id == targetId }
    if (target == null || targetId in pendingDeletionIds) return "target_removed"
    if (!target.allowAsSubAssistant) return "target_disabled"

    val caller = settings.assistants.find { it.id == master.assistantId }
    if (caller == null || caller.id in pendingDeletionIds ||
        LocalToolOption.AssistantDelegation !in caller.localTools ||
        !SubAssistantAccessPolicy.canAccess(caller, target)
    ) {
        return "target_access_revoked"
    }
    val runSpec = resolveSubAssistantRunSpec(settings, caller, target)
    if (runSpec is SubAssistantRunSpecResolution.Blocked) {
        return runSpec.reason
    }
    if (validChild == null) return "child_missing"
    return "app_restarted"
}

internal fun chooseMoreSpecificStopReason(existing: String?, incoming: String): String {
    if (existing == null) return incoming
    val priority = listOf(
        "target_removed",
        "target_disabled",
        "target_access_revoked",
        "target_model_unavailable",
        "caller_model_unavailable",
        "child_missing",
        "app_restarted",
    )
    fun rank(reason: String): Int = priority.indexOf(reason).takeIf { it >= 0 } ?: Int.MAX_VALUE
    return if (rank(incoming) < rank(existing)) incoming else existing
}
