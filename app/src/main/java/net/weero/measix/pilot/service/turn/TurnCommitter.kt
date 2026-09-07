package net.weero.measix.pilot.service.turn
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.StreamingDeltaResult
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.TurnKind
import net.weero.measix.pilot.service.runtime.TurnTransition

import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import me.rerere.ai.util.classifyProviderFailure
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.tools.PendingToolInteraction
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
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.service.turn.TurnFinalizer
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/**
 * Turn 提交协议唯一实现。
 *
 * Master 与 Target 共用同一组 turn owner 汇：[onCheckpoint]（awaited durability boundary）、
 * [publishStream]（流式投影，永不落库）、[commitRunResult]（终态落 FinalizeTurn；暂停已由 loop 的 AWAITING_USER checkpoint 落定）。
 * Application 层只装配 [TurnRunInputs] 并消费副作用。
 *
 * 取消/失败终态在提交 FinalizeTurn 前只经 [TurnFinalizer.prepareOwnedAssistantForFailure]
 * 做 Application IO（关闭未完成工具并桥接 Child 消息），产出终态 Assistant；transition 保持零 IO。
 */
class TurnCommitter(
    private val commandCoordinator: ConversationCommandCoordinator,
    private val runtime: ConversationRuntime,
    private val handle: TurnHandle,
    private val turnFinalizer: TurnFinalizer,
) {
    /** turn 骨架启动结果：新 Assistant owner slot id（START 恒新建；continuation 携带活动消息）与权威 handle。 */
    data class StartedTurn(
        val turnCommitter: TurnCommitter,
        val handle: TurnHandle,
        val assistantMessageId: Uuid,
        val resumableMessage: UIMessage?,
    )

    /**
     * turn 生命周期骨架唯一实现（Master 与 Target 共用）。
     *
     * `START` 没有 slot 复用分支：每个新 Turn 预生成全新的 Assistant node/message，由
     * [TurnTransition.planStartTarget] 得到结构变换后的目标 selected branch 与因果
     * USER anchor，随 [StartTurn] 在同一事务提交 Assistant slot、turn_execution 与可选
     * model-context entry。审批 / ask-user 恢复走 [continueActive]，保留原 handle。
     *
     * @param modelContextCandidate 本次 START 捕获的完整 canonical Disclosure candidate
     */
    companion object {
        suspend fun start(
            commandCoordinator: ConversationCommandCoordinator,
            runtime: ConversationRuntime,
            turnId: Uuid,
            modelContextCandidate: String,
            turnFinalizer: TurnFinalizer,
        ): StartedTurn {
            val command = TurnTransition.buildStartTurnCommand(
                current = runtime.durable,
                turnId = turnId,
                modelContextCandidate = modelContextCandidate,
            )
            val handle = commandCoordinator.startTurn(runtime.id, command)
            runtime.markRunning(handle)
            return StartedTurn(
                turnCommitter = TurnCommitter(commandCoordinator, runtime, handle, turnFinalizer),
                handle = handle,
                assistantMessageId = command.assistantMessageId,
                resumableMessage = null,
            )
        }

        /** Continues a user-paused turn without creating a second execution fact or epoch. */
        fun continueActive(
            commandCoordinator: ConversationCommandCoordinator,
            runtime: ConversationRuntime,
            expectedTurnId: Uuid,
            messages: List<UIMessage>,
            turnFinalizer: TurnFinalizer,
        ): StartedTurn {
            val active = requireNotNull(runtime.snapshot.value.stream) {
                "conversation ${runtime.id} has no user-paused turn"
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
                turnCommitter = TurnCommitter(
                    commandCoordinator = commandCoordinator,
                    runtime = runtime,
                    handle = handle,
                    turnFinalizer = turnFinalizer,
                ),
                handle = handle,
                assistantMessageId = active.assistantMessageId,
                resumableMessage = resumable,
            )
        }
    }

    private var submittedTerminalStatus: TurnExecutionStatus? = null
    private val latestAssistant = AtomicReference(runtime.durable.currentMessages().lastOrNull())

    /** Only the producer updates this slot; presentation delivery can be cancelled or delayed. */
    fun observeAssistant(assistantMessage: UIMessage) {
        require(assistantMessage.id == handle.assistantMessageId) {
            "observed assistant is not the owning assistant message"
        }
        latestAssistant.set(assistantMessage)
    }

    /** 交给 TurnRunInputs.onCheckpoint 的汇：把 loop 直接产出的具名 durable checkpoint 命令落库。 */
    suspend fun onCheckpoint(checkpoint: TurnCheckpoint) {
        commandCoordinator.executeOrThrow(runtime.id, checkpoint)
        // Durable checkpoint outranks later presentation delivery. A cancellation between commit
        // and the next delta must finalize from the committed projection, never an older one.
        latestAssistant.set(checkpoint.assistantMessage)
    }

    /** 流式投影汇：对末位 Assistant 应用 streaming delta（永不落库），拒绝陈旧 delta。 */
    internal suspend fun publishStream(assistantMessage: UIMessage) {
        check(runtime.applyStreamingDelta(handle, assistantMessage) == StreamingDeltaResult.APPLIED) {
            "stale streaming delta for turn ${handle.turnId}"
        }
    }

    /** Finalizes an owner failure that happens outside the generation loop. */
    suspend fun finalizeOwnerFailure(outcome: TurnOutcome) {
        commitRunResult(outcome)
    }

    /**
     * 终态提交（Master/Target 共享）。只处理终态 [TurnOutcome]：幂等收口后落 FinalizeTurn。
     */
    internal suspend fun submitOutcome(
        assistantMessage: UIMessage?,
        outcome: TurnOutcome,
    ) {
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
                assistantMessage = assistantMessage,
                terminalStatus = outcome.status,
                terminalReason = outcome.terminalReason,
                terminalDetail = outcome.terminalDetail,
                toolOutputCompactionPatches =
                    (outcome as? TurnOutcome.Completed)?.toolOutputCompactionPatches ?: emptyList(),
            ),
        )
        submittedTerminalStatus = outcome.status
    }

    /**
     * 结果汇：loop 交出一次运行的 [TurnRunResult]。[TurnPause] 的 AWAITING_USER 已由采样
     * [net.weero.measix.pilot.service.runtime.ModelResponseCheckpoint] 落定；[TurnOutcome] 才落 FinalizeTurn。
     */
    internal suspend fun commitRunResult(result: TurnRunResult) {
        when (result) {
            is TurnPause -> Unit
            is TurnOutcome -> submitTerminal(result)
        }
    }

    private suspend fun submitTerminal(outcome: TurnOutcome) {
        if (submittedTerminalStatus != null) {
            submitOutcome(assistantMessage = null, outcome = outcome)
            return
        }
        val lastAssistant = latestAssistant.get()
        // 无 Tool Final step 的终态 Assistant 由 loop 暂存并随 Completed 交出；其余终态经失败收口准备。
        val prepared = (outcome as? TurnOutcome.Completed)?.assistantMessage
            ?: assistantForFinalize(outcome, lastAssistant)
            ?: lastAssistant
        submitOutcome(
            assistantMessage = prepared,
            outcome = outcome,
        )
    }

    private suspend fun assistantForFinalize(
        outcome: TurnOutcome,
        lastAssistant: UIMessage?,
    ): UIMessage? {
        if (
            outcome !is TurnOutcome.Cancelled &&
            outcome !is TurnOutcome.Failed &&
            outcome !is TurnOutcome.Incomplete
        ) {
            return lastAssistant
        }
        return turnFinalizer.prepareOwnedAssistantForFailure(
            snapshot = runtime.snapshot.value,
            handle = handle,
            latestAssistant = lastAssistant,
            reason = requireNotNull(outcome.terminalReason),
            cancelledByUser = outcome.terminalReason == TurnTerminalReasons.USER_STOP,
        )
    }
}

/**
 * loop 一次运行的结果：终态 [TurnOutcome]，或因等待用户交互而暂停的非终态 [TurnPause]。
 * 暂停不是终态——同一 Turn、同一 stepId 继续，故与终态分列两个子类型。
 */
sealed interface TurnRunResult

/**
 * 因等待用户交互而暂停。[pendingInteractions] 非空，按提交顺序排列；消费者直接用每条携带的 locator 定位，
 * 不回消息里扫描重建，也不依赖任何已落盘的运行时元数据。
 */
data class TurnPause(
    val pendingInteractions: List<PendingToolInteraction>,
) : TurnRunResult {
    init {
        require(pendingInteractions.isNotEmpty()) { "TurnPause requires pending interactions" }
    }
}

sealed interface TurnOutcome : TurnRunResult {
    val status: TurnExecutionStatus
    val terminalReason: String?
    val terminalDetail: String?

    data class Completed(
        /** 无 Tool Final step 暂存的终态 Assistant；loop 恒提供，终态唯一 durable 写据此落定。 */
        val assistantMessage: UIMessage,
        /** 无 Tool Final step 的末批窄压缩 patch，随唯一 [FinalizeTurn] 落定。 */
        val toolOutputCompactionPatches: List<ToolOutputCompactionPatch> = emptyList(),
    ) : TurnOutcome {
        override val status = TurnExecutionStatus.COMPLETED
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
 * 单一装配 owner：`input(turnKind)` 按运行分类给出固定顺序（Tool Artifact 重放仅 USER 侧需要），
 * `output()` 为两侧共享的输出变换。用户会话与子助手注入同一个实例，不再有各自的私有装配。
 */
class TurnPipelineFactory(
    private val templateTransformer: TemplateTransformer,
    private val workspaceReminderTransformer: WorkspaceReminderTransformer,
    private val toolArtifactReplayTransformer: ToolArtifactReplayTransformer,
    private val attachmentProjectionTransformer: AttachmentProjectionTransformer,
    private val base64ImageToLocalFileTransformer: Base64ImageToLocalFileTransformer,
    private val documentAsPromptTransformer: DocumentAsPromptTransformer,
) {
    private val baseInput: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        documentAsPromptTransformer,
    )

    internal fun baseInput(): List<InputMessageTransformer> = baseInput

    private val commonOutput: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /**
     * 输入管道。USER 追加 Tool Artifact 重放（历史 inline tool result 的 artifact 引用需重新物化给模型）；
     * SUB_ASSISTANT 不重放（Child clone 的协议投影由 AttachmentProjection 负责），其余顺序一致。
     */
    fun input(turnKind: TurnKind): List<InputMessageTransformer> = buildList {
        addAll(baseInput)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        if (turnKind == TurnKind.USER) {
            add(toolArtifactReplayTransformer)
        }
        add(attachmentProjectionTransformer)
    }

    fun output(): List<OutputMessageTransformer> = commonOutput
}
