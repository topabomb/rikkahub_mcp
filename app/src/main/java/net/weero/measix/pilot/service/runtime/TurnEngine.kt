package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.ai.ToolExecutionEvent
import net.weero.measix.pilot.data.ai.transformers.AttachmentProjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.DocumentAsPromptTransformer
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.PlaceholderTransformer
import net.weero.measix.pilot.data.ai.transformers.PromptInjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TimeReminderTransformer
import net.weero.measix.pilot.data.ai.transformers.ToolArtifactReplayTransformer
import net.weero.measix.pilot.data.ai.transformers.WorkspaceReminderTransformer
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import kotlin.uuid.Uuid

/**
 * Turn 提交协议唯一实现。
 *
 * Master 与 Target 共用同一 [onCheckpoint] 回调（awaited durability boundary）与
 * [bind]（流式投影 + 终态 FinalizeTurn）。Application 层只装配请求并消费副作用事件。
 *
 * [onCheckpoint] 作为 `generateText(onCheckpoint=…)` 参数传入；[bind] 消费
 * GenerationChunk 流：Messages → applyStreamingDelta（永不落库），Finished / 异常 /
 * 取消 → FinalizeTurn。
 *
 * [prepareFinalize] 仅用于命令构造前的 Application IO（例如取消时并入 Child 消息）；
 * reducer 保持零 IO。
 */
class TurnEngine(
    private val runtime: ConversationRuntime,
    private val turnId: Uuid,
    private val assistantMessageId: Uuid,
    private val prepareFinalize: (suspend (TurnExecutionStatus, List<UIMessage>) -> List<UIMessage>)? = null,
) {
    /** turn 骨架启动结果：槽位 id + 可复用的既有 assistant 消息（null = 新建槽）。 */
    data class StartedTurn(
        val engine: TurnEngine,
        val assistantMessageId: Uuid,
        val resumableMessage: UIMessage?,
    )

    /**
     * turn 生命周期骨架唯一实现（Master 与 Target 共用）：
     * resumable 槽探测 → BeginTurn（打开 assistant 槽）→ 空 CommitCheckpoint（RUNNING
     * turn 事实，崩溃恢复扫描依据）→ 构造并返回绑定槽位的 engine。
     *
     * @param messages 生成输入（resumable 探测基于其末条消息）
     * @param resumeFilter 末条 assistant 消息可复用判定（Master 含审批恢复语义；
     *   默认为 Target 语义：存在未执行工具即可复用）
     * @param prepareFinalize 命令构造前的 Application IO（取消时并入 Child 消息等）
     */
    companion object {
        suspend fun start(
            runtime: ConversationRuntime,
            turnId: Uuid,
            messages: List<UIMessage>,
            resumeFilter: (UIMessage) -> Boolean = { message ->
                message.role == me.rerere.ai.core.MessageRole.ASSISTANT &&
                    message.getTools().any { !it.isExecuted }
            },
            prepareFinalize: (suspend (TurnExecutionStatus, List<UIMessage>) -> List<UIMessage>)? = null,
            onStart: Boolean = true,
        ): StartedTurn {
            val resumable = messages.lastOrNull()?.takeIf(resumeFilter)
            val slotId = resumable?.id ?: Uuid.random()
            runtime.submit(
                BeginTurn(
                    turnId = turnId,
                    assistantMessageId = slotId,
                    fromNodeId = null,
                    resume = resumable != null,
                    onStart = onStart,
                )
            )
            runtime.submit(
                CommitCheckpoint(
                    turnId = turnId,
                    assistantMessageId = slotId,
                    messages = emptyList(),
                    turnStatus = TurnExecutionStatus.RUNNING,
                    turnReason = null,
                    toolExecution = null,
                )
            )
            return StartedTurn(
                engine = TurnEngine(runtime, turnId, slotId, prepareFinalize),
                assistantMessageId = slotId,
                resumableMessage = resumable,
            )
        }
    }

    /** Last FinalizeTurn status submitted by this engine; null if none yet. */
    var lastFinalizedStatus: TurnExecutionStatus? = null
        private set

    /** True after a true terminal (not AWAITING_APPROVAL) was submitted. */
    fun hasSubmittedTerminal(): Boolean {
        val status = lastFinalizedStatus ?: return false
        return status != TurnExecutionStatus.AWAITING_APPROVAL
    }

    /** 交给 generateText(onCheckpoint=…) 的回调：将 GenerationCheckpoint 落为 CommitCheckpoint 命令。 */
    suspend fun onCheckpoint(checkpoint: net.weero.measix.pilot.data.ai.GenerationCheckpoint) {
        val turnStatus = checkpoint.kind.toTurnStatus()
        runtime.submit(
            CommitCheckpoint(
                turnId = turnId,
                assistantMessageId = assistantMessageId,
                messages = checkpoint.messages,
                turnStatus = turnStatus,
                turnReason = null,
                toolExecution = checkpoint.toolExecution.toToolExecutionEntity(turnId),
            )
        )
    }

    /** 把 GenerationChunk 流绑定到提交协议（冷流，collect 触发执行）。 */
    fun bind(source: Flow<GenerationChunk>): Flow<TurnEvent> = flow {
        var lastMessages: List<UIMessage> = emptyList()
        try {
            source.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        lastMessages = chunk.messages
                        runtime.applyStreamingDelta(turnId, assistantMessageId, chunk.messages)
                        emit(TurnEvent.Streaming(chunk.messages.lastOrNull(), chunk.messages))
                    }
                    is GenerationChunk.Phase -> {
                        emit(TurnEvent.Phase(chunk.phase, chunk.toolName))
                    }
                    is GenerationChunk.Checkpoint -> {
                        emit(TurnEvent.Checkpoint(chunk.kind))
                    }
                    is GenerationChunk.Finished -> {
                        val status = chunk.reason.toTerminalStatus()
                        submitStreamFinalize(
                            status = status,
                            lastMessages = lastMessages,
                            terminalReason = terminalReasonFor(status, chunk.reason, error = null),
                            closeInterruptedTools = false,
                        )
                        emit(TurnEvent.Finished(chunk.reason, null))
                    }
                }
            }
        } catch (error: CancellationException) {
            // first()/take() abort the collector with AbortFlowException; that is not a user cancel.
            if (!error.isCollectorAbort()) {
                submitStreamFinalize(
                    status = TurnExecutionStatus.CANCELLED,
                    lastMessages = lastMessages,
                    terminalReason = runtime.consumeCancelReason(turnId) ?: TurnTerminalReasons.USER_STOP,
                    closeInterruptedTools = prepareFinalize == null,
                )
            }
            throw error
        } catch (error: Exception) {
            val status = error.toFailedOrIncompleteStatus()
            submitStreamFinalize(
                status = status,
                lastMessages = lastMessages,
                terminalReason = terminalReasonFor(status, reason = null, error = error),
                closeInterruptedTools = prepareFinalize == null && status == TurnExecutionStatus.FAILED,
            )
            emit(TurnEvent.Finished(null, error))
        }
    }

    /**
     * 终态提交（Master/Target 共享）。bind 在 Finished/异常/取消时调用；
     * Target 的 ask_user 循环在本地把 AWAITING_APPROVAL 升级为 INCOMPLETE 时也可调用。
     */
    suspend fun submitFinalize(
        messages: List<UIMessage>?,
        terminalStatus: TurnExecutionStatus,
        terminalReason: String?,
        closeInterruptedTools: Boolean,
    ) {
        if (lastFinalizedStatus == terminalStatus && runtime.isTurnFinalized(turnId)) {
            return
        }
        runtime.submit(
            FinalizeTurn(
                turnId = turnId,
                assistantMessageId = assistantMessageId,
                messages = messages,
                terminalStatus = terminalStatus,
                terminalReason = terminalReason,
                closeInterruptedTools = closeInterruptedTools,
            )
        )
        lastFinalizedStatus = terminalStatus
        if (terminalStatus != TurnExecutionStatus.AWAITING_APPROVAL) {
            runtime.markTurnFinalized(turnId)
        }
    }

    private suspend fun submitStreamFinalize(
        status: TurnExecutionStatus,
        lastMessages: List<UIMessage>,
        terminalReason: String?,
        closeInterruptedTools: Boolean,
    ) {
        val prepared = prepareFinalize?.invoke(status, lastMessages)
        val messages = prepared?.takeIf { it.isNotEmpty() }
            ?: lastMessages.takeIf { it.isNotEmpty() }
        submitFinalize(
            messages = messages,
            terminalStatus = status,
            terminalReason = terminalReason,
            closeInterruptedTools = closeInterruptedTools,
        )
    }
}

/** Turn 生命周期事件。 */
sealed interface TurnEvent {
    /** 流式 delta：messages 为最新累积消息（只动 activeTurn，不落库）；lastMessage 为末条。 */
    data class Streaming(val lastMessage: UIMessage?, val messages: List<UIMessage> = emptyList()) : TurnEvent
    data class Phase(val phase: String, val toolName: String?) : TurnEvent
    data class Checkpoint(val kind: CheckpointKind) : TurnEvent
    data class Finished(val reason: FinishedReason?, val error: Throwable?) : TurnEvent
}

/**
 * Master/Target 共用管道装配清单。
 *
 * masterInput 顺序对齐 ChatService 装配段；targetInput 对齐 Coordinator 装配段；
 * BASE_OUTPUT 为两侧共用输出变换。
 */
class TurnPipelineFactory(
    private val templateTransformer: TemplateTransformer,
    private val workspaceReminderTransformer: WorkspaceReminderTransformer,
    private val toolArtifactReplayTransformer: ToolArtifactReplayTransformer?,
) {
    companion object {
        val BASE_INPUT: List<InputMessageTransformer> = listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
        )

        val BASE_OUTPUT: List<OutputMessageTransformer> = listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }

    /** Master 输入管道。 */
    fun masterInput(): List<InputMessageTransformer> = buildList {
        addAll(BASE_INPUT)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        toolArtifactReplayTransformer?.let(::add)
        add(AttachmentProjectionTransformer)
    }

    fun masterOutput(): List<OutputMessageTransformer> = BASE_OUTPUT

    /** Target 输入管道（无 toolArtifactReplay；AttachmentProjection 在 template 之前）。 */
    fun targetInput(): List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        AttachmentProjectionTransformer,
        templateTransformer,
        workspaceReminderTransformer,
    )

    fun targetOutput(): List<OutputMessageTransformer> = BASE_OUTPUT
}

private fun CancellationException.isCollectorAbort(): Boolean =
    javaClass.simpleName == "AbortFlowException"

private fun CheckpointKind.toTurnStatus(): TurnExecutionStatus = when (this) {
    CheckpointKind.TERMINAL_STATE -> TurnExecutionStatus.COMPLETED
    else -> TurnExecutionStatus.RUNNING
}

private fun FinishedReason?.toTerminalStatus(): TurnExecutionStatus = when (this) {
    FinishedReason.COMPLETED -> TurnExecutionStatus.COMPLETED
    FinishedReason.AWAITING_APPROVAL -> TurnExecutionStatus.AWAITING_APPROVAL
    FinishedReason.STEP_LIMIT_REACHED,
    FinishedReason.INTERACTION_LIMIT_REACHED,
    -> TurnExecutionStatus.INCOMPLETE
    null -> TurnExecutionStatus.INCOMPLETE
}

private fun Throwable.toFailedOrIncompleteStatus(): TurnExecutionStatus {
    return if (this is HttpException && terminalStatus == ProviderTerminalStatus.INCOMPLETE) {
        TurnExecutionStatus.INCOMPLETE
    } else {
        TurnExecutionStatus.FAILED
    }
}

private fun terminalReasonFor(
    status: TurnExecutionStatus,
    reason: FinishedReason?,
    error: Throwable?,
): String? = when (status) {
    TurnExecutionStatus.COMPLETED,
    TurnExecutionStatus.AWAITING_APPROVAL,
    -> null
    TurnExecutionStatus.CANCELLED -> TurnTerminalReasons.USER_STOP
    TurnExecutionStatus.FAILED -> if (error is HttpException) {
        TurnTerminalReasons.PROVIDER_FAILED
    } else {
        TurnTerminalReasons.RUNTIME_ERROR
    }
    TurnExecutionStatus.INCOMPLETE -> when (reason) {
        FinishedReason.STEP_LIMIT_REACHED -> TurnTerminalReasons.TOOL_LOOP_LIMIT
        FinishedReason.INTERACTION_LIMIT_REACHED -> TurnTerminalReasons.INTERACTION_LIMIT
        else -> TurnTerminalReasons.PROVIDER_INCOMPLETE
    }
    else -> null
}

private fun ToolExecutionEvent?.toToolExecutionEntity(turnId: Uuid): ToolExecutionEntity? {
    if (this == null) return null
    val now = System.currentTimeMillis()
    return ToolExecutionEntity(
        executionId = executionId,
        turnId = turnId.toString(),
        toolOrdinal = toolOrdinal,
        status = when (status) {
            net.weero.measix.pilot.data.ai.ToolExecutionEventStatus.COMPLETED -> ToolExecutionStatus.COMPLETED
            net.weero.measix.pilot.data.ai.ToolExecutionEventStatus.FAILED -> ToolExecutionStatus.FAILED
            net.weero.measix.pilot.data.ai.ToolExecutionEventStatus.STARTED -> ToolExecutionStatus.STARTED
        },
        reason = null,
        childConversationId = childConversationId,
        createdAt = now,
        updatedAt = now,
    )
}
