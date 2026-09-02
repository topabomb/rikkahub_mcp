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
import net.weero.measix.pilot.data.ai.DurableMessageLocator
import net.weero.measix.pilot.data.ai.TurnModelContextProjection
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
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
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
 * [ConversationTransition.planStartTarget] 的结果：一次 `START` 将使用的目标 selected
 * prefix、新 owner node/message 与因果 USER anchor。调用方把它逐项填入 StartTurn 命令，
 * 锁内由同一函数复核。
 */
internal data class StartTurnTarget(
    val assistantNodeId: Uuid,
    val assistantMessageId: Uuid,
    val anchorNodeId: Uuid,
    val anchorMessageId: Uuid,
    val selectedPrefixMessageIds: List<Uuid>,
    val appendVariantToExistingAssistantNode: Boolean,
)

/**
 * Unique conversation command planner. It produces the next snapshot, the exact persistence
 * delta and execution facts together. The coordinator owns locks and IO; the repository owns
 * the Room transaction; the runtime only publishes after commit.
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
        current: ConversationAggregateSnapshot,
        command: ConversationCommand,
    ): ConversationAggregateSnapshot {
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
        // context 生命周期收口的唯一位置：node/variant 一旦被任何树命令删除，其 entry 即从
        // aggregate 消失（DB 侧由同一 mutation 的显式 owner/anchor 删除与 FK cascade 对齐）。
        // 选择变体不会剪枝——unselected owner 的 entry 保留，切回时恢复其 baseline（§14.2）。
        val pruned = reduced.modelContextEntries.filter {
            ConversationModelContextApplicability.stillExists(it, reduced.nodes)
        }
        val next = reduced.copy(
            modelContextEntries = if (pruned.size == reduced.modelContextEntries.size) {
                reduced.modelContextEntries
            } else {
                pruned
            },
            activeTurn = durableActiveTurn(current, reduced, command),
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

    private fun durableActiveTurn(
        old: ConversationAggregateSnapshot,
        reduced: ConversationAggregateSnapshot,
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
        // model-context 窄 delta（§12.2）：插入只来自 StartTurn 的判等结果；删除覆盖
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

    /**
     * `START` 目标分支的唯一纯规划入口（权威方案 §12.2）。regenerate/edit 边界与"新 owner
     * 是既有 Assistant node 的新 variant 还是新 node"都只在这里判定一次；调用方与锁内校验
     * 共用同一个函数，不存在第二套"排除哪个 variant"的判断。
     *
     * 返回的 [StartTurnTarget.selectedPrefixMessageIds] 是结构变换后、新 owner 之前的目标
     * selected branch；将被替换的 unselected Assistant variant 已被排除，因此 regenerate 后
     * 它拥有的 baseline 自然退出比较（§7.5）。
     *
     * 没有因果 USER anchor 的树不能开始模型请求：fail-closed，不按 role 伪造。
     */
    internal fun planStartTarget(
        current: ConversationAggregateSnapshot,
        preallocatedAssistantNodeId: Uuid,
        assistantMessageId: Uuid,
    ): StartTurnTarget {
        if (current.nodes.isEmpty()) {
            throw ConversationCommandConflictException(
                "conversation ${current.conversationId} has no message tree to anchor a START",
            )
        }
        val last = current.nodes.last()
        val lastMsg = last.currentMessage
        check(assistantMessageId != lastMsg.id) {
            "START owner variant collides with the selected message: $assistantMessageId"
        }
        // 树末条是 Assistant 只可能来自 regenerate（新 turn / 编辑都会先提交 USER）：一律按
        // §7.5 把将被替换的旧 variant 视为 unselected；terminalStatus 不豁免，failed /
        // cancelled 的旧回答同样不得留在目标分支。
        val appendVariantToExistingAssistantNode = lastMsg.role == MessageRole.ASSISTANT
        val prefix = if (appendVariantToExistingAssistantNode) {
            current.currentMessages().dropLast(1)
        } else {
            current.currentMessages()
        }
        val anchorIndex = prefix.indexOfLast { it.role == MessageRole.USER }
        if (anchorIndex < 0) {
            throw ConversationCommandConflictException(
                "conversation ${current.conversationId} has no causal USER anchor for the new turn",
            )
        }
        val anchor = prefix[anchorIndex]
        val anchorNode = prefixNodeOf(current.nodes, anchor.id)
            ?: throw ConversationCommandConflictException("causal USER anchor is off the tree: ${anchor.id}")
        return StartTurnTarget(
            assistantNodeId = if (appendVariantToExistingAssistantNode) last.id else preallocatedAssistantNodeId,
            assistantMessageId = assistantMessageId,
            anchorNodeId = anchorNode.id,
            anchorMessageId = anchor.id,
            selectedPrefixMessageIds = prefix.map { it.id },
            appendVariantToExistingAssistantNode = appendVariantToExistingAssistantNode,
        )
    }

    private fun prefixNodeOf(nodes: List<MessageNode>, messageId: Uuid): MessageNode? =
        nodes.firstOrNull { node -> node.messages.any { it.id == messageId } }

    /**
     * Turn 启动时请求侧 model-context 投影的唯一入口（§7.3、§8.2）：
     * branch = 当前 selected 消息序列（含 active owner 末条），entries 用唯一适用谓词过滤。
     * 该 Turn 的所有 step、审批 continuation 与重试只复用这份结果，不重新判定。
     */
    internal fun projectTurnModelContext(
        current: ConversationAggregateSnapshot,
    ): TurnModelContextProjection {
        val branch = current.currentMessages()
        val locators = buildMap {
            current.nodes.forEach { node ->
                node.messages.forEach { message -> put(message.id, DurableMessageLocator(node.id, message.id)) }
            }
        }
        return TurnModelContextProjection(
            entries = current.modelContextEntries.filter {
                ConversationModelContextApplicability.applicable(it, branch)
            },
            locators = locators,
        )
    }

    /**
     * 完整 StartTurn 命令的唯一构造入口（调用方与测试共用，杜绝手拼 anchor/token）。
     * 锁内 plan 会重算目标分支；stale 计划在 [ConversationCommandCoordinator.startTurn]
     * 一律 conflict。
     */
    internal fun buildStartTurnCommand(
        current: ConversationAggregateSnapshot,
        turnId: Uuid,
        modelContextCandidate: String,
        assistantNodeId: Uuid = Uuid.random(),
        assistantMessageId: Uuid = Uuid.random(),
        epoch: Long = 0L,
    ): StartTurn {
        val target = planStartTarget(current, assistantNodeId, assistantMessageId)
        return StartTurn(
            turnId = turnId,
            assistantNodeId = target.assistantNodeId,
            assistantMessageId = target.assistantMessageId,
            anchorNodeId = target.anchorNodeId,
            anchorMessageId = target.anchorMessageId,
            expectedSelectedPrefixMessageIds = target.selectedPrefixMessageIds,
            modelContextCandidate = modelContextCandidate,
            epoch = epoch,
        )
    }

    private fun startTurn(current: ConversationAggregateSnapshot, command: StartTurn): ConversationAggregateSnapshot {
        val target = planStartTarget(current, command.assistantNodeId, command.assistantMessageId)
        // 锁内 CAS：调用者拿到的 snapshot 与执行锁内的 committed snapshot 之间任何
        // prefix variant / anchor 结构变化都在这里冲突，绝不接受 stale 计划（§17.3）。
        if (target.selectedPrefixMessageIds != command.expectedSelectedPrefixMessageIds) {
            throw ConversationCommandConflictException(
                "START target selected branch changed after planStartTarget for conversation ${current.conversationId}",
            )
        }
        if (target.anchorNodeId != command.anchorNodeId || target.anchorMessageId != command.anchorMessageId) {
            throw ConversationCommandConflictException(
                "START causal anchor changed after planStartTarget: ${command.anchorMessageId} -> ${target.anchorMessageId}",
            )
        }
        if (target.assistantNodeId != command.assistantNodeId) {
            throw ConversationCommandConflictException(
                "START owner node identity changed after planStartTarget: ${command.assistantNodeId}",
            )
        }
        // 命令协议只接受合法 canonical envelope；畸形内容不得进入 durable 历史（§12.2）。
        ConversationDisclosureSnapshotService.requireCanonical(command.modelContextCandidate)

        val slot = emptyAssistantMessage(command.assistantMessageId)
        val nodes = current.nodes.toMutableList()
        if (target.appendVariantToExistingAssistantNode) {
            val last = nodes[nodes.lastIndex]
            nodes[nodes.lastIndex] = last.copy(
                messages = last.messages + slot,
                selectIndex = last.messages.size,
            )
        } else {
            nodes.add(
                MessageNode(
                    id = target.assistantNodeId,
                    messages = listOf(slot),
                    selectIndex = 0,
                ),
            )
        }
        // baseline 判等（§7.2）：目标分支上最近一份适用 Snapshot 的 content 与 candidate
        // 逐字相同则不新增 row；变化才由新 owner 追加完整 baseline。
        val branch = nodes.map { it.currentMessage }
        val applicable = current.modelContextEntries.filter {
            ConversationModelContextApplicability.applicable(it, branch)
        }
        val baseline = applicable.maxByOrNull { entry ->
            branch.indexOfFirst { it.id == entry.ownerMessageId }
        }
        val entries = if (baseline?.content == command.modelContextCandidate) {
            current.modelContextEntries
        } else {
            current.modelContextEntries + ConversationModelContextEntry(
                ownerNodeId = target.assistantNodeId,
                ownerMessageId = target.assistantMessageId,
                anchorNodeId = target.anchorNodeId,
                anchorMessageId = target.anchorMessageId,
                content = command.modelContextCandidate,
            )
        }
        return current.copy(nodes = nodes, modelContextEntries = entries)
    }

    private fun replaceMessages(
        current: ConversationAggregateSnapshot,
        assistantMessageId: Uuid,
        messages: List<UIMessage>,
    ): ConversationAggregateSnapshot {
        val message = messages.lastOrNull() ?: return current
        require(message.id == assistantMessageId) {
            "Checkpoint payload does not end with the active assistant message"
        }
        return current.replaceMessageById(assistantMessageId, message, requireLastNode = true)
    }

    private fun finalizeTurn(current: ConversationAggregateSnapshot, command: FinalizeTurn): ConversationAggregateSnapshot {
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
        current: ConversationAggregateSnapshot,
        command: RecoverInterruptedTurn,
    ): ConversationAggregateSnapshot {
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
    private fun ConversationAggregateSnapshot.markAssistantFinishedAt(
        messageId: Uuid,
        finishedAt: kotlinx.datetime.LocalDateTime,
    ): ConversationAggregateSnapshot {
        val message = findMessage(messageId) ?: return this
        return replaceMessageById(messageId, message.copy(finishedAt = finishedAt), requireLastNode = false)
    }

    private fun ConversationAggregateSnapshot.finishReasoning(messageId: Uuid): ConversationAggregateSnapshot {
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

    private fun ResolveToolInteraction(
        current: ConversationAggregateSnapshot,
        command: ResolveToolInteraction,
    ): ConversationAggregateSnapshot {
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
        current: ConversationAggregateSnapshot,
        command: CommitCheckpoint,
    ): ConversationAggregateSnapshot {
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
        current: ConversationAggregateSnapshot,
        patch: net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch,
    ): ConversationAggregateSnapshot {
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

    private fun ConversationAggregateSnapshot.findMessage(messageId: Uuid): UIMessage? {
        nodes.lastOrNull()?.messages?.firstOrNull { it.id == messageId }?.let { return it }
        return nodes.asSequence().flatMap { it.messages.asSequence() }.firstOrNull { it.id == messageId }
    }

    private fun ConversationAggregateSnapshot.replaceMessageById(
        messageId: Uuid,
        replacement: UIMessage,
        requireLastNode: Boolean,
    ): ConversationAggregateSnapshot {
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

    private fun ConversationAggregateSnapshot.markAssistantTerminalInternal(
        messageId: Uuid?,
        status: MessageTerminalStatus,
        reason: String?,
        detail: String? = null,
    ): ConversationAggregateSnapshot {
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

    private fun ConversationAggregateSnapshot.closePendingTools(
        messageId: Uuid,
        cancelledByUser: Boolean,
    ): ConversationAggregateSnapshot {
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
