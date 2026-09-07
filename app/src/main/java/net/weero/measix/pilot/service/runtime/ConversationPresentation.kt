package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import kotlin.uuid.Uuid

/**
 * 用户可见的会话投影。它可以重建、不参与写协议，并且结构上不含 model
 * context：aggregate 的 modelContextEntries 在这里没有对应字段，所以 UI 不是被提醒不要读
 * 某个字段，而是根本拿不到。
 *
 * [nodes] 已经是合并后的渲染列表：未变节点保持与 aggregate 同一实例引用（structural
 * sharing 到 Compose skip），流式期间只有末节点被 [stream] 覆盖。合并规则只存在于本
 * 文件的 [toPresentationSnapshot]，aggregate 不再提供 renderNodes。
 */
data class ConversationPresentationSnapshot(
    val conversationId: Uuid,
    val header: ConversationHeader,
    val nodes: List<MessageNode>,
    val stream: TurnStreamProjection?,
) {
    /**
     * 与 aggregate 的 internal currentMessages() 逐项等价：末节点在 [nodes] 里已被
     * [stream] 覆盖，因此这里只需要线性投影，不存在第二份合并规则。
     */
    fun currentMessages(): List<UIMessage> = nodes.map { it.currentMessage }
}

/**
 * aggregate 到 presentation 的唯一 projector。方向单一：presentation 永不回写 durable 事实，
 * 也不向调用方暴露 aggregate 引用。durable 树与流式草稿在此合并，历史节点保持共享引用。
 */
internal fun ConversationRuntimeSnapshot.toPresentationSnapshot(): ConversationPresentationSnapshot {
    val turn = stream
    val draft = turn?.assistantMessage
    val rendered = if (draft == null || durable.nodes.isEmpty()) {
        durable.nodes
    } else {
        val lastIndex = durable.nodes.lastIndex
        durable.nodes.mapIndexed { index, node ->
            if (index != lastIndex) node else node.copy(
                messages = listOf(draft),
                selectIndex = 0,
            )
        }
    }
    return ConversationPresentationSnapshot(
        conversationId = durable.conversationId,
        header = durable.header,
        nodes = rendered,
        stream = stream,
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
enum class ToolLivePhase {
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

val ToolLivePhase.isBusy: Boolean
    get() = this == ToolLivePhase.CALL_STREAMING || this == ToolLivePhase.EXECUTING

/**
 * Turn-level display state. [AWAITING_USER] covers both authorization approval and user-input
 * collection; the per-call [ToolLivePhase] distinguishes them. The durable Room encoding keeps
 * its historical AWAITING_APPROVAL name — this projection is the application-level meaning.
 *
 * 没有 IDLE 值：没有 active presentation（[ConversationPresentation.phase] 为 null）即 idle。
 */
enum class TurnLivePhase {
    PREPARING,
    MODEL_WAITING,
    MODEL_STREAMING,
    TOOL_PREPARING,
    AWAITING_USER,
    TOOL_EXECUTING,
    STOPPING,
}

/**
 * loop 的字符串阶段词汇 → 进程内 [TurnLivePhase]。reasoning/answer 在 Turn 级合并为
 * [TurnLivePhase.MODEL_STREAMING]（子助手卡片仍保留更细的 SubAssistantCallPhase）。
 * 未知词汇返回 null（不改变当前 phase），绝不合并回一个笼统的 GENERATING。
 */
internal fun turnLivePhaseOf(phase: String): TurnLivePhase? = when (phase) {
    "preparing" -> TurnLivePhase.PREPARING
    "model_waiting" -> TurnLivePhase.MODEL_WAITING
    "reasoning_streaming", "answer_streaming" -> TurnLivePhase.MODEL_STREAMING
    "tool_preparing" -> TurnLivePhase.TOOL_PREPARING
    "tool_executing" -> TurnLivePhase.TOOL_EXECUTING
    "between_steps" -> TurnLivePhase.PREPARING
    else -> null
}

/**
 * UI-facing turn runtime. It is derived from the private active turn and durable
 * snapshot, never persisted, and is not a write protocol.
 */
data class ConversationPresentation(
    val activeTurnId: Uuid?,
    val phase: TurnLivePhase?,
    val processingText: String?,
    val toolLivePhases: Map<ToolCallLocator, ToolLivePhase>,
    /** Latest terminated request identity, used to close a receipt wait without timing guesses. */
    val lastTerminatedRequestTurnId: Uuid? = null,
) {
    val isActive: Boolean get() = phase != null

    /** 正在推进的生成阶段（含等待模型与工具准备/执行），不含等待用户或正在停止。 */
    val isWorking: Boolean
        get() = phase != null && phase != TurnLivePhase.AWAITING_USER && phase != TurnLivePhase.STOPPING

    companion object {
        val IDLE = ConversationPresentation(
            activeTurnId = null,
            phase = null,
            processingText = null,
            toolLivePhases = emptyMap(),
        )
    }
}

internal fun resolveConversationPresentation(
    active: ActiveTurnPresentationFacts?,
    snapshot: ConversationRuntimeSnapshot,
    lastTerminatedRequestTurnId: Uuid? = null,
): ConversationPresentation {
    val requestPhase = active?.phase
    val stream = snapshot.stream
    val phase = when {
        requestPhase != null -> requestPhase
        stream == null -> null
        stream.toolLivePhases.values.any {
            it == ToolLivePhase.AWAITING_APPROVAL || it == ToolLivePhase.AWAITING_INPUT
        } -> TurnLivePhase.AWAITING_USER
        else -> TurnLivePhase.MODEL_STREAMING
    }
    val joined = active?.handle?.takeIf { handle ->
        stream != null &&
            stream.turnId == handle.turnId &&
            stream.epoch == handle.epoch
    }
    val toolLivePhases = when {
        joined != null -> stream?.toolLivePhases.orEmpty()
        requestPhase == TurnLivePhase.PREPARING ||
            requestPhase == TurnLivePhase.STOPPING -> emptyMap()
        stream != null && active == null -> stream.toolLivePhases
        else -> emptyMap()
    }
    return ConversationPresentation(
        activeTurnId = active?.turnId,
        phase = phase,
        processingText = active?.processingText,
        toolLivePhases = toolLivePhases,
        lastTerminatedRequestTurnId = lastTerminatedRequestTurnId,
    )
}

internal fun ConversationRuntime.currentTurnPresentation(): ConversationPresentation =
    resolveConversationPresentation(
        activeTurnPresentationFacts(),
        snapshot.value,
        lastTerminatedRequestTurnId(),
    )

/**
 * Streaming may expose a partial Tool part before the provider has finished its call. Existing
 * committed phases are retained while metadata/output deltas arrive; terminal output therefore
 * cannot make a running tool look completed before its checkpoint commits.
 */
internal fun TurnStreamProjection.withStreamingAssistant(assistant: UIMessage): TurnStreamProjection {
    require(assistant.id == assistantMessageId) {
        "streaming assistant ${assistant.id} does not match the owning assistant $assistantMessageId"
    }
    val nextPhases = assistant.getTools().associate { tool ->
        val locator = ToolCallLocator(assistantMessageId, tool.stepId, tool.localCallId)
        locator to (toolLivePhases[locator] ?: ToolLivePhase.CALL_STREAMING)
    }
    return copy(
        assistantMessage = assistant,
        toolLivePhases = nextPhases,
    )
}

/** Advances the active UI projection only from an already committed checkpoint command. */
internal fun TurnStreamProjection.afterCheckpoint(command: TurnCheckpoint): TurnStreamProjection {
    val assistant = command.assistantMessage.takeIf { it.id == assistantMessageId } ?: return this
    val phases = toolLivePhases.toMutableMap()
    fun setReadyPhases() {
        assistant.getTools().forEach { tool ->
            val locator = ToolCallLocator(assistantMessageId, tool.stepId, tool.localCallId)
            val existing = phases[locator]
            val phase = when {
                tool.isPending -> pendingPhaseOf(tool)
                tool.interactionState is ToolInteractionState.Denied -> ToolLivePhase.DENIED
                tool.interactionState is ToolInteractionState.Answered -> ToolLivePhase.ANSWERED
                existing == ToolLivePhase.FAILED || existing == ToolLivePhase.CANCELLED ||
                    existing == ToolLivePhase.INTERRUPTED || existing == ToolLivePhase.COMPLETED -> existing
                else -> ToolLivePhase.READY
            }
            phases[locator] = phase
        }
    }
    when (command) {
        is ModelResponseCheckpoint -> setReadyPhases()

        is ToolExecutionStartedCheckpoint,
        is ToolExecutionUpdatedCheckpoint,
        -> command.toolExecution?.let { execution ->
            if (execution.status == ToolExecutionStatus.STARTED) {
                phases[ToolCallLocator(assistantMessageId, execution.stepId, execution.localCallId)] =
                    ToolLivePhase.EXECUTING
            }
        }

        is ToolResultCheckpoint -> {
            require(command.toolResults.isNotEmpty()) {
                "tool-result checkpoint requires typed result facts"
            }
            require(command.toolResults.map { it.locator }.distinct().size == command.toolResults.size) {
                "tool-result checkpoint contains duplicate tool locators"
            }
            val tools = assistant.getTools()
            command.toolResults.forEach { result ->
                require(result.locator.assistantMessageId == assistantMessageId) {
                    "tool-result checkpoint targets a different assistant message"
                }
                val target = tools.firstOrNull {
                    it.stepId == result.locator.stepId && it.localCallId == result.locator.localCallId
                } ?: error("tool-result checkpoint targets a missing tool call")
                require(target.hasReplayResult) {
                    "tool-result checkpoint requires a Provider replay result"
                }
                phases[result.locator] = when (result.status) {
                    ToolResultStatus.COMPLETED -> ToolLivePhase.COMPLETED
                    ToolResultStatus.FAILED -> ToolLivePhase.FAILED
                    ToolResultStatus.DENIED -> ToolLivePhase.DENIED
                    ToolResultStatus.ANSWERED -> ToolLivePhase.ANSWERED
                    ToolResultStatus.CANCELLED -> ToolLivePhase.CANCELLED
                    ToolResultStatus.INTERRUPTED -> ToolLivePhase.INTERRUPTED
                    ToolResultStatus.UNKNOWN -> ToolLivePhase.FAILED
                }
            }
            command.toolExecution?.let { execution ->
                require(command.toolResults.size == 1 &&
                    command.toolResults.single().locator.localCallId == execution.localCallId
                ) {
                    "tool execution and result checkpoint target different tools"
                }
                val expected = when (execution.status) {
                    ToolExecutionStatus.COMPLETED -> ToolResultStatus.COMPLETED
                    ToolExecutionStatus.FAILED -> ToolResultStatus.FAILED
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
    // 修复 "tool interaction is no longer pending"：此前只同步 toolLivePhases，草稿的
    // Assistant 停留在流式版本（暂停工具仍是旧版），用户决策会读到非 Pending 状态而误报。
    // checkpoint 携带的 Assistant 是 committed 权威投影，流式草稿必须一并对齐；勿回退为仅同步 phases。
    return copy(assistantMessage = assistant, toolLivePhases = phases)
}

/**
 * 用户在审批/输入暂停点做出决策后，把该决策同步到流式草稿：durable reducer 已提交权威
 * 消息，这里让在途草稿的末条 Assistant 与相位一起对齐，避免下一个 delta 到达前草稿仍显示
 * Pending。相位映射与 durable reducer 保持一致。
 */
internal fun TurnStreamProjection.afterResolve(command: ResolveToolInteraction): TurnStreamProjection {
    val assistant = assistantMessage?.takeIf { it.id == command.messageId } ?: return this
    val updatedMessage = TurnTransition.resolveToolInteractionInMessage(assistant, command)
        ?: return this
    val committedPhase = when (command.interaction) {
        ToolInteractionState.Approved -> ToolLivePhase.READY
        is ToolInteractionState.Denied -> ToolLivePhase.DENIED
        is ToolInteractionState.Answered -> ToolLivePhase.ANSWERED
        else -> error("approval command contains a non-terminal decision")
    }
    return copy(
        assistantMessage = updatedMessage,
        toolLivePhases = toolLivePhases + (
            ToolCallLocator(command.messageId, command.stepId, command.localCallId) to committedPhase
        ),
    )
}

fun resolveToolLivePhase(tool: UIMessagePart.Tool, activePhase: ToolLivePhase?): ToolLivePhase =
    activePhase ?: when {
        tool.resultStatus != null -> phaseOfResultStatus(requireNotNull(tool.resultStatus))
        tool.isPending -> pendingPhaseOf(tool)
        tool.interactionState is ToolInteractionState.Denied -> ToolLivePhase.DENIED
        tool.interactionState is ToolInteractionState.Answered -> ToolLivePhase.ANSWERED
        else -> if (tool.hasReplayResult) ToolLivePhase.COMPLETED else ToolLivePhase.READY
    }

private fun phaseOfResultStatus(status: ToolResultStatus): ToolLivePhase = when (status) {
    ToolResultStatus.COMPLETED -> ToolLivePhase.COMPLETED
    ToolResultStatus.FAILED -> ToolLivePhase.FAILED
    ToolResultStatus.DENIED -> ToolLivePhase.DENIED
    ToolResultStatus.ANSWERED -> ToolLivePhase.ANSWERED
    ToolResultStatus.CANCELLED -> ToolLivePhase.CANCELLED
    ToolResultStatus.INTERRUPTED -> ToolLivePhase.INTERRUPTED
    ToolResultStatus.UNKNOWN -> ToolLivePhase.FAILED
}

/**
 * A paused call shows the interaction the Runtime captured when it paused: the typed
 * [ToolInteractionState] distinguishes authorization approval from user-input collection.
 */
private fun pendingPhaseOf(tool: UIMessagePart.Tool): ToolLivePhase =
    when (tool.interactionState) {
        ToolInteractionState.AwaitingInput -> ToolLivePhase.AWAITING_INPUT
        ToolInteractionState.AwaitingApproval -> ToolLivePhase.AWAITING_APPROVAL
        else -> error("pendingPhaseOf requires a pending interaction, got ${tool.interactionState}")
    }
