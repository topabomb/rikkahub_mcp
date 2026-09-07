package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefBackfill
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
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
     * 本次命令追加的 append-only 模型上下文条目。只有 StartTurn 能填它：Draft materialization、
     * 普通 append、编辑与纯变体选择都必须为空，因为 context 只随一次真实 START 的 Assistant slot
     * 原子提交。
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

/**
 * 会话唯一 durable 事实源：header 与 nodes 分离，UpdateHeader 只改 header。这里只有
 * 已提交树，不含任何运行态——流式草稿与 active turn 身份在 [ConversationRuntimeSnapshot.stream]，
 * durable 提交只换 nodes，与只换 stream 的在途 delta 彼此独立。
 *
 * internal 是边界要求，不是可见性偏好：只有 command / runtime / request planning 读它，
 * UI 必须拿 [ConversationPresentationSnapshot]。否则 modelContextEntries 会
 * 随 aggregate 一起泄漏成第二事实源，靠“约定 UI 不读某个字段”维持边界。
 *
 * [modelContextEntries] 是 append-only 模型上下文（Disclosure Snapshot baseline）：不进入
 * [ConversationHeader]、不进入 presentation、不进入 FTS。UI 末节点合并已移到 projector。
 */
internal data class ConversationAggregateSnapshot(
    val conversationId: Uuid,
    val header: ConversationHeader,
    val nodes: List<MessageNode>,
    val modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
) {
    /**
     * 命令语义读取入口：当前选中消息序列，纯 durable 树（不含流式草稿）。
     */
    fun currentMessages(): List<UIMessage> = nodes.map { it.currentMessage }
}
