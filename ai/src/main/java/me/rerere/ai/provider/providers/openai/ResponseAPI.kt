@file:Suppress("UNNECESSARY_SAFE_CALL")
package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
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
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.OpenAIResponseMetadata
import me.rerere.ai.ui.OpenAIResponseWireFormat
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

private const val TAG = "ResponseAPI"

internal class ResponseStreamState {
    val toolCallIdsByItemId = mutableMapOf<String, String>()
    val toolArgumentDeltasSeenByItemId = mutableSetOf<String>()
    val reasoningTextEmittedByItemId = mutableSetOf<String>()
    private val outputItemsById = linkedMapOf<String, JsonObject>()
    private val terminalSeen = AtomicBoolean(false)

    fun recordOutputItem(item: JsonObject) {
        val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return
        // Replacing an existing key in LinkedHashMap preserves the original output order.
        outputItemsById[id] = item
    }

    fun outputItems(): List<JsonObject> = outputItemsById.values.toList()

    fun markTerminal() {
        terminalSeen.set(true)
    }

    fun prematureCloseError(): HttpException? = if (terminalSeen.get()) {
        null
    } else {
        HttpException(
            message = "Response stream closed before a terminal event",
            terminalStatus = ProviderTerminalStatus.INCOMPLETE,
        )
    }
}

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.d(TAG, "generateText: model=${params.model.modelId}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        parseResponseObjectError(bodyJson)?.let { throw it }
        val endpointProfile = resolveResponseEndpointProfile(providerSetting.baseUrl.toHttpUrl().host)
        val output = parseResponseOutput(bodyJson, endpointProfile)

        return output
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        // 每个 SSE 请求独立维护 item_id -> call_id 映射。OpenAI 的 fc_* 输出项 ID
        // 与下一轮 function_call_output 必须使用的 call_* ID 是两个不同字段，不能混用。
        val streamState = ResponseStreamState()
        val endpointProfile = resolveResponseEndpointProfile(providerSetting.baseUrl.toHttpUrl().host)
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.d(TAG, "streamText: model=${params.model.modelId}")

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    // Legacy-compatible endpoints may still emit [DONE]. Treat it as an explicit terminal marker,
                    // while official Responses endpoints terminate with a typed response.completed event.
                    buildResponseOutputStateChunk(
                        outputItems = streamState.outputItems(),
                        endpointProfile = endpointProfile,
                    )?.let { chunk ->
                        trySend(chunk).onFailure { e ->
                            Log.w(TAG, "onEvent: terminal protocol state dropped (${e?.message})")
                        }
                    }
                    streamState.markTerminal()
                    close()
                    return
                }
                try {
                    val eventJson = json.parseToJsonElement(data).jsonObject
                    val eventType = eventJson["type"]?.jsonPrimitive?.contentOrNull
                        ?: error("response event type not found")
                    val chunk = parseResponseDelta(eventJson, streamState, endpointProfile)
                    if (chunk != null) {
                        trySend(chunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                    }

                    // Responses 的真实终态由 JSON type 定义。部分兼容服务不会设置 SSE event 名称，
                    // 因此不能只依赖 EventSourceListener 的 type 参数判断是否结束。
                    val terminalError = parseResponseStreamError(eventJson)
                    when {
                        terminalError != null -> {
                            streamState.markTerminal()
                            close(terminalError)
                        }

                        eventType == "response.completed" -> {
                            streamState.markTerminal()
                            close()
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onEvent: failed to process response event (sseType=$type)", e)
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
                    close(exception ?: HttpException("Response stream failed without an error detail"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                val error = streamState.prematureCloseError()
                if (error == null) close() else close(error)
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "awaitClose: cancel event source")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val endpointProfile = resolveResponseEndpointProfile(host)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            if (endpointProfile.supportsStore) {
                put("store", false)
            }

            // OpenRouter sticky routing / caching session_id (max 256 chars per contract).
            if (resolveOpenAIEndpointVendor(host) == OpenAIEndpointVendor.OPENROUTER) {
                params.providerSessionId
                    ?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?.let { put("session_id", it) }
            }

            if (isModelAllowTemperature(params.model, endpointProfile, params.reasoningLevel)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            val instructions = messages
                .filter { it.role == MessageRole.SYSTEM }
                .flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            if (instructions.isNotEmpty()) {
                put(
                    "instructions",
                    instructions
                )
            }

            // messages
            put("input", buildMessages(messages, endpointProfile))

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                val reasoning = buildJsonObject {
                    if (endpointProfile.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (endpointProfile == ResponseEndpointProfile.DEEPSEEK) {
                        mapDeepSeekResponsesReasoningEffort(level)?.let { put("effort", it) }
                    } else if (endpointProfile == ResponseEndpointProfile.MIMO) {
                        mapMiMoResponsesReasoningEffort(level)?.let { put("effort", it) }
                    } else if (level != ReasoningLevel.AUTO) {
                        val effort = if (isOfficialOpenAIHost(host)) {
                            mapOfficialOpenAIReasoningEffort(params.model.modelId, level)
                        } else {
                            level.effort
                        }
                        effort?.let { put("effort", it) }
                    }
                }
                if (reasoning.isNotEmpty()) {
                    put("reasoning", reasoning)
                }
                if (endpointProfile.supportsEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            if (useFunctionTools || params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                // Responses 省略 strict 会尝试严格模式；显式 false 保持
                                // Chat Completions 的非严格工具语义，避免已配置 JSON Schema 被隐式收紧。
                                put("strict", false)
                                put("parameters", normalizeToolParameters(tool.parameters()))
                            })
                        }
                    }
                    // built-in tools
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    internal fun buildMessages(
        messages: List<UIMessage>,
        endpointProfile: ResponseEndpointProfile = ResponseEndpointProfile.OPENAI,
    ) = buildJsonArray {
        messages
            .filter { message ->
                message.role != MessageRole.SYSTEM &&
                        (message.isValidToUpload() || message.hasReplayableResponseState(endpointProfile))
            }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message, endpointProfile)
                } else {
                    addUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(
        message: UIMessage,
        endpointProfile: ResponseEndpointProfile,
    ) {
        val responseMetadata = message.metadataAs<OpenAIResponseMetadata>()
        if (responseMetadata != null &&
            responseMetadata.wireFormat == endpointProfile.wireFormat &&
            (responseMetadata.sourceProfile == null ||
                    responseMetadata.sourceProfile == endpointProfile.sourceProfile) &&
            responseMetadata.outputItemGroups.any { it.isNotEmpty() }
        ) {
            addPreservedResponseItems(message, responseMetadata.outputItemGroups, endpointProfile)
            return
        }

        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                        ?.takeIf { metadata ->
                                            metadata.sourceProfile == null ||
                                                    metadata.sourceProfile == endpointProfile.sourceProfile
                                        }
                                    // id 是 Responses output item 的公共信封字段，保留它是通用协议保真；
                                    // DeepSeek 工具续轮明确强制要求的内容是下方 plaintext reasoning_text。
                                    reasoningMetadata?.reasoningId?.let { put("id", it) }
                                    if (endpointProfile.usesReasoningTextContent) {
                                        // DeepSeek Responses 把可回传的明文思考放在 content/reasoning_text；
                                        // 它不接受 OpenAI reasoning item 的 summary/encrypted_content 作为历史状态。
                                        putJsonArray("content") {
                                            add(buildJsonObject {
                                                put("type", "reasoning_text")
                                                put("text", part.reasoning)
                                            })
                                        }
                                    } else {
                                        val encryptedContent = reasoningMetadata?.encryptedContent
                                        if (encryptedContent == null) {
                                            put("summary", buildJsonArray {
                                                if (part.reasoning.isNotEmpty()) {
                                                    add(buildJsonObject {
                                                        put("type", "summary_text")
                                                        put("text", part.reasoning)
                                                    })
                                                }
                                            })
                                        }
                                        encryptedContent?.let {
                                            put("encrypted_content", it)
                                        }
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part))
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // Responses 官方顺序是先追加该 response 的全部 function_call，再追加执行结果。
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                    }
                    group.tools.forEach { tool ->
                        if (tool.isExecuted) addFunctionCallOutput(tool, endpointProfile)
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    /**
     * `store=false` 的 Responses 历史必须回放完整、有序的 `response.output`，而不是从 UI 文本反推。
     * 每批原始 output 完整回放后，再按调用顺序追加本地 function_call_output；其余内置工具、
     * phase/status 和未知 item 均保持服务端原始 JSON，从而不会因 UI 当前不认识它们而丢失协议状态。
     */
    private fun JsonArrayBuilder.addPreservedResponseItems(
        message: UIMessage,
        outputItemGroups: List<List<JsonObject>>,
        endpointProfile: ResponseEndpointProfile,
    ) {
        val toolsByCallId = message.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .groupBy { it.toolCallId }
        val consumedToolsByCallId = mutableMapOf<String, Int>()
        outputItemGroups.forEach { outputItems ->
            outputItems.forEach { item -> add(item) }
            outputItems.forEach { item ->
                if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                    val tool = callId?.let { id ->
                        val index = consumedToolsByCallId.getOrDefault(id, 0)
                        consumedToolsByCallId[id] = index + 1
                        toolsByCallId[id]?.getOrNull(index)
                    }
                    if (tool?.isExecuted == true) addFunctionCallOutput(tool, endpointProfile)
                }
            }
        }
    }

    private fun JsonArrayBuilder.addFunctionCallOutput(
        tool: UIMessagePart.Tool,
        endpointProfile: ResponseEndpointProfile,
    ) {
        add(buildJsonObject {
            put("type", "function_call_output")
            put("call_id", tool.toolCallId)
            val hasImage = tool.output.any { it is UIMessagePart.Image }
            if (hasImage && endpointProfile.supportsMultimodalFunctionOutput) {
                putJsonArray("output") {
                    tool.output.forEach { part ->
                        when (part) {
                            is UIMessagePart.Image -> add(buildJsonObject {
                                part.encodeBase64().onSuccess { encoded ->
                                    put("type", "input_image")
                                    put("image_url", encoded.base64)
                                }.onFailure {
                                    it.printStackTrace()
                                    put("type", "input_text")
                                    put("text", "Error: Failed to encode image to base64")
                                }
                            })

                            is UIMessagePart.Text -> add(buildJsonObject {
                                put("type", "input_text")
                                put("text", part.text)
                            })

                            else -> Unit
                        }
                    }
                }
            } else {
                val textOutput = tool.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                put(
                    "output",
                    textOutput.ifEmpty {
                        if (tool.output.isEmpty()) "" else "Tool returned non-text output"
                    }
                )
            }
        })
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
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

    internal fun parseResponseDelta(
        jsonObject: JsonObject,
        streamState: ResponseStreamState = ResponseStreamState(),
        endpointProfile: ResponseEndpointProfile = ResponseEndpointProfile.OPENAI,
    ): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta", "response.refusal.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.reasoning_summary_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                                            ?: "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                streamState.recordOutputItem(item)
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "function_call") {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: id
                    streamState.toolCallIdsByItemId[id] = callId
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Tool(
                                            // UIMessagePart.Tool 持久化的是协议关联 ID；item_id 只用于定位 SSE 输出项。
                                            toolCallId = callId,
                                            toolName = item["name"]?.jsonPrimitive?.content ?: "",
                                            input = item["arguments"]?.jsonPrimitive?.content
                                                ?: "",
                                            output = emptyList()
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(UIMessagePart.Image(url = ""))
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = OpenAIReasoningMetadata(
                                                reasoningId = id,
                                                encryptedContent = encryptedContent,
                                                sourceProfile = endpointProfile.sourceProfile,
                                            ).toMetadata()
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                streamState.recordOutputItem(item)
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    val reasoningText = if (streamState.reasoningTextEmittedByItemId.remove(id)) {
                        ""
                    } else {
                        // DeepSeek 的标准流会发送 reasoning_text.delta/done；部分兼容实现只在
                        // output_item.done 的完整 item 中提供正文，不能因此把必需的回传状态丢掉。
                        item.reasoningTextContent()
                    }
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = reasoningText,
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = OpenAIReasoningMetadata(
                                                reasoningId = id,
                                                encryptedContent = encryptedContent,
                                                sourceProfile = endpointProfile.sourceProfile,
                                            ).toMetadata()
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitive?.content ?: error("result not found")
                    return MessageChunk(
                        id = item["id"]?.jsonPrimitive?.content ?: error("item_id not found"),
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(url = result)
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                val itemId = jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val toolCallId = streamState.toolCallIdsByItemId.remove(itemId) ?: itemId
                val receivedDeltas = streamState.toolArgumentDeltasSeenByItemId.remove(itemId)
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                // 官方流会先发送参数 delta，再在 done 中给出完整参数。Tool.merge() 采用字符串追加，
                // 所以已消费 delta 时不能再次追加完整值；没有 delta 的兼容服务仍用 done 兜底。
                if (receivedDeltas) return null
                return MessageChunk(
                    id = itemId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = arguments,
                                        output = emptyList()
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.reasoning_text.delta" -> {
                val itemId = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val delta = jsonObject["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    streamState.reasoningTextEmittedByItemId += itemId
                }
                return reasoningTextChunk(itemId, delta)
            }

            "response.reasoning_text.done" -> {
                val itemId = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val text = jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // DeepSeek 会在 done 中再次给出完整 reasoning_text。UIMessage 的流式合并采用
                // 字符串追加，因此已有 delta 时不能重复追加；兼容只发送 done 的实现则用它兜底。
                if (text.isEmpty()) return null
                if (!streamState.reasoningTextEmittedByItemId.add(itemId)) return null
                return reasoningTextChunk(itemId, text)
            }

            "response.function_call_arguments.delta" -> {
                val itemId = jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val toolCallId = streamState.toolCallIdsByItemId[itemId] ?: itemId
                val delta = jsonObject["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (delta.isNotEmpty()) {
                    streamState.toolArgumentDeltasSeenByItemId += itemId
                }
                return MessageChunk(
                    id = itemId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = delta,
                                        output = emptyList(),
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null,
                        )
                    )
                )
            }

            "response.completed", "response.incomplete", "response.failed" -> {
                val response = jsonObject["response"]?.jsonObjectOrNull
                val outputItems = response?.get("output")?.jsonArray
                    ?.map { it.jsonObject }
                    ?.ifEmpty { null }
                    ?: streamState.outputItems()
                val usage = parseTokenUsage(response?.get("usage")?.jsonObjectOrNull)
                return if (chunkType == "response.completed") {
                    buildResponseOutputStateChunk(
                        responseId = response?.get("id")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
                        outputItems = outputItems,
                        endpointProfile = endpointProfile,
                        usage = usage,
                    ) ?: MessageChunk(id = "", model = "", choices = emptyList(), usage = usage)
                } else {
                    MessageChunk(id = "", model = "", choices = emptyList(), usage = usage)
                }
            }
        }

        return null
    }

    private fun buildResponseOutputStateChunk(
        responseId: String = "",
        outputItems: List<JsonObject>,
        endpointProfile: ResponseEndpointProfile,
        usage: TokenUsage? = null,
    ): MessageChunk? {
        if (outputItems.isEmpty()) return null
        return MessageChunk(
            id = responseId,
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        providerMetadata = OpenAIResponseMetadata(
                            wireFormat = endpointProfile.wireFormat,
                            outputItemGroups = listOf(outputItems),
                            sourceProfile = endpointProfile.sourceProfile,
                        ).toMetadata(),
                    ),
                    message = null,
                    finishReason = null,
                )
            ),
            usage = usage,
        )
    }

    private fun reasoningTextChunk(itemId: String, text: String): MessageChunk {
        return MessageChunk(
            id = itemId,
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Reasoning(
                                reasoning = text,
                                createdAt = Clock.System.now(),
                                finishedAt = null,
                            )
                        )
                    ),
                    message = null,
                    finishReason = null,
                )
            )
        )
    }

    private fun JsonObject.reasoningTextContent(): String {
        return this["content"]?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text" }
            ?.joinToString(separator = "") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .orEmpty()
    }

    internal fun parseResponseOutput(
        jsonObject: JsonObject,
        endpointProfile: ResponseEndpointProfile = ResponseEndpointProfile.OPENAI,
    ): MessageChunk {
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    // DeepSeek 返回完整 reasoning_text，OpenAI 通常返回 summary_text。
                    // content 优先可避免 DeepSeek 同时携带空 summary 时丢失正文；最终合并成一个 part，
                    // 使同一 reasoning item 的 id/encrypted_content 只保存和回传一次。
                    val reasoningTextParts = output["content"]?.jsonArray
                        ?.map { it.jsonObject }
                        ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text" }
                        .orEmpty()
                    val summaryTextParts = output["summary"]?.jsonArray
                        ?.map { it.jsonObject }
                        ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "summary_text" }
                        .orEmpty()
                    val selectedParts = reasoningTextParts.ifEmpty { summaryTextParts }
                    val reasoningText = selectedParts.joinToString(separator = "") {
                        it["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    val reasoningId = output["id"]?.jsonPrimitive?.contentOrNull
                    val encryptedContent = output["encrypted_content"]?.jsonPrimitive?.contentOrNull
                    val metadata = if (reasoningId != null || encryptedContent != null) {
                        OpenAIReasoningMetadata(
                            reasoningId = reasoningId,
                            encryptedContent = encryptedContent,
                            sourceProfile = endpointProfile.sourceProfile,
                        ).toMetadata()
                    } else {
                        null
                    }

                    // summary 可以为空，但 encrypted_content 仍是无状态续轮必须回传的协议状态。
                    // 因此不能以“是否有可见摘要”决定是否保留 reasoning item。
                    if (reasoningText.isNotEmpty() || metadata != null) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = reasoningText,
                                createdAt = Clock.System.now(),
                                finishedAt = Clock.System.now(),
                                metadata = metadata,
                            )
                        )
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            "refusal" -> {
                                val refusal = part["refusal"]?.jsonPrimitive?.content
                                    ?: error("refusal not found")
                                parts.add(UIMessagePart.Text(refusal))
                            }

                            // Responses 会持续增加新的输出 part；未知 part 不应让已有文本/工具结果整体失败。
                            else -> Unit
                        }
                    }
                }
            }
        }

        return MessageChunk(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts,
                        providerMetadata = OpenAIResponseMetadata(
                            wireFormat = endpointProfile.wireFormat,
                            outputItemGroups = listOf(outputs.map { it.jsonObject }),
                            sourceProfile = endpointProfile.sourceProfile,
                        ).toMetadata(),
                    ),
                    finishReason = null,
                    delta = null
                )
            ),
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObjectOrNull)
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    /**
     * A Responses request can be HTTP-successful while the response object itself is failed or incomplete.
     * Surface that protocol status instead of falling through to a misleading "output not found" parse error.
     */
    internal fun parseResponseObjectError(response: JsonObject): HttpException? {
        return when (response["status"]?.jsonPrimitive?.contentOrNull) {
            "failed" -> response["error"]?.parseErrorDetail()
                ?: HttpException("Response failed without an error detail")

            "incomplete" -> {
                val reason = response["incomplete_details"]
                    ?.jsonObjectOrNull
                    ?.get("reason")
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                    ?: "unknown reason"
                HttpException(
                    message = "Response incomplete: $reason",
                    terminalStatus = ProviderTerminalStatus.INCOMPLETE,
                )
            }

            else -> null
        }
    }

    /** Parse terminal error events carried inside an otherwise successful SSE connection. */
    internal fun parseResponseStreamError(event: JsonObject): HttpException? {
        return when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "response.failed", "response.incomplete" -> event["response"]
                ?.jsonObjectOrNull
                ?.let(::parseResponseObjectError)
                ?: HttpException("Response stream ended without a terminal status detail")

            "error" -> event["error"]?.parseErrorDetail()
                ?: event.parseErrorDetail()

            else -> null
        }
    }

    private fun UIMessage.hasReplayableResponseState(
        endpointProfile: ResponseEndpointProfile,
    ): Boolean {
        val responseMetadata = metadataAs<OpenAIResponseMetadata>()
        if (responseMetadata != null &&
            responseMetadata.wireFormat == endpointProfile.wireFormat &&
            (responseMetadata.sourceProfile == null ||
                    responseMetadata.sourceProfile == endpointProfile.sourceProfile) &&
            responseMetadata.outputItemGroups.any { it.isNotEmpty() }
        ) {
            return true
        }
        if (endpointProfile.usesReasoningTextContent) return false
        return parts.filterIsInstance<UIMessagePart.Reasoning>().any { part ->
            val metadata = part.metadataAs<OpenAIReasoningMetadata>()
            val sourceCompatible = metadata?.sourceProfile == null ||
                    metadata.sourceProfile == endpointProfile.sourceProfile
            sourceCompatible && (metadata?.reasoningId != null || metadata?.encryptedContent != null)
        }
    }
}

private fun isModelAllowTemperature(
    model: Model,
    endpointProfile: ResponseEndpointProfile = ResponseEndpointProfile.OPENAI_COMPATIBLE,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): Boolean {
    val isOpenAIReasoningModel = ModelRegistry.OPENAI_GPT_5_SERIES.match(model.modelId) &&
            model.abilities.contains(ModelAbility.REASONING)
    // MiMo ignores temperature/top_p when thinking is enabled.
    val mimoThinkingEnabled = endpointProfile == ResponseEndpointProfile.MIMO &&
            isMiMoThinkingEnabled(model, reasoningLevel)
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) &&
            !isOpenAIReasoningModel &&
            !mimoThinkingEnabled
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

