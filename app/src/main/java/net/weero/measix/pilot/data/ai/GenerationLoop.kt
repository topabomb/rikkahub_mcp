package net.weero.measix.pilot.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.core.merge
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.ai.ui.replaySafeProjection
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.MessageTransformer
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.files.FileFolders
import java.io.File
import net.weero.measix.pilot.data.ai.transformers.transforms
import net.weero.measix.pilot.data.ai.tools.buildMemoryTools
import net.weero.measix.pilot.data.ai.tools.local.askUserApprovalRejection
import net.weero.measix.pilot.data.ai.tools.local.generateImageApprovalRejection
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.model.effectiveContextMessageLimit
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationLoop"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

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

internal data class ToolApprovalResolution(
    val tools: List<UIMessagePart.Tool>,
    val hasPendingApproval: Boolean,
)

internal fun resolveToolApprovals(
    unexecutedTools: List<UIMessagePart.Tool>,
    toolDefinitions: List<Tool>,
    nonInteractive: Boolean,
    interactiveToolNames: Set<String>,
    json: Json,
): ToolApprovalResolution {
    var hasPendingApproval = false
    val updatedTools = unexecutedTools.map { tool ->
        val toolDefinition = toolDefinitions.find { it.name == tool.toolName }
        val args = tool.inputAsJson()
        val contractRejection = askUserApprovalRejection(tool.toolName, args)
            ?: generateImageApprovalRejection(tool.toolName, args)
        when {
            contractRejection != null &&
                tool.approvalState !is ToolApprovalState.Denied &&
                tool.approvalState !is ToolApprovalState.Answered -> {
                tool.copy(output = contractRejection)
            }

            tool.approvalState is ToolApprovalState.Pending -> {
                hasPendingApproval = true
                tool
            }

            tool.approvalState is ToolApprovalState.Auto &&
                toolDefinition?.needsApproval(args) == true -> {
                if (nonInteractive && tool.toolName !in interactiveToolNames) {
                    tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                json.encodeToString(
                                    buildJsonObject {
                                        put("error", JsonPrimitive("tool_not_permitted"))
                                        put("reason", JsonPrimitive("approval_unavailable"))
                                        put(
                                            "message",
                                            JsonPrimitive(
                                                "Approval is required but unavailable in this run. Do not retry unchanged."
                                            )
                                        )
                                    }
                                )
                            )
                        )
                    )
                } else {
                    hasPendingApproval = true
                    tool.copy(approvalState = ToolApprovalState.Pending)
                }
            }

            else -> tool
        }
    }
    return ToolApprovalResolution(updatedTools, hasPendingApproval)
}

internal fun UIMessage.replaceToolsAtOrdinals(
    replacements: Map<Int, UIMessagePart.Tool>,
    preserveCurrentMetadata: Boolean = false,
): UIMessage {
    var ordinal = 0
    return copy(
        parts = parts.map { part ->
            if (part !is UIMessagePart.Tool) {
                part
            } else {
                val replacement = replacements[ordinal++]
                when {
                    replacement == null -> part
                    preserveCurrentMetadata -> replacement.copy(metadata = part.metadata)
                    else -> replacement
                }
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
enum class FinishedReason {
    @SerialName("completed") COMPLETED,
    @SerialName("awaiting_approval") AWAITING_APPROVAL,
    @SerialName("step_limit_reached") STEP_LIMIT_REACHED,
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
data class GenerationCheckpoint(
    val kind: CheckpointKind,
    val messages: List<UIMessage>,
    val toolExecution: ToolExecutionEvent? = null,
)

data class GenerationMemoryContext(
    val ownerId: String,
    val memories: List<AssistantMemory>,
)

/**
 * Resolve the only Memory owner allowed for the next Provider step.
 * A null ceiling is the Master policy (latest Settings may enable or switch scope); a Target
 * ceiling forbids enabling Memory mid-run or changing its namespace.
 */
internal fun resolveGenerationMemoryOwner(
    latest: Assistant?,
    runStartCeiling: Assistant? = null,
): String? {
    if (latest?.enableMemory != true) return null
    if (
        runStartCeiling != null &&
        (!runStartCeiling.enableMemory || latest.useGlobalMemory != runStartCeiling.useGlobalMemory)
    ) {
        return null
    }
    return if (latest.useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else latest.id.toString()
}

data class GenerationRequest(
    val settings: Settings,
    val model: Model,
    /** The immutable wire-container contract selected for this run. */
    val mediaCapabilities: RequestMediaCapabilities,
    val messages: List<UIMessage>,
    val assistant: Assistant,
    val inputTransformers: List<InputMessageTransformer> = emptyList(),
    val outputTransformers: List<OutputMessageTransformer> = emptyList(),
    val memories: List<AssistantMemory>? = null,
    val tools: List<Tool> = emptyList(),
    val maxSteps: Int = 256,
    val reportProcessingText: (String?) -> Unit = {},
    val conversationSystemPrompt: String? = null,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val workspaceCwd: String? = null,
    val toolProvider: (suspend () -> List<Tool>)? = null,
    val nonInteractive: Boolean = false,
    val interactiveToolNames: Set<String> = emptySet(),
    /** Re-resolved once per Provider step; null removes Memory tools and memory prompt. */
    val memoryContextProvider: (suspend () -> GenerationMemoryContext?)? = null,
    /** Final write-time guard for the owner captured by [memoryContextProvider]. */
    val memoryToolAllowed: suspend (ownerId: String) -> Boolean = { true },
    val assistantMessageId: Uuid? = null,
    val onCheckpoint: suspend (GenerationCheckpoint) -> Unit = {},
    val providerSessionId: String? = null,
)

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val attachmentResolver: AttachmentResolver,
) {
    fun resolveRequestMediaCapabilities(settings: Settings, model: Model): RequestMediaCapabilities {
        val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
        return providerManager.getProviderByType(providerSetting)
            .requestMediaCapabilities(providerSetting, model)
    }

    fun run(request: GenerationRequest): Flow<GenerationChunk> {
        val tools = request.tools
        val toolProvider = request.toolProvider ?: { tools }
        val settings = request.settings
        val model = request.model
        val inputTransformers = request.inputTransformers
        val outputTransformers = request.outputTransformers
        val assistant = request.assistant
        val defaultMemoryOwnerId = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistant.id.toString()
        }
        val memoryContextProvider = request.memoryContextProvider ?: {
            if (assistant.enableMemory) {
                GenerationMemoryContext(defaultMemoryOwnerId, request.memories.orEmpty())
            } else {
                null
            }
        }
        val maxSteps = request.maxSteps
        val reportProcessingText = request.reportProcessingText
        val conversationSystemPrompt = request.conversationSystemPrompt
        val conversationModeInjectionIds = request.conversationModeInjectionIds
        val workspaceCwd = request.workspaceCwd
        val nonInteractive = request.nonInteractive
        val interactiveToolNames = request.interactiveToolNames
        val memoryToolAllowed = request.memoryToolAllowed
        val assistantMessageId = request.assistantMessageId
        val onCheckpoint = request.onCheckpoint
        val providerSessionId = request.providerSessionId
        return channelFlow {
        val unpublishedResources = UnpublishedResourceScope()
        var generationFailure: Throwable? = null
        try {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
        val mediaCapabilities = request.mediaCapabilities

        var messages: List<UIMessage> = request.messages

        suspend fun commitCheckpoint(
            kind: CheckpointKind,
            toolExecution: ToolExecutionEvent? = null,
            publishResources: Boolean = false,
        ) {
            try {
                onCheckpoint(
                    GenerationCheckpoint(
                        kind = kind,
                        messages = messages,
                        toolExecution = toolExecution,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CheckpointCommitException(e)
            }
            if (publishResources) unpublishedResources.publishAll()
        }

        // 跟踪循环退出原因，默认 step_limit_reached
        var finishReason = FinishedReason.STEP_LIMIT_REACHED

        fun resourceTrackingTransformerContext() = TransformerContext(
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
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

            // 每个 step 重新解析工具
            val stepTools = toolProvider()
            val memoryContext = memoryContextProvider()
                ?.takeIf { context -> memoryToolAllowed(context.ownerId) }

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (memoryContext != null) {
                    buildMemoryTools(
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryContext.ownerId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content, memoryContext.ownerId)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id, memoryContext.ownerId)
                        },
                        isStillAllowed = { memoryToolAllowed(memoryContext.ownerId) },
                    ).let(this::addAll)
                }
                addAll(stepTools)
            }

            // Deterministic tool index. Built before the Provider request so duplicate or
            // empty names are rejected up front, and tool lookup at execution time never
            // falls back to a linear scan that can leak internal exceptions to the model.
            val toolsByName = buildToolIndex(toolsInternal)

            var unexecutedTools = messages.lastOrNull()?.getTools()?.filter { !it.isExecuted }.orEmpty()

            // 没有上一轮待处理 ToolCall 时才请求模型；审批恢复时绝不提前发起下一 step。
            if (unexecutedTools.isEmpty()) {
                send(GenerationChunk.Phase("preparing"))
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = { updatedMessages ->
                        messages = updatedMessages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                            registerUnpublishedResource = unpublishedResources::register,
                        )
                        send(
                            GenerationChunk.Messages(
                                transformStreamingLast(messages)
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memoryEnabled = memoryContext != null,
                    memories = memoryContext?.memories.orEmpty(),
                    stream = assistant.streamOutput,
                    reportProcessingText = reportProcessingText,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    workspaceCwd = workspaceCwd,
                    assistantMessageId = assistantMessageId,
                    registerUnpublishedResource = unpublishedResources::register,
                    mediaCapabilities = mediaCapabilities,
                    onPhase = { phase -> send(GenerationChunk.Phase(phase)) },
                    providerSessionId = providerSessionId,
                )
                messages = finishStreamingLast(messages)
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                send(GenerationChunk.Messages(messages))
                commitCheckpoint(CheckpointKind.STEP_COMPLETED, publishResources = true)

                unexecutedTools = messages.last().getTools().filter { !it.isExecuted }
                if (unexecutedTools.isEmpty()) {
                    // no tool calls, generation completed
                    finishReason = FinishedReason.COMPLETED
                    break
                }
            }

            // 一批 ToolCall 先统一解析审批状态。只要还有 Pending，本批任何自动工具都不先执行；
            // 全部决策完成后再严格按消息中的原顺序串行执行，保证时序清晰且协议结果完整。
            val messageTools = messages.last().getTools()
            val unexecutedOrdinals = messageTools.mapIndexedNotNull { ordinal, tool ->
                ordinal.takeIf { !tool.isExecuted }
            }
            check(unexecutedOrdinals.size == unexecutedTools.size)
            val approvalResolution = resolveToolApprovals(
                unexecutedTools = unexecutedTools,
                toolDefinitions = toolsInternal,
                nonInteractive = nonInteractive,
                interactiveToolNames = interactiveToolNames,
                json = json,
            )
            val updatedTools = approvalResolution.tools

            if (updatedTools != unexecutedTools) {
                val replacements = unexecutedOrdinals.zip(updatedTools).toMap()
                messages = messages.dropLast(1) + messages.last().replaceToolsAtOrdinals(replacements)
                send(GenerationChunk.Messages(messages))
            }

            if (approvalResolution.hasPendingApproval) {
                Log.i(TAG, "generateText: waiting for all tool approvals")
                finishReason = FinishedReason.AWAITING_APPROVAL
                break
            }

            val toolsToProcess = unexecutedOrdinals.zip(updatedTools)
                .filter { (_, tool) -> !tool.isExecuted }
            if (toolsToProcess.isEmpty()) {
                commitCheckpoint(CheckpointKind.TOOL_RESULT_COMPLETED)
                continue
            }

            // Handle tools (execute approved tools, handle denied tools)
            // tool_executing phase with registered tool name is emitted per-tool below
            val executedTools = linkedMapOf<Int, UIMessagePart.Tool>()
            toolsToProcess.forEach { (toolOrdinalInMessage, tool) ->
                var executionEvent: ToolExecutionEvent? = null
                var executionFailed = false
                val temporaryResources = UnpublishedResourceScope()
                var toolFailure: Throwable? = null
                try {
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools[toolOrdinalInMessage] = tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools[toolOrdinalInMessage] = tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool.
                        // Lookup goes through the deterministic step index; a tool that is not
                        // registered in the current run returns a stable tool_not_available
                        // result instead of throwing an internal exception at the model.
                        val currentMessageId = messages.last().id
                        val executionId = "${currentMessageId}_${toolOrdinalInMessage}"
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")
                        val toolDef = toolsByName[tool.toolName]
                        if (toolDef == null) {
                            executionFailed = true
                            executionEvent = ToolExecutionEvent(
                                executionId = executionId,
                                messageId = currentMessageId,
                                toolOrdinal = toolOrdinalInMessage,
                                toolCallId = tool.toolCallId,
                                toolName = tool.toolName,
                                status = ToolExecutionEventStatus.STARTED,
                            )
                            commitCheckpoint(
                                kind = CheckpointKind.TOOL_EXECUTION_STARTED,
                                toolExecution = executionEvent,
                            )
                            Log.w(
                                TAG,
                                "Unavailable tool call: ${tool.toolName}. " +
                                    "Available tools: ${toolsByName.keys.sorted()}",
                            )
                            executedTools[toolOrdinalInMessage] = tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("status", "failed")
                                                put("reason", "tool_not_available")
                                                put("tool", tool.toolName)
                                                put(
                                                    "message",
                                                    "This tool is not available in the current run. Do not retry unchanged.",
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        } else {
                            runCatching {
                                val args = runCatching {
                                    json.parseToJsonElement(tool.input.ifBlank { "{}" })
                                }.getOrElse {
                                    error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                                }
                                // 执行每个具体 ToolCall 前必须发出 Phase(tool_executing, registeredToolName)
                                send(GenerationChunk.Phase("tool_executing", toolDef.name))
                            executionEvent = ToolExecutionEvent(
                                executionId = executionId,
                                messageId = currentMessageId,
                                toolOrdinal = toolOrdinalInMessage,
                                toolCallId = tool.toolCallId,
                                toolName = toolDef.name,
                                status = ToolExecutionEventStatus.STARTED,
                            )
                            commitCheckpoint(
                                kind = CheckpointKind.TOOL_EXECUTION_STARTED,
                                toolExecution = executionEvent,
                            )
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")

                            // 构建 ToolExecutionContext，提供 reportMetadata 回写能力
                            // messageId + toolOrdinal 是精确 locator，不依赖 toolCallId
                            val toolOrdinal = toolOrdinalInMessage
                            val execContext = ToolExecutionContext(
                                messageId = currentMessageId,
                                toolOrdinal = toolOrdinal,
                                toolCallId = tool.toolCallId,
                                // 最小只读附件能力：refs → 统一 AttachmentResolver。
                                // 消息快照（含本 run 内已完成的 Tool 结果）是 Runtime 实现细节，不暴露给工具。
                                resolveAttachments = { refs ->
                                    when (val resolved = attachmentResolver.resolveImages(messages, refs)) {
                                        is AttachmentResolveResult.Success -> {
                                            resolved.createdArtifacts.forEach { owned ->
                                                temporaryResources.register(attachmentResolver.temporaryLease(owned))
                                            }
                                            ToolAttachmentResolution(resolved.parts)
                                        }
                                        is AttachmentResolveResult.Failure -> ToolAttachmentResolution(
                                            failureReason = resolved.reason,
                                        )
                                    }
                                },
                                reportMetadata = { patch: JsonObject, checkpoint: Boolean ->
                                    // 从最新 messages 中按 locator 重新取得 Tool，merge metadata patch
                                    val allTools = messages.last().getTools()
                                    val currentTool = allTools.getOrNull(toolOrdinal)
                                        ?: return@ToolExecutionContext
                                    val existingMeta = currentTool.metadata ?: JsonObject(emptyMap())
                                    val newMeta = JsonObject(
                                        existingMeta.toMutableMap().apply { putAll(patch) }
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
                                    send(
                                        GenerationChunk.Messages(
                                            transformStreamingLast(messages)
                                        )
                                    )
                                    if (checkpoint) {
                                        commitCheckpoint(CheckpointKind.TOOL_STATE_CHANGED)
                                    }
                                },
                                // 委派类工具派生会话确定后，将 child id 并入本次执行的 durable 事实
                                reportChildConversation = { childConversationId ->
                                    executionEvent = executionEvent?.copy(
                                        childConversationId = childConversationId,
                                    )
                                    if (executionEvent != null) {
                                        commitCheckpoint(
                                            CheckpointKind.TOOL_STATE_CHANGED,
                                            toolExecution = executionEvent,
                                            publishResources = true,
                                        )
                                    }
                                },
                                registerUnpublishedResource = unpublishedResources::register,
                            )

                            val result = toolDef.executeWithContext(execContext, args)

                            // 执行完成后，从最新 messages 按 locator 重新取得 Tool，copy terminal output
                            val finalTool = messages.last().getTools().getOrNull(toolOrdinal)
                                ?: tool
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            // 使用 locator 唯一确定的 execution ID，不使用 Provider toolCallId
                            // 避免空值、跨 step 复用或路径字符造成覆盖与越界风险
                            executedTools[toolOrdinalInMessage] = finalTool.copy(
                                output = maybeTruncateToolOutput(
                                    executionId = executionId,
                                    output = result,
                                    hasShellAccess = hasShellAccess,
                                    outputPolicy = toolDef.outputPolicy,
                                )
                            )
                        }.onFailure {
                            if (it is CheckpointCommitException) throw it.cause ?: it
                            // 1. 工具超时: TimeoutCancellationException 是 CancellationException 子类
                            //    → 降级为错误 JSON 返回给 AI，不中断对话
                            if (it is TimeoutCancellationException) {
                                executionFailed = true
                                Log.w(TAG, "Tool ${tool.toolName} timed out: ${it.message}")
                                executedTools[toolOrdinalInMessage] = tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put("error", JsonPrimitive("Tool '${tool.toolName}' timed out"))
                                                    put("type", JsonPrimitive("timeout"))
                                                }
                                            )
                                        )
                                    )
                                )
                                return@onFailure
                            }
                            // 2. 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            executionFailed = true
                            // 3. 其他异常: 包装为结构化错误 JSON 返回给 AI
                            Log.w(TAG, "Tool ${tool.toolName} failed: ${it.message}", it)
                            executedTools[toolOrdinalInMessage] = tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    // Exception class names are obfuscated in Release; the full type and
                                                    // stack remain in Logcat, while protocol output stays stable.
                                                    JsonPrimitive(it.message ?: "Unknown error")
                                                )
                                                put("type", JsonPrimitive("error"))
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
                    }
                } catch (error: Throwable) {
                    toolFailure = error
                    throw error
                } finally {
                    temporaryResources.discardAll()?.let { cleanupFailure ->
                        if (toolFailure != null) {
                            requireNotNull(toolFailure).addSuppressed(cleanupFailure)
                        } else {
                            throw cleanupFailure
                        }
                    }
                }

                val executedTool = executedTools.remove(toolOrdinalInMessage)
                if (executedTool != null) {
                    messages = messages.dropLast(1) + messages.last().replaceToolsAtOrdinals(
                        replacements = mapOf(toolOrdinalInMessage to executedTool),
                        preserveCurrentMetadata = true,
                    )
                    send(
                        GenerationChunk.Messages(
                            messages.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings,
                                registerUnpublishedResource = unpublishedResources::register,
                            )
                        )
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
                        publishResources = true,
                    )
                }
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

    }.flowOn(Dispatchers.IO)
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memoryEnabled: Boolean,
        memories: List<AssistantMemory>,
        stream: Boolean,
        reportProcessingText: (String?) -> Unit = {},
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        assistantMessageId: Uuid? = null,
        registerUnpublishedResource: (ToolResourceLease) -> Unit,
        mediaCapabilities: RequestMediaCapabilities,
        onPhase: (suspend (String) -> Unit)? = null,
        providerSessionId: String? = null,
    ) {
        val contextMessages = messages
            .filterNot { message ->
                message.id == assistantMessageId &&
                    message.role == MessageRole.ASSISTANT &&
                    message.parts.isEmpty()
            }
            .replaySafeProjection()
            .limitContext(assistant.effectiveContextMessageLimit())
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (memoryEnabled) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }

                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, contextMessages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(contextMessages)
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            reportProcessingText = reportProcessingText,
            workspaceCwd = workspaceCwd,
            mediaCapabilities = mediaCapabilities,
            registerUnpublishedResource = registerUnpublishedResource,
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
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
        if (stream) {
            var reasoningPhaseSent = false
            var answerPhaseSent = false
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(
                    chunk = it,
                    model = model,
                    assistantMessageId = assistantMessageId,
                )
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                // Phase uses the same accumulated-message semantics as the output projection.
                // Text inside a leading <think> block is reasoning, not answer content.
                val tagPhase = messages.lastOrNull()?.let(ThinkTagTransformer::classifyPhase)
                if (!reasoningPhaseSent) {
                    val hasReasoning = it.choices.any { choice ->
                        choice.delta?.parts?.any { p -> p is UIMessagePart.Reasoning } == true
                    } || tagPhase?.hasReasoning == true
                    if (hasReasoning) {
                        reasoningPhaseSent = true
                        onPhase?.invoke("reasoning_streaming")
                    }
                }
                if (!answerPhaseSent) {
                    val deltaHasText = it.choices.any { choice ->
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
        } else {
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(
                chunk = chunk,
                model = model,
                assistantMessageId = assistantMessageId,
            )
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private fun maybeTruncateToolOutput(
        executionId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
        outputPolicy: me.rerere.ai.core.ToolOutputPolicy,
    ): List<UIMessagePart> {
        if (outputPolicy == me.rerere.ai.core.ToolOutputPolicy.PRESERVE) return output
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $executionId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${executionId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    /**
     * Build an immutable, deterministic tool index for the current step.
     *
     * Rejects blank names and duplicate registrations before the Provider request, so a
     * misconfigured tool set fails loudly instead of silently dropping or shadowing a tool.
     */
    private fun buildToolIndex(tools: List<Tool>): Map<String, Tool> {
        val map = LinkedHashMap<String, Tool>(tools.size)
        for (tool in tools) {
            require(tool.name.isNotBlank()) { "Tool name must not be blank" }
            require(tool.name !in map) { "Duplicate tool name: ${tool.name}" }
            map[tool.name] = tool
        }
        return map.toMap()
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
