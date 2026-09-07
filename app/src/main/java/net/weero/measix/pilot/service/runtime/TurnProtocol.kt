package net.weero.measix.pilot.service.runtime

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.ToolExecutionFact
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.ToolResultFact
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import kotlin.uuid.Uuid

/**
 * Durable checkpoint 协议：一次 checkpoint 只携带它所属 Step 的 owning Assistant 与精确执行事实，
 * 绝不携带整条 branch 或 currentMessages。每个变体对应采样/执行生命周期里一个明确的持久化边界，
 * 语义由类型本身表达，不依赖额外的 kind 判别。
 */
sealed interface TurnCheckpoint : ConversationCommand {
    val turn: TurnHandle
    val step: StepHandle
    val assistantMessage: UIMessage
}

/** checkpoint 归属的 Step；与 owning Assistant transcript 里的 `UIMessagePart.Step` 同一身份。 */
data class StepHandle(
    val stepId: Uuid,
)

/**
 * 一次 Turn 的唯一运行分类：用户直接会话还是子助手 run。工具装配（过滤 Assistant Tools、保留
 * ask_user）、TTS 来源等按此分流；运行分类只有这一套。
 */
enum class TurnKind {
    /** 用户直接会话。 */
    USER,

    /** 子助手 run：过滤 Assistant Tools、保留 ask_user、TTS 归 SUB_ASSISTANT 来源。 */
    SUB_ASSISTANT,
}

/**
 * 采样成功并形成 durable model output：模型输出、Tool Calls、执行前即时失败、pending 交互与
 * Tool Output 窄压缩 patch 一并落定。Turn 进入 RUNNING（无 pending）或 AWAITING_USER（有 pending）。
 * UI 不得先于 DB 看到新 Pending。
 */
data class ModelResponseCheckpoint(
    override val turn: TurnHandle,
    override val step: StepHandle,
    override val assistantMessage: UIMessage,
    val turnStatus: TurnExecutionStatus,
    val toolOutputCompactionPatches: List<ToolOutputCompactionPatch> = emptyList(),
) : TurnCheckpoint

/**
 * 携带 `tool_execution` 行事实的 checkpoint（Started/Updated/Result）：reducer 与投影据此定位并推进
 * 单个 Call 的执行状态。ModelResponseCheckpoint 不写 tool_execution（模型输出与 pending 在 transcript 内）。
 */
sealed interface ToolExecutionCheckpoint : TurnCheckpoint {
    val toolExecution: ToolExecutionFact?
}

/** 不可逆副作用开始前的 STARTED 事实；成功返回后才真正执行 Tool。Assistant transcript 可以不变。 */
data class ToolExecutionStartedCheckpoint(
    override val turn: TurnHandle,
    override val step: StepHandle,
    override val assistantMessage: UIMessage,
    override val toolExecution: ToolExecutionFact,
) : ToolExecutionCheckpoint

/** 仅 durable 中间事实（Child link、新 Artifact root、unknown-sensitive 远端边界）或 Tool metadata 更新。进度只进流式投影。 */
data class ToolExecutionUpdatedCheckpoint(
    override val turn: TurnHandle,
    override val step: StepHandle,
    override val assistantMessage: UIMessage,
    override val toolExecution: ToolExecutionFact? = null,
) : ToolExecutionCheckpoint

/** 单或批 Tool Result：output、resultStatus、execution 终态与 Tool metadata。可携带同一 Step 的收尾。 */
data class ToolResultCheckpoint(
    override val turn: TurnHandle,
    override val step: StepHandle,
    override val assistantMessage: UIMessage,
    val toolResults: List<ToolResultFact>,
    override val toolExecution: ToolExecutionFact? = null,
) : ToolExecutionCheckpoint
