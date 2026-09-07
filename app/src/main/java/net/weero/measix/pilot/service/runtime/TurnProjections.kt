package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * Runtime 发布的合并视图：durable 聚合 + 唯一高频流式投影。它是 UI 观察的单一事实流；
 * durable 提交只换 [durable]，流式 delta 只换 [stream]，两条更新路径彼此独立。
 *
 * [durable] 是已提交树（header/nodes/modelContextEntries），不含任何运行态；[stream] 是当前
 * active Turn 的流式草稿，进程内、不落库、不进入写协议。
 */
internal data class ConversationRuntimeSnapshot(
    val durable: ConversationAggregateSnapshot,
    val stream: TurnStreamProjection?,
) {
    val conversationId: Uuid get() = durable.conversationId
}

/**
 * 唯一高频流式态：当前 active Turn 的身份 + 正在生成的 Assistant 草稿 + 每个 Tool Call 的
 * 稳定展示相位。草稿只含当前 Assistant message（`null` 表示 START 后尚未收到首个 delta），
 * 不复制整条 branch；历史节点保持与 [ConversationAggregateSnapshot] 同一引用（structural
 * sharing 到 Compose skip），流式期间只有末节点被覆盖。进程内、可重建、不参与写协议、永不落库。
 */
data class TurnStreamProjection(
    val epoch: Long,
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val assistantMessage: UIMessage?,
    val toolLivePhases: Map<ToolCallLocator, ToolLivePhase> = emptyMap(),
)
