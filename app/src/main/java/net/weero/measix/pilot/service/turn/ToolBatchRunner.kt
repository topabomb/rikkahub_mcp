package net.weero.measix.pilot.service.turn
import net.weero.measix.pilot.data.ai.ToolExecutionFact
import net.weero.measix.pilot.data.ai.ToolResultFact

import android.util.Log
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolResultStatus
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.LocatedToolCall
import net.weero.measix.pilot.data.ai.tools.PendingToolInteraction
import net.weero.measix.pilot.data.ai.tools.ResolvedToolCall
import net.weero.measix.pilot.data.ai.tools.ToolCallRuntime
import net.weero.measix.pilot.data.ai.tools.ToolExecutionHooks
import net.weero.measix.pilot.data.ai.transformers.transforms
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus

private const val TAG = "ToolBatchRunner"

/** 一批 Tool Call 门控与执行后交回多 Step 循环的控制结论。 */
internal sealed interface ToolBatchOutcome {
    /** 仍有 Pending：交回 pending 由循环产出非终态 [TurnPause]（AWAITING_USER 已由采样或本批 checkpoint 落定）。 */
    data class Paused(val pending: List<PendingToolInteraction>) : ToolBatchOutcome

    /** 本批已串行执行工具：进入下一 Step 前上报 between_steps。 */
    data object Executed : ToolBatchOutcome

    /** 本批仅有即时结果、无可执行工具：直接进入下一 Step，不上报 between_steps。 */
    data object ImmediateOnly : ToolBatchOutcome
}

/**
 * 一批 Tool Call 的门控、暂停与串行执行。
 *
 * 只要本批还有 Pending，任何自动工具都不先执行；全部决策完成后严格按消息中的原顺序串行执行，
 * 每个不可逆副作用前先提交 [ToolExecutionStartedCheckpoint]，成功返回后提交 [ToolResultCheckpoint]。
 */
internal class ToolBatchRunner(
    private val toolCallRuntime: ToolCallRuntime,
    private val attachmentResolver: AttachmentResolver,
) {
    suspend fun run(state: TurnRunState): ToolBatchOutcome {
        // 本批的门控/可用性/审批准备（含续跑恢复）：Turn live phase = TOOL_PREPARING，
        // 直到逐工具执行才转 TOOL_EXECUTING。
        state.sendPhase("tool_preparing")
        val messageTools = state.messages.last().getTools()
        val replayPendingOrdinals = messageTools.mapIndexedNotNull { ordinal, tool ->
            ordinal.takeIf { !tool.hasReplayResult }
        }
        val preparation = toolCallRuntime.prepareBatch(
            messageId = state.messages.last().id,
            calls = replayPendingOrdinals.map { LocatedToolCall(it, messageTools[it]) },
            toolIndex = state.toolsByName,
            availability = state.interactionAvailability,
        )

        val pendingLocalCallIds = preparation.pending.map { it.locator.localCallId }.toSet()
        val resultReplacements = preparation.replacements.filterKeys { it !in pendingLocalCallIds }
        val pendingReplacements = preparation.replacements.filterKeys { it in pendingLocalCallIds }

        state.applyReplacements(resultReplacements)
        if (preparation.pending.isNotEmpty()) {
            state.applyReplacements(pendingReplacements)
            // Continuation / skip-sampling pause still needs one AWAITING_USER checkpoint.
            // Immediate rejections ride that same write; pending stays off the stream.
            // The step is the resumed batch's own step (pending tools carry it), never the
            // accumulator's not-yet-opened step, so a continuation checkpoint is never step-NIL.
            state.commitModelResponse(
                turnStatus = TurnExecutionStatus.AWAITING_USER,
                stepId = preparation.pending.first().locator.stepId,
            )
            state.handoffDraft()
            Log.i(TAG, "generateText: waiting for all tool user interactions")
            return ToolBatchOutcome.Paused(preparation.pending)
        }

        if (preparation.immediateResults.isNotEmpty()) {
            state.commitToolResult(toolResults = preparation.immediateResults)
            state.publishStreamingProjection()
        }

        if (preparation.resolvedCalls.isEmpty()) {
            return ToolBatchOutcome.ImmediateOnly
        }

        // tool_executing phase with registered tool name is emitted per-tool below
        for (resolved in preparation.resolvedCalls) {
            var executionEvent: ToolExecutionFact? = null
            var executionFailed = false
            val completedTool: UIMessagePart.Tool
            when (resolved) {
                is ResolvedToolCall.Denied -> completedTool = resolved.result
                is ResolvedToolCall.Answered -> completedTool = resolved.result
                is ResolvedToolCall.Executable -> {
                    val call = resolved.call
                    state.sendPhase("tool_executing", call.definition.name)
                    val startedFact = ToolExecutionFact(
                        executionId = call.executionId,
                        assistantMessageId = call.locator.assistantMessageId,
                        stepId = call.locator.stepId,
                        localCallId = call.locator.localCallId,
                        providerCallId = call.source.providerCallId,
                        toolName = call.definition.name,
                        status = ToolExecutionStatus.STARTED,
                    )
                    executionEvent = startedFact
                    state.commitToolExecutionStarted(startedFact)
                    state.publishStreamingProjection()
                    Log.i(
                        TAG,
                        "generateText: executing tool ${call.definition.name} with args: ${call.arguments}",
                    )

                    // File-owner reads are independent of this conversation and Workspace.
                    // The hooks carry the generation owner's capabilities; the Runtime never
                    // obtains Room or presentation write access directly.
                    val hooks = ToolExecutionHooks(
                        resolveAttachments = { paths ->
                            when (val resolvedAttachments = attachmentResolver.readImages(paths)) {
                                is AttachmentResolveResult.Success -> {
                                    ToolAttachmentResolution(resolvedAttachments.parts)
                                }

                                is AttachmentResolveResult.Failure -> ToolAttachmentResolution(
                                    failureReason = resolvedAttachments.reason,
                                )
                            }
                        },
                        reportMetadata = { patch: JsonObject, delivery: ToolMetadataDelivery ->
                            // Tool-owned metadata: re-read the Tool by its stable localCallId from
                            // the latest messages and merge the patch (Runtime state is typed fields,
                            // no reserved metadata namespace remains).
                            val localCallId = call.locator.localCallId
                            val currentTool = state.messages.last().getTools()
                                .firstOrNull { it.localCallId == localCallId }
                                ?: return@ToolExecutionHooks
                            val existingMeta = currentTool.metadata ?: JsonObject(emptyMap())
                            val newMeta = JsonObject(
                                existingMeta.toMutableMap().apply { putAll(patch) }
                            )
                            val updatedTool = currentTool.copy(metadata = newMeta)
                            val lastMsg = state.messages.last()
                            val newParts = lastMsg.parts.map { p ->
                                if (p is UIMessagePart.Tool && p.localCallId == localCallId) {
                                    updatedTool
                                } else {
                                    p
                                }
                            }
                            state.replaceMessages(state.messages.dropLast(1) + lastMsg.copy(parts = newParts))
                            when (delivery) {
                                ToolMetadataDelivery.DEFERRED -> Unit
                                ToolMetadataDelivery.STREAMING -> state.publishStreamingProjection()
                                ToolMetadataDelivery.CHECKPOINT -> {
                                    // Metadata-only update carries no execution fact yet; bind the
                                    // checkpoint to the tool's own step so it is never step-NIL even
                                    // when this batch is resumed after approval (no beginStep ran).
                                    state.commitToolStateUpdated(stepId = updatedTool.stepId)
                                    state.publishStreamingProjection()
                                }
                            }
                        },
                        // Delegation tools report the derived child conversation id into this
                        // execution's durable fact.
                        reportChildConversation = { childConversationId ->
                            executionEvent = executionEvent?.copy(
                                childConversationId = childConversationId,
                            )
                            if (executionEvent != null) {
                                state.commitToolStateUpdated(toolExecution = executionEvent)
                                state.handoffDraft()
                            }
                        },
                        registerUnpublishedResource = state.unpublishedResources::register,

                    )

                    val outcome = toolCallRuntime.execute(call, hooks)
                    executionFailed = outcome.executionFailed
                    // Re-read by stable localCallId so metadata reported during execution is retained.
                    val latestTool = state.messages.last().getTools()
                        .firstOrNull { it.localCallId == call.locator.localCallId } ?: call.source
                    completedTool = latestTool.copy(
                        output = outcome.output,
                        resultStatus = outcome.resultStatus,
                        runtimeState = latestTool.runtimeState.copy(
                            outputPolicy = outcome.outputPolicy,
                        ),
                    )
                }
            }

            val completedLocalCallId = completedTool.localCallId
            state.replaceMessages(state.messages.dropLast(1) + state.messages.last().let { msg ->
                msg.copy(parts = msg.parts.map { p ->
                    if (p is UIMessagePart.Tool && p.localCallId == completedLocalCallId) {
                        completedTool
                    } else {
                        p
                    }
                })
            })
            val presentationMessages = state.messages.transforms(
                transformers = state.outputTransformers,
                context = state.context,
                model = state.model,
                assistant = state.assistant,
                promptInputs = state.promptInputs,
                requestOrigins = state.outputOrigins,
                registerUnpublishedResource = state.unpublishedResources::register,
            )
            state.commitToolResult(
                toolExecution = executionEvent?.copy(
                    status = if (executionFailed) {
                        ToolExecutionStatus.FAILED
                    } else {
                        ToolExecutionStatus.COMPLETED
                    },
                ),
                toolResults = listOf(
                    ToolResultFact(
                        locator = ToolCallLocator(
                            assistantMessageId = state.messages.last().id,
                            stepId = completedTool.stepId,
                            localCallId = completedTool.localCallId,
                        ),
                        status = when {
                            resolved is ResolvedToolCall.Denied -> ToolResultStatus.DENIED
                            resolved is ResolvedToolCall.Answered -> ToolResultStatus.ANSWERED
                            executionFailed -> ToolResultStatus.FAILED
                            else -> ToolResultStatus.COMPLETED
                        },
                    )
                ),
                publishResources = true,
                checkpointMessages = presentationMessages,
            )
            state.replaceMessages(presentationMessages)
            // Clear the committed EXECUTING projection even when no provider chunk follows.
            state.publishMessages(state.messages)
        }
        return ToolBatchOutcome.Executed
    }
}
