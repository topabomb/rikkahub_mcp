package net.weero.measix.pilot.service.turn

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.subassistant.SubAssistantRunGate
import net.weero.measix.pilot.service.subassistant.finalizeSubAssistantToolsAfterInterruption
import net.weero.measix.pilot.service.subassistant.reconcileMasterSubAssistantCalls
import net.weero.measix.pilot.service.subassistant.resolveValidChildSnapshotLineage
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.RecoverInterruptedTurn
import kotlin.time.Clock
import kotlin.time.Instant
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
     *  - Master 行 → 定点加载并校验 Child 链接，暂不发布父级终态
     *  - 被引用 Child → 定点加载并先收口；父级 metadata 随后与其 Turn/Tool 终态同事务提交
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
            requireNotNull(conversationRepo.getConversationSnapshotById(masterId)) {
                "non-terminal execution references missing master conversation $masterId"
            }
        }
        // 仅加载被非终态调用引用的 Child（每 Child 一次）
        val metadataChildIds = masters.asSequence()
            .flatMap { master -> master.nodes.asSequence() }
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
            conversationRepo.getConversationSnapshotById(childId)?.let { childId to it }
        }.toMap()
        interruptedChildIds.forEach { childId ->
            requireNotNull(childrenById[childId]) {
                "non-terminal execution references missing child conversation $childId"
            }
        }

        scoped.filter { !it.isChild }.forEach { scopedExecution ->
            val master = masters.single { it.conversationId.toString() == scopedExecution.execution.conversationId }
            val executions = conversationRepo.getToolExecutions(scopedExecution.execution.turnId)
            val owningMessage = requireNotNull(master.locateAssistant(scopedExecution.execution.assistantMessageId?.let(Uuid::parse))) {
                "Non-terminal master has no owning Assistant"
            }.second
            validatedStartedToolIds(owningMessage, executions)
            loadLinkedChildren(master, executions)
        }

        val childStopReasons = mutableMapOf<Uuid, String>()

        masters.forEach { master ->
            val result = reconcileMasterSubAssistantCalls(
                masterId = master.conversationId,
                masterAssistantId = master.header.assistantId,
                masterNodes = master.nodes,
                settings = settings,
                childrenById = childrenById,
                json = json,
            )
            result.childStopReasons.forEach { (childId, reason) ->
                childStopReasons.putIfAbsent(childId, reason)
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
            val initial = requireNotNull(conversationRepo.getConversationSnapshotById(conversationId)) {
                "non-terminal execution references missing conversation $conversationId"
            }
            check(initial.header.parentConversationId == null) {
                "master recovery query returned child conversation $conversationId"
            }
            val runtime = commandCoordinator.load(conversationId)
            executions.forEach executionLoop@{ execution ->
                val turnId = Uuid.parse(execution.turnId)
                val assistantMessageId = execution.assistantMessageId?.let(Uuid::parse)
                // 后续 execution 复用 Runtime 状态（上一恢复命令已更新树）。
                val snapshot = runtime.durable
                val located = requireNotNull(assistantMessageId?.let(snapshot::locateAssistant)) {
                    "non-terminal turn ${execution.turnId} references a missing owning assistant message " +
                        "in conversation $conversationId"
                }
                val (_, message) = located
                val toolExecutions = conversationRepo.getToolExecutions(execution.turnId)
                val startedLocalCallIds = validatedStartedToolIds(message, toolExecutions)
                val children = loadLinkedChildren(snapshot, toolExecutions)
                val reconciled = reconcileMasterSubAssistantCalls(
                    masterId = conversationId,
                    masterAssistantId = snapshot.header.assistantId,
                    masterNodes = listOf(MessageNode.of(message)),
                    settings = settingsStore.effectiveSettings.value.settings,
                    childrenById = children,
                    json = json,
                ).masterNodes.single().messages.single()
                val recoveredMessage = closeOpenTurnForProcessRestart(reconciled, startedLocalCallIds)
                commandCoordinator.executeRecovery(
                    conversationId,
                    RecoverInterruptedTurn(
                        turnId = turnId,
                        assistantMessageId = requireNotNull(assistantMessageId),
                        assistantMessage = recoveredMessage,
                        terminalReason = TurnTerminalReasons.PROCESS_RESTARTED,
                    ),
                )
            }
        }
    }

    private suspend fun loadLinkedChildren(
        master: ConversationAggregateSnapshot,
        executions: List<ToolExecutionEntity>,
    ): Map<Uuid, ConversationAggregateSnapshot> {
        val metadataChildren = master.nodes.flatMap { it.messages }.flatMap { it.getTools() }
            .mapNotNull { it.getSubAssistantCallMetadata(json)?.childConversationId }.map(Uuid::parse)
        val linkedChildren = executions.mapNotNull { it.childConversationId }.map(Uuid::parse)
        val children = (metadataChildren + linkedChildren).distinct().mapNotNull { id ->
            conversationRepo.getConversationSnapshotById(id)?.let { id to it }
        }.toMap()
        executions.filter { it.childConversationId != null }.forEach { execution ->
            val childId = Uuid.parse(requireNotNull(execution.childConversationId))
            val child = requireNotNull(children[childId]) { "Execution references missing child $childId" }
            check(child.header.parentConversationId == master.conversationId) { "Execution child has a different parent" }
            val tool = master.nodes.flatMap { it.messages }.flatMap { it.getTools() }
                .single { it.localCallId.toString() == execution.localCallId }
            val metadata = requireNotNull(tool.getSubAssistantCallMetadata(json)) { "Child execution has no run metadata" }
            check(metadata.childConversationId == execution.childConversationId) { "Child execution and metadata disagree" }
            requireNotNull(resolveValidChildSnapshotLineage(master.conversationId, metadata, children)) {
                "Execution Child lineage does not match its target and task"
            }
            if (execution.subAssistantRunId != null) {
                check(metadata.runId == execution.subAssistantRunId) { "Child execution belongs to a different run" }
            }
            execution.childTurnId?.let { rawTurnId ->
                val turnId = Uuid.parse(rawTurnId)
                check(execution.subAssistantRunId != null) { "Child Turn link has no run identity" }
                // Absence is legal only before the allocated Child START has been committed.
                conversationRepo.getTurnExecutions(childId).firstOrNull { it.turnId == turnId.toString() }?.let { childTurn ->
                    check(childTurn.conversationId == childId.toString()) { "Linked Child Turn has a different owner" }
                }
            }
        }
        return children
    }

    private fun validatedStartedToolIds(message: UIMessage, executions: List<ToolExecutionEntity>): Set<Uuid> {
        val tools = message.getTools().associateBy { it.localCallId }
        return executions.mapNotNull { execution ->
            val localCallId = Uuid.parse(execution.localCallId)
            val stepId = Uuid.parse(execution.stepId)
            val tool = requireNotNull(tools[localCallId]) { "Execution references missing Tool $localCallId" }
            check(tool.stepId == stepId) { "Execution references a different Tool Step" }
            localCallId.takeIf { execution.status == ToolExecutionStatus.STARTED }
        }.toSet()
    }

    private suspend fun recoverInterruptedChildTurn(
        childId: Uuid,
        child: ConversationAggregateSnapshot,
        reason: String,
    ) {
        var snapshot = child
        conversationRepo.getTurnExecutions(childId)
            .filter {
                it.status == TurnExecutionStatus.RUNNING ||
                    it.status == TurnExecutionStatus.AWAITING_USER
            }
            .forEach executionLoop@{ execution ->
                val turnId = Uuid.parse(execution.turnId)
                val assistantMessageId = execution.assistantMessageId?.let(Uuid::parse)
                val located = requireNotNull(assistantMessageId?.let(snapshot::locateAssistant)) {
                    "non-terminal child turn ${execution.turnId} references a missing owning assistant message " +
                        "in conversation $childId"
                }
                val (_, message) = located
                val startedLocalCallIds = validatedStartedToolIds(message,
                    conversationRepo.getToolExecutions(execution.turnId))
                val recoveredMessage = closeOpenTurnForProcessRestart(
                    message = message.finalizeSubAssistantToolsAfterInterruption(reason),
                    startedToolLocalCallIds = startedLocalCallIds,
                )
                val recoverCommand = RecoverInterruptedTurn(
                    turnId = turnId,
                    assistantMessageId = requireNotNull(assistantMessageId),
                    assistantMessage = recoveredMessage,
                    terminalReason = reason,
                )
                commandCoordinator.executeRecovery(
                    childId,
                    recoverCommand,
                )
                snapshot = ConversationTransition.apply(snapshot, recoverCommand)
            }
    }
}

/**
 * 进程重启恢复：对未终态 Assistant 消息做 fail-closed 收口：
 *  - nonterminal Turn        → Interrupted
 *  - STARTED Tool Execution  → Unknown（output + resultStatus = ToolResultStatus.UNKNOWN）
 *  - open/pending Tool Call  → Interrupted result（output + resultStatus = ToolResultStatus.INTERRUPTED）
 *  - open Step               → Interrupted（outcome = StepOutcome.Interrupted）
 */
internal fun closeOpenTurnForProcessRestart(
    message: UIMessage,
    startedToolLocalCallIds: Set<Uuid>,
    now: Instant = Clock.System.now(),
): UIMessage {
    val updatedParts = message.parts.map { part ->
        when (part) {
            is UIMessagePart.Step -> {
                if (part.outcome == null) {
                    part.copy(
                        outcome = StepOutcome.Interrupted,
                        finishedAt = part.finishedAt ?: now,
                    )
                } else {
                    part
                }
            }
            is UIMessagePart.Tool -> {
                if (part.localCallId in startedToolLocalCallIds) {
                    part.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                """{"status":"unknown","error":"The app stopped after tool execution started; the side-effect outcome is unknown and will not be retried automatically."}"""
                            )
                        ),
                        resultStatus = ToolResultStatus.UNKNOWN,
                    )
                } else if (!part.hasReplayResult) {
                    part.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                """{"status":"interrupted","error":"Tool execution was interrupted before completion."}"""
                            )
                        ),
                        resultStatus = ToolResultStatus.INTERRUPTED,
                    )
                } else {
                    part
                }
            }
            is UIMessagePart.Reasoning -> if (part.finishedAt == null) part.copy(finishedAt = now) else part
            else -> part
        }
    }
    return message.copy(parts = updatedParts)
}

// ---- 恢复域私有扩展 ----

private fun ConversationAggregateSnapshot.locateAssistant(messageId: Uuid?): Pair<Int, UIMessage>? {
    if (messageId == null) return null
    nodes.forEachIndexed { index, node ->
        val message = node.messages.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
        if (message != null) return index to message
    }
    return null
}
