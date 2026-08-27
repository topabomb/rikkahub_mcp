package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefBackfill
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.MessageNode
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
 * 创建/推进一个 turn（新消息开始时提交；resume 幂等——已存在同 turn 不重复）。
 * 在同一 durable command 中打开 Assistant 槽并写入 RUNNING 执行事实。
 */
internal data class StartTurn(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val resume: Boolean,
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
) : ConversationCommand

/** 终态收口（finishReasoning / closeOpenTools / markAssistantTerminal 在 reducer 内纯变换） */
data class FinalizeTurn(
    val handle: TurnHandle,
    val messages: List<UIMessage>?,       // null = 仅终态收口，不替换消息
    val terminalStatus: TurnExecutionStatus,   // COMPLETED / CANCELLED / FAILED / INCOMPLETE / INTERRUPTED
    val terminalReason: String?,
    val closeInterruptedTools: Boolean,   // 崩溃恢复场景：关闭未完工具
    val terminalDetail: String? = null,
) : ConversationCommand

/** Process-recovery-only command. Normal turn owners must use [FinalizeTurn] with their handle. */
data class RecoverInterruptedTurn(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>?,
    val terminalReason: String,
    val closeInterruptedTools: Boolean,
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

/** A terminal HITL decision addressed by the owning message and stable tool ordinal. */
data class UpdateToolApproval(
    val messageId: Uuid,
    val toolOrdinal: Int,
    val approvalState: ToolApprovalState,
    val handle: TurnHandle,
) : ConversationCommand {
    init {
        require(toolOrdinal >= 0) { "tool approval ordinal must be non-negative" }
        require(
            approvalState is ToolApprovalState.Approved ||
                approvalState is ToolApprovalState.Denied ||
                approvalState is ToolApprovalState.Answered
        ) { "tool approval command requires a terminal user decision" }
    }
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
data class ConversationMutation(
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

fun ConversationMutation.hasChanges(): Boolean =
    headerPatch != null || upsertedNodes.isNotEmpty() || deletedNodeIds.isNotEmpty()

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
 * 会话唯一事实源：header 与 nodes 分离（UpdateHeader 不再触碰 nodes），
 * 流式期间仅 activeTurn 变化（nodes 引用共享）。
 */
data class ConversationSnapshot(
    val conversationId: Uuid,
    val header: ConversationHeader,
    val nodes: List<MessageNode>,
    val activeTurn: ActiveTurnState?,
) {
    /**
     * 渲染顺序：未变节点保持 [nodes] 中的同一实例引用（structural sharing → Compose skip）；
     * 流式期间仅末节点由 [activeTurn] 覆盖（每次求值一次 O(N) 浅拷贝，conflation 后每帧至多一次）。
     */
    val renderNodes: List<MessageNode>
        get() {
            val turn = activeTurn ?: return nodes
            if (turn.messages.isEmpty() || nodes.isEmpty()) return nodes
            val lastIndex = nodes.lastIndex
            return nodes.mapIndexed { i, n ->
                if (i != lastIndex) n else n.copy(
                    messages = listOf(turn.messages.last()),
                    selectIndex = 0,
                )
            }
        }

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
