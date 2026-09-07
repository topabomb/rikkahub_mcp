package net.weero.measix.pilot.service.turn
import me.rerere.ai.ui.ToolResultStatus

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishInterruptedTools
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.TurnTerminalReasons
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.collectSubAssistantCallOutputs
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtrasFromInput
import net.weero.measix.pilot.data.ai.subassistant.reportSubAssistantMetadataPatch
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeInfrastructureException
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.CapturedTurnWorker
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.interruptPendingTool
import kotlin.uuid.Uuid

/** Owns normal turn supersede, cancellation and terminal tool cleanup. */
class TurnFinalizer(
    private val conversationRepository: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val json: Json,
) {
    internal class StopRequest internal constructor(
        internal val conversationId: Uuid,
        internal val runtime: ConversationRuntime,
        internal val captured: CapturedTurnWorker,
        internal val reason: String,
    )

    /** Caller must retain this ownership across lock release and finish it even on cancellation. */
    internal fun captureStop(conversationId: Uuid, reason: String = TurnTerminalReasons.USER_STOP): StopRequest? {
        val runtime = runtimeRegistry.findRuntime(conversationId) ?: return null
        val captured = runtime.captureAndRequestStop(reason) ?: return null
        return StopRequest(conversationId, runtime, captured, reason)
    }

    /** Waits outside session/command locks and never captures a replacement worker. */
    internal suspend fun finishStop(request: StopRequest) = withContext(NonCancellable) {
        val job = request.captured.worker
        if (job?.isCompleted == false) job.cancel()
        job?.join()
        commandCoordinator.withResidentRuntime(request.conversationId) { runtime ->
            val execution = conversationRepository.getTurnExecution(request.captured.turnId.toString())
            if (execution?.status in setOf(TurnExecutionStatus.RUNNING, TurnExecutionStatus.AWAITING_USER)) {
                check(runtime === request.runtime && runtime.ownsStoppedWorker(request.captured)) { "stopped_turn_owner_changed" }
                finalizeNonTerminalTurn(request.conversationId, request.captured.turnId, request.reason)
            }
            if (runtime === request.runtime && runtime.ownsStoppedWorker(request.captured)) {
                check(runtime.snapshot.value.stream?.turnId != request.captured.turnId) { "stopped_turn_still_pending" }
                runtime.releaseTurnWorker(request.captured.turnId, request.captured.worker, retainPendingTurnOwner = false)
            }
        }
    }

    suspend fun stopTurn(
        conversationId: Uuid,
        reason: String = TurnTerminalReasons.USER_STOP,
    ) {
        captureStop(conversationId, reason)?.let { finishStop(it) }
    }

    /**
     * Closes the owning Assistant's open tools against the current durable node and returns the
     * resulting terminal Assistant message. Callers commit it via FinalizeTurn; the reducer no
     * longer closes tools itself.
     */
    internal suspend fun closeOpenTools(
        snapshot: ConversationRuntimeSnapshot,
        messageId: Uuid,
        reason: String,
    ): UIMessage {
        val durable = snapshot.durable
        val located = requireNotNull(durable.locateAssistantMessage(messageId)) {
            "assistant message $messageId is not present in the owned conversation snapshot"
        }
        val (_, targetMessage) = located
        return closeOpenToolsInMessage(
            targetMessage,
            reason,
            owningTurnId = snapshot.stream?.takeIf { it.assistantMessageId == messageId }?.turnId,
        )
    }

    /**
     * Prepares the latest turn-owned Assistant draft for a failure/cancellation terminal commit.
     * The provider may have emitted content after the last durable checkpoint, so this path uses
     * TurnCommitter's accumulated Assistant instead of rereading the durable node as payload. The
     * returned message already has its open tools closed; the reducer no longer closes tools.
     */
    internal suspend fun prepareOwnedAssistantForFailure(
        snapshot: ConversationRuntimeSnapshot,
        handle: TurnHandle,
        latestAssistant: UIMessage?,
        reason: String,
    ): UIMessage? {
        require(snapshot.durable.conversationId == handle.conversationId) {
            "turn handle belongs to another conversation: ${handle.conversationId}"
        }
        val active = requireNotNull(snapshot.stream) {
            "turn ${handle.turnId} has no active runtime owner"
        }
        require(
            active.epoch == handle.epoch &&
                active.turnId == handle.turnId &&
                active.assistantMessageId == handle.assistantMessageId
        ) { "turn handle no longer owns the active projection: ${handle.turnId}" }

        val targetMessage = latestAssistant
            ?: snapshot.durable.currentMessages().lastOrNull()?.takeIf { it.id == handle.assistantMessageId }
            ?: return null
        require(
            targetMessage.id == handle.assistantMessageId && targetMessage.role == MessageRole.ASSISTANT
        ) { "latest turn assistant is not the owning assistant message" }
        return closeOpenToolsInMessage(
            targetMessage.withoutUnpersistableBase64(),
            reason,
            owningTurnId = handle.turnId,
        )
    }

    private suspend fun closeOpenToolsInMessage(
        targetMessage: UIMessage,
        reason: String,
        owningTurnId: Uuid?,
    ): UIMessage {
        var updatedMessage = targetMessage.finishPendingTools { tool ->
            interruptPendingTool(tool)
        }
        val executionFacts = if (owningTurnId != null && updatedMessage.getTools().any { !it.hasReplayResult }) {
            conversationRepository.getToolExecutions(owningTurnId.toString())
                .associateBy { Uuid.parse(it.localCallId) }
        } else {
            null
        }
        val children = loadInterruptedChildren(updatedMessage)
        updatedMessage = updatedMessage.finishInterruptedTools { tool ->
            val execution = executionFacts?.get(tool.localCallId)
            if (tool.toolName == "assistant_call") {
                val hasRunMetadata = tool.metadata?.containsKey("sub_assistant_call") == true
                // STARTED precedes materialization. Only the child-link checkpoint establishes a run;
                // an absent metadata key is legal before that point, but never repairs a linked run.
                val hasNoLinkedRun = executionFacts != null && !hasRunMetadata &&
                    (execution == null ||
                        (execution.status == ToolExecutionStatus.STARTED && execution.childConversationId == null))
                if (hasNoLinkedRun) return@finishInterruptedTools interruptPendingTool(tool).copy(
                    resultStatus = if (execution?.status == ToolExecutionStatus.STARTED) {
                        ToolResultStatus.UNKNOWN
                    } else ToolResultStatus.INTERRUPTED,
                )
                execution?.childConversationId?.let { childId ->
                    require(tool.getSubAssistantCallMetadata(json)?.childConversationId == childId) {
                        "assistant_call metadata does not match committed child link at call ${tool.localCallId}"
                    }
                }
            }
            val childId = tool.getSubAssistantCallMetadata(json)?.childConversationId
                ?.let(Uuid::parse)
            if (childId != null) {
                val childTurns = requireNotNull(children[childId]).turns
                execution?.childTurnId?.let { rawTurnId ->
                    val childTurnId = Uuid.parse(rawTurnId).toString()
                    check(childTurns.filter { it.turnId == childTurnId }.none { it.status.isNonTerminal() }) {
                        "Child Turn must be terminal before its parent Tool Result is finalized"
                    }
                    check(execution.subAssistantRunId == tool.getSubAssistantCallMetadata(json)?.runId) {
                        "Child execution and parent metadata have different run identities"
                    }
                } ?: check(childTurns.none { it.status.isNonTerminal() }) {
                    "Child must have no unfinished Turn before its parent Tool Result is finalized"
                }
            }
            val interrupted = finishInterruptedToolAfterGenerationStop(
                tool = tool,
                json = json,
                reason = reason,
                childMessages = childId?.let { children[it]?.messages }.orEmpty(),
            )
            if (execution?.status == ToolExecutionStatus.STARTED) {
                interrupted.copy(
                    resultStatus = ToolResultStatus.UNKNOWN,
                    output = if (tool.toolName == "assistant_call") interrupted.output else listOf(
                        UIMessagePart.Text("""{"status":"unknown","error":"Execution started, but its side-effect outcome could not be confirmed. It will not be retried automatically."}"""),
                    ),
                )
            } else interrupted
        }
        return updatedMessage
    }

    /** Finalizes a previous non-terminal turn before a replacement turn starts. */
    suspend fun finalizeSupersededTurn(conversationId: Uuid, previousTurnId: Uuid?) {
        finalizeNonTerminalTurn(
            conversationId = conversationId,
            turnId = previousTurnId ?: return,
            reason = TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
        )
    }

    private suspend fun finalizeNonTerminalTurn(
        conversationId: Uuid,
        turnId: Uuid,
        reason: String,
    ) {
        val execution = conversationRepository.getTurnExecution(turnId.toString()) ?: return
        if (execution.status !in setOf(
                TurnExecutionStatus.RUNNING,
                TurnExecutionStatus.AWAITING_USER,
            )
        ) return
        val runtime = commandCoordinator.load(conversationId)
        val active = runtime.snapshot.value.stream
            ?.takeIf { it.turnId == turnId }
            ?: error("non-terminal turn has no matching runtime owner: $turnId")
        val terminalAssistant = closeOpenTools(
            snapshot = runtime.snapshot.value,
            messageId = active.assistantMessageId,
            reason = reason,
        )
        commandCoordinator.executeOrThrow(
            conversationId,
            FinalizeTurn(
                handle = TurnHandle(
                    conversationId = conversationId,
                    epoch = active.epoch,
                    turnId = turnId,
                    assistantMessageId = active.assistantMessageId,
                ),
                assistantMessage = terminalAssistant,
                terminalStatus = TurnExecutionStatus.CANCELLED,
                terminalReason = reason,
            ),
        )
    }

    /** Stages terminal Caller metadata when Child materialized but no Child Turn START was committed. */
    suspend fun finalizeUnstartedSubAssistantRun(
        execContext: ToolExecutionContext,
        terminalMetadata: SubAssistantCallMetadata,
    ) {
        try {
            withContext(NonCancellable) {
                withTimeout(FINALIZATION_TIMEOUT_MS) {
                    reportSubAssistantMetadataPatch(json, execContext, terminalMetadata, delivery = ToolMetadataDelivery.DEFERRED)
                }
            }
        } catch (error: Exception) {
            throw ToolRuntimeInfrastructureException(error)
        }
    }

    /** Finalizes the exact Child Turn and stages Caller metadata for its next terminal commit. */
    suspend fun finalizeSubAssistantRun(
        childConversationId: Uuid,
        childTurnId: Uuid,
        reason: String,
        execContext: ToolExecutionContext,
        terminalMetadata: SubAssistantCallMetadata,
    ) {
        try {
            finalizeInterruptedRunSafely(
                timeoutMillis = FINALIZATION_TIMEOUT_MS,
                finalizeChild = { finalizeChild(childConversationId, childTurnId, reason) },
                finalizeMetadata = {
                    reportSubAssistantMetadataPatch(json, execContext, terminalMetadata, delivery = ToolMetadataDelivery.DEFERRED)
                },
            )
        } catch (error: Exception) {
            throw ToolRuntimeInfrastructureException(IllegalStateException(
                "Child finalization must succeed before Caller metadata advances: child=$childConversationId, run=${terminalMetadata.runId}",
                error,
            ))
        }
    }

    /** Finalizes only the Child Turn owned by the caller; stale cleanup never targets a newer owner. */
    suspend fun finalizeChild(childConversationId: Uuid, expectedTurnId: Uuid, reason: String) {
        if (conversationRepository.getConversationHeader(childConversationId) == null) return
        val runtime = commandCoordinator.load(childConversationId)
        val snapshot = runtime.snapshot.value
        val active = snapshot.stream
        if (active != null && active.turnId != expectedTurnId) {
            val expected = conversationRepository.getTurnExecution(expectedTurnId.toString())
            check(expected == null || expected.status !in setOf(
                TurnExecutionStatus.RUNNING,
                TurnExecutionStatus.AWAITING_USER,
            )) {
                "stale Child cleanup lost owner for non-terminal turn $expectedTurnId"
            }
            return
        }
        if (active != null) {
            val terminalAssistant = closeOpenTools(
                snapshot = snapshot,
                messageId = active.assistantMessageId,
                reason = reason,
            )
            commandCoordinator.executeOrThrow(
                childConversationId,
                FinalizeTurn(
                    handle = TurnHandle(
                        conversationId = childConversationId,
                        epoch = active.epoch,
                        turnId = active.turnId,
                        assistantMessageId = active.assistantMessageId,
                    ),
                    assistantMessage = terminalAssistant,
                    terminalStatus = TurnExecutionStatus.INTERRUPTED,
                    terminalReason = reason,
                ),
            )
            return
        }

        val recoverableFacts = conversationRepository.getTurnExecutions(childConversationId).filter {
            it.status == TurnExecutionStatus.RUNNING ||
                it.status == TurnExecutionStatus.AWAITING_USER
        }
        check(recoverableFacts.isEmpty()) {
            "child $childConversationId has recoverable turn facts without an in-memory owner"
        }
        val lastAssistant = snapshot.durable.currentMessages().lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT }
        if (lastAssistant != null) {
            val closedAssistant = closeOpenTools(
                snapshot = snapshot,
                messageId = lastAssistant.id,
                reason = reason,
            )
            check(closedAssistant == lastAssistant) {
                "child $childConversationId has unfinished tool state without an active turn"
            }
        }
    }

    private data class InterruptedChild(
        val messages: List<UIMessage>,
        val turns: List<net.weero.measix.pilot.data.db.entity.TurnExecutionEntity>,
    )

    private suspend fun loadInterruptedChildren(message: UIMessage): Map<Uuid, InterruptedChild> {
        val childIds = message.getTools().mapNotNull { tool ->
            if (tool.toolName != "assistant_call" || tool.hasReplayResult) return@mapNotNull null
            tool.getSubAssistantCallMetadata(json)?.childConversationId?.let(Uuid::parse)
        }.toSet()
        return childIds.associateWith { childId ->
            InterruptedChild(
                messages = runtimeRegistry.findRuntime(childId)?.snapshot?.value?.durable?.currentMessages()
                    ?: conversationRepository.getConversationById(childId)?.currentMessages.orEmpty(),
                turns = conversationRepository.getTurnExecutions(childId),
            )
        }
    }

    private companion object {
        const val FINALIZATION_TIMEOUT_MS = 5_000L
    }
}

/** Completes protocol-visible tool results when a normal turn is stopped or superseded. */
internal fun finishInterruptedToolAfterGenerationStop(
    tool: UIMessagePart.Tool,
    json: Json,
    reason: String,
    childMessages: List<UIMessage> = emptyList(),
): UIMessagePart.Tool {
    if (tool.toolName == "assistant_call") {
        val metadata = requireNotNull(tool.getSubAssistantCallMetadata(json)) {
            "interrupted assistant_call has no execution metadata"
        }
        check(!metadata.state.isTerminal()) {
            "terminal assistant_call has no protocol-visible output: ${metadata.state}"
        }
        val stoppedMetadata = metadata.copy(
            state = SubAssistantCallState.STOPPED,
            phase = null,
            activeToolName = null,
            reason = reason,
            userInteraction = null,
        )
        val taskId = metadata.childTaskNodeId?.let(Uuid::parse)
        val outputs = collectSubAssistantCallOutputs(
            messages = childMessages,
            childTaskNodeId = taskId,
            extras = parseAssistantCallExtrasFromInput(tool.input),
        )
        return tool.mergeSubAssistantCallMetadata(json, stoppedMetadata).copy(
            resultStatus = ToolResultStatus.INTERRUPTED,
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
    return interruptPendingTool(tool)
}

/** Child durability is a prerequisite for staging the parent's terminal projection. */
internal suspend fun finalizeInterruptedRunSafely(
    timeoutMillis: Long,
    finalizeChild: suspend () -> Unit,
    finalizeMetadata: suspend () -> Unit,
): Unit = withContext(NonCancellable) {
    withTimeout(timeoutMillis) { finalizeChild() }
    withTimeout(timeoutMillis) { finalizeMetadata() }
}

private fun ConversationAggregateSnapshot.locateAssistantMessage(messageId: Uuid): Pair<Int, UIMessage>? {
    nodes.forEachIndexed { index, node ->
        node.messages.firstOrNull { message ->
            message.id == messageId && message.role == MessageRole.ASSISTANT
        }?.let { return index to it }
    }
    return null
}

private fun TurnExecutionStatus.isNonTerminal(): Boolean =
    this == TurnExecutionStatus.RUNNING || this == TurnExecutionStatus.AWAITING_USER
