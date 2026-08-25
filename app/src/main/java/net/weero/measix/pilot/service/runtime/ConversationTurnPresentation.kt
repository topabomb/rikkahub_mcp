package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.Job

/** UI-facing phase of the single Master turn owner. It is derived, never persisted. */
enum class ConversationTurnPresentation {
    IDLE,
    GENERATING,
    AWAITING_APPROVAL,
    ;

    val isActive: Boolean get() = this != IDLE
}

internal fun resolveConversationTurnPresentation(
    generationJob: Job?,
    snapshot: ConversationSnapshot,
): ConversationTurnPresentation = when {
    snapshot.activeTurn?.toolCallPhases?.values?.any {
        it == ToolCallPhase.AWAITING_APPROVAL
    } == true -> ConversationTurnPresentation.AWAITING_APPROVAL

    generationJob?.isActive == true || snapshot.activeTurn != null ->
        ConversationTurnPresentation.GENERATING

    else -> ConversationTurnPresentation.IDLE
}

internal fun ConversationRuntime.currentTurnPresentation(): ConversationTurnPresentation =
    resolveConversationTurnPresentation(generationJob.value, snapshot.value)
