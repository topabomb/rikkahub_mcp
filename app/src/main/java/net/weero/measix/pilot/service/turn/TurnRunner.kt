package net.weero.measix.pilot.service.turn

import android.content.Context
import android.util.Log
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.request.RequestAssembler
import net.weero.measix.pilot.data.ai.request.RequestContextPlanner
import net.weero.measix.pilot.data.ai.request.DurableMessageLocator
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.ToolCallRuntime
import net.weero.measix.pilot.data.ai.tools.ToolOutputCompactionPlanner
import net.weero.measix.pilot.data.ai.tools.TurnInteractionCapability
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.service.runtime.TurnHandle
import kotlin.uuid.Uuid

private const val TAG = "TurnRunner"

/**
 * 一次 durable Turn 运行的输入：START 冻结的配置快照、权威 [handle]，以及 turn owner 注入的输出汇。
 *
 * loop 不再产出冷流，而是把每个边界同步回调给汇：流式投影 [onStreamDelta]、阶段变化 [onPhase]、
 * durable checkpoint [onCheckpoint]、运行结果 [onResult]。汇按调用顺序执行，天然形成背压
 * （loop 不会跑到投影之前）。[cancelReason] 只在 worker 被取消时读取用户停止原因。
 */
internal data class TurnRunInputs(
    /** The only model-visible configuration source for every step of this durable Turn. */
    val turnContext: TurnContext,
    /** 本次 durable Turn 的权威句柄；loop 用它构造具名 checkpoint 变体。 */
    val handle: TurnHandle,
    val messages: List<UIMessage>,
    val inputTransformers: List<InputMessageTransformer> = emptyList(),
    val outputTransformers: List<OutputMessageTransformer> = emptyList(),
    val maxSteps: Int = 256,
    val reportProcessingText: (String?) -> Unit = {},
    /** Which user interactions this run may pause for. */
    val interactionAvailability: TurnInteractionCapability = TurnInteractionCapability.FULL,
    val assistantMessageId: Uuid? = null,
    /** Turn START 提交的 disclosure baseline（冻结适用集合）；Provider step 不重新捕获。 */
    val modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
    /** message ID → durable 树位置；Planner 的 Durable origin 唯一来源。 */
    val durableMessageLocators: Map<Uuid, DurableMessageLocator> = emptyMap(),
    val providerSessionId: String? = null,
    /** Synchronous handoff of the current Assistant draft to the turn owner; independent of cancellable presentation delivery. */
    val onAssistantObserved: (UIMessage) -> Unit = {},
    val onCheckpoint: suspend (TurnCheckpoint) -> Unit,
    /** 流式投影：对末位 Assistant 应用 streaming delta（永不落库）并做 turn-owned 呈现。 */
    val onStreamDelta: suspend (UIMessage) -> Unit,
    /** 生成阶段变化；phase 使用稳定英文枚举字符串，toolName 仅 tool_executing 携带。 */
    val onPhase: suspend (String, String?) -> Unit = { _, _ -> },
    /** 结果提交：把 [TurnRunResult] 交给 turn owner——终态落 FinalizeTurn；暂停时 loop 已落 AWAITING_USER checkpoint。 */
    val onResult: suspend (TurnRunResult) -> Unit,
    /** worker 被取消时读取用户停止原因；返回 null 表示非用户主动停止。 */
    val cancelReason: () -> String? = { null },
)

/**
 * 多 Step Turn 循环：驱动 [StepRunner]（单 Step 采样）与 [ToolBatchRunner]（Tool 批次门控与串行执行），
 * 通过共享的 [TurnRunState] 维护唯一 Assistant 草稿、累加器与未发布资源作用域，并把每个 durable 边界
 * 交给 turn owner 的汇。取消或异常时按取得顺序逆序回滚未发布资源；正常、暂停与失败路径都交出结果并返回
 * [TurnRunResult]，取消路径提交 Cancelled 后重新抛出以传播取消。
 */
class TurnRunner(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val attachmentResolver: AttachmentResolver,
    private val toolOutputStore: ToolOutputStore,
) {
    private val toolCallRuntime = ToolCallRuntime(json)
    private val contextPlanner = RequestContextPlanner()
    private val requestAssembler = RequestAssembler()
    private val compactionPlanner = ToolOutputCompactionPlanner()
    private val stepRunner = StepRunner(context, providerManager, contextPlanner, requestAssembler, compactionPlanner, toolOutputStore, toolCallRuntime)
    private val toolBatchRunner = ToolBatchRunner(toolCallRuntime, attachmentResolver)

    fun resolveRequestMediaCapabilities(settings: Settings, model: Model): RequestMediaCapabilities {
        val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
        return providerManager.getProviderByType(providerSetting)
            .requestMediaCapabilities(providerSetting, model)
    }

    internal suspend fun run(inputs: TurnRunInputs): TurnRunResult = withContext(Dispatchers.IO) {
        val state = TurnRunState(inputs, context)
        val result = try {
            inputs.onAssistantObserved(state.messages.last())
            runLoop(state)
        } catch (error: CancellationException) {
            val outcome = TurnOutcome.Cancelled(inputs.cancelReason() ?: TurnTerminalReasons.USER_STOP)
            try {
                withContext(NonCancellable) { inputs.onResult(outcome) }
            } catch (finalizeFailure: Exception) {
                error.addSuppressed(finalizeFailure)
            }
            // 取消终态不 root Final step 资源：精确回滚未发布租约。
            state.unpublishedResources.discardAll()?.let { error.addSuppressed(it) }
            throw error
        } catch (error: Throwable) {
            TurnOutcome.fromFailure(error)
        }
        // 结果提交在生成 try 之外：终态落库失败直接向上抛给 owner 的兜底，不再被误判为生成失败而二次提交。
        // 无 Tool Final step 的压缩/输出租约以终态 FinalizeTurn 为 durable rooting 点：rooting 前尊重取消，
        // 之后把 FinalizeTurn 与租约发布作为 NonCancellable 原子收口，未 root 的资源精确回滚。
        var commitFailure: Throwable? = null
        try {
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                inputs.onResult(result)
                if (result is TurnOutcome.Completed) {
                    // durable rooting 完成：把带本地文件的终态草稿交给 durable 槽，再发布其租约。
                    result.assistantMessage?.let { inputs.onAssistantObserved(it) }
                    state.unpublishedResources.publishAll()
                }
            }
        } catch (error: CancellationException) {
            state.unpublishedResources.discardAll()?.let { error.addSuppressed(it) }
            throw error
        } catch (error: Throwable) {
            commitFailure = error
        }
        state.unpublishedResources.discardAll()?.let { cleanupFailure ->
            commitFailure = commitFailure?.apply { addSuppressed(cleanupFailure) } ?: cleanupFailure
        }
        commitFailure?.let { throw it }
        result
    }

    private suspend fun runLoop(state: TurnRunState): TurnRunResult {
        for (stepIndex in 0 until state.maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${state.model.id})")
            state.latestStreamingProjection = null

            val hasToolsAwaitingReplay =
                state.messages.lastOrNull()?.getTools()?.any { !it.hasReplayResult } == true
            // 没有上一轮待处理 ToolCall 时才请求模型；审批恢复时绝不提前发起下一 step。
            if (!hasToolsAwaitingReplay) {
                when (val stepOutcome = stepRunner.run(state)) {
                    is StepExecutionResult.Final -> {
                        // 无 Tool：唯一 durable 写是携带终态 Assistant 与末批压缩 patch 的 FinalizeTurn。
                        state.result = TurnOutcome.Completed(
                            assistantMessage = requireNotNull(state.terminalAssistant) {
                                "Final step must stage its terminal assistant before completion"
                            },
                            toolOutputCompactionPatches = state.terminalCompactionPatches,
                        )
                        break
                    }
                    is StepExecutionResult.Paused -> {
                        state.result = TurnPause(stepOutcome.pending)
                        break
                    }
                    is StepExecutionResult.ContinueToTools -> Unit
                }
            }
            // 一批 ToolCall 先经过统一门控；仍有 Pending 则暂停，否则全部决策完成后串行执行。
            when (val outcome = toolBatchRunner.run(state)) {
                is ToolBatchOutcome.Paused -> {
                    state.result = TurnPause(outcome.pending)
                    break
                }
                ToolBatchOutcome.Executed -> state.sendPhase("between_steps")
                ToolBatchOutcome.ImmediateOnly -> Unit
            }
        }
        return state.result
    }
}
