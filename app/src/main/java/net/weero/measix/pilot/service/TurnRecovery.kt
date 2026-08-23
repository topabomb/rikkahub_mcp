package net.weero.measix.pilot.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishInterruptedTools
import net.weero.measix.pilot.data.ai.replaceToolsAtOrdinals
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantRunSpecResolution
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.collectSubAssistantCallOutputs
import net.weero.measix.pilot.data.ai.subassistant.computeSubAssistantPreview
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtrasFromInput
import net.weero.measix.pilot.data.ai.subassistant.reportSubAssistantMetadataPatch
import net.weero.measix.pilot.data.ai.subassistant.resolveSubAssistantRunSpec
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.toConversation
import kotlin.uuid.Uuid

/**
 * 中断 / 崩溃恢复语义唯一所有者（V1 正式阶段·架构收敛 §11.3 块 2）。
 *
 * 吸收原 ChatService（master turn 恢复 + 取消收口原语）、原 DelegationCoordinator
 * （stale run 收口 + 恢复树写入）、原 SubAssistantRecovery.kt（子助手调用收口纯函数）。
 *
 * 恢复链路（定点，成本与库大小解耦）：
 *  - master turn：turn_execution 非终态行（DAO JOIN 过滤 Child）→ 会话级加载 → FinalizeTurn 收口
 *  - 子助手 run：turn_execution 非终态行（带 Master/Child 域标注）→ 定点加载 → metadata/child 双侧收口
 *  - 变更前收口：Master 树结构变更（fork/delete/truncate）前收口 stale 调用
 *
 * 纯函数（无状态收口/解析）保留为文件内顶层，可独立单测。
 */
class TurnRecovery(
    private val conversationRepo: ConversationRepository,
    private val sessionRegistry: ConversationRuntimeRegistry,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val runGate: SubAssistantRunGate = SubAssistantRunGate(),
) {
    private fun getOrCreateSession(conversationId: Uuid) =
        sessionRegistry.getOrCreateSession(conversationId)

    // ---- App 启动：子助手 run 恢复（恢复入口，AssistantDataRecovery 消费） ----

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
            .filter { !it.isChild }
            .mapNotNull { runCatching { Uuid.parse(it.execution.conversationId) }.getOrNull() }
            .toSet()
        if (masterIds.isEmpty()) return

        val settings = settingsStore.settingsFlow.value
        // 定点加载候选 Master（每会话一次）
        val masters = masterIds.mapNotNull { conversationRepo.getConversationById(it) }
        // 仅加载被非终态调用引用的 Child（每 Child 一次）
        val neededChildIds = masters.asSequence()
            .flatMap { master -> master.messageNodes.asSequence() }
            .flatMap { node -> node.messages.asSequence() }
            .flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Tool>().asSequence() }
            .mapNotNull { tool -> tool.getSubAssistantCallMetadata(json) }
            .filter { !it.state.isTerminal() }
            .mapNotNull { metadata -> metadata.childConversationId }
            .mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }
            .toSet()
        val childrenById = neededChildIds.mapNotNull { childId ->
            conversationRepo.getConversationById(childId)?.let { childId to it }
        }.toMap()

        val referencedChildIds = mutableSetOf<Uuid>()
        val childStopReasons = mutableMapOf<Uuid, String>()

        masters.forEach { master ->
            val result = recoverMasterSubAssistantCalls(master, settings, childrenById, json)
            referencedChildIds += result.referencedChildIds
            result.childStopReasons.forEach { (childId, reason) ->
                childStopReasons.putIfAbsent(childId, reason)
            }
            if (result.master != master) {
                submitRecoveredTree(master.id, result.master)
            }
        }

        // Every valid referenced Child is protocol-complete before it can be reused.
        referencedChildIds.forEach { childId ->
            val child = childrenById[childId] ?: return@forEach
            val reason = childStopReasons[childId] ?: "app_restarted"
            recoverInterruptedChildTree(childId, child, reason)
        }
    }

    /**
     * master 树 mutation（删除/截断）后的 Child retention 收口：retained children 走
     * children 收缩事务（引用替换 + GC），deleted children 删除 + evict。
     */
    suspend fun applyChildRetentionAfterTreeMutation(masterConversationId: Uuid) {
        val master = sessionRegistry.getSession(masterConversationId)
            ?.snapshot?.value?.toConversation()
            ?: conversationRepo.getConversationById(masterConversationId)
            ?: return
        val children = conversationRepo.getChildConversations(master.id).associateBy { it.id }
        val plan = planSubAssistantRetention(master, children, json)
        conversationRepo.updateChildRetention(
            retainedChildren = plan.retainedChildren,
            deletedChildren = plan.deletedChildren,
        )
        plan.retainedChildren.forEach { child ->
            sessionRegistry.getSession(child.id)?.let {
                sessionRegistry.loadConversation(child.id, child)
            }
        }
        plan.deletedChildren.forEach { child -> sessionRegistry.evictSession(child.id) }
    }

    // ---- App 启动：master turn 恢复 ----

    suspend fun recoverInterruptedTurns() {
        val recoverable = conversationRepo.getRecoverableTurnExecutionsByConversation()
        recoverable.forEach { (conversationId, executions) ->
            // 会话级加载一次（DAO 查询已 JOIN 过滤 Child；防御性跳过残留 Child 行——
            // Child turn 由子助手恢复域全权收口，避免双路径）
            val initial = conversationRepo.getConversationById(conversationId) ?: return@forEach
            if (initial.parentConversationId != null) return@forEach
            val session = getOrCreateSession(conversationId)
            session.loadSnapshot(initial)
            executions.forEach executionLoop@ { execution ->
                val assistantMessageId = execution.assistantMessageId
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@executionLoop
                // 后续 execution 复用 session 内存状态（上一 FinalizeTurn 已更新树）
                var conversation = session.snapshot.value.toConversation()
                val located = conversation.locateAssistant(assistantMessageId) ?: return@executionLoop
                val startedTools = conversationRepo.getToolExecutions(execution.turnId)
                    .filter { it.status == ToolExecutionStatus.STARTED }
                if (startedTools.isNotEmpty()) {
                    val (nodeIndex, message) = located
                    val messageTools = message.getTools()
                    val replacements = startedTools.mapNotNull { toolExecution ->
                        val tool = messageTools.getOrNull(toolExecution.toolOrdinal) ?: return@mapNotNull null
                        toolExecution.toolOrdinal to tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    """{"status":"unknown","error":"The app stopped after tool execution started; the side-effect outcome is unknown and will not be retried automatically."}"""
                                )
                            ),
                        )
                    }.toMap()
                    val recoveredMessage = message.replaceToolsAtOrdinals(replacements)
                    conversation = conversation.copy(
                        messageNodes = conversation.messageNodes.mapIndexed { index, node ->
                            if (index == nodeIndex) {
                                node.copy(
                                    messages = node.messages.map { candidate ->
                                        if (candidate.id == assistantMessageId) recoveredMessage else candidate
                                    }
                                )
                            } else {
                                node
                            }
                        }
                    )
                }
                conversation = conversation.markAssistantTerminal(
                    messageId = assistantMessageId,
                    status = MessageTerminalStatus.INTERRUPTED,
                    reason = TurnTerminalReasons.PROCESS_RESTARTED,
                )
                val now = System.currentTimeMillis()
                // 恢复收口走 FinalizeTurn（消息分发 + markAssistantTerminal 幂等 +
                // turn INTERRUPTED 事实 delta 落库）；startedTools 的 UNKNOWN 语义由 App 层
                // 构造、命令提交后单独落库
                session.submit(
                    FinalizeTurn(
                        turnId = runCatching { Uuid.parse(execution.turnId) }.getOrNull()
                            ?: return@executionLoop,
                        assistantMessageId = assistantMessageId,
                        messages = conversation.currentMessages,
                        terminalStatus = TurnExecutionStatus.INTERRUPTED,
                        terminalReason = TurnTerminalReasons.PROCESS_RESTARTED,
                        closeInterruptedTools = false,
                    )
                )
                startedTools.forEach { tool ->
                    conversationRepo.upsertToolExecution(
                        tool.copy(
                            status = ToolExecutionStatus.UNKNOWN,
                            reason = TurnTerminalReasons.PROCESS_RESTARTED,
                            updatedAt = now,
                        )
                    )
                }
            }
        }
        // Even if an execution cannot be projected back into a message (for example an
        // imported/corrupted snapshot has lost the assistant id), it must not remain RUNNING
        // forever. The transactional sweep only touches records that were not finalized above.
        conversationRepo.recoverInterruptedExecutions(updatedAt = System.currentTimeMillis())
    }

    // ---- 变更前收口：Master 树结构变更前的 stale run 收口 ----

    /**
     * Master 树即将发生结构性变更（fork/delete/truncate）前，将树中未终态的子助手
     * 调用一次性收口；未被树引用的 Child（retention 语义）随之删除。
     */
    suspend fun finalizeStaleRunsBeforeMutation(master: Conversation): Conversation {
        require(master.parentConversationId == null)
        val children = conversationRepo.getChildConversations(master.id).associateBy { it.id }
        val result = recoverMasterSubAssistantCalls(
            master = master,
            settings = settingsStore.settingsFlow.value,
            childrenById = children,
            json = json,
        )
        if (result.master != master) {
            submitRecoveredTree(master.id, result.master)
        }
        result.childStopReasons.forEach { (childId, reason) ->
            val child = children[childId] ?: return@forEach
            recoverInterruptedChildTree(childId, child, reason)
        }
        (children.keys - result.referencedChildIds).forEach { orphanId ->
            conversationRepo.deleteConversation(children.getValue(orphanId))
            sessionRegistry.evictSession(orphanId)
        }
        return result.master
    }

    // ---- 取消 / 失败收口原语（launchRun prepareFinalize 消费） ----

    suspend fun closeOpenTools(
        conversation: Conversation,
        messageId: Uuid?,
        cancelledByUser: Boolean = true,
    ): Conversation {
        val located = conversation.locateAssistant(messageId) ?: return conversation
        val (nodeIndex, targetMessage) = located
        var updatedMessage = targetMessage.finishPendingTools { tool ->
            if (cancelledByUser) cancelToolByUser(tool) else interruptPendingTool(tool)
        }
        val childMessagesByConversation = loadChildMessagesForInterruptedCalls(updatedMessage)
        updatedMessage = updatedMessage.finishInterruptedTools { tool ->
            val childId = tool.getSubAssistantCallMetadata(json)?.childConversationId
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            finishInterruptedToolAfterGenerationStop(
                tool = tool,
                json = json,
                childMessages = childId?.let { childMessagesByConversation[it] }.orEmpty(),
            )
        }
        if (updatedMessage == targetMessage) return conversation
        return conversation.copy(
            messageNodes = conversation.messageNodes.mapIndexed { index, node ->
                if (index != nodeIndex) {
                    node
                } else {
                    node.copy(
                        messages = node.messages.map { message ->
                            if (message.id == targetMessage.id) updatedMessage else message
                        },
                    )
                }
            },
        )
    }

    /**
     * 上一回合残留的开放工具收口：仅当确有未终态的上一 turn、且末条 assistant 仍有未执行工具时
     * 才提交 FinalizeTurn(closeInterruptedTools=true)。不得编造 turnId，也不得覆盖
     * bind 已提交的 CANCELLED/COMPLETED 事实。
     */
    suspend fun finishInterruptedPendingTools(conversationId: Uuid, previousTurnId: Uuid?) {
        val turnId = previousTurnId ?: return
        val session = getOrCreateSession(conversationId)
        if (session.isTurnFinalized(turnId)) return
        val lastAssistant = session.snapshot.value.currentMessages().lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT }
            ?: return
        if (lastAssistant.getTools().none { !it.isExecuted }) return
        session.submit(
            FinalizeTurn(
                turnId = turnId,
                assistantMessageId = lastAssistant.id,
                messages = null,
                terminalStatus = TurnExecutionStatus.INTERRUPTED,
                terminalReason = null,
                closeInterruptedTools = true,
            )
        )
    }

    /** 终态后收口 dangling tool executions：turn 内仍处 STARTED 的工具行。 */
    internal suspend fun finalizeDanglingToolExecutions(
        turnId: Uuid,
        outcome: MasterTurnOutcome,
        reason: String?,
    ) {
        val now = System.currentTimeMillis()
        val dangling = conversationRepo.getToolExecutions(turnId.toString())
            .filter { it.status == ToolExecutionStatus.STARTED }
        if (dangling.isEmpty()) return
        dangling.forEach { tool ->
            conversationRepo.upsertToolExecution(
                tool.copy(
                    status = if (outcome == MasterTurnOutcome.CANCELLED) {
                        ToolExecutionStatus.CANCELLED
                    } else {
                        ToolExecutionStatus.UNKNOWN
                    },
                    reason = reason,
                    updatedAt = now,
                )
            )
        }
    }

    // ---- 子助手 run 恢复（DelegationCoordinator 消费） ----

    /** 恢复收口的树写入——活跃 session 走 ReplaceMessageTree 命令（delta 落库）。 */
    internal suspend fun submitRecoveredTree(conversationId: Uuid, recovered: Conversation) {
        val session = sessionRegistry.getSession(conversationId)
        if (session != null) {
            session.submit(ReplaceMessageTree(recovered.messageNodes))
        } else {
            conversationRepo.updateConversation(recovered)
        }
    }

    /** 中断 Child 树收口（finishReasoning + 工具中断 + turn 事实 INTERRUPTED）。 */
    suspend fun recoverInterruptedChild(childConversationId: Uuid, reason: String) {
        val session = sessionRegistry.getSession(childConversationId) ?: run {
            val persisted = conversationRepo.getConversationById(childConversationId) ?: return
            sessionRegistry.getOrCreateSessionWithConversation(childConversationId, persisted)
        }
        val conversation = session.snapshot.value.toConversation()
        val recoveredNodes = conversation.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    message.finishReasoning().recoverSubAssistantToolsAfterInterruption(reason)
                }
            )
        }
        if (recoveredNodes == conversation.messageNodes) return

        submitRecoveredTree(childConversationId, conversation.copy(messageNodes = recoveredNodes))
    }

    /** run 中断收口（Child 树收口 + terminal metadata 持久化，双段各自限时兜底）。 */
    suspend fun finalizeInterruptedRun(
        childConversationId: Uuid,
        reason: String,
        execContext: ToolExecutionContext,
        terminalMeta: SubAssistantCallMetadata,
        json: Json = this.json,
    ) {
        val failures = finalizeInterruptedRunSafely(
            timeoutMillis = FINALIZATION_TIMEOUT_MS,
            finalizeChild = { recoverInterruptedChild(childConversationId, reason) },
            finalizeMetadata = {
                reportSubAssistantMetadataPatch(json, execContext, terminalMeta, checkpoint = false)
            },
        )
        failures.child?.let { error ->
            android.util.Log.e("TurnRecovery", "Unable to finalize interrupted child $childConversationId", error)
        }
        failures.metadata?.let { error ->
            android.util.Log.e("TurnRecovery", "Unable to persist terminal metadata for ${terminalMeta.runId}", error)
        }
    }

    private companion object {
        const val FINALIZATION_TIMEOUT_MS = 5_000L
    }

    internal suspend fun recoverInterruptedChildTree(childId: Uuid, child: Conversation, reason: String) {
        val recoveredNodes = child.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    message.finishReasoning().recoverSubAssistantToolsAfterInterruption(reason)
                }
            )
        }
        if (recoveredNodes != child.messageNodes) {
            submitRecoveredTree(childId, child.copy(messageNodes = recoveredNodes))
        }
        // Child turn 由子助手恢复域全权收口（Master 恢复过滤 Child）
        conversationRepo.finalizeRunningTurnsOfConversation(childId, reason)
    }

    // ---- 私有原语 ----

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private fun interruptPendingTool(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"interrupted","error":"Tool execution was interrupted before completion."}"""
            )
        ),
    )

    private suspend fun loadChildMessagesForInterruptedCalls(
        message: UIMessage,
    ): Map<Uuid, List<UIMessage>> {
        val childIds = message.getTools().mapNotNull { tool ->
            if (tool.toolName != "assistant_call") return@mapNotNull null
            tool.getSubAssistantCallMetadata(json)?.childConversationId
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        }.toSet()
        return childIds.associateWith { childId ->
            sessionRegistry.getSession(childId)?.snapshot?.value?.currentMessages()
                ?: conversationRepo.getConversationById(childId)?.currentMessages
                ?: emptyList()
        }
    }
}

/** 主回合终态分类（副作用收口与执行事实收口共用）。 */
internal enum class MasterTurnOutcome {
    SUCCESS,
    AWAITING_APPROVAL,
    CANCELLED,
    FAILED,
    INCOMPLETE,
}

/**
 * 主回合被用户停止或被新回合替换后，为尚未返回的 ToolCall 补齐协议结果。
 * assistant_call 还必须同步收口 Running Card，避免持久化后继续显示运行中。
 */
internal fun finishInterruptedToolAfterGenerationStop(
    tool: UIMessagePart.Tool,
    json: Json,
    childMessages: List<UIMessage> = emptyList(),
): UIMessagePart.Tool {
    if (tool.toolName == "assistant_call") {
        val metadata = tool.getSubAssistantCallMetadata(json)
        if (metadata != null && !metadata.state.isTerminal()) {
            val stoppedMetadata = metadata.copy(
                state = SubAssistantCallState.STOPPED,
                phase = null,
                activeToolName = null,
                reason = "user_cancelled",
                userInteraction = null,
            )
            val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val outputs = collectSubAssistantCallOutputs(
                messages = childMessages,
                childTaskNodeId = taskId,
                extras = parseAssistantCallExtrasFromInput(tool.input),
            )
            return tool.mergeSubAssistantCallMetadata(json, stoppedMetadata).copy(
                output = listOf(
                    UIMessagePart.Text(
                        buildSubAssistantCallResult(
                            json = json,
                            status = "stopped",
                            assistantName = metadata.targetNameSnapshot,
                            content = "",
                            reason = "user_cancelled",
                            toolCalls = outputs.toolCalls,
                            ttsTexts = outputs.ttsTexts,
                            ttsStats = outputs.ttsStats,
                        )
                    )
                )
            )
        }
    }
    return tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"interrupted","error":"Tool execution was interrupted before completion."}"""
            )
        )
    )
}

internal data class InterruptedRunFinalizationFailures(
    val child: Throwable?,
    val metadata: Throwable?,
)

internal suspend fun finalizeInterruptedRunSafely(
    timeoutMillis: Long,
    finalizeChild: suspend () -> Unit,
    finalizeMetadata: suspend () -> Unit,
): InterruptedRunFinalizationFailures = withContext(NonCancellable) {
    val childFailure = runCatching {
        withTimeout(timeoutMillis) { finalizeChild() }
    }.exceptionOrNull()
    val metadataFailure = runCatching {
        withTimeout(timeoutMillis) { finalizeMetadata() }
    }.exceptionOrNull()
    InterruptedRunFinalizationFailures(childFailure, metadataFailure)
}

/** run 中断的 Child 侧工具收口（pending→Denied、未执行工具→interrupted 结果）。 */
internal fun UIMessage.recoverSubAssistantToolsAfterInterruption(reason: String): UIMessage {
    fun markInterrupted(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
        output = listOf(
            UIMessagePart.Text(
                """{"status":"interrupted","reason":"$reason"}"""
            )
        ),
        approvalState = if (tool.approvalState is ToolApprovalState.Pending) {
            ToolApprovalState.Denied(reason)
        } else {
            tool.approvalState
        },
    )
    return finishPendingTools(::markInterrupted).finishInterruptedTools(::markInterrupted)
}

// ---- 子助手调用收口纯函数（原 SubAssistantRecovery.kt 全量平移） ----

internal data class SubAssistantRecoveryResult(
    val master: Conversation,
    val referencedChildIds: Set<Uuid>,
    val childStopReasons: Map<Uuid, String>,
)

private data class RecoveryOccurrence(
    val nodeIndex: Int,
    val messageIndex: Int,
    val partIndex: Int,
    val tool: UIMessagePart.Tool,
    val metadata: SubAssistantCallMetadata,
)

internal fun recoverMasterSubAssistantCalls(
    master: Conversation,
    settings: Settings,
    childrenById: Map<Uuid, Conversation>,
    json: Json,
): SubAssistantRecoveryResult {
    require(master.parentConversationId == null)
    val occurrences = buildList {
        master.messageNodes.forEachIndexed { nodeIndex, node ->
            node.messages.forEachIndexed { messageIndex, message ->
                message.parts.forEachIndexed { partIndex, part ->
                    if (part is UIMessagePart.Tool && part.toolName == "assistant_call") {
                        part.getSubAssistantCallMetadata(json)?.let { metadata ->
                            add(RecoveryOccurrence(nodeIndex, messageIndex, partIndex, part, metadata))
                        }
                    }
                }
            }
        }
    }
    val runCounts = occurrences.groupingBy { it.metadata.runId }.eachCount()
    val referenced = mutableSetOf<Uuid>()
    val childReasons = mutableMapOf<Uuid, String>()
    val replacements = mutableMapOf<Triple<Int, Int, Int>, UIMessagePart.Tool>()

    occurrences.forEach { occurrence ->
        val metadata = occurrence.metadata
        val duplicateOrBlankRun = metadata.runId.isBlank() || runCounts[metadata.runId] != 1
        val validChild = if (duplicateOrBlankRun) {
            null
        } else {
            resolveValidRecoveryChild(master, metadata, childrenById)
        }
        if (validChild != null) referenced += validChild.id

        if (!metadata.state.isTerminal()) {
            val reason = if (duplicateOrBlankRun) {
                "child_missing"
            } else {
                resolveRecoveryStopReason(master, metadata, settings, validChild)
            }
            if (validChild != null) {
                childReasons[validChild.id] = chooseMoreSpecificStopReason(
                    childReasons[validChild.id],
                    reason,
                )
            }
            val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            val childMessages = validChild?.currentMessages.orEmpty()
            val outputs = collectSubAssistantCallOutputs(
                messages = childMessages,
                childTaskNodeId = taskId,
                extras = parseAssistantCallExtrasFromInput(occurrence.tool.input),
            )
            val preview = if (validChild != null && taskId != null) {
                val rebuilt = computeSubAssistantPreview(childMessages, taskId)
                rebuilt.ifBlank { metadata.preview.orEmpty() }.takeIf { it.isNotBlank() }
            } else {
                metadata.preview
            }
            val stopped = metadata.copy(
                state = SubAssistantCallState.STOPPED,
                phase = null,
                activeToolName = null,
                preview = preview,
                reason = reason,
                userInteraction = null,
            )
            replacements[Triple(occurrence.nodeIndex, occurrence.messageIndex, occurrence.partIndex)] =
                occurrence.tool.mergeSubAssistantCallMetadata(json, stopped).copy(
                    output = listOf(
                        UIMessagePart.Text(
                            buildSubAssistantCallResult(
                                json = json,
                                status = "stopped",
                                assistantName = metadata.targetNameSnapshot,
                                content = "",
                                reason = reason,
                                toolCalls = outputs.toolCalls,
                                ttsTexts = outputs.ttsTexts,
                                ttsStats = outputs.ttsStats,
                            )
                        )
                    )
                )
        }
    }

    if (replacements.isEmpty()) {
        return SubAssistantRecoveryResult(master, referenced, childReasons)
    }
    val recoveredNodes = master.messageNodes.mapIndexed { nodeIndex, node ->
        node.copy(
            messages = node.messages.mapIndexed { messageIndex, message ->
                message.copy(
                    parts = message.parts.mapIndexed { partIndex, part ->
                        replacements[Triple(nodeIndex, messageIndex, partIndex)] ?: part
                    }
                )
            }
        )
    }
    return SubAssistantRecoveryResult(
        master = master.copy(messageNodes = recoveredNodes),
        referencedChildIds = referenced,
        childStopReasons = childReasons,
    )
}

internal fun resolveValidRecoveryChild(
    master: Conversation,
    metadata: SubAssistantCallMetadata,
    childrenById: Map<Uuid, Conversation>,
): Conversation? {
    val childId = metadata.childConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val targetId = runCatching { Uuid.parse(metadata.targetAssistantId) }.getOrNull() ?: return null
    val taskId = metadata.childTaskNodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        ?: return null
    val child = childrenById[childId] ?: return null
    if (child.parentConversationId != master.id || child.assistantId != targetId) return null
    val hasSelectedTask = child.messageNodes.any { node ->
        node.selectIndex in node.messages.indices &&
            node.currentMessage.id == taskId &&
            node.currentMessage.role == MessageRole.USER
    }
    return child.takeIf { hasSelectedTask }
}

internal fun resolveRecoveryStopReason(
    master: Conversation,
    metadata: SubAssistantCallMetadata,
    settings: Settings,
    validChild: Conversation?,
): String {
    val targetId = runCatching { Uuid.parse(metadata.targetAssistantId) }.getOrNull()
        ?: return "target_removed"
    val pendingDeletionIds = settings.pendingAssistantDeletions.mapTo(mutableSetOf()) { it.assistantId }
    val target = settings.assistants.find { it.id == targetId }
    if (target == null || targetId in pendingDeletionIds) return "target_removed"
    if (!target.allowAsSubAssistant) return "target_disabled"

    val caller = settings.assistants.find { it.id == master.assistantId }
    if (caller == null || caller.id in pendingDeletionIds ||
        LocalToolOption.AssistantDelegation !in caller.localTools ||
        !SubAssistantAccessPolicy.canAccess(caller, target)
    ) {
        return "target_access_revoked"
    }
    val runSpec = resolveSubAssistantRunSpec(settings, caller, target)
    if (runSpec is SubAssistantRunSpecResolution.Blocked) {
        return runSpec.reason
    }
    if (validChild == null) return "child_missing"
    return "app_restarted"
}

internal fun chooseMoreSpecificStopReason(existing: String?, incoming: String): String {
    if (existing == null) return incoming
    val priority = listOf(
        "target_removed",
        "target_disabled",
        "target_access_revoked",
        "target_model_unavailable",
        "caller_model_unavailable",
        "child_missing",
        "app_restarted",
    )
    fun rank(reason: String): Int = priority.indexOf(reason).takeIf { it >= 0 } ?: Int.MAX_VALUE
    return if (rank(incoming) < rank(existing)) incoming else existing
}

// ---- 恢复域私有扩展（自 ChatService 平移） ----

private fun Conversation.locateAssistant(messageId: Uuid?): Pair<Int, UIMessage>? {
    if (messageId == null) return null
    messageNodes.forEachIndexed { index, node ->
        val message = node.messages.firstOrNull { it.id == messageId && it.role == MessageRole.ASSISTANT }
        if (message != null) return index to message
    }
    return null
}

private fun Conversation.markAssistantTerminal(
    messageId: Uuid?,
    status: MessageTerminalStatus,
    reason: String?,
): Conversation {
    val located = locateAssistant(messageId) ?: return this
    val (nodeIndex, targetMessage) = located
    if (targetMessage.role != MessageRole.ASSISTANT) return this
    val marked = targetMessage.copy(terminalStatus = status, terminalReason = reason)
    return copy(
        messageNodes = messageNodes.mapIndexed { index, node ->
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
    )
}
