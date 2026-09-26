package net.weero.measix.pilot.data.repository

import me.rerere.ai.core.ToolCallLocator
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import kotlin.uuid.Uuid

/** A single query distinguishes a tracked unexecuted call from a copied transcript without local execution rows. */
internal data class ContextToolExecutionHistory(
    val trackedAssistantMessageIds: Set<Uuid>,
    val outcomes: Map<ToolCallLocator, ToolExecutionStatus>,
)
