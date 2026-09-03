package net.weero.measix.pilot.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.core.ToolCallLocator
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderResponseException
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.replaySafeProjection
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.MessageTransformer
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.RequestMessageOriginTracker
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.ai.transformers.transforms
import net.weero.measix.pilot.data.ai.tools.LocatedToolCall
import net.weero.measix.pilot.data.ai.tools.PendingInteraction
import net.weero.measix.pilot.data.ai.tools.ResolvedToolCall
import net.weero.measix.pilot.data.ai.tools.ToolCallRuntime
import net.weero.measix.pilot.data.ai.tools.ToolExecutionHooks
import net.weero.measix.pilot.data.ai.tools.ToolInteractionAvailability
import net.weero.measix.pilot.data.ai.tools.ToolOutputArchive
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.effectiveContextMessageLimit
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

private const val TAG = "GenerationLoop"

private class CheckpointCommitException(cause: Throwable) : RuntimeException(cause)

private class UnpublishedResourceScope {
    private val resources = mutableListOf<ToolResourceLease>()

    fun register(resource: ToolResourceLease) {
        synchronized(resources) { resources += resource }
    }

    suspend fun publishAll() {
        withContext(NonCancellable) {
            while (true) {
                val resource = synchronized(resources) { resources.firstOrNull() } ?: break
                resource.publish()
                synchronized(resources) { resources.remove(resource) }
            }
        }
    }

    suspend fun discardAll(): Throwable? = withContext(NonCancellable) {
        val owned = synchronized(resources) {
            resources.toList().also { resources.clear() }
        }
        var failure: Throwable? = null
        owned.asReversed().forEach { resource ->
            try {
                resource.discard()
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure
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
        val byOrdinal = replacements.filterKeys { it.messageId == message.id }
            .mapKeys { it.key.toolOrdinal }
        if (byOrdinal.isEmpty()) return@map message
        var ordinal = 0
        message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val replacement = byOrdinal[ordinal++] ?: return@map part
            part.copy(
                output = listOf(replacement.marker),
                metadata = replacement.archive?.let { ToolRuntimeMetadata.withArchive(part.metadata, it) }
                    ?: part.metadata,
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
internal fun UIMessage.replaceToolsAtOrdinals(
    replacements: Map<Int, UIMessagePart.Tool>,
): UIMessage {
    var ordinal = 0
    return copy(
        parts = parts.map { part ->
            if (part !is UIMessagePart.Tool) {
                part
            } else {
                val replacement = replacements[ordinal++]
                replacement ?: part
            }
        }
    )
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk

    /**
     * 生成阶段变化事件。phase 使用稳定英文枚举，不使用本地化字符串。
     */
    data class Phase(
        val phase: String,
        val toolName: String? = null,
    ) : GenerationChunk

    /**
     * 生成结束事件。Collector 必须区分正常完成、待审批和 step 上限。
     * 达到最大 step 时不能让 Flow 静默正常结束，否则 Coordinator 会把未完成运行误记为 completed。
     */
    data class Finished(
        val reason: FinishedReason,
    ) : GenerationChunk
}

@Serializable
sealed interface FinishedReason {
    @Serializable
    @SerialName("completed")
    data object Completed : FinishedReason

    /**
     * 因等待用户交互而暂停。[pending] 由 Runtime 在判定挂起的那一刻产出，非空，
     * 因此下游消费者无需回消息里扫描重建定位。
     */
    @Serializable
    @SerialName("awaiting_approval")
    data class AwaitingApproval(
        val pending: List<PendingInteraction>,
    ) : FinishedReason {
        init {
            require(pending.isNotEmpty()) { "AWAITING_APPROVAL requires pending interactions" }
        }
    }

    @Serializable
    @SerialName("step_limit_reached")
    data object StepLimitReached : FinishedReason
}

@Serializable
enum class CheckpointKind {
    @Serializable
    STEP_COMPLETED,

    @Serializable
    TOOL_STATE_CHANGED,

    @Serializable
    TOOL_RESULT_COMPLETED,

    @Serializable
    TOOL_EXECUTION_STARTED,

    @Serializable
    AWAITING_APPROVAL,
}

@Serializable
enum class ToolExecutionEventStatus {
    @SerialName("started") STARTED,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
}

@Serializable
data class ToolExecutionEvent(
    val executionId: String,
    val messageId: Uuid,
    val toolOrdinal: Int,
    val toolCallId: String,
    val toolName: String,
    val status: ToolExecutionEventStatus,
    /** 委派类工具派生的 Child 会话 id（调用↔Child 关系归位到执行事实行）。 */
    val childConversationId: String? = null,
)

/**
 * An awaited durability boundary. Production owners must not return until both the
 * message snapshot and optional tool execution fact are committed.
 */
/** 对一个已消费历史 Tool Result 的窄压缩改写；不得携带整条历史消息。 */
data class ToolOutputCompactionPatch(
    val locator: ToolCallLocator,
    val marker: UIMessagePart.Text,
    /** 可归档正文有 Artifact；可再生回查结果只折叠 marker。 */
    val archive: ToolOutputArchive? = null,
)

data class GenerationCheckpoint(
    val kind: CheckpointKind,
    val messages: List<UIMessage>,
    val toolExecution: ToolExecutionEvent? = null,
    val toolResults: List<ToolResultEvent> = emptyList(),
    /** 本 checkpoint 中发生 marker 改写的全部 Tool Result；只允许统一压缩入口填充。 */
    val toolOutputCompactionPatches: List<ToolOutputCompactionPatch> = emptyList(),
)

enum class ToolResultEventStatus {
    COMPLETED,
    FAILED,
    DENIED,
    ANSWERED,
}

/** Typed presentation fact committed with a tool-result message checkpoint. */
data class ToolResultEvent(
    val messageId: Uuid,
    val toolOrdinal: Int,
    val status: ToolResultEventStatus,
)

/** Resolves the Memory namespace captured at START and rechecked before each live write. */
internal fun resolveMemoryOwnerId(assistant: Assistant?): String? {
    if (assistant?.enableMemory != true) return null
    return if (assistant.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString()
}

internal data class GenerationRequest(
    val conversationId: Uuid,
    /** The only model-visible configuration source for every step of this durable Turn. */
    val requestContext: net.weero.measix.pilot.service.runtime.TurnRequestContext,
    val messages: List<UIMessage>,
    val inputTransformers: List<InputMessageTransformer> = emptyList(),
    val outputTransformers: List<OutputMessageTransformer> = emptyList(),
    val maxSteps: Int = 256,
    val reportProcessingText: (String?) -> Unit = {},
    /** Which user interactions this run may pause for; replaces the old name-whitelist policy. */
    val interactionAvailability: ToolInteractionAvailability = ToolInteractionAvailability.FULL,
    val assistantMessageId: Uuid? = null,
    /** Turn START 提交的 disclosure baseline（冻结适用集合）；Provider step 不重新捕获。 */
    val modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
    /** message ID → durable 树位置；Planner 的 Durable origin 唯一来源。 */
    val durableMessageLocators: Map<Uuid, DurableMessageLocator> = emptyMap(),
    /** Synchronous handoff to the turn owner; independent of cancellable presentation delivery. */
    val onMessagesObserved: (List<UIMessage>) -> Unit = {},
    val onCheckpoint: suspend (GenerationCheckpoint) -> Unit = {},
    val providerSessionId: String? = null,
)

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val attachmentResolver: AttachmentResolver,
    private val toolOutputStore: net.weero.measix.pilot.data.ai.tools.ToolOutputStore,
) {
    private val toolCallRuntime = ToolCallRuntime(json)
    private val contextPlanner = ConversationContextPlanner()
    fun resolveRequestMediaCapabilities(settings: Settings, model: Model): RequestMediaCapabilities {
        val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
        return providerManager.getProviderByType(providerSetting)
            .requestMediaCapabilities(providerSetting, model)
    }

    internal fun run(request: GenerationRequest): Flow<GenerationChunk> {
        val conversationId = request.conversationId
        val turnContext = request.requestContext
        val model = turnContext.model.model
        val frozenProvider = turnContext.model.providerShape
        val assistant = turnContext.assistant
        val promptInputs = turnContext.promptInputs
        val toolsByName = turnContext.toolBindingsByName
        val toolDefinitions = turnContext.toolDefinitions
        val inputTransformers = request.inputTransformers
        val outputTransformers = request.outputTransformers
        val maxSteps = request.maxSteps
        val reportProcessingText = request.reportProcessingText
        val interactionAvailability = request.interactionAvailability
        val assistantMessageId = request.assistantMessageId
        val onCheckpoint = request.onCheckpoint
        val providerSessionId = request.providerSessionId
        val modelContextEntries = request.modelContextEntries
        val durableMessageLocators = request.durableMessageLocators
        return channelFlow {
        val unpublishedResources = UnpublishedResourceScope()
        var generationFailure: Throwable? = null
        try {
        val mediaCapabilities = turnContext.mediaCapabilities

        var messages: List<UIMessage> = request.messages
        request.onMessagesObserved(messages)

        suspend fun publishMessages(next: List<UIMessage>) {
            request.onMessagesObserved(next)
            send(GenerationChunk.Messages(next))
        }

        suspend fun commitCheckpoint(
            kind: CheckpointKind,
            toolExecution: ToolExecutionEvent? = null,
            toolResults: List<ToolResultEvent> = emptyList(),
            publishResources: Boolean = false,
            checkpointMessages: List<UIMessage> = messages,
            toolOutputCompactionPatches: List<ToolOutputCompactionPatch> = emptyList(),
        ) {
            suspend fun commit() {
                onCheckpoint(
                    GenerationCheckpoint(
                        kind = kind,
                        messages = checkpointMessages,
                        toolOutputCompactionPatches = toolOutputCompactionPatches,
                        toolExecution = toolExecution,
                        toolResults = toolResults,
                    )
                )
                if (publishResources) {
                    request.onMessagesObserved(checkpointMessages)
                    unpublishedResources.publishAll()
                }
            }
            try {
                if (publishResources) {
                    coroutineContext.ensureActive()
                    // A completed output owns these resources. Durable rooting, lease handoff and
                    // the turn-owned projection must finish together before cancellation resumes.
                    withContext(NonCancellable) { commit() }
                } else {
                    commit()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CheckpointCommitException(e)
            }
        }

        // 跟踪循环退出原因，默认 step_limit_reached
        var finishReason: FinishedReason = FinishedReason.StepLimitReached

        // 流式/终态输出变换不参与请求来源跟踪；请求级 tracker 只属于 generateInternal 的输入链。
        val outputOrigins = RequestMessageOriginTracker()

        fun resourceTrackingTransformerContext() = TransformerContext(
            context = context,
            model = model,
            assistant = assistant,
            promptInputs = promptInputs,
            requestOrigins = outputOrigins,
            mediaCapabilities = mediaCapabilities,
            registerUnpublishedResource = unpublishedResources::register,
        )
        var latestStreamingProjection: UIMessage? = null

        // 流式单消息通道：历史消息 immutable，仅最后一条（active assistant 消息）进入变换。
        // 流式契约：流式期间历史消息进入 transformStreaming 的次数为 0。
        suspend fun transformStreamingLast(current: List<UIMessage>): List<UIMessage> {
            if (current.isEmpty()) return current
            val ctx = resourceTrackingTransformerContext()
            var last = current.last()
            for (transformer in outputTransformers) {
                if (transformer is StreamingMessageTransformer) {
                    last = transformer.transformStreaming(ctx, last, latestStreamingProjection)
                }
            }
            latestStreamingProjection = last
            if (last === current.last()) return current
            return current.dropLast(1) + last
        }

        // 终态收口：step 完成时对最后一条消息应用 onStreamingFinish（reasoning 补时戳、base64 落盘）
        suspend fun finishStreamingLast(current: List<UIMessage>): List<UIMessage> {
            if (current.isEmpty()) return current
            val ctx = resourceTrackingTransformerContext()
            val last = finishStreamingProjection(
                raw = current.last(),
                previousProjection = latestStreamingProjection,
                ctx = ctx,
                transformers = outputTransformers,
            )
            if (last === current.last()) return current
            return current.dropLast(1) + last
        }

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
            latestStreamingProjection = null

            var toolsAwaitingReplayResult = messages.lastOrNull()?.getTools()?.filter { !it.hasReplayResult }.orEmpty()

            // 没有上一轮待处理 ToolCall 时才请求模型；审批恢复时绝不提前发起下一 step。
            if (toolsAwaitingReplayResult.isEmpty()) {
                send(GenerationChunk.Phase("preparing"))
                val provider = net.weero.measix.pilot.service.runtime.mergeProviderTransportCredentials(
                    frozen = frozenProvider,
                    live = turnContext.model.transportLease.acquire(),
                )
                val providerImpl = providerManager.getProviderByType(provider)
                val receipt = generateInternal(
                    assistant = assistant,
                    promptInputs = promptInputs,
                    messages = messages,
                    onUpdateMessages = { updatedMessages ->
                        // Publish the turn-owned raw projection before the first suspension so
                        // cancellation finalization can retain usage already observed on the wire.
                        messages = updatedMessages
                        request.onMessagesObserved(messages)
                        messages = messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            promptInputs = promptInputs,
                            requestOrigins = outputOrigins,
                            registerUnpublishedResource = unpublishedResources::register,
                        )
                        publishMessages(transformStreamingLast(messages))
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    toolDefinitions = toolDefinitions,
                    stream = assistant.streamOutput,
                    reportProcessingText = reportProcessingText,
                    assistantMessageId = assistantMessageId,
                    registerUnpublishedResource = unpublishedResources::register,
                    mediaCapabilities = mediaCapabilities,
                    onPhase = { phase -> send(GenerationChunk.Phase(phase)) },
                    providerSessionId = providerSessionId,
                    modelContextEntries = modelContextEntries,
                    durableLocators = durableMessageLocators,
                )
                messages = finishStreamingLast(messages)
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                val compactionPlan = contextPlanner.planPostStepCompaction(messages, receipt)
                val stagedCompaction = toolOutputStore.stageCompaction(compactionPlan)
                stagedCompaction.lease?.let(unpublishedResources::register)
                val checkpointMessages = applyToolOutputCompactionBatchToCheckpoint(
                    messages,
                    stagedCompaction.replacements,
                )
                val toolOutputCompactionPatches = stagedCompaction.replacements.map { (locator, replacement) ->
                    ToolOutputCompactionPatch(
                        locator = locator,
                        marker = replacement.marker,
                        archive = replacement.archive,
                    )
                }
                commitCheckpoint(
                    CheckpointKind.STEP_COMPLETED,
                    publishResources = true,
                    checkpointMessages = checkpointMessages,
                    toolOutputCompactionPatches = toolOutputCompactionPatches,
                )
                messages = checkpointMessages
                publishMessages(messages)

                toolsAwaitingReplayResult = messages.last().getTools().filter { !it.hasReplayResult }
                if (toolsAwaitingReplayResult.isEmpty()) {
                    // no tool calls, generation completed
                    finishReason = FinishedReason.Completed
                    break
                }
            }

            // 一批 ToolCall 先经过统一门控：解析、校验与交互判断在 ToolCallRuntime 中一次完成。
            // 只要还有 Pending，本批任何自动工具都不先执行；全部决策完成后再严格按消息中的原顺序串行执行。
            val messageTools = messages.last().getTools()
            val replayPendingOrdinals = messageTools.mapIndexedNotNull { ordinal, tool ->
                ordinal.takeIf { !tool.hasReplayResult }
            }
            check(replayPendingOrdinals.size == toolsAwaitingReplayResult.size)
            val preparation = toolCallRuntime.prepareBatch(
                messageId = messages.last().id,
                calls = replayPendingOrdinals.map { LocatedToolCall(it, messageTools[it]) },
                toolIndex = toolsByName,
                availability = interactionAvailability,
            )

            val pendingOrdinals = preparation.pending.map { it.locator.toolOrdinal }.toSet()
            val resultReplacements = preparation.replacements.filterKeys { it !in pendingOrdinals }
            val pendingReplacements = preparation.replacements.filterKeys { it in pendingOrdinals }

            if (resultReplacements.isNotEmpty()) {
                messages = messages.dropLast(1) +
                    messages.last().replaceToolsAtOrdinals(resultReplacements)
            }

            if (preparation.immediateResults.isNotEmpty()) {
                commitCheckpoint(
                    kind = CheckpointKind.TOOL_RESULT_COMPLETED,
                    toolResults = preparation.immediateResults,
                )
                if (preparation.pending.isEmpty()) {
                    publishMessages(transformStreamingLast(messages))
                }
            }

            if (preparation.pending.isNotEmpty()) {
                if (pendingReplacements.isNotEmpty()) {
                    messages = messages.dropLast(1) +
                        messages.last().replaceToolsAtOrdinals(pendingReplacements)
                }
                request.onMessagesObserved(messages)
                Log.i(TAG, "generateText: waiting for all tool user interactions")
                finishReason = FinishedReason.AwaitingApproval(preparation.pending)
                break
            }

            if (preparation.resolvedCalls.isEmpty()) {
                continue
            }

            // tool_executing phase with registered tool name is emitted per-tool below
            for (resolved in preparation.resolvedCalls) {
                var executionEvent: ToolExecutionEvent? = null
                var executionFailed = false
                val completedTool: UIMessagePart.Tool
                when (resolved) {
                    is ResolvedToolCall.Denied -> completedTool = resolved.result
                    is ResolvedToolCall.Answered -> completedTool = resolved.result
                    is ResolvedToolCall.Executable -> {
                        val call = resolved.call
                        val toolOrdinal = resolved.ordinal
                        send(GenerationChunk.Phase("tool_executing", call.definition.name))
                        executionEvent = ToolExecutionEvent(
                            executionId = call.executionId,
                            messageId = call.locator.messageId,
                            toolOrdinal = toolOrdinal,
                            toolCallId = call.source.toolCallId,
                            toolName = call.definition.name,
                            status = ToolExecutionEventStatus.STARTED,
                        )
                        commitCheckpoint(
                            kind = CheckpointKind.TOOL_EXECUTION_STARTED,
                            toolExecution = executionEvent,
                        )
                        publishMessages(transformStreamingLast(messages))
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
                                // The tool_runtime namespace belongs to the Runtime alone.
                                val toolPatch = ToolRuntimeMetadata.requireToolOwnedPatch(patch)
                                // Re-read the Tool by locator from the latest messages and merge the patch.
                                val allTools = messages.last().getTools()
                                val currentTool = allTools.getOrNull(toolOrdinal)
                                    ?: return@ToolExecutionHooks
                                val existingMeta = currentTool.metadata ?: JsonObject(emptyMap())
                                val newMeta = JsonObject(
                                    existingMeta.toMutableMap().apply { putAll(toolPatch) }
                                )
                                val updatedTool = currentTool.copy(metadata = newMeta)
                                val lastMsg = messages.last()
                                var toolCount = 0
                                val newParts = lastMsg.parts.map { p ->
                                    if (p is UIMessagePart.Tool) {
                                        if (toolCount == toolOrdinal) {
                                            toolCount++
                                            updatedTool
                                        } else {
                                            toolCount++
                                            p
                                        }
                                    } else {
                                        p
                                    }
                                }
                                messages = messages.dropLast(1) + lastMsg.copy(parts = newParts)
                                when (delivery) {
                                    ToolMetadataDelivery.DEFERRED -> Unit
                                    ToolMetadataDelivery.STREAMING -> publishMessages(transformStreamingLast(messages))
                                    ToolMetadataDelivery.CHECKPOINT -> {
                                        commitCheckpoint(CheckpointKind.TOOL_STATE_CHANGED)
                                        publishMessages(transformStreamingLast(messages))
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
                                    commitCheckpoint(
                                        CheckpointKind.TOOL_STATE_CHANGED,
                                        toolExecution = executionEvent,
                                    )
                                    request.onMessagesObserved(messages)
                                }
                            },
                            registerUnpublishedResource = unpublishedResources::register,

                        )

                        val outcome = toolCallRuntime.execute(call, hooks)
                        executionFailed = outcome.executionFailed
                        // Re-read by locator so metadata reported during execution is retained; the
                        // Runtime-owned tool_metadata is merged here and committed with the result.
                        val latestTool = messages.last().getTools().getOrNull(toolOrdinal) ?: call.source
                        completedTool = latestTool.copy(
                            output = outcome.output,
                            metadata = ToolRuntimeMetadata.applyTo(
                                latestTool.metadata,
                                outcome.runtimeMetadata,
                            ),
                        )
                    }
                }

                messages = messages.dropLast(1) + messages.last().replaceToolsAtOrdinals(
                    replacements = mapOf(resolved.ordinal to completedTool),
                )
                val presentationMessages = messages.transforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    promptInputs = promptInputs,
                    requestOrigins = outputOrigins,
                    registerUnpublishedResource = unpublishedResources::register,
                )
                commitCheckpoint(
                    kind = CheckpointKind.TOOL_RESULT_COMPLETED,
                    toolExecution = executionEvent?.copy(
                        status = if (executionFailed) {
                            ToolExecutionEventStatus.FAILED
                        } else {
                            ToolExecutionEventStatus.COMPLETED
                        },
                    ),
                    toolResults = listOf(
                        ToolResultEvent(
                            messageId = messages.last().id,
                            toolOrdinal = resolved.ordinal,
                            status = when {
                                resolved is ResolvedToolCall.Denied -> ToolResultEventStatus.DENIED
                                resolved is ResolvedToolCall.Answered -> ToolResultEventStatus.ANSWERED
                                executionFailed -> ToolResultEventStatus.FAILED
                                else -> ToolResultEventStatus.COMPLETED
                            },
                        )
                    ),
                    publishResources = true,
                    checkpointMessages = presentationMessages,
                )
                messages = presentationMessages
                // Clear the committed EXECUTING projection even when no provider chunk follows.
                publishMessages(messages)
            }

            send(GenerationChunk.Phase("between_steps"))
        }

        // 生成结束事件：Collector 必须区分正常完成、待审批和 step 上限
        send(GenerationChunk.Finished(finishReason))

        } catch (error: Throwable) {
            generationFailure = error
            throw error
        } finally {
            unpublishedResources.discardAll()?.let { cleanupFailure ->
                if (generationFailure != null) {
                    requireNotNull(generationFailure).addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }

    }.buffer(capacity = 0).flowOn(Dispatchers.IO)
    }

    private suspend fun generateInternal(
        assistant: net.weero.measix.pilot.service.runtime.ResolvedAssistantRequest,
        promptInputs: net.weero.measix.pilot.service.runtime.FrozenTurnPromptInputs,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
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
    ): ModelStepReceipt {
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

            // Memory 内容不再进入 System：唯一披露路径是 START 提交的 canonical Snapshot（§10.1）。

            // 工具prompt：只使用装配时冻结的 contribution（§7.6），不按 step 重新求值。
            toolDefinitions.forEach { definition ->
                appendLine()
                append(definition.systemPromptContribution)
            }

            // 请求携带 Snapshot 时才追加 §6.3 固定规则；规则本身无任何动态内容。
            if (requestPlan.contextProjections.isNotEmpty()) {
                appendLine()
                append(ConversationDisclosureSnapshotService.MODEL_RULES)
            }
        }
        // 本次请求唯一的来源跟踪器：System 与后续注入内容由管线合成，不能被 messageTemplate 包裹。
        // 只标记本次新建的 System；preset 等 durable SYSTEM 消息仍是用户配置，保持原模板行为。
        val requestOrigins = RequestMessageOriginTracker()
        // 唯一 origin 表的 durable 半边在此登记；transformers 完成后 frozenOrigins 是
        // Durable + Synthetic 的完整来源事实（§8.1）。
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

        // §8.3 步骤 6：context part 在全部 input transformers 之后注入，只附着 Durable USER；
        // Token estimate 与 receipt 使用包含 context 的最终 projection。
        val internalMessages = contextPlanner.applyContextProjections(
            transformedMessages = transformedMessages,
            projections = requestPlan.contextProjections,
            originsByMessageId = requestOrigins.frozenOrigins(),
        )

        val pendingReceipt = contextPlanner.receiptOf(internalMessages)
        val estimatedRequestContextTokens = contextPlanner.estimateRequestContextTokens(
            providerMessages = internalMessages,
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
                    messages = internalMessages,
                    params = params
                ).collect { chunk ->
                    responseEstablished = true
                    observeFirstOutput(chunk)
                    messages = messages.handleMessageChunk(
                        chunk = chunk,
                        model = model,
                        assistantMessageId = assistantMessageId,
                    )
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
                        messages = internalMessages,
                        params = params,
                    )
                } catch (error: ProviderResponseException) {
                    observeFirstOutput(error.response)
                    messages = messages.handleMessageChunk(
                        chunk = error.response,
                        model = model,
                        assistantMessageId = assistantMessageId,
                    )
                    error.response.usage?.let(requestUsage::accept)
                    throw error
                }
                observeFirstOutput(chunk)
                messages = messages.handleMessageChunk(
                    chunk = chunk,
                    model = model,
                    assistantMessageId = assistantMessageId,
                )
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

internal fun MessageChunk.hasModelOutputPayload(): Boolean = choices.any { choice ->
    val message = choice.delta ?: choice.message ?: return@any false
    message.parts.any { part ->
        when (part) {
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

internal suspend fun finishStreamingProjection(
    raw: UIMessage,
    previousProjection: UIMessage?,
    ctx: TransformerContext,
    transformers: List<OutputMessageTransformer>,
): UIMessage = transformers.fold(raw) { current, transformer ->
    if (transformer is StreamingMessageTransformer) {
        transformer.onStreamingFinish(ctx, current, previousProjection)
    } else {
        current
    }
}
