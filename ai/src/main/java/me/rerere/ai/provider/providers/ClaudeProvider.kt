@file:Suppress("UNNECESSARY_SAFE_CALL")
package me.rerere.ai.provider.providers

import android.content.Context
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.sumTokenCountsOrNull
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.RequestBodyOwnership
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeNativeImage
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_MIN_MANUAL_THINKING_BUDGET = 1_024

/**
 * Builds the model-compatible Anthropic thinking fields.
 *
 * Claude 4.6+ uses adaptive thinking and `output_config.effort`. Older reasoning models use the
 * manual `enabled + budget_tokens` form. AUTO omits thinking for those older models because they
 * have no adaptive mode; inventing a fixed budget would not preserve AUTO semantics.
 */
internal fun buildClaudeThinkingFields(
    modelId: String,
    level: ReasoningLevel,
    maxTokens: Int,
): JsonObject = buildJsonObject {
    if (level == ReasoningLevel.OFF) {
        put("thinking", buildJsonObject { put("type", "disabled") })
        return@buildJsonObject
    }

    if (ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match(modelId)) {
        put("thinking", buildJsonObject {
            put("type", "adaptive")
            put("display", "summarized")
        })
        if (level != ReasoningLevel.AUTO) {
            val effort = when {
                level == ReasoningLevel.MAX -> "max"
                level == ReasoningLevel.XHIGH && !ModelRegistry.CLAUDE_XHIGH_EFFORT.match(modelId) -> "max"
                else -> level.effort
            }
            put("output_config", buildJsonObject { put("effort", effort) })
        }
        return@buildJsonObject
    }

    if (level == ReasoningLevel.AUTO) return@buildJsonObject

    require(maxTokens > CLAUDE_MIN_MANUAL_THINKING_BUDGET) {
        "Claude manual thinking requires maxTokens greater than $CLAUDE_MIN_MANUAL_THINKING_BUDGET"
    }
    put("thinking", buildJsonObject {
        put("type", "enabled")
        put(
            "budget_tokens",
            level.budgetTokens
                .coerceAtLeast(CLAUDE_MIN_MANUAL_THINKING_BUDGET)
                .coerceAtMost(maxTokens - 1)
        )
        put("display", "summarized")
    })
    if (ModelRegistry.CLAUDE_MANUAL_THINKING_WITH_EFFORT.match(modelId)) {
        val effort = if (level == ReasoningLevel.XHIGH || level == ReasoningLevel.MAX) {
            ReasoningLevel.HIGH.effort
        } else {
            level.effort
        }
        put("output_config", buildJsonObject { put("effort", effort) })
    }
}

class ClaudeProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override fun requestMediaCapabilities(
        providerSetting: ProviderSetting.Claude,
        model: Model,
    ): RequestMediaCapabilities {
        val structured = if (Modality.IMAGE in model.inputModalities) {
            RequestImageSupport.STRUCTURED
        } else {
            RequestImageSupport.NONE
        }
        return RequestMediaCapabilities(
            userImages = structured,
            assistantImages = RequestImageSupport.NONE,
            toolOutputImages = structured,
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildMessageRequest(providerSetting, messages, params)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = parseTokenUsage(bodyJson)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(content),
                    finishReason = stopReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildMessageRequest(providerSetting, messages, params, stream = true)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        requestBody["messages"]!!.jsonArray.forEach {
            Log.i(TAG, "streamText: $it")
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: type=$type, data=$data")
                if (data == "[DONE]") {
                    return
                }

                val dataJson = json.parseToJsonElement(data).jsonObject
                val deltaMessage = parseMessage(buildJsonArray {
                    val contentBlockObj = dataJson["content_block"]?.jsonObject
                    val deltaObj = dataJson["delta"]?.jsonObject
                    if (contentBlockObj != null) {
                        add(contentBlockObj)
                    }
                    if (deltaObj != null) {
                        add(deltaObj)
                    }
                })
                val tokenUsage = parseTokenUsage(dataJson)
                val messageChunk = MessageChunk(
                    id = id ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = deltaMessage,
                            message = null,
                            finishReason = null
                        )
                    ),
                    usage = tokenUsage
                )

                trySend(messageChunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }

                when (type) {
                    "message_stop" -> {
                        Log.d(TAG, "Stream ended")
                        close()
                    }

                    "error" -> {
                        val eventData = json.parseToJsonElement(data).jsonObject
                        val error = eventData["error"]?.parseErrorDetail()
                        close(error)
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.i(TAG, "Error response: $bodyElement")
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    private fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        val maxTokens = params.maxTokens ?: 64_000
        val thinkingFields = if (params.model.abilities.contains(ModelAbility.REASONING)) {
            buildClaudeThinkingFields(params.model.modelId, params.reasoningLevel, maxTokens)
        } else {
            JsonObject(emptyMap())
        }
        val thinkingType = thinkingFields["thinking"]
            ?.jsonObject
            ?.get("type")
            ?.jsonPrimitive
            ?.content
        val thinkingEnabled = thinkingType == "adaptive" || thinkingType == "enabled"
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    stripClaudeThinkingFromOtherModels(messages, params.model.id),
                    providerSetting.promptCaching,
                    providerSetting.promptCacheTtl,
                    params.mediaCapabilities,
                )
            )
            put("max_tokens", maxTokens)

            // 顶层 cache_control: 让 Anthropic 自动管理缓存断点
            if (providerSetting.promptCaching) {
                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
            }

            if (params.temperature != null && !thinkingEnabled) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val systemTextParts = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
            if (systemTextParts.isNotEmpty()) {
                put("system", buildJsonArray {
                    systemTextParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                            if (providerSetting.promptCaching && index == systemTextParts.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                })
            }

            thinkingFields.forEach { (key, value) -> put(key, value) }

            // 处理工具
            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEachIndexed { index, tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters?.let { put("input_schema", it) }
                            if (providerSetting.promptCaching && index == params.tools.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                }
            }
        }.mergeCustomBody(
            params.customBody,
            CLAUDE_MESSAGES_OWNERSHIP,
        )
    }

    private fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl,
        mediaCapabilities: RequestMediaCapabilities,
    ) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantMessage(message, mediaCapabilities)
                } else {
                    addUserMessage(message, mediaCapabilities)
                }
            }
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray, promptCacheTtl)
    }

    /**
     * 在倒数第二条非 tool_result 的 user message 的最后一个 content block 上插入 cache_control
     */
    private fun insertMessagesCacheControl(
        messages: JsonArray,
        promptCacheTtl: ClaudePromptCacheTtl
    ): JsonArray {
        // 找出所有非 tool_result 的 user message 的索引
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // 取倒数第二条
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else return messages

        // 在目标 message 的最后一个 content block 上添加 cache_control
        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(
                            block.jsonObject + mapOf("cache_control" to cacheControlEphemeral(promptCacheTtl))
                        )
                    } else block
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else msg
        })
    }

    private fun JsonArrayBuilder.addAssistantMessage(
        message: UIMessage,
        mediaCapabilities: RequestMediaCapabilities,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull {
                        it.toContentBlock(mediaCapabilities.assistantImages)
                    }.forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock(mediaCapabilities)) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(
        message: UIMessage,
        mediaCapabilities: RequestMediaCapabilities,
    ) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.mapNotNull {
                    it.toContentBlock(mediaCapabilities.userImages)
                }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toContentBlock(imageSupport: RequestImageSupport): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Image -> {
            check(imageSupport == RequestImageSupport.STRUCTURED) {
                "Claude received a native image after request projection"
            }
            val encoded = encodeNativeImage(withPrefix = false)
            buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }
        }

        is UIMessagePart.Reasoning -> {
            val reasoningMetadata = metadataAs<ClaudeReasoningMetadata>()
            reasoningMetadata?.redactedData?.let { redactedData ->
                buildJsonObject {
                    put("type", "redacted_thinking")
                    put("data", redactedData)
                }
            } ?: buildJsonObject {
                put("type", "thinking")
                put("thinking", reasoning)
                reasoningMetadata?.signature?.let { put("signature", it) }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

    private fun UIMessagePart.Tool.toToolResultBlock(
        mediaCapabilities: RequestMediaCapabilities,
    ) = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        putJsonArray("content") {
            output.mapNotNull { it.toContentBlock(mediaCapabilities.toolOutputImages) }.forEach { add(it) }
        }
    }

    private fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()

        content.forEach { contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty()) {
                        parts.add(UIMessagePart.Text(text))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null) {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = thinking,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                        if (signature != null) {
                            reasoning.metadata = ClaudeReasoningMetadata(signature = signature).toMetadata()
                        }
                        parts.add(reasoning)
                    }
                }

                "redacted_thinking" -> {
                    val data = block["data"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (data != null) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = null,
                                metadata = ClaudeReasoningMetadata(redactedData = data).toMetadata(),
                            )
                        )
                    }
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList()
                        )
                    )
                }

                "input_json_delta" -> {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = "",
                            toolName = "",
                            input = input ?: "",
                            output = emptyList()
                        )
                    )
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    internal fun parseTokenUsage(bodyJson: JsonObject?): ProviderUsageSnapshot? {
        if (bodyJson == null) return null

        // 回退到标准 usage 字段
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val uncachedInputTokens = usageJson["input_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val cacheReadInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val cacheWriteInputTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val outputTokens = usageJson["output_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val inputTokens = uncachedInputTokens?.let {
            sumTokenCountsOrNull(it, cacheReadInputTokens ?: 0L, cacheWriteInputTokens ?: 0L)
        }
        val isStreamingUsageEvent = bodyJson["type"]?.jsonPrimitiveOrNull?.contentOrNull in
            setOf("message_start", "message_delta")
        return ProviderUsageSnapshot(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadInputTokens = cacheReadInputTokens,
            cacheWriteInputTokens = cacheWriteInputTokens,
            totalTokens = if (isStreamingUsageEvent) {
                null
            } else {
                sumTokenCountsOrNull(inputTokens, outputTokens)
            },
            canDeriveTotalFromInputAndOutput = true,
        )
    }
}

/**
 * Anthropic thinking signatures are bound to the model that produced them. When the selected model
 * changes, keep the visible answer and tool history but strip thinking/redacted blocks from messages
 * that are known to have been produced by another configured model. Legacy messages without modelId
 * remain untouched for persistence compatibility.
 */
internal fun stripClaudeThinkingFromOtherModels(
    messages: List<UIMessage>,
    activeModelId: Uuid,
): List<UIMessage> = messages.map { message ->
    if (message.role == MessageRole.ASSISTANT &&
        message.modelId != null &&
        message.modelId != activeModelId
    ) {
        message.copy(parts = message.parts.filterNot { it is UIMessagePart.Reasoning })
    } else {
        message
    }
}

/**
 * Builder-owned structural keys for Anthropic Messages requests.
 *
 * These fields are exclusively owned by [ClaudeProvider.buildMessageRequest]; a [CustomBody]
 * entry attempting to override any of them is rejected before HTTP with a typed
 * [me.rerere.ai.util.CustomBodyReservedKeyException].
 */
internal val CLAUDE_MESSAGES_OWNERSHIP = RequestBodyOwnership(
    protocol = "anthropic-messages",
    reservedKeys = setOf(
        "model",
        "messages",
        "system",
        "tools",
        "stream",
    ),
)
