package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.finishReasoning
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * Conversation 结构性修改的纯函数 reducer。
 *
 * 约束：reducer 零 IO；未被命令触及的 MessageNode 保持同一实例引用（structural sharing，
 * 是 delta 持久化与 Compose skip 的共同前提）。
 *
 * 实现迁移来源（从 ChatService / Conversation 私有函数平移为纯函数）：
 *  - Conversation.updateCurrentMessages 等价逻辑（BeginTurn / CommitCheckpoint / FinalizeTurn 消息替换）
 *  - markAssistantTerminal（FinalizeTurn 终态收口）
 *  - cancelToolByUser / interruptPendingTool（UpdateToolApproval 拒绝路径 / 终态关闭未完工具）
 *  - regenerateAtMessage 的树截断（TruncateToNodeIndex）
 *  - sanitizeForPersistence（UpdateHeader(sanitize=true) 路径，纯 base64 剥离）
 *
 * 说明：closeOpenTools 中"加载 Child 子助手消息"是 IO 操作（loadChildMessagesForInterruptedCalls），
 * 不属于 reducer（保持零 IO）；reducer 只做纯的 tool 收口（cancelByUser / interrupt）。
 * Child 消息并入由 Application 层在构造 FinalizeTurn 前完成。
 */
internal object ConversationReducer {

    fun reduce(current: Conversation, command: ConversationCommand): Conversation = when (command) {
        is BeginTurn -> beginTurn(current, command)
        is ApplyStreamingDelta -> current // 仅 activeTurn 更新，reducer 不动 nodes；由 Runtime 直接处理
        is CommitCheckpoint -> replaceMessages(current, command.assistantMessageId, command.messages)
        is FinalizeTurn -> finalizeTurn(current, command)
        is AppendUserMessage -> appendUser(current, command.message)
        is EditMessageVariant -> editVariant(current, command)
        is DeleteMessage -> deleteMessage(current, command)
        is SelectNodeVariant -> selectNodeVariant(current, command)
        is TruncateToNodeIndex -> truncateTo(current, command.nodeIndexInclusive)
        is ReplaceMessageTree -> current.copy(messageNodes = command.nodes)
        is UpdateHeader -> updateHeader(current, command)
        is UpdateToolApproval -> updateToolApproval(current, command)
    }

    // ---- BeginTurn：在节点树末端追加/复用 assistant 槽 ----

    private fun beginTurn(current: Conversation, command: BeginTurn): Conversation {
        if (current.messageNodes.isEmpty()) {
            val node = MessageNode.of(emptyAssistantMessage(command.assistantMessageId))
            return current.copy(messageNodes = listOf(node))
        }
        val last = current.messageNodes.last()
        val lastMsg = last.messages.lastOrNull()
        // resume：末尾已是同 assistant 节点 → 直接复用（幂等）
        if (command.resume && lastMsg != null && lastMsg.id == command.assistantMessageId && lastMsg.role == MessageRole.ASSISTANT) {
            return current
        }
        val nodes = current.messageNodes.toMutableList()
        if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && lastMsg.terminalStatus == null) {
            // 追加到现有未闭合 assistant 节点
            val msg = emptyAssistantMessage(command.assistantMessageId)
            nodes[nodes.lastIndex] = last.copy(messages = last.messages + msg, selectIndex = last.messages.size)
        } else {
            nodes.add(MessageNode.of(emptyAssistantMessage(command.assistantMessageId)))
        }
        return current.copy(messageNodes = nodes)
    }

    // ---- 消息替换（checkpoint / 终态）----

    /**
     * checkpoint 消息分发（= Conversation.updateCurrentMessages 语义）：
     * messages 为完整 currentMessages，按 index 对应节点分发；未被触及的节点保持
     * 同一实例引用（structural sharing，是 delta 持久化 O(changed) 与 Compose skip 的前提）。
     */
    private fun replaceMessages(current: Conversation, assistantMessageId: Uuid, messages: List<UIMessage>): Conversation {
        if (messages.isEmpty()) return current
        val newNodes = current.messageNodes.toMutableList()
        messages.forEachIndexed { index, message ->
            val node = newNodes.getOrElse(index) { MessageNode.of(message) }
            val existingIndex = node.messages.indexOfFirst { it.id == message.id }
            val newNode: MessageNode
            if (existingIndex >= 0) {
                if (node.messages[existingIndex] === message) {
                    // 引用相同 → 节点未变，保持原引用（structural sharing）
                    if (index <= newNodes.lastIndex) return@forEachIndexed
                    newNode = node
                } else {
                    newNode = node.copy(
                        messages = node.messages.mapIndexed { i, m -> if (i == existingIndex) message else m },
                        selectIndex = node.selectIndex,
                    )
                }
            } else {
                val newMessages = node.messages + message
                newNode = node.copy(messages = newMessages, selectIndex = newMessages.lastIndex)
            }
            if (index > newNodes.lastIndex) newNodes.add(newNode) else newNodes[index] = newNode
        }
        if (newNodes == current.messageNodes) return current
        return current.copy(messageNodes = newNodes)
    }

    // ---- FinalizeTurn 终态收口 ----

    private fun finalizeTurn(current: Conversation, command: FinalizeTurn): Conversation {
        var result = current
        command.messages?.let { result = result.updateCurrentMessagesByUIMessages(it) }
        // 终态收口 1：所有消息的 reasoning 段标记 finishedAt（对齐 ChatService.finishReasoning，纯变换）
        result = result.applyFinishReasoning()
        // 标记 assistant 终态（仅非成功状态；COMPLETED 的 terminalStatus 保持 null）。
        // 抑制场景：messages=null 且 closeInterruptedTools=true 的纯工具收口
        // （finishInterruptedPendingTools 的 super-section 收口）——不标记消息终态，
        // 旧 assistant 分支的终态由其所属 turn 的正常 FinalizeTurn 管理。
        val suppressTerminalMark = command.messages == null && command.closeInterruptedTools
        if (!suppressTerminalMark) {
            toMessageTerminalStatus(command.terminalStatus)?.let { status ->
                result = result.markAssistantTerminalInternal(command.assistantMessageId, status, command.terminalReason)
            }
        }
        // 关闭未完工具
        if (command.closeInterruptedTools) {
            result = result.closePendingTools(cancelledByUser = false)
        }
        return result
    }

    /** 对所有消息应用 finishReasoning（终态收口；对齐 ChatService.finalize 段的 finishReasoning 语义）。 */
    private fun Conversation.applyFinishReasoning(): Conversation {
        val needsChange = messageNodes.any { node ->
            node.messages.any { msg -> msg.parts.any { it is UIMessagePart.Reasoning } }
        }
        if (!needsChange) return this
        return copy(
            messageNodes = messageNodes.map { node ->
                node.copy(messages = node.messages.map { it.finishReasoning() })
            },
        )
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

    private fun appendUser(current: Conversation, message: UIMessage): Conversation {
        // append 用户消息意味着会话不再处于"新会话"运行态（@Transient 标记）
        return current.copy(
            messageNodes = current.messageNodes + MessageNode.of(message),
            newConversation = false,
        )
    }

    /** 编辑 = 在目标节点追加新变体并选中（对齐 ChatService.editMessage 语义）。 */
    private fun editVariant(current: Conversation, command: EditMessageVariant): Conversation {
        val nodeIndex = current.messageNodes.indexOfFirst { it.id == command.nodeId }
        if (nodeIndex < 0) return current
        val node = current.messageNodes[nodeIndex]
        if (node.messages.any { it.id == command.variant.id }) return current
        val newMessages = node.messages + command.variant
        return current.copy(
            messageNodes = current.messageNodes.mapIndexed { i, n ->
                if (i != nodeIndex) n else node.copy(
                    messages = newMessages,
                    selectIndex = newMessages.lastIndex,
                )
            },
        )
    }

    /**
     * 删除消息变体（对齐 ChatService.buildConversationAfterMessageDelete 语义）：
     * 删除节点内单条消息变体；节点仍余变体则保留节点（selectIndex 收缩到有效范围）；
     * 变体清空则删除整个节点。
     */
    private fun deleteMessage(current: Conversation, command: DeleteMessage): Conversation {
        val targetNodeIndex = current.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == command.messageId }
        }
        if (targetNodeIndex < 0) return current
        val targetNode = current.messageNodes[targetNodeIndex]
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
            messageNodes = current.messageNodes.mapIndexedNotNull { index, node ->
                when {
                    index != targetNodeIndex -> node
                    replacement != null -> replacement
                    else -> null
                }
            },
        )
    }

    private fun selectNodeVariant(current: Conversation, command: SelectNodeVariant): Conversation {
        val nodeIndex = current.messageNodes.indexOfFirst { it.id == command.nodeId }
        if (nodeIndex < 0) return current
        val node = current.messageNodes[nodeIndex]
        val safeIndex = command.selectIndex.coerceIn(0, (node.messages.size - 1).coerceAtLeast(0))
        if (safeIndex == node.selectIndex) return current
        return current.copy(
            messageNodes = current.messageNodes.mapIndexed { i, n ->
                if (i != nodeIndex) n else node.copy(selectIndex = safeIndex)
            },
        )
    }

    private fun truncateTo(current: Conversation, nodeIndexInclusive: Int): Conversation {
        if (nodeIndexInclusive >= current.messageNodes.size) return current
        return current.copy(messageNodes = current.messageNodes.subList(0, nodeIndexInclusive + 1))
    }

    // ---- Header ----

    private fun updateHeader(current: Conversation, command: UpdateHeader): Conversation {
        var result = current
        if (command.sanitizeForPersistence) {
            result = result.sanitizeBase64()
        }
        return result.copy(
            title = command.title ?: current.title,
            chatSuggestions = command.suggestions ?: current.chatSuggestions,
            isPinned = command.isPinned ?: current.isPinned,
            folderId = when (command.folderId) {
                is OptionalFolderId.Keep -> current.folderId
                is OptionalFolderId.Clear -> null
                is OptionalFolderId.SetTo -> command.folderId.id
            },
            assistantId = command.assistantId ?: current.assistantId,
            customSystemPrompt = when (command.customSystemPrompt) {
                is OptionalString.Keep -> current.customSystemPrompt
                is OptionalString.Set -> command.customSystemPrompt.value
            },
            modeInjectionIds = when (command.modeInjectionIds) {
                is OptionalUuidSet.Keep -> current.modeInjectionIds
                is OptionalUuidSet.Set -> command.modeInjectionIds.value
            },
            workspaceCwd = when (command.workspaceCwd) {
                is OptionalString.Keep -> current.workspaceCwd
                is OptionalString.Set -> command.workspaceCwd.value
            },
        )
    }

    /**
     * HITL 工具审批（对齐 ChatService.updateCurrentToolApproval 语义）：
     * 仅作用于最后一条消息；按 toolOrdinal 定位；目标必须是未执行且 Pending 的工具；
     * 只变更 approvalState（Denied 的 cancelled output 由终态取消路径写入，此处不写——
     * resume 生成会按 Denied 状态继续）。
     */
    private fun updateToolApproval(current: Conversation, command: UpdateToolApproval): Conversation {
        val currentMessage = current.currentMessages.lastOrNull() ?: return current
        if (currentMessage.id != command.messageId) return current
        var ordinal = 0
        var matched = false
        val updatedParts = currentMessage.parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val currentOrdinal = ordinal++
            if (currentOrdinal != command.toolOrdinal) return@map part
            if (part.isExecuted || part.approvalState !is ToolApprovalState.Pending) return current
            matched = true
            part.copy(approvalState = command.approvalState)
        }
        if (!matched) return current
        val updatedMessage = currentMessage.copy(parts = updatedParts)
        return current.copy(
            messageNodes = current.messageNodes.map { node ->
                if (node.currentMessage.id == currentMessage.id) {
                    node.copy(
                        messages = node.messages.mapIndexed { index, message ->
                            if (index == node.selectIndex) updatedMessage else message
                        }
                    )
                } else {
                    node
                }
            }
        )
    }

    // ---- 私有纯工具 ----

    private fun emptyAssistantMessage(id: Uuid): UIMessage = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
    )

    private fun Conversation.updateCurrentMessagesByUIMessages(messages: List<UIMessage>): Conversation {
        val newNodes = messageNodes.toMutableList()
        messages.forEachIndexed { index, message ->
            val node = newNodes.getOrElse(index) { MessageNode.of(message) }
            val newMessages = node.messages.toMutableList()
            var newIndex = node.selectIndex
            val existingIndex = newMessages.indexOfFirst { it.id == message.id }
            if (existingIndex >= 0) {
                newMessages[existingIndex] = message
            } else {
                newMessages.add(message)
                newIndex = newMessages.lastIndex
            }
            val newNode = node.copy(messages = newMessages, selectIndex = newIndex)
            if (index > newNodes.lastIndex) newNodes.add(newNode) else newNodes[index] = newNode
        }
        return copy(messageNodes = newNodes)
    }

    private fun Conversation.markAssistantTerminalInternal(
        messageId: Uuid?,
        status: MessageTerminalStatus,
        reason: String?,
    ): Conversation {
        if (messageId == null) return this
        messageNodes.forEachIndexed { index, node ->
            val message = node.messages.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
            if (message != null) {
                val marked = message.copy(terminalStatus = status, terminalReason = reason)
                return copy(
                    messageNodes = messageNodes.mapIndexed { i, n ->
                        if (i != index) n else n.copy(
                            messages = n.messages.map { m -> if (m.id == messageId) marked else m },
                        )
                    },
                )
            }
        }
        return this
    }

    /** 关闭所有未完成工具（FinalizeTurn closeInterruptedTools） */
    private fun Conversation.closePendingTools(cancelledByUser: Boolean): Conversation {
        var changed = false
        val newNodes = messageNodes.map { node ->
            val newMessages = node.messages.map { message ->
                val hasPending = message.parts.any { it is UIMessagePart.Tool && it.approvalState is ToolApprovalState.Pending }
                if (!hasPending) message else {
                    changed = true
                    message.copy(parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.approvalState is ToolApprovalState.Pending) {
                            if (cancelledByUser) cancelToolByUserInternal(part) else interruptToolInternal(part)
                        } else {
                            part
                        }
                    })
                }
            }
            if (newMessages == node.messages) node else node.copy(messages = newMessages)
        }
        if (!changed) return this
        return copy(messageNodes = newNodes)
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

    private fun Conversation.sanitizeBase64(): Conversation {
        // UIMessage 自带 hasBase64Part() 成员；无 base64 时短路保持引用不变
        if (messageNodes.none { node -> node.messages.any { it.hasBase64Part() } }) return this
        return copy(
            messageNodes = messageNodes.map { node ->
                node.copy(messages = node.messages.map { it.withoutUnpersistableBase64() })
            },
        )
    }
}
