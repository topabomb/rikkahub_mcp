package net.weero.measix.pilot.service.runtime

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * Conversation 结构性修改的原子命令集。
 * 所有命令经 [ConversationRuntime.submit] 应用：reducer 零 IO 纯变换 → diff → 发布快照 → delta 持久化。
 */

/** 生成期命令（流式/checkpoint/终态；提交后 delta + executionFacts 同事务落库） */
sealed interface GenerationCommand : ConversationCommand

/** 结构与域命令（立即持久化；由 Application 层/UI 提交） */
sealed interface DomainCommand : ConversationCommand

/** 所有命令的统一密封接口 */
sealed interface ConversationCommand

/**
 * 流式增量：无锁、conflated、永不落库。仅更新 activeTurn.messages。
 */
data class ApplyStreamingDelta(val messages: List<UIMessage>) : GenerationCommand

/**
 * 创建/推进一个 turn（新消息开始时提交；resume 幂等——已存在同 turn 不重复）。
 * beginTurn: 打开 Assistant turn 槽（append 新节点或复用现有 terminal assistant 节点）。
 */
data class BeginTurn(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val fromNodeId: Uuid?,          // 上游用户节点 id（树分支定位）
    val resume: Boolean,            // true=复用现有 assistant 节点（重生成/审批恢复）
    val onStart: Boolean,           // 立即在终端显示空 assistant 节点
) : GenerationCommand

/**
 * checkpoint 持久化：提交时点完整 currentMessages + turn/tool 执行事实。
 */
data class CommitCheckpoint(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>,        // checkpoint 时点完整 currentMessages
    val turnStatus: TurnExecutionStatus,
    val turnReason: String?,
    val toolExecution: ToolExecutionEntity?,
) : GenerationCommand

/** 终态收口（finishReasoning / closeOpenTools / markAssistantTerminal 在 reducer 内纯变换） */
data class FinalizeTurn(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>?,       // null = 仅终态收口，不替换消息
    val terminalStatus: TurnExecutionStatus,   // COMPLETED / CANCELLED / FAILED / INCOMPLETE / INTERRUPTED
    val terminalReason: String?,
    val closeInterruptedTools: Boolean,   // 崩溃恢复场景：关闭未完工具
) : GenerationCommand

/** 追加用户消息（sendMessage 的 append 段） */
data class AppendUserMessage(val message: UIMessage) : DomainCommand

/** 编辑某节点的一个消息变体 */
data class EditMessageVariant(val nodeId: Uuid, val variant: UIMessage) : DomainCommand

/** 删除整条消息（按消息 id 定位节点，删除整个节点） */
data class DeleteMessage(val messageId: Uuid) : DomainCommand

/** 选择节点的一个变体（currentMessages 切换） */
data class SelectNodeVariant(val nodeId: Uuid, val selectIndex: Int) : DomainCommand

/** 截断到节点索引（含）——regenerate 前的树收缩 */
data class TruncateToNodeIndex(val nodeIndexInclusive: Int) : DomainCommand

/** 整树替换（压缩 / 恢复 / fork 载入 / 新会话初始化） */
data class ReplaceMessageTree(val nodes: List<MessageNode>) : DomainCommand

/** 头部窄列更新 */
data class UpdateHeader(
    val title: String? = null,
    val suggestions: List<String>? = null,
    val isPinned: Boolean? = null,
    val folderId: OptionalFolderId = OptionalFolderId.Keep,
    val assistantId: Uuid? = null,
    val customSystemPrompt: OptionalString = OptionalString.Keep,
    val modeInjectionIds: OptionalUuidSet = OptionalUuidSet.Keep,
    val workspaceCwd: OptionalString = OptionalString.Keep,
    val sanitizeForPersistence: Boolean = false,
) : DomainCommand

/** 工具审批状态更新（拒绝路径 cancelToolByUser / interruptPendingTool 语义在 reducer） */
data class UpdateToolApproval(
    val messageId: Uuid,
    val toolOrdinal: Int,
    val approvalState: ToolApprovalState,
) : DomainCommand

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
 * 收藏语义：MessageNode.isFavorite 为 @Transient（加载时由 FavoriteRepository 回填，不持久化），
 * 故不设收藏命令——收藏记录写 FavoriteRepository（现状保留），Runtime 下次 load 自然回填。
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
)

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
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>,
)

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

/**
 * 持久化边界形状转换（纯函数，显式调用）：供以 Conversation 为参数的域纯函数
 * （link 解析 / retention 计划等）使用。运行时状态不采用该形状——快照是唯一事实源。
 */
fun ConversationSnapshot.toConversation(): net.weero.measix.pilot.data.model.Conversation =
    net.weero.measix.pilot.data.model.Conversation(
        id = conversationId,
        assistantId = header.assistantId,
        title = header.title,
        messageNodes = renderNodes,
        chatSuggestions = header.chatSuggestions,
        isPinned = header.isPinned,
        createAt = java.time.Instant.ofEpochMilli(header.createAt),
        updateAt = java.time.Instant.ofEpochMilli(header.updateAt),
        customSystemPrompt = header.customSystemPrompt,
        modeInjectionIds = header.modeInjectionIds,
        workspaceCwd = header.workspaceCwd,
        folderId = header.folderId,
        parentConversationId = header.parentConversationId,
        newConversation = header.newConversation,
    )
