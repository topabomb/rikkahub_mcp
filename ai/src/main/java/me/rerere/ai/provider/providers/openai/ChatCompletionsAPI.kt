@file:Suppress("UNNECESSARY_SAFE_CALL")
package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ChatCompletionsAPI"

/**
 * Chat Completions may interleave argument deltas for parallel tool calls. Later deltas may omit
 * id/name while retaining the stable array [index]. Keep this mapping per SSE request so an id-less
 * continuation is merged into the correct tool instead of whichever tool arrived most recently.
 */
internal class ChatCompletionsStreamState {
    private val toolCallIdsByIndex = mutableMapOf<Int, String>()

    fun resolveToolCallId(index: Int?, announcedId: String?): String? {
        if (index != null && !announcedId.isNullOrBlank()) {
            toolCallIdsByIndex[index] = announcedId
        }
        return announcedId?.takeIf { it.isNotBlank() }
            ?: index?.let(toolCallIdsByIndex::get)
    }
}

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.d(TAG, "generateText: model=${params.model.modelId}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(message),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.d(TAG, "streamText: model=${params.model.modelId}")

        val streamState = ChatCompletionsStreamState()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                try {
                    data
                        .trim()
                        .split("\n")
                        .filter { it.isNotBlank() }
                        .map { json.parseToJsonElement(it).jsonObject }
                        .forEach {
                            // OpenAI-compatible gateways can report an application error inside an HTTP 200 SSE
                            // stream. Throwing from OkHttp's callback would bypass the Flow collector; close it with
                            // the parsed cause so the UI receives the failure through the normal generation path.
                            if (it["error"] != null) {
                                close(it["error"]!!.parseErrorDetail())
                                return
                            }
                            val messageChunk = parseStreamPayload(it, streamState)
                            trySend(messageChunk).onFailure { e ->
                                Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                            }
                        }
                } catch (e: Throwable) {
                    Log.w(TAG, "onEvent: failed to process chat completion event", e)
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                if (t != null) {
                    Log.w(TAG, "onFailure: stream transport failed (http=${response?.code})", t)
                }

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse error response", e)
                    if (exception == null) exception = e
                } finally {
                    close(exception ?: HttpException("Chat completion stream failed without an error detail"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "awaitClose: cancel event source")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)


    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val endpointVendor = resolveOpenAIEndpointVendor(host)
        val isOfficialOpenAI = endpointVendor == OpenAIEndpointVendor.OPENAI
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    messages = messages,
                    // OpenAI 官方 Chat Completions 不定义 reasoning_content，且 tool 消息只接受文本。
                    // 兼容网关可提供 reasoning_content 和多模态 tool result 扩展；
                    // 官方 OpenAI host 使用标准 Chat Completions 形状。
                    includeHistoryReasoning = providerSetting.includeHistoryReasoning && !isOfficialOpenAI,
                    supportToolResultModalities = if (isOfficialOpenAI) {
                        listOf(Modality.TEXT)
                    } else {
                        params.model.inputModalities
                    },
                    requiresToolReasoningReplay = requiresToolReasoningReplay(
                        host = host,
                        modelId = params.model.modelId,
                    ),
                    includeOpenRouterReasoningDetails = endpointVendor == OpenAIEndpointVendor.OPENROUTER,
                    useDeveloperRoleForSystemMessages = isOfficialOpenAI &&
                            (ModelRegistry.OPENAI_O_MODELS.match(params.model.modelId) ||
                                    ModelRegistry.OPENAI_GPT_5_SERIES.match(params.model.modelId)),
                )
            )

            if (isModelAllowTemperature(params.model, endpointVendor, params.reasoningLevel)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) {
                // max_tokens 已被 OpenAI 官方弃用，且不兼容 o-series；兼容服务继续使用旧字段，
                // 避免把官方协议升级扩散到尚未支持 max_completion_tokens 的第三方网关。
                // MiMo 支持 max_completion_tokens，与官方 OpenAI 一致。
                val isMaxCompletionTokensHost = isOfficialOpenAI ||
                        endpointVendor == OpenAIEndpointVendor.MIMO
                put(if (isMaxCompletionTokensHost) "max_completion_tokens" else "max_tokens", params.maxTokens)
            }

            put("stream", stream)
            if (stream) {
                if (endpointVendor != OpenAIEndpointVendor.MISTRAL) { // Mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if (endpointVendor == OpenAIEndpointVendor.OPENROUTER) {
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
                // OpenRouter sticky routing / caching session_id (max 256 chars per contract).
                params.providerSessionId
                    ?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?.let { put("session_id", it) }
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                when (endpointVendor) {
                    OpenAIEndpointVendor.OPENROUTER -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    OpenAIEndpointVendor.DASHSCOPE -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                    }

                    OpenAIEndpointVendor.VOLC_ARK -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    OpenAIEndpointVendor.MISTRAL -> {
                        // Mistral 不支持
                    }

                    OpenAIEndpointVendor.INTERN -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    OpenAIEndpointVendor.SILICONFLOW -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
                            "Pro/moonshotai/Kimi-K2.5",
                            "Pro/zai-org/GLM-5",
                            "Pro/zai-org/GLM-5.1",
                            "Pro/zai-org/GLM-4.7",
                            "deepseek-ai/DeepSeek-V3.2",
                            "Pro/deepseek-ai/DeepSeek-V3.2",
                            "Qwen/Qwen3.5-397B-A17B",
                            "Qwen/Qwen3.5-122B-A10B",
                            "Qwen/Qwen3.5-35B-A3B",
                            "Qwen/Qwen3.5-27B",
                            "Qwen/Qwen3.5-9B",
                            "Qwen/Qwen3.5-4B",
                            "zai-org/GLM-4.6",
                            "Qwen/Qwen3-8B",
                            "Qwen/Qwen3-14B",
                            "Qwen/Qwen3-32B",
                            "Qwen/Qwen3-30B-A3B",
                            "tencent/Hunyuan-A13B-Instruct",
                            "zai-org/GLM-4.5V",
                            "deepseek-ai/DeepSeek-V3.1-Terminus",
                            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                            "deepseek-ai/DeepSeek-V4-Flash",
                            "Pro/deepseek-ai/DeepSeek-V4-Flash",
                            "deepseek-ai/DeepSeek-V4-Pro",
                            "Pro/deepseek-ai/DeepSeek-V4-Pro",
                        )
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    OpenAIEndpointVendor.BIGMODEL -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    OpenAIEndpointVendor.MOONSHOT -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                            // K2.6 的 thinking.keep 默认为 null（忽略历史思考），思考开启时
                            // 需显式传 "all" 才是保留式思考；文档推荐与 enabled 搭配（#1586）
                            if (level.isEnabled && ModelRegistry.KIMI_K2_6.match(params.model.modelId)) {
                                put("keep", "all")
                            }
                        })
                    }

                    OpenAIEndpointVendor.DEEPSEEK -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        mapDeepSeekChatReasoningEffort(level)?.let { put("reasoning_effort", it) }
                    }

                    OpenAIEndpointVendor.MIMO -> {
                        // MiMo Chat Completions uses thinking.type=enabled|disabled.
                        // When thinking is enabled, temperature/top_p are ignored and must be omitted.
                        // Token limit uses max_completion_tokens (handled in isModelAllowTemperature
                        // and maxTokens below).
                        put("thinking", buildJsonObject {
                            put("type", mapMiMoChatThinkingType(level))
                        })
                    }

                    OpenAIEndpointVendor.NVIDIA -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = when (level) {
                                    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                            }
                        }
                    }

                    OpenAIEndpointVendor.OPENCODE -> {
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    OpenAIEndpointVendor.OPENAI,
                    OpenAIEndpointVendor.COMPATIBLE,
                        -> {
                        if (level != ReasoningLevel.AUTO) {
                            val effort = if (isOfficialOpenAI) {
                                mapOfficialOpenAIReasoningEffort(params.model.modelId, level)
                            } else {
                                // Unknown compatible gateways retain the existing conservative OFF fallback.
                                if (level.effort == "none") "low" else level.effort
                            }
                            effort?.let { put("reasoning_effort", it) }
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", normalizeToolParameters(tool.parameters()))
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun isModelAllowTemperature(
        model: Model,
        endpointVendor: OpenAIEndpointVendor = OpenAIEndpointVendor.COMPATIBLE,
        reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    ): Boolean {
        val isMoonshotRestricted = ModelRegistry.KIMI_K2_5.match(model.modelId) ||
                ModelRegistry.KIMI_K3.match(model.modelId) ||
                ModelRegistry.KIMI_K3_ALIAS.match(model.modelId)
        val isOpenAIReasoningModel = ModelRegistry.OPENAI_GPT_5_SERIES.match(model.modelId) &&
                model.abilities.contains(ModelAbility.REASONING)
        // MiMo ignores temperature/top_p when thinking is enabled.
        val mimoThinkingEnabled = endpointVendor == OpenAIEndpointVendor.MIMO &&
                isMiMoThinkingEnabled(model, reasoningLevel)
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) &&
               !isOpenAIReasoningModel &&
               !isMoonshotRestricted &&
               !mimoThinkingEnabled
    }

    internal fun buildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        supportToolResultModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
        requiresToolReasoningReplay: Boolean = false,
        includeOpenRouterReasoningDetails: Boolean = false,
        useDeveloperRoleForSystemMessages: Boolean = false,
    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeHistoryReasoning = includeHistoryReasoning,
                    supportToolResultModalities = supportToolResultModalities,
                    requiresToolReasoningReplay = requiresToolReasoningReplay,
                    includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
                )
            } else {
                addNonAssistantMessage(message, useDeveloperRoleForSystemMessages)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeHistoryReasoning: Boolean,
        supportToolResultModalities: List<Modality>,
        requiresToolReasoningReplay: Boolean,
        includeOpenRouterReasoningDetails: Boolean,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        val reasoningParts = mutableListOf<UIMessagePart.Reasoning>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().forEach { reasoningParts.add(it) }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // DeepSeek 将工具调用前的 reasoning_content 视为后续请求必须携带的协议状态。
                    // 因此这里只对“即将绑定 tool_calls 的 assistant 消息”强制回传；末尾普通回答仍由
                    // includeHistoryReasoning 控制，避免把协议要求误扩散到其他历史思考内容。
                    // OpenRouter 的 reasoning_details 同样是工具续轮必须回传的 host 限定信封。
                    val replayReasoning = includeHistoryReasoning || requiresToolReasoningReplay
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningContent = reasoningParts
                            .takeIf { replayReasoning }
                            ?.joinToString(separator = "") { it.reasoning },
                        reasoningDetails = collectOpenRouterReasoningDetails(
                            parts = reasoningParts,
                            include = includeOpenRouterReasoningDetails,
                        ),
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningParts.clear()

                    // 紧跟 tool 结果消息
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", tool.toolCallId)
                            put("content", tool.toToolResultContent(supportToolResultModalities))
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        val trailingDetails = collectOpenRouterReasoningDetails(
            parts = reasoningParts,
            include = includeOpenRouterReasoningDetails && includeHistoryReasoning,
        )
        if (contentBuffer.isNotEmpty() ||
            (includeHistoryReasoning && reasoningParts.any { it.reasoning.isNotBlank() }) ||
            trailingDetails != null
        ) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningContent = reasoningParts
                    .takeIf { includeHistoryReasoning }
                    ?.joinToString(separator = "") { it.reasoning },
                reasoningDetails = trailingDetails,
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun collectOpenRouterReasoningDetails(
        parts: List<UIMessagePart.Reasoning>,
        include: Boolean,
    ): JsonArray? {
        if (!include) return null
        val items = parts.flatMap { part ->
            part.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails.orEmpty()
        }
        return items.takeIf { it.isNotEmpty() }?.let(::JsonArray)
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningContent: String?,
        reasoningDetails: JsonArray? = null,
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val hasDetails = !reasoningDetails.isNullOrEmpty()
        val hasReasoning = !reasoningContent.isNullOrBlank() || hasDetails
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            if (hasDetails) {
                put("reasoning_details", reasoningDetails)
            } else if (!reasoningContent.isNullOrBlank()) {
                put("reasoning_content", reasoningContent)
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                                put("arguments", tool.inputAsJson().toString())
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(
        message: UIMessage,
        useDeveloperRoleForSystemMessages: Boolean,
    ) {
        add(buildJsonObject {
            val role = if (message.role == MessageRole.SYSTEM && useDeveloperRoleForSystemMessages) {
                // OpenAI 官方要求 o1 及更新推理模型用 developer 取代旧 system 角色。
                "developer"
            } else {
                message.role.name.lowercase()
            }
            put("role", JsonPrimitive(role))

            if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun UIMessagePart.Tool.toToolResultContent(supportInputModalities: List<Modality>): JsonElement {
        // 只考虑文字和图片;只有模型支持图片输入时,图片才作为多模态内容回传,否则以文本占位,避免发给不支持的模型报错
        val supportsImageInput = Modality.IMAGE in supportInputModalities
        val hasImageToSend = output.any { it is UIMessagePart.Image && supportsImageInput }
        return if (!hasImageToSend) {
            JsonPrimitive(output.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    is UIMessagePart.Image -> "[Image output omitted: current model does not support image input]"
                    else -> null
                }
            }.joinToString("\n"))
        } else {
            buildJsonArray {
                output.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                }.onFailure {
                                    Log.w(TAG, "encode tool result image failed: ${part.url}", it)
                                    put("type", "text")
                                    put("text", "Error: Failed to encode image to base64")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    /**
     * Compatible gateways may serialize `choices`, `delta`, `message` or `tool_calls` as JSON null.
     * Forced `jsonArray`/`jsonObject` access would abort the whole generation turn.
     */
    internal fun parseStreamPayload(
        payload: JsonObject,
        streamState: ChatCompletionsStreamState? = null,
    ): MessageChunk {
        val id = payload["id"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        val model = payload["model"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        val choices = payload["choices"]?.jsonArrayOrNull ?: JsonArray(emptyList())
        val choiceList = buildList {
            val choice = choices.firstOrNull()?.jsonObjectOrNull ?: return@buildList
            val message = choice["delta"]?.jsonObjectOrNull
                ?: choice["message"]?.jsonObjectOrNull
                ?: return@buildList
            val finishReason = choice["finish_reason"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: "unknown"
            add(
                UIMessageChoice(
                    index = 0,
                    delta = parseMessage(message, streamState),
                    message = null,
                    finishReason = finishReason,
                )
            )
        }
        return MessageChunk(
            id = id,
            model = model,
            choices = choiceList,
            usage = parseTokenUsage(payload["usage"] as? JsonObject),
        )
    }

    internal fun parseMessage(
        jsonObject: JsonObject,
        streamState: ChatCompletionsStreamState? = null,
    ): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content?
        val content = when (val contentElement = jsonObject["content"]) {
            is JsonPrimitive -> contentElement.contentOrNull.orEmpty()
            is JsonArray -> contentElement.mapNotNull { element ->
                val part = element.jsonObjectOrNull ?: return@mapNotNull null
                when (part["type"]?.jsonPrimitiveOrNull?.contentOrNull) {
                    "text" -> part["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    "refusal" -> part["refusal"]?.jsonPrimitiveOrNull?.contentOrNull
                    else -> null
                }
            }.joinToString(separator = "")
            else -> ""
        }
        val refusal = jsonObject["refusal"]?.jsonPrimitiveOrNull?.contentOrNull
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["content"]?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
        val toolCalls = jsonObject["tool_calls"]?.jsonArrayOrNull ?: JsonArray(emptyList())
        val images = jsonObject["images"]?.jsonArrayOrNull ?: JsonArray(emptyList())
        val reasoningDetails = jsonObject["reasoning_details"]?.jsonArrayOrNull
        val reasoningMetadata = reasoningDetails?.let {
            OpenRouterReasoningMetadata(reasoningDetails = it).toMetadata()
        }

        return UIMessage(
            role = role,
            parts = buildList {
                // Chat Completions 的 reasoning_content、content、tool_calls 属于同一个 assistant envelope。
                // UIMessage 使用 Tool 作为历史重建边界，因此必须先保存 Reasoning/Content，再保存 Tool；
                // 否则工具执行后 content 会被错误地移动到 tool result 之后，DeepSeek 无法原样校验该步骤。
                if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning.orEmpty(),
                            createdAt = Clock.System.now(),
                            finishedAt = null,
                        ).also { part ->
                            part.metadata = reasoningMetadata
                        }
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                // 官方 Chat Completions 的拒答可能位于顶层 refusal，也可能位于 content part。
                // UI 暂无独立拒答 part，按可见文本保存，确保非流式和流式响应都不会静默丢失。
                if (!refusal.isNullOrEmpty() && refusal != content) add(UIMessagePart.Text(refusal))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url = url))
                }
                toolCalls.forEach { toolCall ->
                    val toolCallObject = toolCall.jsonObjectOrNull ?: return@forEach
                    val type = toolCallObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallIndex = toolCallObject["index"]?.jsonPrimitive?.intOrNull
                    val announcedToolCallId = toolCallObject["id"]?.jsonPrimitive?.contentOrNull
                    // Official Chat chunks may omit id after the first delta. Resolve it through index so
                    // interleaved parallel calls never append their argument fragments to another call.
                    val toolCallId = streamState?.resolveToolCallId(toolCallIndex, announcedToolCallId)
                        ?: announcedToolCallId
                    val function = toolCallObject["function"]?.jsonObjectOrNull
                    val toolName = function?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments = function?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList()
                        )
                    )
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            // 各 provider 汇报缓存命中的字段形状不统一，按方言兜底解析（#1576）：
            // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}

/**
 * 判断 Chat Completions 工具步骤是否必须回传 reasoning_content。
 *
 * DeepSeek 和 MiMo 都将工具调用前的 reasoning_content 视为后续请求必须携带的协议状态。
 * 直连 DeepSeek/MiMo 时 host 可以覆盖自定义模型别名；经过代理时只能依赖可识别的 modelId。
 * 未知代理上的其他模型保持原行为，不会因为使用 OpenAI 兼容接口而被误判。
 */
internal fun requiresToolReasoningReplay(host: String, modelId: String): Boolean {
    val endpointVendor = resolveOpenAIEndpointVendor(host)
    if (endpointVendor == OpenAIEndpointVendor.OPENAI) return false
    return endpointVendor == OpenAIEndpointVendor.DEEPSEEK ||
            endpointVendor == OpenAIEndpointVendor.MIMO ||
            ModelRegistry.DEEPSEEK_V4.match(modelId)
}
