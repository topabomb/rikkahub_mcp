package net.weero.measix.pilot.service

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import net.weero.measix.pilot.service.runtime.TurnLivePhase
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

/** Read-only foreground feedback input; timing and delivery belong to the visible chat page. */
data class ConversationTurnFeedback(
    val turnId: Uuid,
    val outputCharacters: Long?,
    val awaitingUser: Boolean,
    val attentionKeys: Set<String>,
)

internal fun projectConversationTurnFeedback(
    snapshot: ConversationPresentationSnapshot,
    presentation: ConversationPresentation,
): ConversationTurnFeedback? {
    val phase = presentation.phase
    if (phase == null || phase == TurnLivePhase.STOPPING) return null

    val requestId = presentation.activeTurnId
    val active = snapshot.stream
    // A durable unfinished turn alone is not evidence of a running request.
    if (phase != TurnLivePhase.AWAITING_USER && requestId == null) return null
    if (phase != TurnLivePhase.PREPARING &&
        (active == null || (requestId != null && requestId != active.turnId))
    ) return null

    val turnId = requestId ?: active?.turnId ?: return null
    val message = active?.takeIf { it.turnId == turnId }?.let { turn ->
        // StartTurn 在首个流式投影前只提交了空 slot：草稿为 null 时读 committed 末节点。
        turn.assistantMessage
            ?: snapshot.nodes.lastOrNull()?.currentMessage?.takeIf { it.id == turn.assistantMessageId }
    }
    val attentionKeys = buildSet {
        if (phase != TurnLivePhase.PREPARING) {
            message?.getTools()?.forEach { tool ->
                val locator = ToolCallLocator(message.id, tool.stepId, tool.localCallId)
                val toolPhase = presentation.toolLivePhases[locator]
                if (phase == TurnLivePhase.AWAITING_USER &&
                    toolPhase == ToolLivePhase.AWAITING_APPROVAL
                ) {
                    add("tool:${message.id}:${tool.localCallId}")
                }
                if (toolPhase == ToolLivePhase.EXECUTING) {
                    val metadata = tool.getSubAssistantCallMetadata(JsonInstant)
                    val interaction = metadata?.userInteraction
                    if (metadata?.state == SubAssistantCallState.RUNNING &&
                        metadata.phase == SubAssistantCallPhase.AWAITING_USER &&
                        interaction != null && interaction.interactionId.isNotBlank()
                    ) {
                        add("ask:${interaction.interactionId}")
                    }
                }
            }
        }
    }
    return ConversationTurnFeedback(
        turnId = turnId,
        // Approximate output volume, not tokens: exclude replay results, metadata and media URLs.
        outputCharacters = message?.parts?.sumOf { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.length.toLong()
                is UIMessagePart.Reasoning -> part.reasoning.length.toLong()
                is UIMessagePart.Tool -> part.input.length.toLong()
                else -> 0L
            }
        },
        awaitingUser = phase == TurnLivePhase.AWAITING_USER || attentionKeys.isNotEmpty(),
        attentionKeys = attentionKeys,
    )
}
