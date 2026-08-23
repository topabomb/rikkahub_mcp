package net.weero.measix.pilot.service.runtime

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.model.Conversation
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
 * 流式增量：无锁、conflated、永不落库。
 * 仅更新 activeTurn.messages 并派生 conversation 投影（旧消费方继续看到更新）。
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
 * checkpoint 持久化（= 现 persistTurnCheckpoint 语义）：
 * 提交 checkpoint 时点完整 currentMessages + turn/tool 执行事实。
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

/** 会话头投影（snapshot 的一部分） */
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
    /** @Transient 运行态标记；snapshot 派生 conversation 投影时必须保留 */
    val newConversation: Boolean,
    val createAt: Long,
    val updateAt: Long,
)

data class ActiveTurnState(
    val turnId: Uuid,
    val assistantMessageId: Uuid,
    val messages: List<UIMessage>,
)

class ConversationSnapshot(
    val conversationId: Uuid,
    val header: ConversationHeader,
    val nodes: List<MessageNode>,
    val activeTurn: ActiveTurnState?,
) {
    /** 兼容投影：header + nodes（含 activeTurn 覆盖）派生；newConversation 保留 */
    val conversation: Conversation
        get() = Conversation(
            id = conversationId,
            assistantId = header.assistantId,
            title = header.title,
            messageNodes = buildList {
                addAll(nodes)
                activeTurn?.let { turn ->
                    // 最后一个 assistant 节点当前消息替换为 activeTurn 内容
                    val lastIdx = lastIndex
                    if (lastIdx >= 0) {
                        val last = this[lastIdx]
                        if (turn.messages.isNotEmpty()) {
                            this[lastIdx] = last.copy(
                                messages = listOf(turn.messages.last()),
                                selectIndex = 0,
                            )
                        }
                    }
                }
            },
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
}
