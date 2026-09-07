package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.data.ai.attachments.AttachmentRefBackfill
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.MessageNode
import java.time.Instant
import kotlin.uuid.Uuid

internal sealed interface ConversationChange {
    val snapshot: ConversationAggregateSnapshot

    data class DraftOnly(
        override val snapshot: ConversationAggregateSnapshot,
    ) : ConversationChange

    data class Durable(
        override val snapshot: ConversationAggregateSnapshot,
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
 * 会话结构事实（header / tree / variant / attachment）的唯一 reducer。Turn transcript 的
 * 归约与执行事实由 [TurnTransition] 负责；两者由同一个 [ConversationCommandCoordinator]
 * 分发，仍是一个写入口、一个事务。
 */
internal object ConversationTransition {

    fun plan(
        current: ConversationAggregateSnapshot,
        command: ConversationCommand,
        nowMillis: Long,
    ): ConversationChange {
        if (current.header.newConversation) {
            return planDraft(current, command, nowMillis)
        }
        val reduced = apply(current, command)
        val snapshot = stampActivity(current, reduced, command, nowMillis)
        val mutation = mutationOf(current, snapshot, command)
        val facts = TurnTransition.factsOf(current.conversationId, command, nowMillis)
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
        current: ConversationAggregateSnapshot,
        command: ConversationCommand,
    ): ConversationAggregateSnapshot {
        val reduced = when (command) {
            is StartTurn,
            is TurnCheckpoint,
            is FinalizeTurn,
            is RecoverInterruptedTurn,
            is ResolveToolInteraction,
            -> TurnTransition.reduce(current, command)
            is AppendUserMessage -> appendUser(current, command)
            is EditMessageVariant -> editVariant(current, command)
            is DeleteMessage -> deleteMessage(current, command)
            is SelectNodeVariant -> selectNodeVariant(current, command)
            is TruncateToNodeIndex -> truncateTo(current, command.nodeIndexInclusive)
            is ReplaceMessageTree -> current.copy(nodes = command.nodes)
            is BackfillAttachmentRefs -> backfillAttachmentRefs(current, command.backfills)
            is HeaderConversationCommand -> current.copy(header = applyHeader(current.header, command))
        }
        // context 生命周期收口的唯一位置：node/variant 一旦被任何树命令删除，其 entry 即从
        // aggregate 消失（DB 侧由同一 mutation 的显式 owner/anchor 删除与 FK cascade 对齐）。
        // 选择变体不会剪枝——unselected owner 的 entry 保留，切回时恢复其 baseline。
        val pruned = reduced.modelContextEntries.filter {
            ConversationModelContextApplicability.stillExists(it, reduced.nodes)
        }
        val next = reduced.copy(
            modelContextEntries = if (pruned.size == reduced.modelContextEntries.size) {
                reduced.modelContextEntries
            } else {
                pruned
            },
        )
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
        current: ConversationAggregateSnapshot,
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

    private fun stampActivity(
        old: ConversationAggregateSnapshot,
        snapshot: ConversationAggregateSnapshot,
        command: ConversationCommand,
        nowMillis: Long,
    ): ConversationAggregateSnapshot =
        if (snapshot != old && command.updatesConversationActivity()) {
            snapshot.copy(header = snapshot.header.copy(updateAt = nowMillis))
        } else {
            snapshot
        }

    private fun mutationOf(
        old: ConversationAggregateSnapshot,
        new: ConversationAggregateSnapshot,
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
            is AppendUserMessage,
            -> lastNodeDelta()
            is FinalizeTurn,
            is ModelResponseCheckpoint -> {
                lastNodeDelta()
                // 历史 Tool Output marker 不是 active last node；必须作为同一 mutation 的精确节点 delta 落库，
                // 这样本事务才能同时建立 TOOL_OUTPUT durable reference，再允许 lease publish。
                // 无 Tool Final step 的末批压缩随唯一 FinalizeTurn 落定，同样需要改写历史节点。
                val patches = when (command) {
                    is FinalizeTurn -> command.toolOutputCompactionPatches
                    is ModelResponseCheckpoint -> command.toolOutputCompactionPatches
                    else -> emptyList()
                }
                val activeAssistantMessageId = when (command) {
                    is FinalizeTurn -> command.handle.assistantMessageId
                    is ModelResponseCheckpoint -> command.turn.assistantMessageId
                    else -> null
                }
                patches
                    .filterNot { it.locator.assistantMessageId == activeAssistantMessageId }
                    .forEach { patch -> selectedNodeByMessageId(patch.locator.assistantMessageId) }
            }
            is ToolExecutionCheckpoint -> lastNodeDelta()
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
        // model-context 窄 delta：插入只来自 StartTurn 的判等结果；删除覆盖
        // node 级（FK cascade 之外的 anchor 悬挂行）与 variant 级两种收口。
        val oldContextOwners = old.modelContextEntries.mapTo(HashSet()) { it.ownerNodeId to it.ownerMessageId }
        val insertedContextEntries = new.modelContextEntries.filter {
            (it.ownerNodeId to it.ownerMessageId) !in oldContextOwners
        }
        val deletedContextEntries = old.modelContextEntries
            .filterNot { ConversationModelContextApplicability.stillExists(it, new.nodes) }
        return ConversationMutation(
            conversationId = new.header.id,
            headerPatch = headerPatchFor(old.header, new.header, command),
            upsertedNodes = changedNodes,
            deletedNodeIds = deletedNodeIds,
            updateAt = new.header.updateAt,
            upsertedNodeIndices = changedIndices,
            insertedModelContextEntries = insertedContextEntries,
            deletedModelContextEntries = deletedContextEntries,
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

    private fun appendUser(current: ConversationAggregateSnapshot, command: AppendUserMessage): ConversationAggregateSnapshot =
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

    private fun editVariant(current: ConversationAggregateSnapshot, command: EditMessageVariant): ConversationAggregateSnapshot {
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

    private fun deleteMessage(current: ConversationAggregateSnapshot, command: DeleteMessage): ConversationAggregateSnapshot {
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
        current: ConversationAggregateSnapshot,
        command: SelectNodeVariant,
    ): ConversationAggregateSnapshot {
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

    private fun truncateTo(current: ConversationAggregateSnapshot, nodeIndexInclusive: Int): ConversationAggregateSnapshot {
        if (nodeIndexInclusive >= current.nodes.size) return current
        return current.copy(nodes = current.nodes.subList(0, nodeIndexInclusive + 1))
    }

    private fun backfillAttachmentRefs(
        current: ConversationAggregateSnapshot,
        backfills: List<AttachmentRefBackfill>,
    ): ConversationAggregateSnapshot {
        val nodes = AttachmentRefs.applyBackfills(current.nodes, backfills)
        return if (nodes === current.nodes) current else current.copy(nodes = nodes)
    }
}

internal fun ConversationCommand.updatesConversationActivity(): Boolean = this !is HeaderConversationCommand

internal fun ConversationAggregateSnapshot.materializeConversation(): Conversation = Conversation(
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
