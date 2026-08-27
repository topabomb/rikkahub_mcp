package net.weero.measix.pilot.service

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.ToolApprovalState
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
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.interruptPendingTool
import kotlin.uuid.Uuid

/** Owns normal turn supersede, cancellation and terminal tool cleanup. */
class TurnFinalization(
    private val conversationRepository: ConversationRepository,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val json: Json,
) {
    suspend fun stopTurn(
        conversationId: Uuid,
        reason: String = TurnTerminalReasons.USER_STOP,
    ) {
        val runtime = runtimeRegistry.findRuntime(conversationId) ?: return
        val captured = runtime.captureAndRequestStop(reason) ?: return
        val job = captured.worker
        if (job?.isCompleted == false) {
            job.cancel()
        }
        withContext(NonCancellable) {
            job?.join()
            finalizeNonTerminalTurn(
                conversationId = conversationId,
                turnId = captured.turnId,
                reason = reason,
                cancelledByUser = reason == TurnTerminalReasons.USER_STOP,
            )
            runtime.releaseActiveRequest(captured.turnId, retainAwaitingOwner = false)
        }
    }

    suspend fun closeOpenTools(
        snapshot: ConversationSnapshot,
        messageId: Uuid,
        reason: String,
        cancelledByUser: Boolean = true,
    ): ConversationSnapshot {
        val located = requireNotNull(snapshot.locateAssistantMessage(messageId)) {
            "assistant message $messageId is not present in the owned conversation snapshot"
        }
        val (nodeIndex, targetMessage) = located
        val updatedMessage = closeOpenToolsInMessage(targetMessage, reason, cancelledByUser)
        if (updatedMessage == targetMessage) return snapshot
        return snapshot.copy(
            nodes = snapshot.nodes.mapIndexed { index, node ->
                if (index != nodeIndex) node else node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == targetMessage.id) updatedMessage else message
                    },
                )
            },
            activeTurn = null,
        )
    }

    /**
     * Prepares the latest turn-owned projection for a failure/cancellation terminal commit.
     * The provider may have emitted messages after the last durable checkpoint, so this path must
     * use TurnEngine's accumulated messages instead of rereading the durable node as payload.
     */
    suspend fun prepareOwnedTurnMessagesForFailure(
        snapshot: ConversationSnapshot,
        handle: TurnHandle,
        latestMessages: List<UIMessage>,
        reason: String,
        cancelledByUser: Boolean,
    ): List<UIMessage> {
        require(snapshot.conversationId == handle.conversationId) {
            "turn handle belongs to another conversation: ${handle.conversationId}"
        }
        val active = requireNotNull(snapshot.activeTurn) {
            "turn ${handle.turnId} has no active runtime owner"
        }
        require(
            active.epoch == handle.epoch &&
                active.turnId == handle.turnId &&
                active.assistantMessageId == handle.assistantMessageId
        ) { "turn handle no longer owns the active projection: ${handle.turnId}" }

        val ownedMessages = latestMessages.ifEmpty { snapshot.currentMessages() }
        val targetMessage = requireNotNull(ownedMessages.lastOrNull()) {
            "turn ${handle.turnId} has no messages to finalize"
        }
        require(
            targetMessage.id == handle.assistantMessageId && targetMessage.role == MessageRole.ASSISTANT
        ) { "latest turn messages do not end with the owning assistant message" }
        val updatedMessage = closeOpenToolsInMessage(targetMessage, reason, cancelledByUser)
        if (updatedMessage == targetMessage) return ownedMessages
        return ownedMessages.toMutableList().apply { set(lastIndex, updatedMessage) }
    }

    private suspend fun closeOpenToolsInMessage(
        targetMessage: UIMessage,
        reason: String,
        cancelledByUser: Boolean,
    ): UIMessage {
        var updatedMessage = targetMessage.finishPendingTools { tool ->
            if (cancelledByUser) {
                net.weero.measix.pilot.service.runtime.cancelPendingToolByUser(tool)
            } else {
                net.weero.measix.pilot.service.runtime.interruptPendingTool(tool)
            }
        }
        val childMessagesByConversation = loadChildMessagesForInterruptedCalls(updatedMessage)
        updatedMessage = updatedMessage.finishInterruptedTools { tool ->
            val childId = tool.getSubAssistantCallMetadata(json)?.childConversationId
                ?.let(Uuid::parse)
            finishInterruptedToolAfterGenerationStop(
                tool = tool,
                json = json,
                reason = reason,
                childMessages = childId?.let { childMessagesByConversation[it] }.orEmpty(),
            )
        }
        return updatedMessage
    }

    /** Finalizes a previous non-terminal turn before a replacement turn starts. */
    suspend fun finalizeSupersededTurn(conversationId: Uuid, previousTurnId: Uuid?) {
        finalizeNonTerminalTurn(
            conversationId = conversationId,
            turnId = previousTurnId ?: return,
            reason = TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
            cancelledByUser = false,
        )
    }

    private suspend fun finalizeNonTerminalTurn(
        conversationId: Uuid,
        turnId: Uuid,
        reason: String,
        cancelledByUser: Boolean,
    ) {
        val runtime = commandCoordinator.load(conversationId)
        val execution = conversationRepository.getTurnExecution(turnId.toString()) ?: return
        if (execution.status !in setOf(
                TurnExecutionStatus.CREATED,
                TurnExecutionStatus.RUNNING,
                TurnExecutionStatus.AWAITING_APPROVAL,
            )
        ) return
        val active = runtime.snapshot.value.activeTurn
            ?.takeIf { it.turnId == turnId }
            ?: error("non-terminal turn has no matching runtime owner: $turnId")
        val prepared = closeOpenTools(
            snapshot = runtime.snapshot.value,
            messageId = active.assistantMessageId,
            reason = reason,
            cancelledByUser = cancelledByUser,
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
                messages = prepared.currentMessages(),
                terminalStatus = TurnExecutionStatus.CANCELLED,
                terminalReason = reason,
                closeInterruptedTools = false,
            ),
        )
    }

    /** Finalizes an active sub-assistant run on cancellation or failure. */
    suspend fun finalizeSubAssistantRun(
        childConversationId: Uuid,
        reason: String,
        execContext: ToolExecutionContext,
        terminalMetadata: SubAssistantCallMetadata,
    ) {
        val failures = finalizeInterruptedRunSafely(
            timeoutMillis = FINALIZATION_TIMEOUT_MS,
            finalizeChild = { finalizeChild(childConversationId, reason) },
            finalizeMetadata = {
                reportSubAssistantMetadataPatch(json, execContext, terminalMetadata, checkpoint = false)
            },
        )
        failures.child?.let { error ->
            Log.e(TAG, "Unable to finalize interrupted child $childConversationId", error)
        }
        failures.metadata?.let { error ->
            Log.e(TAG, "Unable to persist terminal metadata for ${terminalMetadata.runId}", error)
        }
        failures.throwIfAny(childConversationId, terminalMetadata.runId)
    }

    suspend fun finalizeChild(childConversationId: Uuid, reason: String) {
        if (conversationRepository.getConversationHeader(childConversationId) == null) return
        val runtime = commandCoordinator.load(childConversationId)
        val snapshot = runtime.snapshot.value
        val active = snapshot.activeTurn
        if (active != null) {
            val prepared = closeOpenTools(
                snapshot = snapshot,
                messageId = active.assistantMessageId,
                reason = reason,
                cancelledByUser = false,
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
                    messages = prepared.currentMessages(),
                    terminalStatus = TurnExecutionStatus.INTERRUPTED,
                    terminalReason = reason,
                    closeInterruptedTools = false,
                ),
            )
            return
        }

        val recoverableFacts = conversationRepository.getTurnExecutions(childConversationId).filter {
            it.status == TurnExecutionStatus.CREATED ||
                it.status == TurnExecutionStatus.RUNNING ||
                it.status == TurnExecutionStatus.AWAITING_APPROVAL
        }
        check(recoverableFacts.isEmpty()) {
            "child $childConversationId has recoverable turn facts without an in-memory owner"
        }
        val lastAssistantId = snapshot.currentMessages().lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT }
            ?.id
        val prepared = lastAssistantId?.let { messageId ->
            closeOpenTools(
                snapshot = snapshot,
                messageId = messageId,
                reason = reason,
                cancelledByUser = false,
            )
        } ?: snapshot
        check(prepared.nodes == snapshot.nodes) {
            "child $childConversationId has unfinished tool state without an active turn"
        }
    }

    private suspend fun loadChildMessagesForInterruptedCalls(
        message: UIMessage,
    ): Map<Uuid, List<UIMessage>> {
        val childIds = message.getTools().mapNotNull { tool ->
            if (tool.toolName != "assistant_call") return@mapNotNull null
            tool.getSubAssistantCallMetadata(json)?.childConversationId
                ?.let(Uuid::parse)
        }.toSet()
        return childIds.associateWith { childId ->
            runtimeRegistry.findRuntime(childId)?.snapshot?.value?.currentMessages()
                ?: conversationRepository.getConversationById(childId)?.currentMessages
                ?: emptyList()
        }
    }

    private companion object {
        const val TAG = "TurnFinalization"
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

internal data class InterruptedRunFinalizationFailures(
    val child: Throwable?,
    val metadata: Throwable?,
) {
    fun throwIfAny(childConversationId: Uuid, runId: String) {
        val primary = child ?: metadata ?: return
        val failure = IllegalStateException(
            "Sub-assistant terminal state was not durably finalized: child=$childConversationId, run=$runId",
            primary,
        )
        if (child != null && metadata != null && metadata !== primary) {
            failure.addSuppressed(metadata)
        }
        throw failure
    }
}

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

private fun ConversationSnapshot.locateAssistantMessage(messageId: Uuid): Pair<Int, UIMessage>? {
    nodes.forEachIndexed { index, node ->
        node.messages.firstOrNull { message ->
            message.id == messageId && message.role == MessageRole.ASSISTANT
        }?.let { return index to it }
    }
    return null
}
