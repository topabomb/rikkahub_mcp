package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import me.rerere.ai.util.classifyProviderFailure
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
import net.weero.measix.pilot.service.TurnFinalization
import kotlin.uuid.Uuid

/**
 * Turn 提交协议唯一实现。
 *
 * Master 与 Target 共用同一 [onCheckpoint] 回调（awaited durability boundary）与
 * [bind]（流式投影 + 终态 FinalizeTurn）。Application 层只装配请求并消费副作用事件。
 *
 * [onCheckpoint] 作为 `GenerationRequest.onCheckpoint` 传入；[bind] 消费
 * GenerationChunk 流：Messages → applyStreamingDelta（永不落库），Finished / 异常 /
 * 取消 → FinalizeTurn。
 *
 * 取消/失败终态在提交 FinalizeTurn 前只经 [TurnFinalization.prepareOwnedTurnMessagesForFailure]
 * 做 Application IO（并入未完成工具与 Child 消息）；transition 保持零 IO。
 */
class TurnEngine(
    private val commandCoordinator: ConversationCommandCoordinator,
    private val runtime: ConversationRuntime,
    private val handle: TurnHandle,
    private val turnFinalization: TurnFinalization,
) {
    /** turn 骨架启动结果：槽位 id + 可复用的既有 assistant 消息（null = 新建槽）。 */
    data class StartedTurn(
        val engine: TurnEngine,
        val assistantMessageId: Uuid,
        val resumableMessage: UIMessage?,
    )

    /**
     * turn 生命周期骨架唯一实现（Master 与 Target 共用）：
     * resumable 槽探测 → StartTurn 单事务打开 assistant 槽并写入 RUNNING 事实 →
     * 构造并返回持有唯一 TurnHandle 的 engine。
     *
     * @param messages 生成输入（resumable 探测基于其末条消息）
     * @param resumeFilter 末条 assistant 消息可复用判定（Master 含审批恢复语义；
     *   默认为 Target 语义：存在未执行工具即可复用）
     * @param turnFinalization 取消/失败终态在提交前的唯一 Application IO
     */
    companion object {
        suspend fun start(
            commandCoordinator: ConversationCommandCoordinator,
            runtime: ConversationRuntime,
            turnId: Uuid,
            messages: List<UIMessage>,
            resumeFilter: (UIMessage) -> Boolean = { message ->
                message.role == me.rerere.ai.core.MessageRole.ASSISTANT &&
                    message.getTools().any { !it.isExecuted }
            },
            turnFinalization: TurnFinalization,
        ): StartedTurn {
            val resumable = messages.lastOrNull()?.takeIf(resumeFilter)
            val slotId = resumable?.id ?: Uuid.random()
            val handle = commandCoordinator.startTurn(runtime.id, turnId, slotId, resume = resumable != null)
            runtime.markRunning(handle)
            return StartedTurn(
                engine = TurnEngine(commandCoordinator, runtime, handle, turnFinalization),
                assistantMessageId = slotId,
                resumableMessage = resumable,
            )
        }

        /** Continues an approval-paused turn without creating a second execution fact or epoch. */
        fun continueActive(
            commandCoordinator: ConversationCommandCoordinator,
            runtime: ConversationRuntime,
            expectedTurnId: Uuid,
            messages: List<UIMessage>,
            turnFinalization: TurnFinalization,
        ): StartedTurn {
            val active = requireNotNull(runtime.snapshot.value.activeTurn) {
                "conversation ${runtime.id} has no approval-paused turn"
            }
            check(active.turnId == expectedTurnId) {
                "active turn ${active.turnId} does not match continuation $expectedTurnId"
            }
            val resumable = messages.lastOrNull()
            check(resumable?.id == active.assistantMessageId) {
                "active turn assistant slot ${active.assistantMessageId} is not the current message"
            }
            val handle = TurnHandle(runtime.id, active.epoch, active.turnId, active.assistantMessageId)
            runtime.markRunning(handle)
            return StartedTurn(
                engine = TurnEngine(
                    commandCoordinator = commandCoordinator,
                    runtime = runtime,
                    handle = handle,
                    turnFinalization = turnFinalization,
                ),
                assistantMessageId = active.assistantMessageId,
                resumableMessage = resumable,
            )
        }
    }

    private var submittedTerminalStatus: TurnExecutionStatus? = null

    /** 交给 GenerationRequest.onCheckpoint 的回调：将 GenerationCheckpoint 落为 CommitCheckpoint 命令。 */
    suspend fun onCheckpoint(checkpoint: net.weero.measix.pilot.data.ai.GenerationCheckpoint) {
        commandCoordinator.executeOrThrow(
            runtime.id,
            CommitCheckpoint(
                handle = handle,
                kind = checkpoint.kind,
                messages = checkpoint.messages,
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = checkpoint.toolExecution.toToolExecutionEntity(handle.turnId),
            ),
        )
    }

    /** 把 GenerationChunk 流绑定到提交协议（冷流，collect 触发执行）。 */
    fun bind(source: Flow<GenerationChunk>): Flow<TurnEvent> = flow {
        var lastMessages: List<UIMessage> = emptyList()
        var terminalSeen = false
        val sourceEvents = source.transform<GenerationChunk, TurnSourceEvent> { chunk ->
            emit(TurnSourceEvent.Chunk(chunk))
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(TurnSourceEvent.Failed(error))
        }
        try {
            sourceEvents.collect { event ->
                if (event is TurnSourceEvent.Failed) {
                    val outcome = TurnOutcome.fromFailure(event.error)
                    submitStreamFinalize(
                        outcome = outcome,
                        lastMessages = lastMessages,
                        closeInterruptedTools = false,
                    )
                    terminalSeen = true
                    emit(TurnEvent.Finished(outcome))
                    return@collect
                }
                when (val chunk = (event as TurnSourceEvent.Chunk).value) {
                    is GenerationChunk.Messages -> {
                        lastMessages = chunk.messages
                        check(runtime.applyStreamingDelta(handle, chunk.messages) == StreamingDeltaResult.APPLIED) {
                            "stale streaming delta for turn ${handle.turnId}"
                        }
                        emit(TurnEvent.Streaming(chunk.messages.lastOrNull(), chunk.messages))
                    }
                    is GenerationChunk.Phase -> {
                        emit(TurnEvent.Phase(chunk.phase, chunk.toolName))
                    }
                    is GenerationChunk.Finished -> {
                        val outcome = TurnOutcome.fromFinishedReason(chunk.reason)
                        submitStreamFinalize(
                            outcome = outcome,
                            lastMessages = lastMessages,
                            closeInterruptedTools = false,
                        )
                        terminalSeen = true
                        emit(TurnEvent.Finished(outcome))
                    }
                }
            }
            if (!terminalSeen) {
                val outcome = TurnOutcome.Incomplete(
                    terminalReason = TurnTerminalReasons.PROVIDER_INCOMPLETE,
                    terminalDetail = "Response stream ended without a terminal event.",
                )
                submitStreamFinalize(
                    outcome = outcome,
                    lastMessages = lastMessages,
                    closeInterruptedTools = false,
                )
                emit(TurnEvent.Finished(outcome))
            }
        } catch (error: CancellationException) {
            // first()/take() abort the collector with AbortFlowException; that is not a user cancel.
            if (!error.isCollectorAbort()) {
                val outcome = TurnOutcome.Cancelled(
                    runtime.peekCancelReason(handle.turnId) ?: TurnTerminalReasons.USER_STOP
                )
                try {
                    withContext(NonCancellable) {
                        submitStreamFinalize(
                            outcome = outcome,
                            lastMessages = lastMessages,
                            closeInterruptedTools = false,
                        )
                    }
                } catch (finalizationError: Exception) {
                    error.addSuppressed(finalizationError)
                }
            }
            throw error
        }
    }

    /** Finalizes an owner failure that happens outside the bound provider flow. */
    suspend fun finalizeOwnerFailure(
        outcome: TurnOutcome,
        messages: List<UIMessage>,
        closeInterruptedTools: Boolean = false,
    ) {
        require(outcome !is TurnOutcome.AwaitingApproval) { "an approval checkpoint is not an owner failure" }
        submitStreamFinalize(outcome, messages, closeInterruptedTools)
    }

    /**
     * 终态提交（Master/Target 共享）。bind 在 Finished/异常/取消时调用；
     * AWAITING_APPROVAL 是非终态 checkpoint，保留同一 handle；其余 outcome 才提交 FinalizeTurn。
     */
    suspend fun submitOutcome(
        messages: List<UIMessage>?,
        outcome: TurnOutcome,
        closeInterruptedTools: Boolean,
    ) {
        if (outcome is TurnOutcome.AwaitingApproval) {
            val checkpointMessages = requireNotNull(messages) {
                "approval checkpoint requires the current assistant messages"
            }
            commandCoordinator.executeOrThrow(
                runtime.id,
                CommitCheckpoint(
                    handle = handle,
                    kind = CheckpointKind.AWAITING_APPROVAL,
                    messages = checkpointMessages,
                    turnStatus = TurnExecutionStatus.AWAITING_APPROVAL,
                    turnReason = null,
                    toolExecution = null,
                ),
            )
            return
        }
        val previous = submittedTerminalStatus
        if (previous != null) {
            check(previous == outcome.status) {
                "turn ${handle.turnId} already finalized as $previous, cannot finalize as ${outcome.status}"
            }
            return
        }
        commandCoordinator.executeOrThrow(
            runtime.id,
            FinalizeTurn(
                handle = handle,
                messages = messages,
                terminalStatus = outcome.status,
                terminalReason = outcome.terminalReason,
                closeInterruptedTools = closeInterruptedTools,
                terminalDetail = outcome.terminalDetail,
            ),
        )
        submittedTerminalStatus = outcome.status
    }

    private suspend fun submitStreamFinalize(
        outcome: TurnOutcome,
        lastMessages: List<UIMessage>,
        closeInterruptedTools: Boolean,
    ) {
        if (outcome !is TurnOutcome.AwaitingApproval && submittedTerminalStatus != null) {
            submitOutcome(messages = null, outcome = outcome, closeInterruptedTools = closeInterruptedTools)
            return
        }
        val prepared = messagesForFinalize(outcome, lastMessages)
        val messages = prepared.takeIf { it.isNotEmpty() }
            ?: lastMessages.takeIf { it.isNotEmpty() }
        submitOutcome(
            messages = messages,
            outcome = outcome,
            closeInterruptedTools = closeInterruptedTools,
        )
    }

    private suspend fun messagesForFinalize(
        outcome: TurnOutcome,
        lastMessages: List<UIMessage>,
    ): List<UIMessage> {
        if (outcome !is TurnOutcome.Cancelled && outcome !is TurnOutcome.Failed) {
            return lastMessages
        }
        return turnFinalization.prepareOwnedTurnMessagesForFailure(
            snapshot = runtime.snapshot.value,
            handle = handle,
            latestMessages = lastMessages,
            reason = requireNotNull(outcome.terminalReason),
            cancelledByUser = outcome.terminalReason == TurnTerminalReasons.USER_STOP,
        )
    }
}

/** Turn 生命周期事件。 */
sealed interface TurnEvent {
    /** 流式 delta：messages 为最新累积消息（只动 activeTurn，不落库）；lastMessage 为末条。 */
    data class Streaming(val lastMessage: UIMessage?, val messages: List<UIMessage> = emptyList()) : TurnEvent
    data class Phase(val phase: String, val toolName: String?) : TurnEvent
    data class Finished(val outcome: TurnOutcome) : TurnEvent
}

sealed interface TurnOutcome {
    val status: TurnExecutionStatus
    val terminalReason: String?
    val terminalDetail: String?

    data object Completed : TurnOutcome {
        override val status = TurnExecutionStatus.COMPLETED
        override val terminalReason: String? = null
        override val terminalDetail: String? = null
    }

    data object AwaitingApproval : TurnOutcome {
        override val status = TurnExecutionStatus.AWAITING_APPROVAL
        override val terminalReason: String? = null
        override val terminalDetail: String? = null
    }

    data class Incomplete(
        override val terminalReason: String,
        override val terminalDetail: String? = null,
    ) : TurnOutcome {
        override val status = TurnExecutionStatus.INCOMPLETE
    }

    data class Cancelled(override val terminalReason: String) : TurnOutcome {
        override val status = TurnExecutionStatus.CANCELLED
        override val terminalDetail: String? = null
    }

    data class Failed(
        val error: Throwable,
        override val terminalReason: String,
        override val terminalDetail: String? = null,
    ) : TurnOutcome {
        override val status = TurnExecutionStatus.FAILED
    }

    companion object {
        fun fromFinishedReason(reason: FinishedReason): TurnOutcome = when (reason) {
            FinishedReason.COMPLETED -> Completed
            FinishedReason.AWAITING_APPROVAL -> AwaitingApproval
            FinishedReason.STEP_LIMIT_REACHED -> Incomplete(TurnTerminalReasons.TOOL_LOOP_LIMIT)
        }

        fun fromFailure(error: Throwable): TurnOutcome {
            val causeChain = errorCauseChain(error).toList()
            val incompleteProviderFailure = causeChain
                .filterIsInstance<HttpException>()
                .firstOrNull { it.terminalStatus == ProviderTerminalStatus.INCOMPLETE }
            val classified = classifyProviderFailure(error)
            return if (incompleteProviderFailure != null) {
                // A wrapper can describe the transport/runtime boundary while the nested
                // HttpException owns the protocol terminal detail. Preserve that provider
                // diagnostic instead of letting the wrapper hide why the response was incomplete.
                val incompleteDetail = classifyProviderFailure(incompleteProviderFailure).detail
                Incomplete(
                    terminalReason = TurnTerminalReasons.PROVIDER_INCOMPLETE,
                    terminalDetail = incompleteDetail.ifBlank { classified.detail },
                )
            } else {
                Failed(
                    error = error,
                    terminalReason = classified.kind.reason,
                    terminalDetail = classified.detail,
                )
            }
        }

        private fun errorCauseChain(error: Throwable): Sequence<Throwable> = sequence {
            val seen = HashSet<Throwable>()
            var current: Throwable? = error
            repeat(MAX_CAUSE_DEPTH) {
                val next = current ?: return@sequence
                if (!seen.add(next)) return@sequence
                yield(next)
                current = next.cause
            }
        }

        private const val MAX_CAUSE_DEPTH = 6
    }
}

/**
 * Master/Target 共用管道装配清单。
 *
 * masterInput 与 targetInput 分别定义 Master/Target 的固定装配顺序；
 * 两侧共享同一组显式注入的输出变换。
 */
class TurnPipelineFactory(
    private val templateTransformer: TemplateTransformer,
    private val workspaceReminderTransformer: WorkspaceReminderTransformer,
    private val toolArtifactReplayTransformer: ToolArtifactReplayTransformer?,
    private val attachmentProjectionTransformer: AttachmentProjectionTransformer,
    private val base64ImageToLocalFileTransformer: Base64ImageToLocalFileTransformer,
) {
    companion object {
        val BASE_INPUT: List<InputMessageTransformer> = listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
        )

    }

    private val commonOutput: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** Master 输入管道。 */
    fun masterInput(): List<InputMessageTransformer> = buildList {
        addAll(BASE_INPUT)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        toolArtifactReplayTransformer?.let(::add)
        add(attachmentProjectionTransformer)
    }

    fun masterOutput(): List<OutputMessageTransformer> = commonOutput

    /** Target 输入管道（无 toolArtifactReplay；AttachmentProjection 最后生成协议投影）。 */
    fun targetInput(): List<InputMessageTransformer> = buildList {
        addAll(BASE_INPUT)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        add(attachmentProjectionTransformer)
    }

    fun targetOutput(): List<OutputMessageTransformer> = commonOutput
}

private fun CancellationException.isCollectorAbort(): Boolean =
    javaClass.simpleName == "AbortFlowException"

private sealed interface TurnSourceEvent {
    data class Chunk(val value: GenerationChunk) : TurnSourceEvent
    data class Failed(val error: Throwable) : TurnSourceEvent
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
