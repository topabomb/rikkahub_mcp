package net.weero.measix.pilot.service.runtime

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefBackfill
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.ToolResultEvent
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.MessageNode
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Conversation 结构性修改的原子命令集。
 * 所有命令经 [ConversationCommandCoordinator] 应用：唯一 [ConversationTransition] 产生
 * snapshot/mutation/facts，事务成功后再发布 resident 快照。
 */

/** 所有命令的统一密封接口 */
sealed interface ConversationCommand

/** Header-only commands share one [ConversationTransition.applyHeader] implementation. */
internal sealed interface HeaderConversationCommand : ConversationCommand

/**
 * 一次新的 `START`：唯一能打开 Assistant request owner、原子提交 turn_execution 与可选
 * model-context entry 的 durable command（权威方案 §12.2）。
 *
 * 每个 StartTurn 都创建新的 Assistant owner；没有 slot 复用分支——审批 / ask-user
 * continuation 只走 `continueActive` 并保留原 handle，进程恢复只收口旧 Turn。
 * 因此一个 owner message 永远只对应一次 START 请求语义。
 *
 * 命令始终携带完整 canonical candidate，而不是调用者判定好的 nullable entry：执行锁内
 * [ConversationTransition] 重新计算目标 selected prefix（token 不同即 conflict），相同才对
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
    /** 调用者经 [ConversationTransition.planStartTarget] 得到的目标 selected prefix；锁内逐项复核。 */
    val expectedSelectedPrefixMessageIds: List<Uuid>,
    /** 合法 canonical Disclosure envelope；内容相对目标分支 baseline 变化才追加 entry。 */
    val modelContextCandidate: String,
    val epoch: Long = 0L,
) : ConversationCommand

/**
 * checkpoint 持久化：提交时点完整 currentMessages + turn/tool 执行事实。
 */
data class CommitCheckpoint(
    val handle: TurnHandle,
    val kind: CheckpointKind,
    val messages: List<UIMessage>,        // checkpoint 时点完整 currentMessages
    val turnStatus: TurnExecutionStatus,
    val turnReason: String?,
    val toolExecution: ToolExecutionEntity?,
    val toolResults: List<ToolResultEvent> = emptyList(),
    /** 与当前 Assistant 消息同事务提交的全部 Tool Output 窄压缩 patch。 */
    val toolOutputCompactionPatches: List<ToolOutputCompactionPatch> = emptyList(),
) : ConversationCommand

/** 终态收口（finishReasoning / closeOpenTools / markAssistantTerminal 在 reducer 内纯变换） */
data class FinalizeTurn(
    val handle: TurnHandle,
    val messages: List<UIMessage>?,       // null = 仅终态收口，不替换消息
    val terminalStatus: TurnExecutionStatus,   // COMPLETED / CANCELLED / FAILED / INCOMPLETE / INTERRUPTED
    val terminalReason: String?,
    val closeInterruptedTools: Boolean,   // 崩溃恢复场景：关闭未完工具
    val terminalDetail: String? = null,
    /** owning turn 的稳定终止时间；用于 durable Total，而不是复用中间 Provider step 的完成时间。 */
    val finishedAt: LocalDateTime =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
) : ConversationCommand

/** Process-recovery-only command. Normal turn owners must use [FinalizeTurn] with their handle. */
data class RecoverInterruptedTurn(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>?,
    val terminalReason: String,
    val closeInterruptedTools: Boolean,
    /** 恢复收口的稳定终止时间。 */
    val finishedAt: LocalDateTime =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
) : ConversationCommand

/** Appends the user message and, for the first user turn, its deterministic local title atomically. */
data class AppendUserMessage(
    val message: UIMessage,
    val initialTitle: String? = null,
) : ConversationCommand

/** 编辑某节点的一个消息变体 */
data class EditMessageVariant(val nodeId: Uuid, val variant: UIMessage) : ConversationCommand

/** 删除整条消息（按消息 id 定位节点，删除整个节点） */
data class DeleteMessage(val messageId: Uuid) : ConversationCommand

/** 选择节点的一个变体（currentMessages 切换） */
data class SelectNodeVariant(val nodeId: Uuid, val selectIndex: Int) : ConversationCommand

/** 截断到节点索引（含）——regenerate 前的树收缩 */
data class TruncateToNodeIndex(val nodeIndexInclusive: Int) : ConversationCommand

/** 整树替换（压缩 / 恢复 / fork 载入 / 新会话初始化） */
data class ReplaceMessageTree(val nodes: List<MessageNode>) : ConversationCommand

/** Adds exact missing attachment handles without accepting a replacement tree. */
data class BackfillAttachmentRefs(val backfills: List<AttachmentRefBackfill>) : ConversationCommand {
    init {
        require(backfills.isNotEmpty()) { "attachment backfill command is empty" }
    }
}

/** 头部窄列更新 */
data class UpdateHeader(
    val title: String? = null,
    val suggestions: List<String>? = null,
    val isPinned: Boolean? = null,
    val folderId: OptionalFolderId = OptionalFolderId.Keep,
    val customSystemPrompt: OptionalString = OptionalString.Keep,
    val modeInjectionIds: OptionalUuidSet = OptionalUuidSet.Keep,
    val workspaceCwd: OptionalString = OptionalString.Keep,
) : HeaderConversationCommand

/** Commits an asynchronous title only while the request's source title is still current. */
data class UpdateTitleIfCurrent(
    val expectedTitle: String,
    val title: String,
) : HeaderConversationCommand

/** Changes the conversation owner and clears its assistant-scoped folder only when the owner changes. */
data class MoveToAssistant(val assistantId: Uuid) : HeaderConversationCommand

/** Atomically flips the committed pin state inside the coordinator's per-conversation lock. */
data object TogglePinned : HeaderConversationCommand

/** The user's typed decision for one paused tool call. */
sealed interface ToolUserDecision {
    /** Permission granted for an Approval interaction. */
    data object Approve : ToolUserDecision

    /** Permission refused for an Approval interaction. */
    data class Deny(val reason: String = "") : ToolUserDecision

    /** Content collected for a UserInput interaction; the answer is the replay result. */
    data class Answer(val answer: String) : ToolUserDecision
}

/** Durable encoding of a terminal user decision on the Tool message part. */
internal fun ToolUserDecision.toApprovalState(): ToolApprovalState = when (this) {
    ToolUserDecision.Approve -> ToolApprovalState.Approved
    is ToolUserDecision.Deny -> ToolApprovalState.Denied(reason)
    is ToolUserDecision.Answer -> ToolApprovalState.Answered(answer)
}

/** A terminal HITL decision addressed by the owning message and stable tool ordinal. */
data class ResolveToolInteraction(
    val messageId: Uuid,
    val toolOrdinal: Int,
    val decision: ToolUserDecision,
    val handle: TurnHandle,
) : ConversationCommand {
    init {
        require(toolOrdinal >= 0) { "tool interaction ordinal must be non-negative" }
    }

    /** The durable encoding stays `ToolApprovalState`; it is not a second source of truth. */
    val approvalState: ToolApprovalState = decision.toApprovalState()
}

// ---- 三态包装（同文件） ----
sealed interface OptionalFolderId {
    data object Keep : OptionalFolderId
    data object Clear : OptionalFolderId
    data class SetTo(val id: Uuid) : OptionalFolderId
}

sealed interface OptionalString {
    data object Keep : OptionalString
    data class Set(val value: String?) : OptionalString
}

sealed interface OptionalUuidSet {
    data object Keep : OptionalUuidSet
    data class Set(val value: kotlin.collections.Set<Uuid>) : OptionalUuidSet
}

/**
 * 收藏是 FavoriteService 单写的独立查询投影，不属于 Conversation 聚合命令。
 * MessageNode.isFavorite 仅为不落 message_node 的投影字段。
 */

// ---- 持久化 delta 与 UI 投影 ----
internal data class ConversationMutation(
    val conversationId: Uuid,
    val headerPatch: ConversationHeaderPatch?,
    val upsertedNodes: List<MessageNode>,
    val deletedNodeIds: List<Uuid>,
    val updateAt: Long,
    /**
     * Each upserted node's position in the **new** message tree (not the index inside
     * [upsertedNodes]). applyMutation writes this value to message_node.node_index.
     * Size must equal [upsertedNodes].
     */
    val upsertedNodeIndices: List<Int>,
    /** Child conversations are durable but intentionally excluded from the user search index. */
    val indexForSearch: Boolean = true,
    /**
     * 本次命令追加的 append-only 模型上下文条目（权威方案 §12.2 窄 delta）。
     * 只有 StartTurn 能填它：Draft materialization、普通 append、编辑与纯变体选择都必须为空，
     * 因为 context 只随一次真实 START 的 Assistant slot 原子提交。
     */
    val insertedModelContextEntries: List<ConversationModelContextEntry> = emptyList(),
    /**
     * 以被删除 message variant 为 owner 或 anchor 的完整条目。删除整个 node 由 FK cascade
     * 收口，因此这里只表达“node 仍在、variant 没了”的精确删除；携带完整条目以便按
     * (owner_node_id, owner_message_id) 主键删除，不按全局 message id 误伤其他 Conversation。
     */
    val deletedModelContextEntries: List<ConversationModelContextEntry> = emptyList(),
    /** Only title/activity changes require updating metadata on existing FTS rows. */
    val searchMetadataChanged: Boolean = false,
    /**
     * FTS 索引用标题（Runtime 内存 header 为权威，随 delta 携带；
     * applyMutation 不得为取 title 回查 DB）。null = 测试/无标题场景，FTS 回退空串。
     */
    val titleForIndex: String? = null,
)

data class ConversationHeaderPatch(
    val title: String? = null,
    val chatSuggestions: List<String>? = null,
    val isPinned: Boolean? = null,
    val folderId: OptionalFolderId = OptionalFolderId.Keep,
    val assistantId: Uuid? = null,
    val customSystemPrompt: OptionalString = OptionalString.Keep,
    val modeInjectionIds: OptionalUuidSet = OptionalUuidSet.Keep,
    val workspaceCwd: OptionalString = OptionalString.Keep,
)

data class ExecutionFacts(
    val turn: TurnExecutionEntity?,
    val toolExecution: ToolExecutionEntity?,
    val turnOperation: TurnExecutionOperation = TurnExecutionOperation.ADVANCE,
)

enum class TurnExecutionOperation {
    START,
    ADVANCE,
    RECOVER,
}

/**
 * 事务是否需要执行的唯一判定。context delta 必须计入：一次只追加/只删除 model context 的
 * 命令不能因为 nodes 未变而被静默丢弃。
 */
internal fun ConversationMutation.hasChanges(): Boolean =
    headerPatch != null ||
    upsertedNodes.isNotEmpty() ||
    deletedNodeIds.isNotEmpty() ||
    insertedModelContextEntries.isNotEmpty() ||
    deletedModelContextEntries.isNotEmpty()

/** 会话头（snapshot 的一部分；header 变更不触碰 nodes） */
data class ConversationHeader(
    val id: Uuid,
    val title: String,
    val assistantId: Uuid,
    val folderId: Uuid?,
    val isPinned: Boolean,
    val chatSuggestions: List<String>,
    val customSystemPrompt: String?,
    val modeInjectionIds: Set<Uuid>,
    val workspaceCwd: String?,
    val parentConversationId: Uuid?,
    /** @Transient 运行态标记 */
    val newConversation: Boolean,
    val createAt: Long,
    val updateAt: Long,
)

data class ActiveTurnState(
    val epoch: Long,
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>,
    val toolCallPhases: Map<ToolCallLocator, ToolCallPhase> = emptyMap(),
)

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

/**
 * 会话唯一 durable 事实源（权威方案 §12.2）：header 与 nodes 分离（UpdateHeader 不再触碰
 * nodes），流式期间仅 activeTurn 变化（nodes 引用共享）。
 *
 * internal 是边界要求，不是可见性偏好：只有 command / runtime / request planning 读它，
 * UI 必须拿 [ConversationPresentationSnapshot]（§3.2、§17.7）。否则 modelContextEntries 会
 * 随 aggregate 一起泄漏成第二事实源，靠“约定 UI 不读某个字段”维持边界。
 *
 * [modelContextEntries] 是 append-only 模型上下文（Disclosure Snapshot baseline）：不进入
 * [ConversationHeader]、不进入 presentation、不进入 FTS。UI 末节点合并已移到 projector。
 */
internal data class ConversationAggregateSnapshot(
    val conversationId: Uuid,
    val header: ConversationHeader,
    val nodes: List<MessageNode>,
    val activeTurn: ActiveTurnState?,
    val modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
) {
    /**
     * 命令语义读取入口：当前选中消息序列（末 assistant 消息由 activeTurn 覆盖）。
     * 调用时一次 O(N) 派生——仅用于 turn 边界低频点，流式高频路径禁用。
     */
    fun currentMessages(): List<UIMessage> {
        val turn = activeTurn
        if (turn == null || turn.messages.isEmpty() || nodes.isEmpty()) {
            return nodes.map { it.currentMessage }
        }
        return nodes.subList(0, nodes.lastIndex).map { it.currentMessage } + turn.messages.last()
    }
}
