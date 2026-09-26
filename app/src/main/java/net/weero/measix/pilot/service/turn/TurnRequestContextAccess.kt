package net.weero.measix.pilot.service.turn

import me.rerere.ai.core.ToolCallLocator
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.model.ConversationContextAdmission
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import kotlin.uuid.Uuid

/** The loop borrows committed facts; only the Conversation command owner can admit new input. */
internal interface TurnRequestContextAccess {
    suspend fun read(): TurnRequestHistory
    suspend fun admit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission)
}

internal data class TurnRequestHistory(
    val conversation: ConversationAggregateSnapshot,
    val toolOutcomes: Map<ToolCallLocator, ToolExecutionStatus>,
    /** Absence of a Tool row means unexecuted only when its original Turn is still tracked. */
    val trackedAssistantMessageIds: Set<Uuid> = toolOutcomes.keys.mapTo(mutableSetOf()) { it.assistantMessageId },
)
