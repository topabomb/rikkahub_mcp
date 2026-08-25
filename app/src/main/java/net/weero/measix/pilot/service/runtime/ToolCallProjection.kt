package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ToolApprovalState
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus

/**
 * Streaming may expose a partial Tool part before the provider has finished its call. Existing
 * committed phases are retained while metadata/output deltas arrive; terminal output therefore
 * cannot make a running tool look completed before its checkpoint commits.
 */
internal fun ActiveTurnState.withStreamingMessages(nextMessages: List<UIMessage>): ActiveTurnState {
    val assistant = nextMessages.lastOrNull { it.id == assistantMessageId }
        ?: return copy(messages = nextMessages, toolCallPhases = emptyMap())
    val nextPhases = assistant.getTools().mapIndexed { ordinal, _ ->
        val locator = ToolCallLocator(assistantMessageId, ordinal)
        val current = toolCallPhases[locator]
        // Streaming content can expose the card but cannot advance a committed lifecycle fact.
        // Approval decisions and execution/result phases are published by command checkpoints.
        locator to (current ?: ToolCallPhase.CALL_STREAMING)
    }.toMap()
    return copy(messages = nextMessages, toolCallPhases = nextPhases)
}

/** Advances the active UI projection only from an already committed checkpoint command. */
internal fun ActiveTurnState.afterCheckpoint(command: CommitCheckpoint): ActiveTurnState {
    val assistant = command.messages.lastOrNull { it.id == assistantMessageId } ?: return this
    val phases = toolCallPhases.toMutableMap()
    fun setFromMessage(includeExecuted: Boolean) {
        assistant.getTools().forEachIndexed { ordinal, tool ->
            val locator = ToolCallLocator(assistantMessageId, ordinal)
            val phase = when {
                tool.approvalState is ToolApprovalState.Pending -> ToolCallPhase.AWAITING_APPROVAL
                tool.approvalState is ToolApprovalState.Denied -> ToolCallPhase.DENIED
                tool.approvalState is ToolApprovalState.Answered -> ToolCallPhase.ANSWERED
                phases[locator] == ToolCallPhase.FAILED -> ToolCallPhase.FAILED
                phases[locator] == ToolCallPhase.CANCELLED -> ToolCallPhase.CANCELLED
                phases[locator] == ToolCallPhase.INTERRUPTED -> ToolCallPhase.INTERRUPTED
                phases[locator] == ToolCallPhase.COMPLETED -> ToolCallPhase.COMPLETED
                tool.isExecuted && includeExecuted -> ToolCallPhase.COMPLETED
                !tool.isExecuted -> ToolCallPhase.READY
                else -> phases[locator]
            }
            if (phase != null) phases[locator] = phase
        }
    }
    when (command.kind) {
        CheckpointKind.STEP_COMPLETED,
        CheckpointKind.AWAITING_APPROVAL,
        -> setFromMessage(includeExecuted = false)

        CheckpointKind.TOOL_EXECUTION_STARTED,
        CheckpointKind.TOOL_STATE_CHANGED,
        -> command.toolExecution?.let { execution ->
            if (execution.status == ToolExecutionStatus.STARTED) {
                phases[ToolCallLocator(assistantMessageId, execution.toolOrdinal)] = ToolCallPhase.EXECUTING
            }
        }

        CheckpointKind.TOOL_RESULT_COMPLETED -> {
            val execution = command.toolExecution
            if (execution == null) {
                setFromMessage(includeExecuted = true)
            } else {
                phases[ToolCallLocator(assistantMessageId, execution.toolOrdinal)] = when (execution.status) {
                    ToolExecutionStatus.COMPLETED -> ToolCallPhase.COMPLETED
                    ToolExecutionStatus.FAILED -> ToolCallPhase.FAILED
                    ToolExecutionStatus.STARTED -> ToolCallPhase.EXECUTING
                    ToolExecutionStatus.CANCELLED -> ToolCallPhase.CANCELLED
                    ToolExecutionStatus.UNKNOWN -> ToolCallPhase.INTERRUPTED
                }
            }
        }
    }
    return copy(toolCallPhases = phases)
}
