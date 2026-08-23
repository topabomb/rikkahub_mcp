package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import me.rerere.ai.ui.UIMessage
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
 * Master 与 Target 共用同一 [TurnEngine.onCheckpoint] 回调（持久化边界）+ [bind] 流绑定
 * （提交协议），消除 ChatService.collect 与 Coordinator.collect 的双轨。
 *
 * [onCheckpoint] 作为 `generateText(onCheckpoint=…)` 参数传入，是 awaited durability
 * boundary；[bind] 消费 GenerationChunk 流做流式/Phase/Checkpoint/Finished 事件转发。
 */
class TurnEngine(
    private val runtime: ConversationRuntime,
    private val turnId: Uuid,
    private val assistantMessageId: Uuid,
) {
    /** 交给 generateText(onCheckpoint=…) 的回调：将 GenerationCheckpoint 落为 CommitCheckpoint 命令。 */
    suspend fun onCheckpoint(checkpoint: net.weero.measix.pilot.data.ai.GenerationCheckpoint) {
        val turnStatus = checkpoint.kind.toTurnStatus()
        runtime.submitGeneration(
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
        source.collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    runtime.applyStreamingDelta(turnId, assistantMessageId, chunk.messages)
                    emit(TurnEvent.Streaming(chunk.messages.lastOrNull(), chunk.messages))
                }
                is GenerationChunk.Phase -> {
                    emit(TurnEvent.Phase(chunk.phase, chunk.toolName))
                }
                is GenerationChunk.Checkpoint -> {
                    // 真正的落库已在 onCheckpoint 回调完成；此处仅发事件供调用方做副作用
                    emit(TurnEvent.Checkpoint(chunk.kind))
                }
                is GenerationChunk.Finished -> {
                    emit(TurnEvent.Finished(chunk.reason, null))
                }
            }
        }
    }.catch { error ->
        // 流内异常/取消：转发为 Finished 错误态，由调用方决定终态收口
        emit(TurnEvent.Finished(null, error))
    }

    /** 终态提交（Master/Target 共享；在调用方确定 outcome 后调用）。 */
    suspend fun submitFinalize(
        messages: List<UIMessage>?,
        terminalStatus: TurnExecutionStatus,
        terminalReason: String?,
        closeInterruptedTools: Boolean,
    ) {
        runtime.submitGeneration(
            FinalizeTurn(
                turnId = turnId,
                assistantMessageId = assistantMessageId,
                messages = messages,
                terminalStatus = terminalStatus,
                terminalReason = terminalReason,
                closeInterruptedTools = closeInterruptedTools,
            )
        )
        runtime.markTurnFinalized(turnId)
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
 * 装配等价性：masterInput 输出与现 ChatService 装配段逐项一致；targetInput 与现
 * Coordinator 硬编码列表一致；BASE_OUTPUT 与现顶层 outputTransformers 一致。
 * D2/D3 落地后，ChatService 与 Coordinator 不再各自硬编码 transformer 列表。
 */
class TurnPipelineFactory(
    private val templateTransformer: TemplateTransformer,
    private val workspaceReminderTransformer: WorkspaceReminderTransformer,
    private val toolArtifactReplayTransformer: ToolArtifactReplayTransformer?,
) {
    companion object {
        /** 现顶层 inputTransformers（ChatService + Coordinator 共用基底）。 */
        val BASE_INPUT: List<InputMessageTransformer> = listOf(
            TimeReminderTransformer,
            PromptInjectionTransformer,
            PlaceholderTransformer,
            DocumentAsPromptTransformer,
        )

        /** 现顶层 outputTransformers（Master 与 Target 共用基底）。 */
        val BASE_OUTPUT: List<OutputMessageTransformer> = listOf(
            ThinkTagTransformer,
            Base64ImageToLocalFileTransformer,
            RegexOutputTransformer,
        )
    }

    /** Master 输入管道（对齐 ChatService.handleMessageComplete 装配段）。 */
    fun masterInput(): List<InputMessageTransformer> = buildList {
        addAll(BASE_INPUT)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        toolArtifactReplayTransformer?.let(::add)
        add(AttachmentProjectionTransformer)
    }

    fun masterOutput(): List<OutputMessageTransformer> = BASE_OUTPUT

    /** Target 输入管道（对齐 Coordinator 硬编码列表）。 */
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

private fun CheckpointKind.toTurnStatus(): TurnExecutionStatus = when (this) {
    CheckpointKind.TERMINAL_STATE -> TurnExecutionStatus.COMPLETED
    else -> TurnExecutionStatus.RUNNING
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
        createdAt = now,
        updatedAt = now,
    )
}
