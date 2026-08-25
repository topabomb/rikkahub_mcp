package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefBackfill
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.finishReasoning
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * Conversation 结构性修改的纯函数 reducer。
 *
 * 状态形状为 [ConversationSnapshot]（header/nodes 分离）：UpdateHeader 只动 header，
 * 树命令只动 nodes——两类变更互不触碰，structural sharing 保持（未被命令触及的
 * MessageNode 保持同一实例引用，是 delta 持久化与 Compose skip 的共同前提）。
 *
 * 约束：reducer 零 IO。
 *
 * 说明：closeOpenTools 中"加载 Child 子助手消息"是 IO 操作，不属于 reducer
 * （保持零 IO）；reducer 只做纯的 tool 收口。Child 消息并入由 Application 层
 * 在构造 FinalizeTurn 前完成。
 */
internal object ConversationReducer {

    fun reduce(current: ConversationSnapshot, command: ConversationCommand): ConversationSnapshot = when (command) {
        is StartTurn -> startTurn(current, command)
        is CommitCheckpoint -> commitCheckpoint(current, command)
        is FinalizeTurn -> finalizeTurn(current, command)
        is RecoverInterruptedTurn -> recoverInterruptedTurn(current, command)
        is ReconcileOrphanedTurnExecution -> reconcileOrphanedTurnExecution(current, command)
        is AppendUserMessage -> appendUser(current, command)
        is EditMessageVariant -> editVariant(current, command)
        is DeleteMessage -> deleteMessage(current, command)
        is SelectNodeVariant -> selectNodeVariant(current, command)
        is TruncateToNodeIndex -> truncateTo(current, command.nodeIndexInclusive)
        is ReplaceMessageTree -> current.copy(nodes = command.nodes)
        is BackfillAttachmentRefs -> backfillAttachmentRefs(current, command.backfills)
        is UpdateHeader -> current.copy(header = reduceHeader(current.header, command))
        is UpdateTitleIfCurrent -> if (current.header.title == command.expectedTitle) {
            current.copy(header = current.header.copy(title = command.title))
        } else {
            current
        }
        is MoveToAssistant -> current.copy(header = reduceHeader(current.header, command))
        TogglePinned -> current.copy(header = reduceHeader(current.header, TogglePinned))
        is UpdateToolApproval -> updateToolApproval(current, command)
    }

    // ---- StartTurn：在节点树末端追加/复用 assistant 槽 ----

    private fun startTurn(current: ConversationSnapshot, command: StartTurn): ConversationSnapshot {
        if (current.nodes.isEmpty()) {
            val node = MessageNode.of(emptyAssistantMessage(command.assistantMessageId))
            return current.copy(nodes = listOf(node))
        }
        val last = current.nodes.last()
        val lastMsg = last.currentMessage
        // resume：末尾已是同 assistant 节点 → 直接复用（幂等）
        if (command.resume && lastMsg.id == command.assistantMessageId && lastMsg.role == MessageRole.ASSISTANT) {
            return current
        }
        val nodes = current.nodes.toMutableList()
        if (lastMsg.role == MessageRole.ASSISTANT && lastMsg.terminalStatus == null) {
            // 追加到现有未闭合 assistant 节点
            val msg = emptyAssistantMessage(command.assistantMessageId)
            nodes[nodes.lastIndex] = last.copy(messages = last.messages + msg, selectIndex = last.messages.size)
        } else {
            nodes.add(MessageNode.of(emptyAssistantMessage(command.assistantMessageId)))
        }
        return current.copy(nodes = nodes)
    }

    // ---- 消息替换（checkpoint / 终态）----

    /** Checkpoint 只允许替换 active assistant；历史消息即使随请求传入也不参与遍历或复制。 */
    private fun replaceMessages(current: ConversationSnapshot, assistantMessageId: Uuid, messages: List<UIMessage>): ConversationSnapshot {
        val message = messages.lastOrNull() ?: return current
        require(message.id == assistantMessageId) {
            "Checkpoint payload does not end with the active assistant message"
        }
        return current.replaceMessageById(assistantMessageId, message, requireLastNode = true)
    }

    // ---- FinalizeTurn 终态收口 ----

    private fun finalizeTurn(current: ConversationSnapshot, command: FinalizeTurn): ConversationSnapshot {
        var result = current
        command.messages?.let { result = replaceMessages(result, command.handle.assistantMessageId, it) }
        result = result.finishReasoning(command.handle.assistantMessageId)
        // 标记 assistant 终态（仅非成功状态；COMPLETED 的 terminalStatus 保持 null）。
        toMessageTerminalStatus(command.terminalStatus)?.let { status ->
            result = result.markAssistantTerminalInternal(command.handle.assistantMessageId, status, command.terminalReason)
        }
        // 关闭未完工具
        if (command.closeInterruptedTools) {
            result = result.closePendingTools(command.handle.assistantMessageId, cancelledByUser = false)
        }
        return result
    }

    private fun recoverInterruptedTurn(
        current: ConversationSnapshot,
        command: RecoverInterruptedTurn,
    ): ConversationSnapshot {
        var result = current
        command.messages?.let { messages ->
            val message = requireNotNull(messages.lastOrNull { it.id == command.assistantMessageId }) {
                "Recovery payload does not contain the owning assistant message"
            }
            result = result.replaceMessageById(command.assistantMessageId, message, requireLastNode = false)
        }
        require(result.findMessage(command.assistantMessageId)?.role == MessageRole.ASSISTANT) {
            "Recovery target is not an assistant message: ${command.assistantMessageId}"
        }
        result = result.finishReasoning(command.assistantMessageId)
        result = result.markAssistantTerminalInternal(
            command.assistantMessageId,
            MessageTerminalStatus.INTERRUPTED,
            command.terminalReason,
        )
        if (command.closeInterruptedTools) {
            result = result.closePendingTools(command.assistantMessageId, cancelledByUser = false)
        }
        return result
    }

    private fun reconcileOrphanedTurnExecution(
        current: ConversationSnapshot,
        command: ReconcileOrphanedTurnExecution,
    ): ConversationSnapshot {
        // Recovery may observe an execution fact without its owning message. The fact is closed
        // explicitly; recovery must not invent a message or weaken the live-turn owner invariant.
        require(
            command.assistantMessageId == null ||
                current.findMessage(command.assistantMessageId) == null
        ) {
            "Orphan reconciliation cannot close an execution that still owns a message: " +
                command.assistantMessageId
        }
        return current
    }

    private fun ConversationSnapshot.finishReasoning(messageId: Uuid): ConversationSnapshot {
        val message = findMessage(messageId) ?: return this
        val unfinished = message.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null }
        return if (unfinished) replaceMessageById(messageId, message.finishReasoning(), false) else this
    }

    /** 将持久化 turn 终态映射为渲染可见的 MessageTerminalStatus（成功态映射为 null）。 */
    private fun toMessageTerminalStatus(status: TurnExecutionStatus): MessageTerminalStatus? = when (status) {
        TurnExecutionStatus.CANCELLED -> MessageTerminalStatus.CANCELLED
        TurnExecutionStatus.FAILED -> MessageTerminalStatus.FAILED
        TurnExecutionStatus.INCOMPLETE -> MessageTerminalStatus.INCOMPLETE
        TurnExecutionStatus.INTERRUPTED -> MessageTerminalStatus.INTERRUPTED
        // CREATED / RUNNING / AWAITING_APPROVAL / COMPLETED → 正常完成或仍在进行，不标失败态
        else -> null
    }

    // ---- 树操作 ----

    private fun appendUser(current: ConversationSnapshot, command: AppendUserMessage): ConversationSnapshot {
        // append 用户消息意味着会话不再处于"新会话"运行态（@Transient 标记）
        return current.copy(
            nodes = current.nodes + MessageNode.of(command.message),
            header = current.header.copy(
                title = if (current.header.title.isBlank()) {
                    command.initialTitle ?: current.header.title
                } else {
                    current.header.title
                },
                newConversation = false,
            ),
        )
    }

    /** 编辑 = 在目标节点追加新变体并选中。 */
    private fun editVariant(current: ConversationSnapshot, command: EditMessageVariant): ConversationSnapshot {
        val nodeIndex = current.nodes.indexOfFirst { it.id == command.nodeId }
        if (nodeIndex < 0) return current
        val node = current.nodes[nodeIndex]
        if (node.messages.any { it.id == command.variant.id }) return current
        val newMessages = node.messages + command.variant
        return current.copy(
            nodes = current.nodes.mapIndexed { i, n ->
                if (i != nodeIndex) n else node.copy(
                    messages = newMessages,
                    selectIndex = newMessages.lastIndex,
                )
            },
        )
    }

    /**
     * 删除消息变体：删除节点内单条消息变体；节点仍余变体则保留节点（selectIndex 收缩到
     * 有效范围）；变体清空则删除整个节点。
     */
    private fun deleteMessage(current: ConversationSnapshot, command: DeleteMessage): ConversationSnapshot {
        val targetNodeIndex = current.nodes.indexOfFirst { node ->
            node.messages.any { it.id == command.messageId }
        }
        if (targetNodeIndex < 0) return current
        val targetNode = current.nodes[targetNodeIndex]
        val nextMessages = targetNode.messages.filterNot { it.id == command.messageId }
        val replacement: MessageNode? = if (nextMessages.isEmpty()) {
            null
        } else {
            targetNode.copy(
                messages = nextMessages,
                selectIndex = targetNode.selectIndex.coerceAtMost(nextMessages.lastIndex),
            )
        }
        return current.copy(
            nodes = current.nodes.mapIndexedNotNull { index, node ->
                when {
                    index != targetNodeIndex -> node
                    replacement != null -> replacement
                    else -> null
                }
            },
        )
    }

    private fun selectNodeVariant(current: ConversationSnapshot, command: SelectNodeVariant): ConversationSnapshot {
        val nodeIndex = current.nodes.indexOfFirst { it.id == command.nodeId }
        if (nodeIndex < 0) return current
        val node = current.nodes[nodeIndex]
        val safeIndex = command.selectIndex.coerceIn(0, (node.messages.size - 1).coerceAtLeast(0))
        if (safeIndex == node.selectIndex) return current
        return current.copy(
            nodes = current.nodes.mapIndexed { i, n ->
                if (i != nodeIndex) n else node.copy(selectIndex = safeIndex)
            },
        )
    }

    private fun truncateTo(current: ConversationSnapshot, nodeIndexInclusive: Int): ConversationSnapshot {
        if (nodeIndexInclusive >= current.nodes.size) return current
        return current.copy(nodes = current.nodes.subList(0, nodeIndexInclusive + 1))
    }

    private fun backfillAttachmentRefs(
        current: ConversationSnapshot,
        backfills: List<AttachmentRefBackfill>,
    ): ConversationSnapshot {
        val nodes = AttachmentRefs.applyBackfills(current.nodes, backfills)
        return if (nodes === current.nodes) current else current.copy(nodes = nodes)
    }

    // ---- Header（只动 header，不触碰 nodes）----

    fun reduceHeader(old: ConversationHeader, command: UpdateHeader): ConversationHeader = old.copy(
        title = command.title ?: old.title,
        chatSuggestions = command.suggestions ?: old.chatSuggestions,
        isPinned = command.isPinned ?: old.isPinned,
        folderId = when (command.folderId) {
            is OptionalFolderId.Keep -> old.folderId
            is OptionalFolderId.Clear -> null
            is OptionalFolderId.SetTo -> command.folderId.id
        },
        customSystemPrompt = when (command.customSystemPrompt) {
            is OptionalString.Keep -> old.customSystemPrompt
            is OptionalString.Set -> command.customSystemPrompt.value
        },
        modeInjectionIds = when (command.modeInjectionIds) {
            is OptionalUuidSet.Keep -> old.modeInjectionIds
            is OptionalUuidSet.Set -> command.modeInjectionIds.value
        },
        workspaceCwd = when (command.workspaceCwd) {
            is OptionalString.Keep -> old.workspaceCwd
            is OptionalString.Set -> command.workspaceCwd.value
        },
    )

    fun reduceHeader(old: ConversationHeader, command: MoveToAssistant): ConversationHeader =
        if (old.assistantId == command.assistantId) {
            old
        } else {
            old.copy(assistantId = command.assistantId, folderId = null)
        }

    fun reduceHeader(old: ConversationHeader, command: TogglePinned): ConversationHeader =
        old.copy(isPinned = !old.isPinned)

    /**
     * HITL 工具审批按稳定的 messageId + toolOrdinal 定位；目标必须是未执行且 Pending。
     * durable node 与 active-turn projection 必须由同一次纯变换得到，否则提交后的旧投影会
     * 遮住已持久化决定，使 UI 与 resume 仍看到 Pending。Runtime 只会在 durable commit 成功
     * 后以同一命令重放到最新 projection，因此相同决定必须幂等。Denied 的输出由恢复生成/终态路径负责，
     * 本命令只写决定。
     */
    private fun updateToolApproval(current: ConversationSnapshot, command: UpdateToolApproval): ConversationSnapshot {
        val durableMessage = current.findMessage(command.messageId) ?: return current
        val updatedDurableMessage = updateToolApproval(durableMessage, command) ?: return current
        val durable = current.replaceMessageById(
            messageId = command.messageId,
            replacement = updatedDurableMessage,
            requireLastNode = true,
        )
        val updatedActiveTurn = current.activeTurn?.let { active ->
            val messageIndex = active.messages.indexOfFirst { it.id == command.messageId }
            if (messageIndex < 0) {
                active
            } else {
                val updatedActiveMessage = requireNotNull(
                    updateToolApproval(active.messages[messageIndex], command)
                ) { "active approval projection disagrees with its durable owner" }
                val committedPhase = when (command.approvalState) {
                    ToolApprovalState.Approved -> ToolCallPhase.READY
                    is ToolApprovalState.Denied -> ToolCallPhase.DENIED
                    is ToolApprovalState.Answered -> ToolCallPhase.ANSWERED
                    ToolApprovalState.Auto,
                    ToolApprovalState.Pending,
                    -> error("approval command contains a non-terminal decision")
                }
                active.copy(
                    messages = active.messages.toMutableList().apply { set(messageIndex, updatedActiveMessage) },
                    toolCallPhases = active.toolCallPhases + (
                        ToolCallLocator(command.messageId, command.toolOrdinal) to committedPhase
                    ),
                )
            }
        }
        return durable.copy(activeTurn = updatedActiveTurn)
    }

    private fun commitCheckpoint(current: ConversationSnapshot, command: CommitCheckpoint): ConversationSnapshot {
        val replaced = replaceMessages(current, command.handle.assistantMessageId, command.messages)
        val active = current.activeTurn ?: return replaced
        return replaced.copy(activeTurn = active.afterCheckpoint(command))
    }

    private fun updateToolApproval(
        message: UIMessage,
        command: UpdateToolApproval,
    ): UIMessage? {
        var ordinal = 0
        var matched = false
        val updatedParts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val currentOrdinal = ordinal++
            if (currentOrdinal != command.toolOrdinal) return@map part
            if (part.isExecuted) return null
            if (part.approvalState == command.approvalState) {
                matched = true
                return@map part
            }
            if (part.approvalState !is ToolApprovalState.Pending) return null
            matched = true
            part.copy(approvalState = command.approvalState)
        }
        return if (matched) message.copy(parts = updatedParts) else null
    }

    // ---- 私有纯工具 ----

    private fun emptyAssistantMessage(id: Uuid): UIMessage = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
    )

    private fun ConversationSnapshot.findMessage(messageId: Uuid): UIMessage? {
        nodes.lastOrNull()?.messages?.firstOrNull { it.id == messageId }?.let { return it }
        return nodes.asSequence().flatMap { it.messages.asSequence() }.firstOrNull { it.id == messageId }
    }

    private fun ConversationSnapshot.replaceMessageById(
        messageId: Uuid,
        replacement: UIMessage,
        requireLastNode: Boolean,
    ): ConversationSnapshot {
        val lastIndex = nodes.lastIndex
        val nodeIndex = if (lastIndex >= 0 && nodes[lastIndex].messages.any { it.id == messageId }) {
            lastIndex
        } else {
            check(!requireLastNode) { "Active assistant message is not in the last node" }
            nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        }
        check(nodeIndex >= 0) { "Assistant message is missing from the durable tree: $messageId" }
        val node = nodes[nodeIndex]
        val messageIndex = node.messages.indexOfFirst { it.id == messageId }
        if (node.messages[messageIndex] == replacement && node.selectIndex == messageIndex) return this
        val updatedMessages = node.messages.toMutableList().apply { set(messageIndex, replacement) }
        val updatedNodes = nodes.toMutableList().apply {
            set(nodeIndex, node.copy(messages = updatedMessages, selectIndex = messageIndex))
        }
        return copy(nodes = updatedNodes)
    }

    private fun ConversationSnapshot.markAssistantTerminalInternal(
        messageId: Uuid?,
        status: MessageTerminalStatus,
        reason: String?,
    ): ConversationSnapshot {
        if (messageId == null) return this
        val message = findMessage(messageId)?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
        return replaceMessageById(
            messageId,
            message.copy(terminalStatus = status, terminalReason = reason),
            requireLastNode = false,
        )
    }

    /** 只关闭目标 turn 的未完成工具，不改变历史 turn。 */
    private fun ConversationSnapshot.closePendingTools(
        messageId: Uuid,
        cancelledByUser: Boolean,
    ): ConversationSnapshot {
        val message = findMessage(messageId) ?: return this
        val hasPending = message.parts.any { it is UIMessagePart.Tool && it.approvalState is ToolApprovalState.Pending }
        if (!hasPending) return this
        val updated = message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool && part.approvalState is ToolApprovalState.Pending) {
                if (cancelledByUser) cancelToolByUserInternal(part) else interruptToolInternal(part)
            } else {
                part
            }
        })
        return replaceMessageById(messageId, updated, false)
    }

    private fun cancelToolByUserInternal(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text("""{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""),
        ),
        approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
    )

    private fun interruptToolInternal(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text("""{"status":"interrupted","error":"Tool execution was interrupted before completion."}"""),
        ),
    )

}
