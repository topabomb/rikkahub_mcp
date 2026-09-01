package net.weero.measix.pilot.service

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallPhase
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationTurnPhase
import net.weero.measix.pilot.service.runtime.ToolCallPhase
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
    snapshot: ConversationSnapshot,
    presentation: ConversationPresentation,
): ConversationTurnFeedback? {
    val phase = presentation.phase
    if (phase == ConversationTurnPhase.IDLE || phase == ConversationTurnPhase.STOPPING) return null

    val requestId = presentation.activeRequestTurnId
    val active = snapshot.activeTurn
    // A durable unfinished turn alone is not evidence of a running request.
    if (phase != ConversationTurnPhase.AWAITING_USER && requestId == null) return null
    if (phase != ConversationTurnPhase.PREPARING &&
        (active == null || (requestId != null && requestId != active.turnId))
    ) return null

    val turnId = requestId ?: active?.turnId ?: return null
    val message = active?.takeIf { it.turnId == turnId }?.let { turn ->
        if (turn.messages.isEmpty()) {
            // StartTurn publishes the committed slot before its first streaming projection.
            snapshot.nodes.lastOrNull()?.currentMessage?.takeIf { it.id == turn.assistantMessageId }
        } else {
            turn.messages.lastOrNull { it.id == turn.assistantMessageId }
        }
    }
    val attentionKeys = buildSet {
        if (phase != ConversationTurnPhase.PREPARING) {
            message?.getTools()?.forEachIndexed { ordinal, tool ->
                val locator = ToolCallLocator(message.id, ordinal)
                val toolPhase = presentation.toolCallPhases[locator]
                if (phase == ConversationTurnPhase.AWAITING_USER &&
                    toolPhase == ToolCallPhase.AWAITING_APPROVAL
                ) {
                    add("tool:${message.id}:$ordinal")
                }
                if (toolPhase == ToolCallPhase.EXECUTING) {
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
        awaitingUser = phase == ConversationTurnPhase.AWAITING_USER || attentionKeys.isNotEmpty(),
        attentionKeys = attentionKeys,
    )
}
