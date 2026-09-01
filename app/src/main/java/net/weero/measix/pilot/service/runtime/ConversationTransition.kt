package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.ContextTrimmingPolicy
import net.weero.measix.pilot.data.ai.estimateStableTextTokens
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefBackfill
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.tools.ToolInteractionKind
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.buildToolOutputMarker
import net.weero.measix.pilot.data.ai.tools.canonicalizeToolOutput
import net.weero.measix.pilot.data.ai.tools.virtualLineCount
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import java.time.Instant
import kotlin.uuid.Uuid

internal sealed interface ConversationChange {
    val snapshot: ConversationSnapshot

    data class DraftOnly(
        override val snapshot: ConversationSnapshot,
    ) : ConversationChange

    data class Durable(
        override val snapshot: ConversationSnapshot,
        val write: ConversationWrite,
    ) : ConversationChange
}

internal sealed interface ConversationWrite {
    data class MaterializeDraft(val conversation: Conversation) : ConversationWrite
    data class Mutate(
        val mutation: ConversationMutation,
        val executionFacts: ExecutionFacts? = null,
    ) : ConversationWrite
}

internal data class ConversationHeaderChange(
    val committedHeader: ConversationHeader,
    val write: ConversationWrite.Mutate,
)

/**
 * Unique conversation command planner. It produces the next snapshot, the exact persistence
 * delta and execution facts together. The coordinator owns locks and IO; the repository owns
 * the Room transaction; the runtime only publishes after commit.
 */
internal object ConversationTransition {

    fun plan(
        current: ConversationSnapshot,
        command: ConversationCommand,
        nowMillis: Long,
    ): ConversationChange {
        if (current.header.newConversation) {
            return planDraft(current, command, nowMillis)
        }
        val reduced = apply(current, command)
        val snapshot = stampActivity(current, reduced, command, nowMillis)
        val mutation = mutationOf(current, snapshot, command)
        val facts = factsOf(current.conversationId, command, nowMillis)
        return ConversationChange.Durable(
            snapshot = snapshot,
            write = ConversationWrite.Mutate(mutation, facts),
        )
    }

    fun planHeader(
        current: ConversationHeader,
        command: HeaderConversationCommand,
        nowMillis: Long,
    ): ConversationHeaderChange {
        val updated = applyHeader(current, command)
        val mutation = ConversationMutation(
            conversationId = updated.id,
            headerPatch = headerPatchFromCommand(current, command)
                ?: ConversationHeaderPatch().takeIf { updated.updateAt != current.updateAt },
            upsertedNodes = emptyList(),
            deletedNodeIds = emptyList(),
            updateAt = updated.updateAt,
            upsertedNodeIndices = emptyList(),
            indexForSearch = updated.parentConversationId == null,
            searchMetadataChanged = updated.title != current.title,
            titleForIndex = updated.title,
        )
        return ConversationHeaderChange(
            committedHeader = updated,
            write = ConversationWrite.Mutate(mutation),
        )
    }

    internal fun apply(
        current: ConversationSnapshot,
        command: ConversationCommand,
    ): ConversationSnapshot {
        val reduced = when (command) {
            is StartTurn -> startTurn(current, command)
            is CommitCheckpoint -> commitCheckpoint(current, command)
            is FinalizeTurn -> finalizeTurn(current, command)
            is RecoverInterruptedTurn -> recoverInterruptedTurn(current, command)
            is AppendUserMessage -> appendUser(current, command)
            is EditMessageVariant -> editVariant(current, command)
            is DeleteMessage -> deleteMessage(current, command)
            is SelectNodeVariant -> selectNodeVariant(current, command)
            is TruncateToNodeIndex -> truncateTo(current, command.nodeIndexInclusive)
            is ReplaceMessageTree -> current.copy(nodes = command.nodes)
            is BackfillAttachmentRefs -> backfillAttachmentRefs(current, command.backfills)
            is HeaderConversationCommand -> current.copy(header = applyHeader(current.header, command))
            is ResolveToolInteraction -> ResolveToolInteraction(current, command)
        }
        val next = reduced.copy(activeTurn = durableActiveTurn(current, reduced, command))
        return if (next == current) current else next
    }

    internal fun applyHeader(
        old: ConversationHeader,
        command: HeaderConversationCommand,
    ): ConversationHeader = when (command) {
        is UpdateHeader -> old.copy(
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
        is UpdateTitleIfCurrent -> if (old.title == command.expectedTitle) {
            old.copy(title = command.title)
        } else {
            old
        }
        is MoveToAssistant -> if (old.assistantId == command.assistantId) {
            old
        } else {
            old.copy(assistantId = command.assistantId, folderId = null)
        }
        TogglePinned -> old.copy(isPinned = !old.isPinned)
    }

    private fun planDraft(
        current: ConversationSnapshot,
        command: ConversationCommand,
        nowMillis: Long,
    ): ConversationChange = when (command) {
        is UpdateHeader,
        is MoveToAssistant,
        -> ConversationChange.DraftOnly(apply(current, command))
        is AppendUserMessage -> {
            val snapshot = stampActivity(current, apply(current, command), command, nowMillis)
            ConversationChange.Durable(
                snapshot = snapshot,
                write = ConversationWrite.MaterializeDraft(snapshot.materializeConversation()),
            )
        }
        else -> throw ConversationCommandConflictException(
            "draft ${current.conversationId} only accepts header edits or its first user message",
        )
    }

    private fun durableActiveTurn(
        old: ConversationSnapshot,
        reduced: ConversationSnapshot,
        command: ConversationCommand,
    ): ActiveTurnState? = when (command) {
        is StartTurn -> ActiveTurnState(
            epoch = command.epoch,
            turnId = command.turnId,
            assistantMessageId = command.assistantMessageId,
            messages = emptyList(),
        )
        is HeaderConversationCommand,
        is CommitCheckpoint,
        is ResolveToolInteraction,
        -> reduced.activeTurn
        is FinalizeTurn,
        is RecoverInterruptedTurn,
        -> null
        else -> null
    }

    private fun stampActivity(
        old: ConversationSnapshot,
        snapshot: ConversationSnapshot,
        command: ConversationCommand,
        nowMillis: Long,
    ): ConversationSnapshot =
        if (snapshot != old && command.updatesConversationActivity()) {
            snapshot.copy(header = snapshot.header.copy(updateAt = nowMillis))
        } else {
            snapshot
        }

    private fun mutationOf(
        old: ConversationSnapshot,
        new: ConversationSnapshot,
        command: ConversationCommand,
    ): ConversationMutation {
        val changedNodes = mutableListOf<MessageNode>()
        val changedIndices = mutableListOf<Int>()
        val deletedNodeIds = mutableListOf<Uuid>()
        fun upsert(index: Int, node: MessageNode) {
            val existing = changedIndices.indexOf(index)
            if (existing >= 0) {
                changedNodes[existing] = node
            } else {
                changedNodes += node
                changedIndices += index
            }
        }
        fun lastNodeDelta() {
            val index = maxOf(old.nodes.lastIndex, new.nodes.lastIndex)
            if (index < 0) return
            val oldNode = old.nodes.getOrNull(index)
            val newNode = new.nodes.getOrNull(index)
            when {
                newNode == null -> oldNode?.let { deletedNodeIds += it.id }
                oldNode === newNode -> Unit
                oldNode == null -> upsert(index, newNode)
                oldNode.id != newNode.id -> {
                    deletedNodeIds += oldNode.id
                    upsert(index, newNode)
                }
                else -> upsert(index, newNode)
            }
        }
        fun nodeById(nodeId: Uuid) {
            val index = new.nodes.indexOfFirst { it.id == nodeId }
            if (index < 0) return
            val node = new.nodes[index]
            if (old.nodes.getOrNull(index)?.let { it.id == nodeId && it === node } == true) return
            upsert(index, node)
        }
        fun selectedNodeByMessageId(messageId: Uuid) {
            val index = new.nodes.indexOfFirst { it.currentMessage.id == messageId }
            check(index >= 0) { "Selected checkpoint message is missing from the durable tree: $messageId" }
            val node = new.nodes[index]
            if (old.nodes.getOrNull(index) !== node) upsert(index, node)
        }
        when (command) {
            is HeaderConversationCommand -> Unit
            is StartTurn,
            is FinalizeTurn,
            is AppendUserMessage,
            -> lastNodeDelta()
            is CommitCheckpoint -> {
                lastNodeDelta()
                // 历史 Tool Output marker 不是 active last node；必须作为同一 mutation 的精确节点 delta 落库，
                // 这样本事务才能同时建立 TOOL_OUTPUT durable reference，再允许 lease publish。
                command.toolOutputCompactionPatches
                    .filterNot { it.locator.messageId == command.handle.assistantMessageId }
                    .forEach { patch -> selectedNodeByMessageId(patch.locator.messageId) }
            }
            is RecoverInterruptedTurn -> {
                val index = new.nodes.indexOfFirst { node ->
                    node.messages.any { it.id == command.assistantMessageId }
                }
                if (index >= 0) upsert(index, new.nodes[index])
            }
            is EditMessageVariant -> nodeById(command.nodeId)
            is SelectNodeVariant -> nodeById(command.nodeId)
            is DeleteMessage -> {
                val oldIndex = old.nodes.indexOfFirst { node ->
                    node.messages.any { it.id == command.messageId }
                }
                if (oldIndex >= 0) {
                    val oldNode = old.nodes[oldIndex]
                    val kept = new.nodes.getOrNull(oldIndex)?.takeIf { it.id == oldNode.id }
                    if (kept == null) {
                        deletedNodeIds += oldNode.id
                        new.nodes.drop(oldIndex).forEachIndexed { offset, node ->
                            upsert(oldIndex + offset, node)
                        }
                    } else if (kept !== oldNode) {
                        upsert(oldIndex, kept)
                    }
                }
            }
            is TruncateToNodeIndex -> {
                old.nodes.drop(command.nodeIndexInclusive + 1).forEach { deletedNodeIds += it.id }
            }
            is BackfillAttachmentRefs -> {
                command.backfills.map { it.nodeId }.distinct().forEach(::nodeById)
            }
            is ResolveToolInteraction -> {
                val index = new.nodes.indexOfFirst { node ->
                    node.messages.any { it.id == command.messageId }
                }
                if (index >= 0) {
                    val node = new.nodes[index]
                    if (old.nodes.getOrNull(index) !== node) upsert(index, node)
                }
            }
            is ReplaceMessageTree -> {
                val newIds = new.nodes.mapTo(hashSetOf(), MessageNode::id)
                old.nodes.filter { it.id !in newIds }.forEach { deletedNodeIds += it.id }
                val oldById = old.nodes.associateBy(MessageNode::id)
                val oldIndices = old.nodes.withIndex().associate { (index, node) -> node.id to index }
                new.nodes.forEachIndexed { index, node ->
                    if (oldById[node.id] != node || oldIndices[node.id] != index) {
                        upsert(index, node)
                    }
                }
            }
        }
        return ConversationMutation(
            conversationId = new.header.id,
            headerPatch = headerPatchFor(old.header, new.header, command),
            upsertedNodes = changedNodes,
            deletedNodeIds = deletedNodeIds,
            updateAt = new.header.updateAt,
            upsertedNodeIndices = changedIndices,
            indexForSearch = new.header.parentConversationId == null,
            searchMetadataChanged = new.header.title != old.header.title ||
                new.header.updateAt != old.header.updateAt,
            titleForIndex = new.header.title,
        )
    }

    private fun headerPatchFor(
        oldHeader: ConversationHeader,
        newHeader: ConversationHeader,
        command: ConversationCommand,
    ): ConversationHeaderPatch? {
        val fromCommand = when (command) {
            is HeaderConversationCommand -> headerPatchFromCommand(oldHeader, command)
            is AppendUserMessage -> ConversationHeaderPatch(
                title = newHeader.title.takeIf { it != oldHeader.title },
            ).takeUnless { it.isNoOp() }
            else -> null
        }
        return fromCommand ?: ConversationHeaderPatch().takeIf { newHeader.updateAt != oldHeader.updateAt }
    }

    private fun headerPatchFromCommand(
        oldHeader: ConversationHeader,
        command: HeaderConversationCommand,
    ): ConversationHeaderPatch? = when (command) {
        is UpdateHeader -> ConversationHeaderPatch(
            title = command.title?.takeIf { it != oldHeader.title },
            chatSuggestions = command.suggestions?.takeIf { it != oldHeader.chatSuggestions },
            isPinned = command.isPinned?.takeIf { it != oldHeader.isPinned },
            folderId = when (val folderId = command.folderId) {
                OptionalFolderId.Keep -> OptionalFolderId.Keep
                OptionalFolderId.Clear -> if (oldHeader.folderId == null) OptionalFolderId.Keep else OptionalFolderId.Clear
                is OptionalFolderId.SetTo -> if (oldHeader.folderId == folderId.id) OptionalFolderId.Keep else folderId
            },
            customSystemPrompt = when (val prompt = command.customSystemPrompt) {
                OptionalString.Keep -> OptionalString.Keep
                is OptionalString.Set -> if (prompt.value == oldHeader.customSystemPrompt) OptionalString.Keep else prompt
            },
            modeInjectionIds = when (val ids = command.modeInjectionIds) {
                OptionalUuidSet.Keep -> OptionalUuidSet.Keep
                is OptionalUuidSet.Set -> if (ids.value == oldHeader.modeInjectionIds) OptionalUuidSet.Keep else ids
            },
            workspaceCwd = when (val cwd = command.workspaceCwd) {
                OptionalString.Keep -> OptionalString.Keep
                is OptionalString.Set -> if (cwd.value == oldHeader.workspaceCwd) OptionalString.Keep else cwd
            },
        ).takeUnless { it.isNoOp() }
        is UpdateTitleIfCurrent -> ConversationHeaderPatch(title = command.title)
            .takeIf { oldHeader.title == command.expectedTitle && command.title != oldHeader.title }
        is MoveToAssistant -> ConversationHeaderPatch(
            assistantId = command.assistantId,
            folderId = OptionalFolderId.Clear,
        ).takeIf { oldHeader.assistantId != command.assistantId }
        TogglePinned -> ConversationHeaderPatch(isPinned = !oldHeader.isPinned)
    }

    private fun ConversationHeaderPatch.isNoOp(): Boolean =
        title == null &&
            chatSuggestions == null &&
            isPinned == null &&
            folderId is OptionalFolderId.Keep &&
            assistantId == null &&
            customSystemPrompt is OptionalString.Keep &&
            modeInjectionIds is OptionalUuidSet.Keep &&
            workspaceCwd is OptionalString.Keep

    private fun factsOf(
        conversationId: Uuid,
        command: ConversationCommand,
        nowMillis: Long,
    ): ExecutionFacts? = when (command) {
        is StartTurn -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.turnId,
                TurnExecutionStatus.RUNNING,
                null,
                command.assistantMessageId,
                nowMillis,
            ),
            toolExecution = null,
            turnOperation = TurnExecutionOperation.START,
        )
        is CommitCheckpoint -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.handle.turnId,
                command.turnStatus,
                command.turnReason,
                command.handle.assistantMessageId,
                nowMillis,
            ),
            toolExecution = command.toolExecution,
        )
        is FinalizeTurn -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.handle.turnId,
                command.terminalStatus,
                command.terminalReason,
                command.handle.assistantMessageId,
                nowMillis,
            ),
            toolExecution = null,
        )
        is RecoverInterruptedTurn -> ExecutionFacts(
            turn = buildTurn(
                conversationId,
                command.turnId,
                TurnExecutionStatus.INTERRUPTED,
                command.terminalReason,
                command.assistantMessageId,
                nowMillis,
            ),
            toolExecution = null,
            turnOperation = TurnExecutionOperation.RECOVER,
        )
        else -> null
    }

    private fun buildTurn(
        conversationId: Uuid,
        turnId: Uuid,
        status: TurnExecutionStatus,
        reason: String?,
        assistantMessageId: Uuid?,
        nowMillis: Long,
    ): TurnExecutionEntity = TurnExecutionEntity(
        turnId = turnId.toString(),
        conversationId = conversationId.toString(),
        assistantMessageId = assistantMessageId?.toString(),
        status = status,
        reason = reason,
        createdAt = nowMillis,
        updatedAt = nowMillis,
    )

    private fun startTurn(current: ConversationSnapshot, command: StartTurn): ConversationSnapshot {
        if (current.nodes.isEmpty()) {
            val node = MessageNode.of(emptyAssistantMessage(command.assistantMessageId))
            return current.copy(nodes = listOf(node))
        }
        val last = current.nodes.last()
        val lastMsg = last.currentMessage
        if (command.resume && lastMsg.id == command.assistantMessageId && lastMsg.role == MessageRole.ASSISTANT) {
            return current
        }
        val nodes = current.nodes.toMutableList()
        if (lastMsg.role == MessageRole.ASSISTANT && lastMsg.terminalStatus == null) {
            val msg = emptyAssistantMessage(command.assistantMessageId)
            nodes[nodes.lastIndex] = last.copy(messages = last.messages + msg, selectIndex = last.messages.size)
        } else {
            nodes.add(MessageNode.of(emptyAssistantMessage(command.assistantMessageId)))
        }
        return current.copy(nodes = nodes)
    }

    private fun replaceMessages(
        current: ConversationSnapshot,
        assistantMessageId: Uuid,
        messages: List<UIMessage>,
    ): ConversationSnapshot {
        val message = messages.lastOrNull() ?: return current
        require(message.id == assistantMessageId) {
            "Checkpoint payload does not end with the active assistant message"
        }
        return current.replaceMessageById(assistantMessageId, message, requireLastNode = true)
    }

    private fun finalizeTurn(current: ConversationSnapshot, command: FinalizeTurn): ConversationSnapshot {
        var result = current
        command.messages?.let { result = replaceMessages(result, command.handle.assistantMessageId, it) }
        result = result.finishReasoning(command.handle.assistantMessageId)
        toMessageTerminalStatus(command.terminalStatus)?.let { status ->
            result = result.markAssistantTerminalInternal(
                command.handle.assistantMessageId,
                status,
                command.terminalReason,
                command.terminalDetail,
            )
        }
        if (command.closeInterruptedTools) {
            result = result.closePendingTools(command.handle.assistantMessageId, cancelledByUser = false)
        }
        return result.markAssistantFinishedAt(command.handle.assistantMessageId, command.finishedAt)
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
        return result.markAssistantFinishedAt(command.assistantMessageId, command.finishedAt)
    }

    /** 所有终态统一覆盖中间 step 时间，确保消息 Total 表示完整 turn 生命周期。 */
    private fun ConversationSnapshot.markAssistantFinishedAt(
        messageId: Uuid,
        finishedAt: kotlinx.datetime.LocalDateTime,
    ): ConversationSnapshot {
        val message = findMessage(messageId) ?: return this
        return replaceMessageById(messageId, message.copy(finishedAt = finishedAt), requireLastNode = false)
    }

    private fun ConversationSnapshot.finishReasoning(messageId: Uuid): ConversationSnapshot {
        val message = findMessage(messageId) ?: return this
        val unfinished = message.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null }
        return if (unfinished) replaceMessageById(messageId, message.finishReasoning(), false) else this
    }

    private fun toMessageTerminalStatus(status: TurnExecutionStatus): MessageTerminalStatus? = when (status) {
        TurnExecutionStatus.CANCELLED -> MessageTerminalStatus.CANCELLED
        TurnExecutionStatus.FAILED -> MessageTerminalStatus.FAILED
        TurnExecutionStatus.INCOMPLETE -> MessageTerminalStatus.INCOMPLETE
        TurnExecutionStatus.INTERRUPTED -> MessageTerminalStatus.INTERRUPTED
        else -> null
    }

    private fun appendUser(current: ConversationSnapshot, command: AppendUserMessage): ConversationSnapshot =
        current.copy(
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

    private fun selectNodeVariant(
        current: ConversationSnapshot,
        command: SelectNodeVariant,
    ): ConversationSnapshot {
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

    private fun ResolveToolInteraction(
        current: ConversationSnapshot,
        command: ResolveToolInteraction,
    ): ConversationSnapshot {
        val durableMessage = current.findMessage(command.messageId) ?: return current
        val updatedDurableMessage = ResolveToolInteraction(durableMessage, command) ?: return current
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
                    ResolveToolInteraction(active.messages[messageIndex], command)
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

    private fun commitCheckpoint(
        current: ConversationSnapshot,
        command: CommitCheckpoint,
    ): ConversationSnapshot {
        val patches = command.toolOutputCompactionPatches
        require(patches.map { it.locator }.distinct().size == patches.size) {
            "Tool Output compaction patches contain duplicate locators"
        }
        require(patches.isEmpty() || command.kind == CheckpointKind.STEP_COMPLETED) {
            "Tool Output compaction patches are only valid on a completed Provider step"
        }
        val activePatches = patches.filter { it.locator.messageId == command.handle.assistantMessageId }
        if (command.kind == CheckpointKind.STEP_COMPLETED) {
            val activeSource = current.nodes.lastOrNull()?.currentMessage
                ?.takeIf { it.id == command.handle.assistantMessageId }
                ?: error("Active Tool Output compaction source is missing")
            val expected = activePatches.fold(activeSource) { message, patch ->
                applyToolOutputCompactionPatch(message, patch)
            }
            val projected = command.messages.lastOrNull()
                ?.takeIf { it.id == command.handle.assistantMessageId }
                ?: error("Active Tool Output compaction projection is missing")
            activeSource.parts.filterIsInstance<UIMessagePart.Tool>().indices.forEach { toolOrdinal ->
                require(toolAtOrdinal(projected, toolOrdinal) == toolAtOrdinal(expected, toolOrdinal)) {
                    "Active Tool Output projection changed an existing Tool outside its typed patch"
                }
            }
        }
        var replaced = replaceMessages(current, command.handle.assistantMessageId, command.messages)
        patches.filterNot { it.locator.messageId == command.handle.assistantMessageId }.forEach { patch ->
            replaced = applyHistoricalToolOutputCompactionPatch(replaced, patch)
        }
        val active = current.activeTurn ?: return replaced
        return replaced.copy(activeTurn = active.afterCheckpoint(command))
    }

    /**
     * 历史改写只接受 locator 指向的已消费纯文本 Tool Result，并只替换该 Tool 的 output/archive。
     * 可归档文本必须携带新 Artifact；可再生回查结果只能写固定 marker，不得复制 payload。
     * 任何正文、usage、时间、Tool 身份或其他 part 都没有进入命令协议，因而不能被顺带回写。
     */
    private fun applyHistoricalToolOutputCompactionPatch(
        current: ConversationSnapshot,
        patch: net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch,
    ): ConversationSnapshot {
        val node = current.nodes.firstOrNull { it.currentMessage.id == patch.locator.messageId }
            ?: error("Historical Tool Output patch is not on the selected branch: ${patch.locator.messageId}")
        val message = node.currentMessage
        require(message.role == MessageRole.ASSISTANT) {
            "Historical Tool Output patch must target an Assistant message"
        }
        val updated = applyToolOutputCompactionPatch(message, patch)
        return current.replaceMessageById(
            patch.locator.messageId,
            updated,
            requireLastNode = false,
        )
    }

    /** 对单条 Assistant 的一个 locator 应用并验证窄 Tool Output 压缩协议。 */
    private fun applyToolOutputCompactionPatch(
        message: UIMessage,
        patch: net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch,
    ): UIMessage {
        require(message.role == MessageRole.ASSISTANT) {
            "Tool Output compaction patch must target an Assistant message"
        }
        var ordinal = 0
        var matched = false
        val updatedParts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val currentOrdinal = ordinal++
            if (currentOrdinal != patch.locator.toolOrdinal) return@map part
            require(!matched) { "Tool Output compaction patch matched more than one Tool" }
            val existingArchive = ToolRuntimeMetadata.archiveOf(part.metadata)
            if (existingArchive != null) {
                // 同一 checkpoint 在“durable 已提交、lease 发布结果未返回”后可能被恢复重放。
                // 精确相同的 patch 必须幂等；不同归档仍是冲突，不能覆盖已有 durable 事实。
                require(existingArchive == patch.archive && part.output == listOf(patch.marker)) {
                    "Tool Output archive patch conflicts with the committed archive"
                }
                matched = true
                return@map part
            }
            val outputPolicy = ToolRuntimeMetadata.outputPolicyOf(part.metadata)
            if (patch.archive == null &&
                outputPolicy == ToolOutputPolicy.REGENERABLE_TEXT.name &&
                part.output == listOf(patch.marker) &&
                patch.marker.text == REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
            ) {
                matched = true
                return@map part
            }
            val expectedPolicy = if (patch.archive == null) {
                ToolOutputPolicy.REGENERABLE_TEXT.name
            } else {
                ToolOutputPolicy.ARCHIVABLE_TEXT.name
            }
            require(
                outputPolicy == expectedPolicy &&
                    part.output.isNotEmpty() &&
                    part.output.all { it is UIMessagePart.Text }
            ) { "Tool Output patch target has the wrong compaction policy" }
            val inlineText = part.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            require(
                estimateStableTextTokens(inlineText) - estimateStableTextTokens(patch.marker.text) >=
                    ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS
            ) {
                "Tool Output patch is below the minimum estimated token reclaim"
            }
            val terminalStatus = ToolRuntimeMetadata.terminalStatusOf(part.metadata)
            require(terminalStatus == "completed" || terminalStatus == "failed") {
                "Tool Output patch target has no compactable terminal status"
            }
            val archive = patch.archive
            val metadata = if (archive == null) {
                require(patch.marker.text == REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER) {
                    "Regenerable Tool Output marker is invalid"
                }
                part.metadata
            } else {
                val canonical = canonicalizeToolOutput(inlineText)
                require(archive.characters == canonical.length.toLong()) {
                    "Tool Output archive character count does not match"
                }
                require(archive.lines == virtualLineCount(canonical)) {
                    "Tool Output archive line count does not match"
                }
                require(patch.marker.text == buildToolOutputMarker(archive, terminalStatus, canonical)) {
                    "Tool Output marker does not match the archive"
                }
                ToolRuntimeMetadata.withArchive(part.metadata, archive).also { archivedMetadata ->
                    require(ToolRuntimeMetadata.archiveOf(archivedMetadata) == archive) {
                        "Tool Output archive metadata is invalid"
                    }
                }
            }
            matched = true
            part.copy(output = listOf(patch.marker), metadata = metadata)
        }
        require(matched) { "Tool Output patch locator does not resolve a Tool" }
        return message.copy(parts = updatedParts)
    }

    private fun toolAtOrdinal(message: UIMessage, toolOrdinal: Int): UIMessagePart.Tool =
        message.parts.filterIsInstance<UIMessagePart.Tool>().getOrNull(toolOrdinal)
            ?: error("Tool Output patch locator does not resolve a projected Tool")

    private fun ResolveToolInteraction(
        message: UIMessage,
        command: ResolveToolInteraction,
    ): UIMessage? {
        var ordinal = 0
        var matched = false
        val updatedParts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val currentOrdinal = ordinal++
            if (currentOrdinal != command.toolOrdinal) return@map part
            if (part.hasReplayResult) return null
            if (part.approvalState == command.approvalState) {
                matched = true
                return@map part
            }
            if (part.approvalState !is ToolApprovalState.Pending) return null
            require(!ToolRuntimeMetadata.isInvalid(part.metadata)) {
                "pending tool interaction metadata is invalid"
            }
            val interaction = ToolRuntimeMetadata.interactionKindOf(part.metadata)
            if (interaction != null && !command.decision.matches(interaction)) return null
            matched = true
            part.copy(approvalState = command.approvalState)
        }
        return if (matched) message.copy(parts = updatedParts) else null
    }

    /** reducer 在 durable 写入边界再次校验 typed 决策；旧消息缺 metadata 时由 Runtime 恢复重建。 */
    private fun ToolUserDecision.matches(interaction: ToolInteractionKind): Boolean = when (this) {
        ToolUserDecision.Approve,
        is ToolUserDecision.Deny,
        -> interaction == ToolInteractionKind.APPROVAL

        is ToolUserDecision.Answer -> interaction == ToolInteractionKind.USER_INPUT
    }

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
        detail: String? = null,
    ): ConversationSnapshot {
        if (messageId == null) return this
        val message = findMessage(messageId)?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
        return replaceMessageById(
            messageId,
            message.copy(
                terminalStatus = status,
                terminalReason = reason,
                terminalDetail = detail,
            ),
            requireLastNode = false,
        )
    }

    private fun ConversationSnapshot.closePendingTools(
        messageId: Uuid,
        cancelledByUser: Boolean,
    ): ConversationSnapshot {
        val message = findMessage(messageId) ?: return this
        val hasPending = message.parts.any { it is UIMessagePart.Tool && it.approvalState is ToolApprovalState.Pending }
        if (!hasPending) return this
        val updated = message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool && part.approvalState is ToolApprovalState.Pending) {
                if (cancelledByUser) cancelPendingToolByUser(part) else interruptPendingTool(part)
            } else {
                part
            }
        })
        return replaceMessageById(messageId, updated, false)
    }
}

internal fun cancelPendingToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    output = listOf(
        UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}""",
        ),
    ),
    approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
)

internal fun interruptPendingTool(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    output = listOf(
        UIMessagePart.Text(
            """{"status":"interrupted","error":"Tool execution was interrupted before completion."}""",
        ),
    ),
)

internal fun ConversationCommand.updatesConversationActivity(): Boolean = this !is HeaderConversationCommand

internal fun ConversationSnapshot.materializeConversation(): Conversation = Conversation(
    id = conversationId,
    assistantId = header.assistantId,
    title = header.title,
    messageNodes = nodes,
    chatSuggestions = header.chatSuggestions,
    isPinned = header.isPinned,
    createAt = Instant.ofEpochMilli(header.createAt),
    updateAt = Instant.ofEpochMilli(header.updateAt),
    customSystemPrompt = header.customSystemPrompt,
    modeInjectionIds = header.modeInjectionIds,
    workspaceCwd = header.workspaceCwd,
    folderId = header.folderId,
    parentConversationId = header.parentConversationId,
    newConversation = header.newConversation,
)
