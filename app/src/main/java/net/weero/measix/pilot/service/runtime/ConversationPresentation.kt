package net.weero.measix.pilot.service.runtime

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.ToolResultEventStatus
import net.weero.measix.pilot.data.ai.tools.ToolInteractionKind
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 用户可见的会话投影（权威方案 §3.2）。它可以重建、不参与写协议，并且结构上不含 model
 * context：aggregate 的 modelContextEntries 在这里没有对应字段，所以 UI 不是被提醒不要读
 * 某个字段，而是根本拿不到（§17.7）。
 *
 * [nodes] 已经是合并后的渲染列表：未变节点保持与 aggregate 同一实例引用（structural
 * sharing 到 Compose skip），流式期间只有末节点被 [activeTurn] 覆盖。合并规则只存在于本
 * 文件的 [toPresentationSnapshot]，aggregate 不再提供 renderNodes。
 */
data class ConversationPresentationSnapshot(
    val conversationId: Uuid,
    val header: ConversationHeader,
    val nodes: List<MessageNode>,
    val activeTurn: ActiveTurnState?,
) {
    /**
     * 与 aggregate 的 internal currentMessages() 逐项等价：末节点在 [nodes] 里已被
     * [activeTurn] 覆盖，因此这里只需要线性投影，不存在第二份合并规则。
     */
    fun currentMessages(): List<UIMessage> = nodes.map { it.currentMessage }
}

/**
 * aggregate 到 presentation 的唯一 projector。方向单一：presentation 永不回写 durable 事实，
 * 也不向调用方暴露 aggregate 引用。
 */
internal fun ConversationAggregateSnapshot.toPresentationSnapshot(): ConversationPresentationSnapshot {
    val turn = activeTurn
    val rendered = if (turn == null || turn.messages.isEmpty() || nodes.isEmpty()) {
        nodes
    } else {
        val lastIndex = nodes.lastIndex
        nodes.mapIndexed { index, node ->
            if (index != lastIndex) node else node.copy(
                messages = listOf(turn.messages.last()),
                selectIndex = 0,
            )
        }
    }
    return ConversationPresentationSnapshot(
        conversationId = conversationId,
        header = header,
        nodes = rendered,
        activeTurn = activeTurn,
    )
}

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
    AWAITING_INPUT,
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

/**
 * Turn-level display state. [AWAITING_USER] covers both authorization approval and user-input
 * collection; the per-call [ToolCallPhase] distinguishes them. The durable Room encoding keeps
 * its historical AWAITING_APPROVAL name — this projection is the application-level meaning.
 */
enum class ConversationTurnPhase {
    IDLE,
    PREPARING,
    GENERATING,
    AWAITING_USER,
    STOPPING,
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
    snapshot: ConversationAggregateSnapshot,
    lastTerminatedRequestTurnId: Uuid? = null,
): ConversationPresentation {
    val requestPhase = active?.phase
    val durable = snapshot.activeTurn
    val phase = when {
        requestPhase != null -> requestPhase
        durable == null -> ConversationTurnPhase.IDLE
        durable.toolCallPhases.values.any {
            it == ToolCallPhase.AWAITING_APPROVAL || it == ToolCallPhase.AWAITING_INPUT
        } -> ConversationTurnPhase.AWAITING_USER
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
                tool.approvalState is ToolApprovalState.Pending -> pendingPhaseOf(tool)
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
    // 2026-9-2 15:01 修复 "tool interaction is no longer pending"：此前只同步 toolCallPhases，
    // messages 停留在流式投影（暂停工具仍是 Auto 旧版），而 currentMessages() 末条取自
    // turn.messages.last()，用户决策会读到非 Pending 状态而误报。checkpoint 携带的 messages
    // 是 committed 权威投影，activeTurn 必须一并对齐；勿回退为仅同步 phases。
    return copy(messages = command.messages, toolCallPhases = phases)
}

fun resolveToolCallPhase(tool: UIMessagePart.Tool, activePhase: ToolCallPhase?): ToolCallPhase =
    activePhase ?: when {
        ToolRuntimeMetadata.isInvalid(tool.metadata) -> ToolCallPhase.FAILED
        ToolRuntimeMetadata.terminalStatusOf(tool.metadata) == "completed" -> ToolCallPhase.COMPLETED
        ToolRuntimeMetadata.terminalStatusOf(tool.metadata) == "failed" -> ToolCallPhase.FAILED
        ToolRuntimeMetadata.terminalStatusOf(tool.metadata) == "denied" -> ToolCallPhase.DENIED
        ToolRuntimeMetadata.terminalStatusOf(tool.metadata) == "answered" -> ToolCallPhase.ANSWERED
        tool.approvalState is ToolApprovalState.Pending -> pendingPhaseOf(tool)
        tool.approvalState is ToolApprovalState.Denied -> ToolCallPhase.DENIED
        tool.approvalState is ToolApprovalState.Answered -> ToolCallPhase.ANSWERED
        else -> tool.resultTerminalPhase() ?: if (tool.hasReplayResult) {
            ToolCallPhase.COMPLETED
        } else {
            ToolCallPhase.READY
        }
    }

/**
 * A paused call shows the interaction the Runtime captured when it paused. Calls without that
 * metadata predate the typed protocol; authorization approval is the safe default projection.
 */
private fun pendingPhaseOf(tool: UIMessagePart.Tool): ToolCallPhase =
    when (ToolRuntimeMetadata.interactionKindOf(tool.metadata)) {
        ToolInteractionKind.USER_INPUT -> ToolCallPhase.AWAITING_INPUT
        else -> ToolCallPhase.AWAITING_APPROVAL
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
        stringField("status") in setOf("failed", "stopped", "unavailable") -> ToolCallPhase.FAILED
        result["error"] != null && stringField("type") == "error" ->
            ToolCallPhase.FAILED
        result["error"] != null && stringField("type") == "timeout" ->
            ToolCallPhase.FAILED
        else -> null
    }
}
