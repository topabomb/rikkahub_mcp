package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
        is BeginTurn -> beginTurn(current, command)
        is ApplyStreamingDelta -> current // 仅 activeTurn 更新，reducer 不动 nodes；由 Runtime 直接处理
        is CommitCheckpoint -> replaceMessages(current, command.assistantMessageId, command.messages)
        is FinalizeTurn -> finalizeTurn(current, command)
        is AppendUserMessage -> appendUser(current, command.message)
        is EditMessageVariant -> editVariant(current, command)
        is DeleteMessage -> deleteMessage(current, command)
        is SelectNodeVariant -> selectNodeVariant(current, command)
        is TruncateToNodeIndex -> truncateTo(current, command.nodeIndexInclusive)
        is ReplaceMessageTree -> current.copy(nodes = command.nodes)
        is UpdateHeader -> updateHeader(current, command)
        is UpdateToolApproval -> updateToolApproval(current, command)
    }

    // ---- BeginTurn：在节点树末端追加/复用 assistant 槽 ----

    private fun beginTurn(current: ConversationSnapshot, command: BeginTurn): ConversationSnapshot {
        if (current.nodes.isEmpty()) {
            val node = MessageNode.of(emptyAssistantMessage(command.assistantMessageId))
            return current.copy(nodes = listOf(node))
        }
        val last = current.nodes.last()
        val lastMsg = last.messages.lastOrNull()
        // resume：末尾已是同 assistant 节点 → 直接复用（幂等）
        if (command.resume && lastMsg != null && lastMsg.id == command.assistantMessageId && lastMsg.role == MessageRole.ASSISTANT) {
            return current
        }
        val nodes = current.nodes.toMutableList()
        if (lastMsg != null && lastMsg.role == MessageRole.ASSISTANT && lastMsg.terminalStatus == null) {
            // 追加到现有未闭合 assistant 节点
            val msg = emptyAssistantMessage(command.assistantMessageId)
            nodes[nodes.lastIndex] = last.copy(messages = last.messages + msg, selectIndex = last.messages.size)
        } else {
            nodes.add(MessageNode.of(emptyAssistantMessage(command.assistantMessageId)))
        }
        return current.copy(nodes = nodes)
    }

    // ---- 消息替换（checkpoint / 终态）----

    /**
     * checkpoint 消息分发：messages 为完整 currentMessages，按 index 对应节点分发；
     * 未被触及的节点保持同一实例引用（structural sharing，是 delta 持久化 O(changed)
     * 与 Compose skip 的前提）。
     */
    private fun replaceMessages(current: ConversationSnapshot, assistantMessageId: Uuid, messages: List<UIMessage>): ConversationSnapshot {
        if (messages.isEmpty()) return current
        val newNodes = current.nodes.toMutableList()
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
        if (newNodes == current.nodes) return current
        return current.copy(nodes = newNodes)
    }

    // ---- FinalizeTurn 终态收口 ----

    private fun finalizeTurn(current: ConversationSnapshot, command: FinalizeTurn): ConversationSnapshot {
        var result = current
        command.messages?.let { result = result.updateCurrentMessagesByUIMessages(it) }
        // 终态收口：未结束的 reasoning 标记 finishedAt（已结束的历史节点保持同一实例）
        result = result.applyFinishReasoning()
        // 标记 assistant 终态（仅非成功状态；COMPLETED 的 terminalStatus 保持 null）。
        // 抑制场景：messages=null 且 closeInterruptedTools=true 的纯工具收口
        // （finalizeSupersededTurn 的 super-section 收口）——不标记消息终态，
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

    /**
     * 只收口未结束的 reasoning（finishedAt == null）。已结束的历史节点保持同一实例，
     * 避免 FinalizeTurn 把整棵树标脏导致 checkpoint 写放大。
     */
    private fun ConversationSnapshot.applyFinishReasoning(): ConversationSnapshot {
        var changed = false
        val newNodes = nodes.map { node ->
            val hasUnfinished = node.messages.any { msg ->
                msg.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null }
            }
            if (!hasUnfinished) {
                node
            } else {
                changed = true
                node.copy(
                    messages = node.messages.map { msg ->
                        val unfinished = msg.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null }
                        if (unfinished) msg.finishReasoning() else msg
                    },
                )
            }
        }
        return if (!changed) this else copy(nodes = newNodes)
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

    private fun appendUser(current: ConversationSnapshot, message: UIMessage): ConversationSnapshot {
        // append 用户消息意味着会话不再处于"新会话"运行态（@Transient 标记）
        return current.copy(
            nodes = current.nodes + MessageNode.of(message),
            header = current.header.copy(newConversation = false),
        )
    }

    /** 编辑 = 在目标节点追加新变体并选中（对齐 ChatService.editMessage 语义）。 */
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

    // ---- Header（只动 header，不触碰 nodes）----

    private fun updateHeader(current: ConversationSnapshot, command: UpdateHeader): ConversationSnapshot {
        var nodes = current.nodes
        if (command.sanitizeForPersistence) {
            nodes = current.sanitizeBase64Nodes()
        }
        val old = current.header
        return current.copy(
            nodes = nodes,
            header = old.copy(
                title = command.title ?: old.title,
                chatSuggestions = command.suggestions ?: old.chatSuggestions,
                isPinned = command.isPinned ?: old.isPinned,
                folderId = when (command.folderId) {
                    is OptionalFolderId.Keep -> old.folderId
                    is OptionalFolderId.Clear -> null
                    is OptionalFolderId.SetTo -> command.folderId.id
                },
                assistantId = command.assistantId ?: old.assistantId,
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
            ),
        )
    }

    /**
     * HITL 工具审批：仅作用于最后一条消息；按 toolOrdinal 定位；目标必须是未执行且
     * Pending 的工具；只变更 approvalState（Denied 的 cancelled output 由终态取消路径
     * 写入，此处不写——resume 生成会按 Denied 状态继续）。
     */
    private fun updateToolApproval(current: ConversationSnapshot, command: UpdateToolApproval): ConversationSnapshot {
        val currentMessage = current.currentMessages().lastOrNull() ?: return current
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
            nodes = current.nodes.map { node ->
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

    private fun ConversationSnapshot.updateCurrentMessagesByUIMessages(messages: List<UIMessage>): ConversationSnapshot {
        val newNodes = nodes.toMutableList()
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
        return copy(nodes = newNodes)
    }

    private fun ConversationSnapshot.markAssistantTerminalInternal(
        messageId: Uuid?,
        status: MessageTerminalStatus,
        reason: String?,
    ): ConversationSnapshot {
        if (messageId == null) return this
        nodes.forEachIndexed { index, node ->
            val message = node.messages.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
            if (message != null) {
                val marked = message.copy(terminalStatus = status, terminalReason = reason)
                return copy(
                    nodes = nodes.mapIndexed { i, n ->
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
    private fun ConversationSnapshot.closePendingTools(cancelledByUser: Boolean): ConversationSnapshot {
        var changed = false
        val newNodes = nodes.map { node ->
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
        return copy(nodes = newNodes)
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

    private fun ConversationSnapshot.sanitizeBase64Nodes(): List<MessageNode> {
        // UIMessage 自带 hasBase64Part() 成员；无 base64 时短路保持引用不变
        if (nodes.none { node -> node.messages.any { it.hasBase64Part() } }) return nodes
        return nodes.map { node ->
            node.copy(messages = node.messages.map { it.withoutUnpersistableBase64() })
        }
    }
}
