package net.weero.measix.pilot.service.runtime

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.ToolResultEventStatus
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * Stable presentation phase of one tool call inside an active Assistant message.
 *
 * Model call assembly and tool execution are separate phases: a tool can already have a
 * complete name/input while it is waiting for approval or running remotely, but still have no
 * protocol output. Keeping that distinction in the Runtime projection prevents UI code from
 * treating `output.isEmpty()` as the meaning of every in-flight state.
 */
enum class ToolCallPhase {
    CALL_STREAMING,
    READY,
    AWAITING_APPROVAL,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    DENIED,
    ANSWERED,
}

val ToolCallPhase.isBusy: Boolean
    get() = this == ToolCallPhase.CALL_STREAMING || this == ToolCallPhase.EXECUTING

enum class ConversationTurnPhase {
    IDLE,
    PREPARING,
    GENERATING,
    AWAITING_APPROVAL,
    STOPPING,
}

/** One observed request pair for display only; never a turn accounting baseline. */
data class ContextCacheDisplay(
    val sourceAssistantMessageId: Uuid,
    val contextTokens: Long,
    val cacheReadInputTokens: Long,
) {
    init {
        require(contextTokens > 0 && cacheReadInputTokens in 0..contextTokens)
    }

    val cachePercent: Double get() = cacheReadInputTokens.toDouble() / contextTokens * 100.0
}

data class ActiveContextCache(
    val assistantMessageId: Uuid,
    val value: ContextCacheDisplay,
) {
    fun forMessage(messageId: Uuid): ContextCacheDisplay? = value.takeIf { messageId == assistantMessageId }
}

internal fun UIMessage.contextCacheDisplay(): ContextCacheDisplay? {
    if (role != MessageRole.ASSISTANT) return null
    val context = usage?.latestRequestContextTokens?.takeIf { it > 0 } ?: return null
    val cache = usage?.latestRequestCacheReadInputTokens?.takeIf { it in 0..context } ?: return null
    return ContextCacheDisplay(id, context, cache)
}

internal fun ConversationSnapshot.latestBranchContextCache(): ContextCacheDisplay? =
    nodes.asReversed().firstNotNullOfOrNull { it.currentMessage.contextCacheDisplay() }

internal fun ConversationSnapshot.activeContextCache(): ActiveContextCache? {
    val active = activeTurn ?: return null
    val pair = active.latestAvailableContextCache ?: return null
    return ActiveContextCache(active.assistantMessageId, pair)
}

/**
 * UI-facing turn runtime. It is derived from the private active request and durable
 * snapshot, never persisted, and is not a write protocol.
 */
data class ConversationPresentation(
    val activeRequestTurnId: Uuid?,
    val phase: ConversationTurnPhase,
    val processingText: String?,
    val toolCallPhases: Map<ToolCallLocator, ToolCallPhase>,
    /** Latest terminated request identity, used to close a receipt wait without timing guesses. */
    val lastTerminatedRequestTurnId: Uuid? = null,
    val activeContextCache: ActiveContextCache? = null,
) {
    val isActive: Boolean get() = phase != ConversationTurnPhase.IDLE

    companion object {
        val IDLE = ConversationPresentation(
            activeRequestTurnId = null,
            phase = ConversationTurnPhase.IDLE,
            processingText = null,
            toolCallPhases = emptyMap(),
        )
    }
}

internal fun resolveConversationPresentation(
    active: ActiveRequestPresentationFacts?,
    snapshot: ConversationSnapshot,
    lastTerminatedRequestTurnId: Uuid? = null,
): ConversationPresentation {
    val requestPhase = active?.phase
    val durable = snapshot.activeTurn
    val phase = when {
        requestPhase != null -> requestPhase
        durable == null -> ConversationTurnPhase.IDLE
        durable.toolCallPhases.values.any { it == ToolCallPhase.AWAITING_APPROVAL } ->
            ConversationTurnPhase.AWAITING_APPROVAL
        else -> ConversationTurnPhase.GENERATING
    }
    val joined = active?.handle?.takeIf { handle ->
        durable != null &&
            durable.turnId == handle.turnId &&
            durable.epoch == handle.epoch
    }
    val toolCallPhases = when {
        joined != null -> durable?.toolCallPhases.orEmpty()
        requestPhase == ConversationTurnPhase.PREPARING ||
            requestPhase == ConversationTurnPhase.STOPPING -> emptyMap()
        durable != null && active == null -> durable.toolCallPhases
        else -> emptyMap()
    }
    return ConversationPresentation(
        activeRequestTurnId = active?.turnId,
        phase = phase,
        processingText = active?.processingText,
        toolCallPhases = toolCallPhases,
        lastTerminatedRequestTurnId = lastTerminatedRequestTurnId,
        activeContextCache = snapshot.activeContextCache(),
    )
}

internal fun ConversationRuntime.currentTurnPresentation(): ConversationPresentation =
    resolveConversationPresentation(
        activeRequestPresentationFacts(),
        snapshot.value,
        lastTerminatedRequestTurnId(),
    )

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
        locator to (current ?: ToolCallPhase.CALL_STREAMING)
    }.toMap()
    return copy(
        messages = nextMessages,
        toolCallPhases = nextPhases,
        latestAvailableContextCache = assistant.contextCacheDisplay() ?: latestAvailableContextCache,
    )
}

/** Advances the active UI projection only from an already committed checkpoint command. */
internal fun ActiveTurnState.afterCheckpoint(command: CommitCheckpoint): ActiveTurnState {
    val assistant = command.messages.lastOrNull { it.id == assistantMessageId } ?: return this
    val phases = toolCallPhases.toMutableMap()
    fun setReadyPhases() {
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
                else -> ToolCallPhase.READY
            }
            phases[locator] = phase
        }
    }
    when (command.kind) {
        CheckpointKind.STEP_COMPLETED,
        CheckpointKind.AWAITING_APPROVAL,
        -> setReadyPhases()

        CheckpointKind.TOOL_EXECUTION_STARTED,
        CheckpointKind.TOOL_STATE_CHANGED,
        -> command.toolExecution?.let { execution ->
            if (execution.status == ToolExecutionStatus.STARTED) {
                phases[ToolCallLocator(assistantMessageId, execution.toolOrdinal)] = ToolCallPhase.EXECUTING
            }
        }

        CheckpointKind.TOOL_RESULT_COMPLETED -> {
            require(command.toolResults.isNotEmpty()) {
                "tool-result checkpoint requires typed result facts"
            }
            require(command.toolResults.map { it.toolOrdinal }.distinct().size == command.toolResults.size) {
                "tool-result checkpoint contains duplicate tool ordinals"
            }
            val tools = assistant.getTools()
            command.toolResults.forEach { result ->
                require(result.messageId == assistantMessageId) {
                    "tool-result checkpoint targets a different assistant message"
                }
                require(result.toolOrdinal in tools.indices) {
                    "tool-result checkpoint targets a missing tool ordinal"
                }
                require(tools[result.toolOrdinal].hasReplayResult) {
                    "tool-result checkpoint requires a Provider replay result"
                }
                phases[ToolCallLocator(result.messageId, result.toolOrdinal)] = when (result.status) {
                    ToolResultEventStatus.COMPLETED -> ToolCallPhase.COMPLETED
                    ToolResultEventStatus.FAILED -> ToolCallPhase.FAILED
                    ToolResultEventStatus.DENIED -> ToolCallPhase.DENIED
                    ToolResultEventStatus.ANSWERED -> ToolCallPhase.ANSWERED
                }
            }
            command.toolExecution?.let { execution ->
                require(command.toolResults.size == 1 && command.toolResults.single().toolOrdinal == execution.toolOrdinal) {
                    "tool execution and result checkpoint target different tools"
                }
                val expected = when (execution.status) {
                    ToolExecutionStatus.COMPLETED -> ToolResultEventStatus.COMPLETED
                    ToolExecutionStatus.FAILED -> ToolResultEventStatus.FAILED
                    ToolExecutionStatus.STARTED,
                    ToolExecutionStatus.CANCELLED,
                    ToolExecutionStatus.UNKNOWN,
                    -> error("tool-result checkpoint contains a non-result execution status")
                }
                require(command.toolResults.single().status == expected) {
                    "tool execution and result checkpoint have conflicting terminal statuses"
                }
            }
        }
    }
    return copy(
        toolCallPhases = phases,
        latestAvailableContextCache = assistant.contextCacheDisplay() ?: latestAvailableContextCache,
    )
}

fun resolveToolCallPhase(tool: UIMessagePart.Tool, activePhase: ToolCallPhase?): ToolCallPhase =
    activePhase ?: when {
        tool.approvalState is ToolApprovalState.Pending -> ToolCallPhase.AWAITING_APPROVAL
        tool.approvalState is ToolApprovalState.Denied -> ToolCallPhase.DENIED
        tool.approvalState is ToolApprovalState.Answered -> ToolCallPhase.ANSWERED
        else -> tool.resultTerminalPhase() ?: if (tool.hasReplayResult) {
            ToolCallPhase.COMPLETED
        } else {
            ToolCallPhase.READY
        }
    }

private fun UIMessagePart.Tool.resultTerminalPhase(): ToolCallPhase? {
    if (!hasReplayResult) return null
    val result = runCatching {
        JsonInstant.parseToJsonElement(
            output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
        ).jsonObject
    }.getOrNull() ?: return null
    fun stringField(name: String): String? = (result[name] as? JsonPrimitive)?.contentOrNull
    return when {
        stringField("status") == "cancelled" -> ToolCallPhase.CANCELLED
        stringField("status") == "interrupted" -> ToolCallPhase.INTERRUPTED
        result["error"] != null && stringField("type") == "error" ->
            ToolCallPhase.FAILED
        result["error"] != null && stringField("type") == "timeout" ->
            ToolCallPhase.FAILED
        else -> null
    }
}
