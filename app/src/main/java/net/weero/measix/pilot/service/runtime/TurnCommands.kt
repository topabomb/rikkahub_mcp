package net.weero.measix.pilot.service.runtime

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Turn 命令：打开 Assistant request owner、推进 checkpoint、收口终态、恢复中断 Turn、落定一次
 * HITL 决策。根类型仍是 [ConversationCommand]，由同一个 [ConversationCommandCoordinator] 分发；
 * transcript 的 reducer 归 [TurnTransition]，结构（header/tree/variant）reducer 归 [ConversationTransition]。
 */

/**
 * 一次新的 `START`：唯一能打开 Assistant request owner、原子提交 turn_execution 与可选
 * model-context entry 的 durable command。
 *
 * 每个 StartTurn 都创建新的 Assistant owner；没有 slot 复用分支——审批 / ask-user
 * continuation 只走 `continueActive` 并保留原 handle，进程恢复只收口旧 Turn。
 * 因此一个 owner message 永远只对应一次 START 请求语义。
 *
 * 命令始终携带完整 canonical candidate，而不是调用者判定好的 nullable entry：执行锁内
 * [TurnTransition] 重新计算目标 selected prefix（token 不同即 conflict），相同才对
 * 当前适用 baseline 做 exact-content comparison 并决定是否插入——branch CAS 与判等属于
 * 同一个纯 Transition / transaction 计划，不存在 stale null。
 */
internal data class StartTurn(
    val turnId: Uuid,
    /** 预生成：新 owner node（variant 追加场景由 planStartTarget 解析为既有 Assistant node）。 */
    val assistantNodeId: Uuid,
    /** 预生成的 owner Assistant message variant。 */
    val assistantMessageId: Uuid,
    /** 该请求实际使用的因果 USER variant（entry 在 model view 中位于它之前）。 */
    val anchorNodeId: Uuid,
    val anchorMessageId: Uuid,
    /** 调用者经 [TurnTransition.planStartTarget] 得到的目标 selected prefix；锁内逐项复核。 */
    val expectedSelectedPrefixMessageIds: List<Uuid>,
    /** 合法 canonical Disclosure envelope；内容相对目标分支 baseline 变化才追加 entry。 */
    val modelContextCandidate: String,
    val epoch: Long = 0L,
) : ConversationCommand

/**
 * 终态收口：只带 owning Assistant（`null` = 仅收口既有 durable 状态，不替换消息）。
 * finishReasoning / markAssistantTerminal / markAssistantFinishedAt 在 reducer 内纯变换；
 * 未完工具的收口由 TurnFinalizer 在提交前写入该 Assistant 消息，reducer 不再关闭工具。
 * 无 Tool 的 Final step 不发独立 [ModelResponseCheckpoint]：modelResult、Step Final、Turn COMPLETED
 * 与末批 Tool Output 压缩 patch 一次落在此命令。
 */
data class FinalizeTurn(
    val handle: TurnHandle,
    val assistantMessage: UIMessage?,
    val terminalStatus: TurnExecutionStatus,   // COMPLETED / CANCELLED / FAILED / INCOMPLETE / INTERRUPTED
    val terminalReason: String?,
    val terminalDetail: String? = null,
    /** 末批窄压缩 patch：active patch 已并入 [assistantMessage]，historical patch 由 reducer 改写历史节点。 */
    val toolOutputCompactionPatches: List<ToolOutputCompactionPatch> = emptyList(),
    /** owning turn 的稳定终止时间；用于 durable Total，而不是复用中间 Provider step 的完成时间。 */
    val finishedAt: LocalDateTime =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
) : ConversationCommand

/** Process-recovery-only command. Normal turn owners must use [FinalizeTurn] with their handle. */
data class RecoverInterruptedTurn(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    /** 恢复时已收口未完工具的 owning Assistant；`null` = 仅按 durable 现状收口。 */
    val assistantMessage: UIMessage?,
    val terminalReason: String,
    /** 恢复收口的稳定终止时间。 */
    val finishedAt: LocalDateTime =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
) : ConversationCommand

/** The user's typed decision for one paused tool call. */
sealed interface ToolInteractionDecision {
    /** Permission granted for an Approval interaction. */
    data object Approve : ToolInteractionDecision

    /** Permission refused for an Approval interaction. */
    data class Deny(val reason: String = "") : ToolInteractionDecision

    /** Content collected for a UserInput interaction; the answer is the replay result. */
    data class Answer(val answer: String) : ToolInteractionDecision
}

/** Durable encoding of a terminal user decision on the Tool message part. */
internal fun ToolInteractionDecision.toInteractionState(): ToolInteractionState = when (this) {
    ToolInteractionDecision.Approve -> ToolInteractionState.Approved
    is ToolInteractionDecision.Deny -> ToolInteractionState.Denied(reason)
    is ToolInteractionDecision.Answer -> ToolInteractionState.Answered(answer)
}

/** A terminal HITL decision addressed by the owning message and the call's stable locator. */
data class ResolveToolInteraction(
    val messageId: Uuid,
    val stepId: Uuid,
    val localCallId: Uuid,
    val decision: ToolInteractionDecision,
    val handle: TurnHandle,
) : ConversationCommand {
    /** The durable interaction state stays `ToolInteractionState`; it is not a second source of truth. */
    val interaction: ToolInteractionState = decision.toInteractionState()
}

data class TurnHandle(
    val conversationId: Uuid,
    val epoch: Long,
    val turnId: Uuid,
    val assistantMessageId: Uuid,
)

enum class StreamingDeltaResult {
    APPLIED,
    STALE_TURN,
}
