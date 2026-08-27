package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.finishReasoning
import net.weero.measix.pilot.data.ai.replaceToolsAtOrdinals
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.RecoverInterruptedTurn
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.toSnapshot
import kotlin.uuid.Uuid

/**
 * 进程中断恢复的唯一所有者。
 *
 * 恢复链路（定点，成本与库大小解耦）：
 *  - master turn：turn_execution 非终态行（DAO JOIN 过滤 Child）→ 会话级加载 → 恢复命令收口
 *  - 子助手 run：turn_execution 非终态行（带 Master/Child 域标注）→ 定点加载 → metadata/child 双侧收口
 * 纯投影与 lineage 校验位于 [SubAssistantReconciliation] 所在文件。
 */
class TurnRecovery(
    private val conversationRepo: ConversationRepository,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val runGate: SubAssistantRunGate = SubAssistantRunGate(),
) {
    // ---- App 启动：子助手 run 恢复（ApplicationRecoveryCoordinator 唯一入口） ----

    /**
     * 子助手 run 收口（定点链路）。输入 = 非终态 turn 事实行（JOIN 会话表区分
     * Master/Child）——恢复成本与库大小解耦：
     *  - Master 行 → 定点加载 → 收口树中 stale 调用（metadata 非 terminal → stopped）
     *  - 被引用 Child → 定点加载 → 收口（finishReasoning + 工具中断 + turn 事实）
     *
     * 孤儿 Child 清理由 v7 自引用 FK CASCADE 结构性杜绝（存量在 Migration_6_7 收敛）。
     */
    suspend fun recoverInterruptedRuns() {
        runGate.cancelAllRuns("app_restarted")
        runGate.cancelPendingInteractions()

        val scoped = conversationRepo.getNonTerminalTurnExecutionsWithScope()
        val masterIds = scoped.asSequence()
            .map { scopedExecution ->
                val rawId = if (scopedExecution.isChild) {
                    requireNotNull(scopedExecution.parentConversationId) {
                        "child turn ${scopedExecution.execution.turnId} has no parent conversation"
                    }
                } else {
                    scopedExecution.execution.conversationId
                }
                Uuid.parse(rawId)
            }
            .toSet()
        val settings = settingsStore.effectiveSettings.value.settings
        // 定点加载候选 Master（每会话一次）
        val masters = masterIds.map { masterId ->
            requireNotNull(conversationRepo.getConversationById(masterId)) {
                "non-terminal execution references missing master conversation $masterId"
            }
        }
        // 仅加载被非终态调用引用的 Child（每 Child 一次）
        val metadataChildIds = masters.asSequence()
            .flatMap { master -> master.messageNodes.asSequence() }
            .flatMap { node -> node.messages.asSequence() }
            .flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Tool>().asSequence() }
            .mapNotNull { tool -> tool.getSubAssistantCallMetadata(json) }
            .filter { !it.state.isTerminal() }
            .mapNotNull { metadata -> metadata.childConversationId }
            .map(Uuid::parse)
            .toSet()
        val interruptedChildIds = scoped.asSequence()
            .filter { it.isChild }
            .map { Uuid.parse(it.execution.conversationId) }
            .toSet()
        val childrenById = (metadataChildIds + interruptedChildIds).mapNotNull { childId ->
            conversationRepo.getConversationById(childId)?.let { childId to it }
        }.toMap()
        interruptedChildIds.forEach { childId ->
            requireNotNull(childrenById[childId]) {
                "non-terminal execution references missing child conversation $childId"
            }
        }

        val childStopReasons = mutableMapOf<Uuid, String>()

        masters.forEach { master ->
            val result = reconcileMasterSubAssistantCalls(
                masterId = master.id,
                masterAssistantId = master.assistantId,
                masterNodes = master.messageNodes,
                settings = settings,
                childrenById = childrenById,
                json = json,
            )
            result.childStopReasons.forEach { (childId, reason) ->
                childStopReasons.putIfAbsent(childId, reason)
            }
            if (result.masterNodes != master.messageNodes) {
                submitRecoveredTree(master.id, result.masterNodes)
            }
        }

        // Every interrupted Child is protocol-complete even when Master metadata is absent or corrupt.
        interruptedChildIds.forEach { childId ->
            val child = requireNotNull(childrenById[childId])
            val reason = childStopReasons[childId] ?: "app_restarted"
            recoverInterruptedChildTurn(childId, child, reason)
        }
    }

    // ---- App 启动：master turn 恢复 ----

    suspend fun recoverInterruptedTurns() {
        val recoverable = conversationRepo.getRecoverableTurnExecutionsByConversation()
        recoverable.forEach { (conversationId, executions) ->
            // 会话级加载一次；DAO 已 JOIN 过滤 Child，任何域错配都视为完整性错误。
            val initial = requireNotNull(conversationRepo.getConversationById(conversationId)) {
                "non-terminal execution references missing conversation $conversationId"
            }
            check(initial.parentConversationId == null) {
                "master recovery query returned child conversation $conversationId"
            }
            val runtime = commandCoordinator.load(conversationId)
            executions.forEach executionLoop@{ execution ->
                val turnId = Uuid.parse(execution.turnId)
                val assistantMessageId = execution.assistantMessageId?.let(Uuid::parse)
                // 后续 execution 复用 Runtime 状态（上一恢复命令已更新树）。
                var snapshot = runtime.snapshot.value
                val located = requireNotNull(assistantMessageId?.let(snapshot::locateAssistant)) {
                    "non-terminal turn ${execution.turnId} references a missing owning assistant message " +
                        "in conversation $conversationId"
                }
                val startedTools = conversationRepo.getToolExecutions(execution.turnId)
                    .filter { it.status == ToolExecutionStatus.STARTED }
                if (startedTools.isNotEmpty()) {
                    val (nodeIndex, message) = located
                    val messageTools = message.getTools()
                    val replacements = startedTools.associate { toolExecution ->
                        val tool = requireNotNull(messageTools.getOrNull(toolExecution.toolOrdinal)) {
                            "tool fact ${toolExecution.executionId} has invalid ordinal ${toolExecution.toolOrdinal}"
                        }
                        toolExecution.toolOrdinal to tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    """{"status":"unknown","error":"The app stopped after tool execution started; the side-effect outcome is unknown and will not be retried automatically."}"""
                                )
                            ),
                        )
                    }
                    val recoveredMessage = message.replaceToolsAtOrdinals(replacements)
                    snapshot = snapshot.copy(
                        nodes = snapshot.nodes.mapIndexed { index, node ->
                            if (index == nodeIndex) {
                                node.copy(
                                    messages = node.messages.map { candidate ->
                                        if (candidate.id == assistantMessageId) recoveredMessage else candidate
                                    }
                                )
                            } else {
                                node
                            }
                        },
                        activeTurn = null,
                    )
                }
                snapshot = snapshot.markAssistantTerminal(
                    messageId = assistantMessageId,
                    status = MessageTerminalStatus.INTERRUPTED,
                    reason = TurnTerminalReasons.PROCESS_RESTARTED,
                )
                commandCoordinator.executeRecovery(
                    conversationId,
                    RecoverInterruptedTurn(
                        turnId = turnId,
                        assistantMessageId = assistantMessageId,
                        messages = snapshot.currentMessages(),
                        terminalReason = TurnTerminalReasons.PROCESS_RESTARTED,
                        closeInterruptedTools = false,
                    )
                )
            }
        }
    }

    /** 恢复树写入与普通树命令共享同一 durable command 协议。 */
    private suspend fun submitRecoveredTree(
        conversationId: Uuid,
        recoveredNodes: List<MessageNode>,
    ) {
        commandCoordinator.executeRecovery(conversationId, ReplaceMessageTree(recoveredNodes))
    }

    private suspend fun recoverInterruptedChildTurn(
        childId: Uuid,
        child: Conversation,
        reason: String,
    ) {
        var snapshot = child.toSnapshot()
        conversationRepo.getTurnExecutions(childId)
            .filter {
                it.status == TurnExecutionStatus.RUNNING ||
                    it.status == TurnExecutionStatus.CREATED ||
                    it.status == TurnExecutionStatus.AWAITING_APPROVAL
            }
            .forEach executionLoop@{ execution ->
                val turnId = Uuid.parse(execution.turnId)
                val assistantMessageId = execution.assistantMessageId?.let(Uuid::parse)
                val located = requireNotNull(assistantMessageId?.let(snapshot::locateAssistant)) {
                    "non-terminal child turn ${execution.turnId} references a missing owning assistant message " +
                        "in conversation $childId"
                }
                val (_, message) = located
                val startedTools = conversationRepo.getToolExecutions(execution.turnId)
                    .filter { it.status == ToolExecutionStatus.STARTED }
                val messageTools = message.getTools()
                val replacements = startedTools.associate { toolExecution ->
                    val tool = requireNotNull(messageTools.getOrNull(toolExecution.toolOrdinal)) {
                        "tool fact ${toolExecution.executionId} has invalid ordinal ${toolExecution.toolOrdinal}"
                    }
                    toolExecution.toolOrdinal to tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                """{"status":"unknown","error":"The app stopped after tool execution started; the side-effect outcome is unknown and will not be retried automatically."}"""
                            )
                        ),
                    )
                }
                val recoveredMessage = message
                    .replaceToolsAtOrdinals(replacements)
                    .finishReasoning()
                    .finalizeSubAssistantToolsAfterInterruption(reason)
                val recoverCommand = RecoverInterruptedTurn(
                    turnId = turnId,
                    assistantMessageId = assistantMessageId,
                    messages = listOf(recoveredMessage),
                    terminalReason = reason,
                    closeInterruptedTools = false,
                )
                commandCoordinator.executeRecovery(
                    childId,
                    recoverCommand,
                )
                snapshot = ConversationTransition.apply(snapshot, recoverCommand)
            }
    }

}

// ---- 恢复域私有扩展 ----

private fun ConversationSnapshot.locateAssistant(messageId: Uuid?): Pair<Int, UIMessage>? {
    if (messageId == null) return null
    nodes.forEachIndexed { index, node ->
        val message = node.messages.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
        if (message != null) return index to message
    }
    return null
}

private fun ConversationSnapshot.markAssistantTerminal(
    messageId: Uuid?,
    status: MessageTerminalStatus,
    reason: String?,
): ConversationSnapshot {
    val located = locateAssistant(messageId) ?: return this
    val (nodeIndex, targetMessage) = located
    val marked = targetMessage.copy(terminalStatus = status, terminalReason = reason)
    return copy(
        nodes = nodes.mapIndexed { index, node ->
            if (index != nodeIndex) {
                node
            } else {
                node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == targetMessage.id) marked else message
                    },
                )
            }
        },
        activeTurn = null,
    )
}
