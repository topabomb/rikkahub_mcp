package net.weero.measix.pilot.service.turn
import net.weero.measix.pilot.data.ai.ToolExecutionFact
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.ToolResultFact
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.service.runtime.ModelResponseCheckpoint
import net.weero.measix.pilot.service.runtime.StepHandle
import net.weero.measix.pilot.service.runtime.ToolExecutionStartedCheckpoint
import net.weero.measix.pilot.service.runtime.ToolExecutionUpdatedCheckpoint
import net.weero.measix.pilot.service.runtime.ToolResultCheckpoint
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.service.runtime.TurnHandle

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.transformers.RequestMessageOriginTracker
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

internal class CheckpointCommitException(cause: Throwable) : RuntimeException(cause)

/**
 * 本次 Turn 运行持有但尚未 durable 落根的 Tool 资源租约集合。
 * checkpoint 提交成功后发布；失败或取消时按取得顺序逆序精确回滚。
 */
internal class UnpublishedResourceScope {
    private val resources = mutableListOf<ToolResourceLease>()

    fun register(resource: ToolResourceLease) {
        synchronized(resources) { resources += resource }
    }

    suspend fun publishAll() {
        withContext(NonCancellable) {
            while (true) {
                val resource = synchronized(resources) { resources.firstOrNull() } ?: break
                resource.publish()
                synchronized(resources) { resources.remove(resource) }
            }
        }
    }

    suspend fun discardAll(): Throwable? = withContext(NonCancellable) {
        val owned = synchronized(resources) {
            resources.toList().also { resources.clear() }
        }
        var failure: Throwable? = null
        owned.asReversed().forEach { resource ->
            try {
                resource.discard()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure
    }
}

/**
 * 一次 durable Turn 运行的共享状态：START 冻结的输入快照、跨 Step 与 Tool 批次复用的可变消息/累加器/
 * 资源作用域，以及 checkpoint 提交、流式投影与消息发布等唯一写协议。
 *
 * 唯一 Assistant 草稿由 [messages] 的末位承载，写入只经 [replaceMessages]；[accumulator] 只向它追加 Step 片段。
 * [commit] 是 durable 事实进入 reducer 的唯一入口；[publishMessages] 经 onStreamDelta 汇发布 turn-owned 投影，
 * 而审批暂停与 Child 交接等「草稿已变但无新内容可流式」的场景经 [handoffDraft] 只同步草稿给 turn owner、不发投影。
 */
internal class TurnRunState(
    val inputs: TurnRunInputs,
    val context: Context,
) {
    val turnContext = inputs.turnContext
    val model = turnContext.model.model
    val frozenProvider = turnContext.model.providerShape
    val assistant = turnContext.assistant
    val promptInputs = turnContext.promptInputs
    val toolsByName = turnContext.toolBindingsByName
    val toolDefinitions = turnContext.toolDefinitions
    val inputTransformers = inputs.inputTransformers
    val outputTransformers = inputs.outputTransformers
    val maxSteps = inputs.maxSteps
    val reportProcessingText = inputs.reportProcessingText
    val interactionAvailability = inputs.interactionAvailability
    val assistantMessageId = inputs.assistantMessageId
    val providerSessionId = inputs.providerSessionId
    val modelContextEntries = inputs.modelContextEntries
    val durableMessageLocators = inputs.durableMessageLocators
    val mediaCapabilities = turnContext.mediaCapabilities
    val handle = inputs.handle

    private val onCheckpoint = inputs.onCheckpoint

    var messages: List<UIMessage> = inputs.messages
        private set

    val accumulator = StepOutputAccumulator.fromDraft(
        assistantMessageId = assistantMessageId ?: Uuid.NIL,
        activeMessage = messages.lastOrNull(),
    )
    val unpublishedResources = UnpublishedResourceScope()
    var latestStreamingProjection: UIMessage? = null
    val outputOrigins = RequestMessageOriginTracker()

    // 循环退出结果，默认达到 step 上限（未显式完成或暂停即视为 Incomplete 终态）
    var result: TurnRunResult = TurnOutcome.Incomplete(TurnTerminalReasons.TOOL_LOOP_LIMIT)

    /** 无 Tool Final step 暂存的终态 Assistant 与末批压缩 patch：随唯一 FinalizeTurn 落定。 */
    var terminalAssistant: UIMessage? = null
        private set
    var terminalCompactionPatches: List<ToolOutputCompactionPatch> = emptyList()
        private set

    /**
     * 无 Tool 的 Final step：暂存已应用 compaction 的终态 Assistant 与末批 patch，交由唯一
     * [net.weero.measix.pilot.service.runtime.FinalizeTurn] 落定。此处不提交、不发布租约，
     * 也不把带本地文件的草稿提前交给 durable 槽——rooting 前不得让 UI/终态引用未根化资源。
     */
    fun stageTerminalModelOutput(
        checkpointMessages: List<UIMessage>,
        toolOutputCompactionPatches: List<ToolOutputCompactionPatch>,
    ) {
        replaceMessages(checkpointMessages)
        terminalAssistant = checkpointMessages.last()
        terminalCompactionPatches = toolOutputCompactionPatches
    }

    fun replaceMessages(next: List<UIMessage>) {
        messages = next
    }

    /** 阶段变化：转发给 turn owner 的 onPhase 汇。 */
    suspend fun sendPhase(phase: String, toolName: String? = null) = inputs.onPhase(phase, toolName)

    suspend fun publishMessages(next: List<UIMessage>) {
        inputs.onAssistantObserved(next.last())
        inputs.onStreamDelta(next.last())
    }

    /** 草稿已推进但本轮无新流式内容时，仅同步给 turn owner（审批暂停、Child 交接）。 */
    fun handoffDraft() {
        inputs.onAssistantObserved(messages.last())
    }

    /** 流式投影发布：对末位 Assistant 应用 streaming 变换后发布 turn-owned 投影。 */
    suspend fun publishStreamingProjection() {
        publishMessages(transformStreamingLast(messages))
    }

    /**
     * durable 事实进入 reducer 的唯一入口：提交 loop 直接构造的具名 [TurnCheckpoint] 变体。
     * [publishResources] 时，durable 落根、租约发布与 turn-owned 投影必须在取消恢复前一并完成。
     */
    private suspend fun commit(
        checkpoint: TurnCheckpoint,
        publishResources: Boolean,
        checkpointMessages: List<UIMessage>,
    ) {
        suspend fun doCommit() {
            onCheckpoint(checkpoint)
            if (publishResources) {
                inputs.onAssistantObserved(checkpointMessages.last())
                unpublishedResources.publishAll()
            }
        }
        try {
            if (publishResources) {
                coroutineContext.ensureActive()
                // A completed output owns these resources. Durable rooting, lease handoff and
                // the turn-owned projection must finish together before cancellation resumes.
                withContext(NonCancellable) { doCommit() }
            } else {
                doCommit()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckpointCommitException(e)
        }
    }

    /** 采样成功形成 durable model output：Step 用累加器当前 stepId；Turn 进入 RUNNING 或 AWAITING_USER。 */
    suspend fun commitModelResponse(
        toolOutputCompactionPatches: List<ToolOutputCompactionPatch> = emptyList(),
        turnStatus: TurnExecutionStatus = TurnExecutionStatus.RUNNING,
        publishResources: Boolean = false,
        checkpointMessages: List<UIMessage> = messages,
        stepId: Uuid = accumulator.currentStepId,
    ) = commit(
        ModelResponseCheckpoint(
            turn = handle,
            step = StepHandle(stepId),
            assistantMessage = checkpointMessages.last(),
            turnStatus = turnStatus,
            toolOutputCompactionPatches = toolOutputCompactionPatches,
        ),
        publishResources,
        checkpointMessages,
    )

    /** 不可逆副作用前的 STARTED 事实；Step 取执行事实自身。 */
    suspend fun commitToolExecutionStarted(
        toolExecution: ToolExecutionFact,
        publishResources: Boolean = false,
        checkpointMessages: List<UIMessage> = messages,
    ) = commit(
        ToolExecutionStartedCheckpoint(
            turn = handle,
            step = StepHandle(toolExecution.stepId),
            assistantMessage = checkpointMessages.last(),
            toolExecution = toolExecution,
        ),
        publishResources,
        checkpointMessages,
    )

    /** durable 中间事实（Child link / Tool metadata）；Step 优先取事实，否则当前 step。 */
    suspend fun commitToolStateUpdated(
        toolExecution: ToolExecutionFact? = null,
        checkpointMessages: List<UIMessage> = messages,
        stepId: Uuid = accumulator.currentStepId,
    ) = commit(
        ToolExecutionUpdatedCheckpoint(
            turn = handle,
            step = StepHandle(toolExecution?.stepId ?: stepId),
            assistantMessage = checkpointMessages.last(),
            toolExecution = toolExecution,
        ),
        publishResources = false,
        checkpointMessages = checkpointMessages,
    )

    /** 单或批 Tool Result；Step 优先取首个 result locator，其次执行事实，最后当前 step。 */
    suspend fun commitToolResult(
        toolResults: List<ToolResultFact>,
        toolExecution: ToolExecutionFact? = null,
        publishResources: Boolean = false,
        checkpointMessages: List<UIMessage> = messages,
    ) = commit(
        ToolResultCheckpoint(
            turn = handle,
            step = StepHandle(
                toolResults.firstOrNull()?.locator?.stepId
                    ?: toolExecution?.stepId
                    ?: accumulator.currentStepId,
            ),
            assistantMessage = checkpointMessages.last(),
            toolResults = toolResults,
            toolExecution = toolExecution,
        ),
        publishResources,
        checkpointMessages,
    )

    // 流式/终态输出变换不参与请求来源跟踪；请求级 tracker 只属于 generateInternal 的输入链。
    fun resourceTrackingTransformerContext() = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        promptInputs = promptInputs,
        requestOrigins = outputOrigins,
        mediaCapabilities = mediaCapabilities,
        registerUnpublishedResource = unpublishedResources::register,
    )

    // 流式单消息通道：历史消息 immutable，仅最后一条（active assistant 消息）进入变换。
    // 流式契约：流式期间历史消息进入 transformStreaming 的次数为 0。
    suspend fun transformStreamingLast(current: List<UIMessage>): List<UIMessage> {
        if (current.isEmpty()) return current
        val ctx = resourceTrackingTransformerContext()
        var last = current.last()
        for (transformer in outputTransformers) {
            if (transformer is StreamingMessageTransformer) {
                last = transformer.transformStreaming(ctx, last, latestStreamingProjection)
            }
        }
        latestStreamingProjection = last
        if (last === current.last()) return current
        return current.dropLast(1) + last
    }

    // 终态收口：step 完成时对最后一条消息应用 onStreamingFinish（reasoning 补时戳、base64 落盘）
    suspend fun finishStreamingLast(current: List<UIMessage>): List<UIMessage> {
        if (current.isEmpty()) return current
        val ctx = resourceTrackingTransformerContext()
        val last = finishStreamingProjection(
            raw = current.last(),
            previousProjection = latestStreamingProjection,
            ctx = ctx,
            transformers = outputTransformers,
        )
        if (last === current.last()) return current
        return current.dropLast(1) + last
    }

    fun applyReplacements(replacements: Map<Uuid, UIMessagePart.Tool>) {
        if (replacements.isEmpty()) return
        replaceMessages(messages.dropLast(1) + messages.last().let { msg ->
            msg.copy(parts = msg.parts.map { p ->
                if (p is UIMessagePart.Tool) replacements[p.localCallId] ?: p else p
            })
        })
    }
}

internal suspend fun finishStreamingProjection(
    raw: UIMessage,
    previousProjection: UIMessage?,
    ctx: TransformerContext,
    transformers: List<OutputMessageTransformer>,
): UIMessage = transformers.fold(raw) { current, transformer ->
    if (transformer is StreamingMessageTransformer) {
        transformer.onStreamingFinish(ctx, current, previousProjection)
    } else {
        current
    }
}
