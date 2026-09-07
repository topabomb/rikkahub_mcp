package net.weero.measix.pilot.service.turn
import net.weero.measix.pilot.data.ai.request.RequestContextPlanner
import net.weero.measix.pilot.data.ai.request.DurableMessageLocator
import net.weero.measix.pilot.data.ai.request.ModelRequestReceipt
import net.weero.measix.pilot.data.ai.ProviderRequestOutcome
import net.weero.measix.pilot.data.ai.request.RequestMessageOrigin
import net.weero.measix.pilot.data.ai.RequestUsageReducer
import net.weero.measix.pilot.data.ai.request.SyntheticMessageKind
import net.weero.measix.pilot.data.ai.ToolOutputCompactionPatch
import net.weero.measix.pilot.data.ai.TurnUsageAccumulator
import net.weero.measix.pilot.data.ai.tools.LocatedToolCall
import net.weero.measix.pilot.data.ai.tools.PendingToolInteraction
import net.weero.measix.pilot.data.ai.tools.ToolCallRuntime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderResponseException
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.replaySafeProjection
import net.weero.measix.pilot.data.ai.request.RequestAssembler
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.MessageTransformer
import net.weero.measix.pilot.data.ai.transformers.RequestMessageOriginTracker
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.transforms
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.runtime.mergeProviderTransportCredentials
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private const val TAG = "StepRunner"

/**
 * 单次采样的 durable 结论：无 Tool Call 可结束 Turn；有 Tool Call 则已落一次
 * [net.weero.measix.pilot.service.runtime.ModelResponseCheckpoint]（RUNNING 或 AWAITING_USER）。
 */
internal sealed interface StepExecutionResult {
    data object Final : StepExecutionResult
    data object ContinueToTools : StepExecutionResult
    data class Paused(val pending: List<PendingToolInteraction>) : StepExecutionResult
}

/**
 * 单个 Step 的采样：装配 replay-safe 请求、取实时 Provider 凭据、流式生成、关闭请求 usage、
 * 规划并暂存 rolling compaction。有 Tool Call 时在提交前 [ToolCallRuntime.prepareBatch]，
 * 把 immediate failures 与 pending 交互一并写入一次 ModelResponseCheckpoint。
 */
internal class StepRunner(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val contextPlanner: RequestContextPlanner,
    private val requestAssembler: RequestAssembler,
    private val compactionPlanner: net.weero.measix.pilot.data.ai.tools.ToolOutputCompactionPlanner,
    private val toolOutputStore: net.weero.measix.pilot.data.ai.tools.ToolOutputStore,
    private val toolCallRuntime: ToolCallRuntime,
) {
    suspend fun run(state: TurnRunState): StepExecutionResult {
        state.accumulator.beginStep()
        state.sendPhase("preparing")
        val provider = mergeProviderTransportCredentials(
            frozen = state.frozenProvider,
            live = state.turnContext.model.transportLease.acquire(),
        )
        val providerImpl = providerManager.getProviderByType(provider)
        val receipt = generateInternal(
            assistant = state.assistant,
            promptInputs = state.promptInputs,
            messages = state.messages,
            onUpdateMessages = { updatedMessages ->
                // Publish the turn-owned raw projection before the first suspension so
                // cancellation finalization can retain usage already observed on the wire.
                state.replaceMessages(updatedMessages)
                state.handoffDraft()
                state.replaceMessages(
                    state.messages.transforms(
                        transformers = state.outputTransformers,
                        context = state.context,
                        model = state.model,
                        assistant = state.assistant,
                        promptInputs = state.promptInputs,
                        requestOrigins = state.outputOrigins,
                        registerUnpublishedResource = state.unpublishedResources::register,
                    )
                )
                state.publishStreamingProjection()
            },
            transformers = state.inputTransformers,
            accumulator = state.accumulator,
            model = state.model,
            providerImpl = providerImpl,
            provider = provider,
            toolDefinitions = state.toolDefinitions,
            stream = state.assistant.streamOutput,
            reportProcessingText = state.reportProcessingText,
            assistantMessageId = state.assistantMessageId,
            registerUnpublishedResource = state.unpublishedResources::register,
            mediaCapabilities = state.mediaCapabilities,
            onPhase = { phase -> state.sendPhase(phase) },
            providerSessionId = state.providerSessionId,
            modelContextEntries = state.modelContextEntries,
            durableLocators = state.durableMessageLocators,
        )
        state.replaceMessages(state.finishStreamingLast(state.messages))
        state.replaceMessages(state.messages.slice(0 until state.messages.lastIndex) + state.messages.last().copy(
            finishedAt = Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
        ))
        val compactionPlan = compactionPlanner.planAfterSuccessfulRequest(state.messages, receipt)
        val stagedCompaction = toolOutputStore.stageCompaction(compactionPlan)
        stagedCompaction.lease?.let(state.unpublishedResources::register)
        val checkpointMessages = applyToolOutputCompactionBatchToCheckpoint(
            state.messages,
            stagedCompaction.replacements,
        )
        val toolOutputCompactionPatches = stagedCompaction.replacements.map { (locator, replacement) ->
            ToolOutputCompactionPatch(
                locator = locator,
                marker = replacement.marker,
                archive = replacement.archive,
            )
        }
        val lastMessage = checkpointMessages.last()
        val messageTools = lastMessage.getTools()
        val replayPendingOrdinals = messageTools.mapIndexedNotNull { ordinal, tool ->
            ordinal.takeIf { !tool.hasReplayResult }
        }
        if (replayPendingOrdinals.isEmpty()) {
            // 无 Tool 的 Final step：不发独立 ModelResponseCheckpoint。modelResult、Step Final、
            // Turn COMPLETED 与末批压缩 patch 一次落在唯一 FinalizeTurn。
            // 终态 Assistant 只在 durable rooting 后由 run() 交给 durable 槽，此处不投影。
            state.stageTerminalModelOutput(
                checkpointMessages = checkpointMessages,
                toolOutputCompactionPatches = toolOutputCompactionPatches,
            )
            return StepExecutionResult.Final
        }
        // 有 Tool Call：进入 batch 准备（解析参数、可用性/审批门禁），Turn live phase = TOOL_PREPARING。
        state.sendPhase("tool_preparing")
        val preparation = toolCallRuntime.prepareBatch(
            messageId = lastMessage.id,
            calls = replayPendingOrdinals.map { LocatedToolCall(it, messageTools[it]) },
            toolIndex = state.toolsByName,
            availability = state.interactionAvailability,
        )
        val preparedMessages = if (preparation.replacements.isEmpty()) {
            checkpointMessages
        } else {
            checkpointMessages.dropLast(1) + lastMessage.copy(
                parts = lastMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool) preparation.replacements[part.localCallId] ?: part else part
                },
            )
        }
        val hasPending = preparation.pending.isNotEmpty()
        state.commitModelResponse(
            publishResources = true,
            turnStatus = if (hasPending) {
                TurnExecutionStatus.AWAITING_USER
            } else {
                TurnExecutionStatus.RUNNING
            },
            checkpointMessages = preparedMessages,
            toolOutputCompactionPatches = toolOutputCompactionPatches,
        )
        state.replaceMessages(preparedMessages)
        if (hasPending) {
            // commitModelResponse(publishResources=true) 已把挂起草稿交给 durable 槽；
            // Pending 只经该 durable checkpoint，不得先于 DB 出现在流式通道。
            Log.i(TAG, "generateText: waiting for all tool user interactions")
            return StepExecutionResult.Paused(preparation.pending)
        }
        state.publishMessages(state.messages)
        return StepExecutionResult.ContinueToTools
    }

    private suspend fun generateInternal(
        assistant: TurnAssistantSnapshot,
        promptInputs: TurnPromptSnapshot,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        accumulator: StepOutputAccumulator,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        toolDefinitions: List<FrozenToolDefinition>,
        stream: Boolean,
        reportProcessingText: (String?) -> Unit = {},
        assistantMessageId: Uuid? = null,
        registerUnpublishedResource: (ToolResourceLease) -> Unit,
        mediaCapabilities: RequestMediaCapabilities,
        onPhase: (suspend (String) -> Unit)? = null,
        providerSessionId: String? = null,
        modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
        durableLocators: Map<Uuid, DurableMessageLocator> = emptyMap(),
    ): ModelRequestReceipt {
        val requestPlan = contextPlanner.planRequest(
            durableMessages = messages.filterNot { message ->
                message.id == assistantMessageId &&
                    message.role == MessageRole.ASSISTANT &&
                    message.parts.isEmpty()
            },
            durableLocators = durableLocators,
            modelContextEntries = modelContextEntries,
            messageLimit = assistant.contextMessageLimit,
        )
        val contextMessages = requestPlan.messages
        val system = buildString {
            val effectiveSystemPrompt = promptInputs.conversationSystemPrompt ?: assistant.systemPrompt
            if (effectiveSystemPrompt.isNotBlank()) {
                append(effectiveSystemPrompt)
            }

            // Memory 内容不再进入 System：唯一披露路径是 START 提交的 canonical Snapshot。

            // 工具prompt：只使用装配时冻结的 contribution，不按 step 重新求值。
            toolDefinitions.forEach { definition ->
                appendLine()
                append(definition.systemPromptContribution)
            }

            // 请求携带 Snapshot 时才追加固定规则；规则本身无任何动态内容。
            if (requestPlan.contextProjections.isNotEmpty()) {
                appendLine()
                append(ConversationDisclosureSnapshotService.MODEL_RULES)
            }
        }
        // 本次请求唯一的来源跟踪器：System 与后续注入内容由管线合成，不能被 messageTemplate 包裹。
        // 只标记本次新建的 System；preset 等 durable SYSTEM 消息仍是用户配置，保持原模板行为。
        val requestOrigins = RequestMessageOriginTracker()
        // 唯一 origin 表的 durable 半边在此登记；transformers 完成后 frozenOrigins 是
        // Durable + Synthetic 的完整来源事实。
        requestPlan.originsByMessageId.forEach { (messageId, origin) ->
            (origin as? RequestMessageOrigin.Durable)?.let { requestOrigins.markDurable(messageId, it.locator) }
        }
        val requestMessages = buildList {
            if (system.isNotBlank()) {
                val systemMessage = UIMessage.system(prompt = system)
                requestOrigins.markSynthetic(systemMessage, SyntheticMessageKind.SYSTEM_PROMPT)
                add(systemMessage)
            }
            val durableById = messages.associateBy(UIMessage::id)
            addAll(contextMessages.map { projected ->
                val durable = durableById[projected.id]
                projected.copy(
                    terminalStatus = durable?.terminalStatus,
                    terminalReason = durable?.terminalReason,
                    terminalDetail = durable?.terminalDetail,
                    providerReplayProjection = null,
                )
            })
        }
        val transformedMessages = requestMessages.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            promptInputs = promptInputs,
            requestOrigins = requestOrigins,
            reportProcessingText = reportProcessingText,
            mediaCapabilities = mediaCapabilities,
            registerUnpublishedResource = registerUnpublishedResource,
        ).replaySafeProjection()

        // context part 在全部 input transformers 之后注入，只附着 Durable USER；
        // Token estimate 与 receipt 使用包含 context 的最终 projection。
        // RequestAssembler 是 Step 的唯一丢弃点与 UIMessage → ModelRequestMessage 的唯一转换边界：
        // Step 是 durable 边界事实，绝不进入 Provider 线协议，也不计入发给模型的 token。
        val assembled = requestAssembler.assemble(
            contextPlanner.applyContextProjections(
                transformedMessages = transformedMessages,
                projections = requestPlan.contextProjections,
                originsByMessageId = requestOrigins.frozenOrigins(),
            ),
        )
        val internalMessages = assembled.providerVisibleMessages

        val pendingReceipt = contextPlanner.receiptOf(internalMessages)
        val estimatedRequestContextTokens = contextPlanner.estimateRequestContextTokens(
            providerVisibleMessages = internalMessages,
            tools = toolDefinitions,
        )
        var messages: List<UIMessage> = messages
        val turnUsage = TurnUsageAccumulator.from(messages.lastOrNull()?.usage)
        val requestUsage = RequestUsageReducer(turnUsage.nextRequestOrdinal())

        fun attachUsage(usage: me.rerere.ai.core.TokenUsage) {
            messages = messages.mapIndexed { index, message ->
                if (index == messages.lastIndex) message.copy(usage = usage) else message
            }
        }

        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = toolDefinitions,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            providerSessionId = providerSessionId,
            mediaCapabilities = mediaCapabilities,
        )
        // 请求构建完成，进入等待模型响应阶段
        onPhase?.invoke("model_waiting")
        attachUsage(turnUsage.recordRequestStarted(estimatedRequestContextTokens))
        onUpdateMessages(messages)
        val requestStarted = TimeSource.Monotonic.markNow()
        fun providerDurationMillis(): Long = requestStarted.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        var timeToFirstOutputMillis: Long? = null
        fun observeFirstOutput(chunk: MessageChunk) {
            if (timeToFirstOutputMillis == null && chunk.hasModelOutputPayload()) {
                val observed = providerDurationMillis()
                timeToFirstOutputMillis = observed
                attachUsage(turnUsage.recordFirstOutput(observed))
            }
        }
        var requestOutcome = ProviderRequestOutcome.FAILED
        var providerFailure: Throwable? = null
        try {
            if (stream) {
                var reasoningPhaseSent = false
                var answerPhaseSent = false
                var responseEstablished = false
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = assembled.providerMessages,
                    params = params
                ).collect { chunk ->
                    responseEstablished = true
                    observeFirstOutput(chunk)
                    messages = accumulator.accumulate(messages, chunk, model)
                    // Provider usage 只在请求关闭时原子并入 turn；流式快照不改写累计账本。
                    chunk.usage?.let(requestUsage::accept)
                    // Phase uses the same accumulated-message semantics as the output projection.
                    // Text inside a leading <think> block is reasoning, not answer content.
                    val tagPhase = messages.lastOrNull()?.let(ThinkTagTransformer::classifyPhase)
                    if (!reasoningPhaseSent) {
                        val hasReasoning = chunk.choices.any { choice ->
                            choice.delta?.parts?.any { p -> p is UIMessagePart.Reasoning } == true
                        } || tagPhase?.hasReasoning == true
                        if (hasReasoning) {
                            reasoningPhaseSent = true
                            onPhase?.invoke("reasoning_streaming")
                        }
                    }
                    if (!answerPhaseSent) {
                        val deltaHasText = chunk.choices.any { choice ->
                            choice.delta?.parts?.any { p -> p is UIMessagePart.Text && p.text.isNotEmpty() } == true
                        }
                        val hasText = when {
                            tagPhase?.undecided == true -> false
                            tagPhase != null -> tagPhase.hasAnswer
                            else -> deltaHasText
                        }
                        if (hasText) {
                            answerPhaseSent = true
                            onPhase?.invoke("answer_streaming")
                        }
                    }
                    onUpdateMessages(messages)
                }
                check(responseEstablished) { "Provider stream completed without a response" }
            } else {
                val chunk = try {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = assembled.providerMessages,
                        params = params,
                    )
                } catch (error: ProviderResponseException) {
                    observeFirstOutput(error.response)
                    messages = accumulator.accumulate(messages, error.response, model)
                    error.response.usage?.let(requestUsage::accept)
                    throw error
                }
                observeFirstOutput(chunk)
                messages = accumulator.accumulate(messages, chunk, model)
                chunk.usage?.let(requestUsage::accept)
                onUpdateMessages(messages)
            }
            requestOutcome = ProviderRequestOutcome.COMPLETED
        } catch (error: Throwable) {
            providerFailure = error
            requestOutcome = if (error is CancellationException) {
                ProviderRequestOutcome.CANCELLED
            } else {
                ProviderRequestOutcome.FAILED
            }
            throw error
        } finally {
            val completedUsage = requestUsage.close(
                outcome = requestOutcome,
                providerRequestDurationMillis = providerDurationMillis(),
                timeToFirstOutputMillis = timeToFirstOutputMillis,
            )
            val appliedUsage = turnUsage.apply(completedUsage)
            val usageDiagnostics = completedUsage.diagnostics + appliedUsage.diagnostics
            if (usageDiagnostics.isNotEmpty()) {
                Log.w(TAG, "Provider usage normalization diagnostics: ${usageDiagnostics.joinToString()}")
            }
            attachUsage(appliedUsage.usage)
            try {
                onUpdateMessages(messages)
            } catch (updateError: Throwable) {
                val failure = providerFailure
                if (failure == null) {
                    throw updateError
                }
                if (updateError !== failure) {
                    failure.addSuppressed(updateError)
                }
            }
        }
        return pendingReceipt
    }
}

/**
 * 把一次非空 Tool Result 滚动裁剪批次写入同一 checkpoint 候选，并在 owning Assistant usage 中只计一次。
 * 只有该 checkpoint 提交成功后消息才会发布，因此失败、取消和重试前不会形成可见计数。
 */
internal fun applyToolOutputCompactionBatchToCheckpoint(
    messages: List<UIMessage>,
    replacements: Map<me.rerere.ai.core.ToolCallLocator, net.weero.measix.pilot.data.ai.tools.ToolOutputStore.CompactionReplacement>,
): List<UIMessage> {
    if (replacements.isEmpty()) return messages
    val replaced = messages.map { message ->
        val byCall = replacements.filterKeys { it.assistantMessageId == message.id }
            .mapKeys { it.key.localCallId }
        if (byCall.isEmpty()) return@map message
        message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val replacement = byCall[part.localCallId] ?: return@map part
            part.copy(
                output = listOf(replacement.marker),
                runtimeState = part.runtimeState.copy(archive = replacement.archive),
            )
        })
    }
    val assistant = replaced.lastOrNull()
        ?.takeIf { it.role == MessageRole.ASSISTANT }
        ?: error("tool output trim batch requires an owning Assistant message")
    val usage = assistant.usage ?: error("tool output trim batch requires turn usage")
    val count = Math.addExact(usage.successfulToolOutputCompactionBatchCount ?: 0, 1)
    return replaced.dropLast(1) + assistant.copy(
        usage = usage.copy(successfulToolOutputCompactionBatchCount = count),
    )
}

internal fun MessageChunk.hasModelOutputPayload(): Boolean = choices.any { choice ->
    val message = choice.delta ?: choice.message ?: return@any false
    message.parts.any { part ->
        when (part) {
            is UIMessagePart.Step -> false
            is UIMessagePart.Text -> part.text.isNotEmpty()
            is UIMessagePart.Reasoning -> part.reasoning.isNotEmpty()
            is UIMessagePart.Tool -> true
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
        }
    }
}
