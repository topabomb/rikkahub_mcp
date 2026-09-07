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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderResponseException
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.vertex.ServiceAccountTokenProvider
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.renderableImageUrl
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.formatProviderHttpError
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.RequestBodyOwnership
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeNativeImage
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override fun requestMediaCapabilities(
        providerSetting: ProviderSetting.Google,
        model: Model,
    ): RequestMediaCapabilities {
        val structured = if (Modality.IMAGE in model.inputModalities) {
            RequestImageSupport.STRUCTURED
        } else {
            RequestImageSupport.NONE
        }
        return RequestMediaCapabilities(
            userImages = structured,
            assistantImages = structured,
            toolOutputImages = structured,
        )
    }
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(providerSetting = providerSetting, path = "models?pageSize=100")
            val request = transformRequest(
                providerSetting = providerSetting,
                request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
            )
            val response = client.newCall(request).await()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: error("empty body")
                Log.d(TAG, "listModels: $body")
                val bodyObject = json.parseToJsonElement(body).jsonObject
                val models = bodyObject["models"]?.jsonArray ?: return@withContext emptyList()

                models.mapNotNull {
                    val modelObject = it.jsonObject

                    // 忽略非chat/embedding模型
                    val supportedGenerationMethods =
                        modelObject["supportedGenerationMethods"]!!.jsonArray
                            .map { method -> method.jsonPrimitive.content }
                    if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                        return@mapNotNull null
                    }

                    Model(
                        modelId = modelObject["name"]!!.jsonPrimitive.content.substringAfter("/"),
                        displayName = modelObject["displayName"]!!.jsonPrimitive.content,
                        type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                    )
                }
            } else {
                emptyList()
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val replaySourceProfile = googleReplaySourceProfile(providerSetting)
        val requestBody = buildCompletionRequestBody(messages, params, replaySourceProfile)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw formatProviderHttpError(response.code, response.body?.string())
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val candidates = bodyJson["candidates"]?.jsonArray.orEmpty()
        val usage = bodyJson["usageMetadata"] as? JsonObject
        val terminalError = bodyJson.googlePromptBlockException()
            ?: (if (candidates.isEmpty()) HttpException("Gemini returned no candidates") else null)
            ?: candidates.firstNotNullOfOrNull { candidate ->
                geminiFinishReasonException(candidate.jsonObject["finishReason"]?.jsonPrimitive?.contentOrNull)
            }

        val messageChunk = MessageChunk(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            choices = candidates.mapIndexed { index, candidate ->
                val candidateObject = candidate.jsonObject
                val finishReason = candidateObject["finishReason"]?.jsonPrimitive?.contentOrNull
                UIMessageChoice(
                    message = if (candidateObject["content"] is JsonObject) {
                        parseMessage(
                            message = candidateObject,
                            sourceModelId = params.model.modelId,
                            sourceProfile = replaySourceProfile,
                            providerStepId = Uuid.random().toString(),
                        )
                    } else {
                        check(terminalError != null) { "Gemini returned no content" }
                        null
                    },
                    index = candidateObject["index"]?.jsonPrimitive?.intOrNull ?: index,
                    finishReason = finishReason,
                    delta = null
                )
            },
            usage = parseUsageMeta(usage)
        )

        terminalError?.let { throw ProviderResponseException(messageChunk, it) }
        messageChunk
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val replaySourceProfile = googleReplaySourceProfile(providerSetting)
        val requestBody = buildCompletionRequestBody(messages, params, replaySourceProfile)
        val candidateStepIds = mutableMapOf<Int, String>()
        var terminalObserved = false

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    val jsonData = json.parseToJsonElement(data).jsonObject
                    jsonData.googlePromptBlockException()?.let { error ->
                        eventSource.cancel()
                        close(error)
                        return
                    }
                    val usage = parseUsageMeta(jsonData["usageMetadata"] as? JsonObject)
                    val candidates = jsonData["candidates"]?.jsonArray.orEmpty()
                    if (candidates.isEmpty()) {
                        if (usage != null) {
                            trySend(
                                MessageChunk(
                                    id = Uuid.random().toString(),
                                    model = params.model.modelId,
                                    choices = emptyList(),
                                    usage = usage,
                                )
                            )
                        }
                        return
                    }
                    val messageChunk = MessageChunk(
                        id = Uuid.random().toString(),
                        model = params.model.modelId,
                        choices = candidates.mapIndexed { arrayIndex, candidate ->
                            val candidateObj = candidate.jsonObject
                            val candidateIndex = candidateObj["index"]?.jsonPrimitive?.intOrNull ?: arrayIndex
                            val content = candidateObj["content"]?.jsonObject
                            val groundingMetadata = candidateObj["groundingMetadata"]?.jsonObject
                            val finishReason =
                                candidateObj["finishReason"]?.jsonPrimitive?.contentOrNull
                            if (candidateIndex == 0 && finishReason != null) {
                                terminalObserved = true
                            }

                            val message = content?.let {
                                parseMessage(buildJsonObject {
                                    put("role", JsonPrimitive("model"))
                                    put("content", it)
                                    groundingMetadata?.let { groundingMetadata ->
                                        put("groundingMetadata", groundingMetadata)
                                    }
                                }, params.model.modelId, replaySourceProfile, candidateStepIds.getOrPut(candidateIndex) {
                                    Uuid.random().toString()
                                })
                            }

                            UIMessageChoice(
                                index = candidateIndex,
                                delta = message,
                                message = null,
                                finishReason = finishReason
                            )
                        },
                        usage = usage
                    )

                    trySend(messageChunk).onFailure { e ->
                        Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                    }
                    candidates.firstOrNull()?.jsonObject
                        ?.get("finishReason")?.jsonPrimitive?.contentOrNull
                        ?.let { finishReason ->
                            runCatching { validateGeminiFinishReason(finishReason) }
                                .exceptionOrNull()
                                ?.let { error ->
                                    eventSource.cancel()
                                    close(error)
                                }
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "onEvent: failed to parse Gemini stream event", e)
                    eventSource.cancel()
                    close(e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        exception = formatProviderHttpError(response.code, bodyStr)
                    }
                } catch (e: Throwable) {
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (terminalObserved) {
                    close()
                } else {
                    close(
                        HttpException(
                            message = "Gemini stream closed before a terminal finishReason",
                            terminalStatus = me.rerere.ai.util.ProviderTerminalStatus.INCOMPLETE,
                        )
                    )
                }
            }
        }

        val eventSource = EventSources.createFactory(client)
                .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    internal fun buildCompletionRequestBody(
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
        sourceProfile: String,
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemText = messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
            .joinToString("\n") { it.text }
        if (systemText.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put("text", systemText)
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                if (ModelRegistry.GEMINI_3_NO_MINIMAL_THINKING.match(modelId = params.model.modelId)) {
                                    // 3.1 Pro / 3.7 Flash 不支持 minimal（low / medium / high），显式设置会返回 API 校验错误；
                                    // OFF 降级到最低支持档，与 OpenAI-compatible 未知网关的 OFF → low 回退一致。
                                    put("thinkingLevel", "low")
                                } else {
                                    put("thinkingLevel", "minimal")
                                }
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId)) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH, MAX
                                }
                            } else {
                                put("thinkingBudget", gemini25ThinkingBudget(params.model.modelId, params.reasoningLevel))
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages, params.mediaCapabilities, params.model.modelId, sourceProfile)
        )

        // Function declarations and model built-in tools share one tools array. Writing this key
        // twice silently replaces the first value and makes mixed tool requests incomplete.
        val useFunctionTools = params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        if (useFunctionTools || params.model.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                if (useFunctionTools) {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    tool.parameters?.let { schema ->
                                        put(
                                            key = "parametersJsonSchema",
                                            element = schema.removeElements(listOf("\$schema")),
                                        )
                                    }
                                })
                            }
                        })
                    })
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
                            })
                        }

                        else -> {}
                    }
                }
            })
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                put("threshold", "OFF")
            })
        }
    }.mergeCustomBody(
        params.customBody,
        GEMINI_OWNERSHIP,
    )

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(
        message: JsonObject,
        sourceModelId: String,
        sourceProfile: String,
        providerStepId: String,
    ): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = content["parts"]?.jsonArray?.map { part ->
            parseMessagePart(part.jsonObject, sourceModelId, sourceProfile, providerStepId)
        } ?: emptyList()

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    internal fun parseMessagePart(
        jsonObject: JsonObject,
        sourceModelId: String,
        sourceProfile: String,
        providerStepId: String,
    ): UIMessagePart {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                val metadata = if (thought || thoughtSignature != null) {
                    GoogleThoughtMetadata(
                        thoughtSignature = thoughtSignature,
                        thought = if (thought) true else null,
                        sourceModelId = sourceModelId.takeIf { it.isNotBlank() },
                        sourceProfile = sourceProfile.takeIf { it.isNotBlank() },
                        providerStepId = providerStepId.takeIf { it.isNotBlank() },
                    ).toMetadata()
                } else {
                    null
                }
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = metadata,
                ) else UIMessagePart.Text(text, metadata = metadata)
            }

            jsonObject.containsKey("functionCall") -> {
                val functionCall = jsonObject["functionCall"]!!.jsonObject
                val functionName = functionCall["name"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Gemini functionCall.name must be a non-blank string")
                val functionArgs = functionCall["args"] as? JsonObject
                    ?: throw IllegalArgumentException("Gemini functionCall.args must be an object")
                val functionCallId = functionCall["id"]?.jsonPrimitive?.contentOrNull
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                UIMessagePart.Tool(
                    localCallId = Uuid.NIL,
                    stepId = Uuid.NIL,
                    providerCallId = functionCallId ?: Uuid.random().toString(),
                    toolName = functionName,
                    input = json.encodeToString(functionArgs),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = thoughtSignature,
                        functionCallId = functionCallId,
                        sourceModelId = sourceModelId.takeIf { it.isNotBlank() },
                        sourceProfile = sourceProfile.takeIf { it.isNotBlank() },
                        providerStepId = providerStepId.takeIf { it.isNotBlank() },
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // UI 使用占位文本显示草稿图，metadata 保留原始 Part 供下一轮无损回放。
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = GoogleThoughtMetadata(
                            thoughtSignature = thoughtSignature,
                            thought = true,
                            inlineData = inlineData,
                            sourceModelId = sourceModelId.takeIf { it.isNotBlank() },
                            sourceProfile = sourceProfile.takeIf { it.isNotBlank() },
                            providerStepId = providerStepId.takeIf { it.isNotBlank() },
                        ).toMetadata(),
                    )
                }
                UIMessagePart.Image(
                    url = renderableImageUrl(data, mime),
                    metadata = thoughtSignature?.let {
                        GoogleThoughtMetadata(
                            thoughtSignature = it,
                            sourceModelId = sourceModelId.takeIf { it.isNotBlank() },
                            sourceProfile = sourceProfile.takeIf { it.isNotBlank() },
                            providerStepId = providerStepId.takeIf { it.isNotBlank() },
                        ).toMetadata()
                    },
                )
            }

            else -> error("Unknown Gemini message part fields: ${jsonObject.keys.sorted()}")
        }
    }

    internal fun buildContents(
        messages: List<ModelRequestMessage>,
        mediaCapabilities: RequestMediaCapabilities,
        modelId: String,
        sourceProfile: String,
    ): JsonArray {
        val contents = buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message, mediaCapabilities, modelId, sourceProfile)
                    } else {
                        addUserMessage(message, mediaCapabilities)
                    }
                }
        }
        return mergeAdjacentSameRoleContents(contents)
    }

    private fun JsonArrayBuilder.addModelMessage(
        message: ModelRequestMessage,
        mediaCapabilities: RequestMediaCapabilities,
        modelId: String,
        sourceProfile: String,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull {
                        it.toGooglePart(mediaCapabilities.assistantImages, modelId, sourceProfile)
                    }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.splitGoogleProviderSteps().forEach { stepTools ->
                        stepTools.forEach {
                            partsBuffer.add(it.toFunctionCallPart(modelId, sourceProfile))
                        }

                        add(buildJsonObject {
                            put("role", "model")
                            putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                        })
                        partsBuffer.clear()

                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                stepTools.forEach {
                                    add(it.toFunctionResponsePart(mediaCapabilities, modelId, sourceProfile))
                                }
                            }
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun mergeAdjacentSameRoleContents(contents: JsonArray): JsonArray {
        if (contents.size <= 1) return contents
        return buildJsonArray {
            var currentRole: String? = null
            val mergedParts = ArrayList<JsonElement>()
            fun flush() {
                val role = currentRole ?: return
                add(buildJsonObject {
                    put("role", role)
                    putJsonArray("parts") { mergedParts.forEach { add(it) } }
                })
                mergedParts.clear()
            }
            contents.forEach { element ->
                val obj = element.jsonObject
                val role = obj["role"]?.jsonPrimitive?.content
                val parts = obj["parts"]?.jsonArray ?: JsonArray(emptyList())
                if (role != null && role == currentRole) {
                    mergedParts.addAll(parts)
                } else {
                    flush()
                    currentRole = role
                    mergedParts.addAll(parts)
                }
            }
            flush()
        }
    }

    private fun JsonArrayBuilder.addUserMessage(
        message: ModelRequestMessage,
        mediaCapabilities: RequestMediaCapabilities,
    ) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.mapNotNull {
                    it.toGooglePart(
                        mediaCapabilities.userImages,
                        modelId = "",
                        sourceProfile = "",
                    )
                }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGooglePart(
        imageSupport: RequestImageSupport,
        modelId: String,
        sourceProfile: String,
    ): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("text", text)
            metadataAs<GoogleThoughtMetadata>()
                ?.replayableThoughtSignature(modelId, sourceProfile)?.let {
                put("thoughtSignature", it)
            }
        }

        is UIMessagePart.Image -> {
            check(imageSupport == RequestImageSupport.STRUCTURED) {
                "Gemini received a native image after request projection"
            }
            val encoded = encodeNativeImage(false)
            buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", encoded.mimeType)
                    put("data", encoded.base64)
                })
                metadataAs<GoogleThoughtMetadata>()
                    ?.replayableThoughtSignature(modelId, sourceProfile)?.let {
                    put("thoughtSignature", it)
                }
            }
        }

        // RequestMediaCapabilities currently owns only native image support. Audio/video remain
        // reference-only attachment facts until the common capability and real MIME owner expand.
        is UIMessagePart.Video -> null

        is UIMessagePart.Audio -> null

        is UIMessagePart.Reasoning -> {
            val thoughtMetadata = metadataAs<GoogleThoughtMetadata>()
            when {
                thoughtMetadata?.inlineData != null -> buildJsonObject {
                    put("inlineData", thoughtMetadata.inlineData)
                    put("thought", true)
                    thoughtMetadata.replayableThoughtSignature(modelId, sourceProfile)
                        ?.let { put("thoughtSignature", it) }
                }

                thoughtMetadata?.thought == true || thoughtMetadata?.thoughtSignature != null -> buildJsonObject {
                    put("text", reasoning)
                    put("thought", true)
                    thoughtMetadata.replayableThoughtSignature(modelId, sourceProfile)
                        ?.let { put("thoughtSignature", it) }
                }

                else -> null
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toFunctionCallPart(
        modelId: String,
        sourceProfile: String,
    ) = buildJsonObject {
        val thoughtMetadata = metadataAs<GoogleThoughtMetadata>()
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
            thoughtMetadata?.functionCallId?.let { put("id", it) }
        })
        thoughtMetadata?.replayableThoughtSignature(modelId, sourceProfile)?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart(
        mediaCapabilities: RequestMediaCapabilities,
        modelId: String,
        sourceProfile: String,
    ) = buildJsonObject {
        val thoughtMetadata = metadataAs<GoogleThoughtMetadata>()
        put("functionResponse", buildJsonObject {
            put("name", toolName)
            thoughtMetadata?.functionCallId?.let { put("id", it) }

            // 1. 拆分出纯文本部分
            val textParts = output.filterIsInstance<UIMessagePart.Text>()

            // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
            // 过滤出最终包含 inlineData 的数据块
            val mediaGoogleParts = output
                .filter { it !is UIMessagePart.Text }
                .mapNotNull {
                    it.toGooglePart(mediaCapabilities.toolOutputImages, modelId, sourceProfile)
                }
                .filter { it.containsKey("inlineData") }

            // 3. 构建给模型看的结构化 response 节点
            put("response", buildJsonObject {
                // 处理文本结果
                if (textParts.isNotEmpty()) {
                    put(
                        "result",
                        textParts.joinToString("\n") { it.text }
                    )
                } else if (mediaGoogleParts.isEmpty()) {
                    // 如果工具啥都没返回，给个兜底成功状态
                    put("result", " ")
                }

                // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                mediaGoogleParts.forEachIndexed { index, _ ->
                    val refName = "media_ref_$index"
                    put(refName, buildJsonObject {
                        put("\$ref", refName)
                    })
                }
            })

            // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
            if (mediaGoogleParts.isNotEmpty()) {
                putJsonArray("parts") {
                    mediaGoogleParts.forEachIndexed { index, googlePart ->
                        val refName = "media_ref_$index"
                        val inlineData = googlePart["inlineData"]!!.jsonObject

                        add(buildJsonObject {
                            // 重新组装 inlineData，并在内部注入 displayName
                            put("inlineData", buildJsonObject {
                                // 保留 mimeType 和 data。
                                inlineData.forEach { (k, v) -> put(k, v) }
                                // 添加能够让 $ref 认出它的唯一名称
                                put("displayName", refName)
                            })

                            // 保留可能存在的其他字段
                            googlePart.forEach { (k, v) ->
                                if (k != "inlineData") put(k, v)
                            }
                        })
                    }
                }
            }
        })
    }

    private fun List<UIMessagePart.Tool>.splitGoogleProviderSteps(): List<List<UIMessagePart.Tool>> {
        if (isEmpty()) return emptyList()
        val steps = mutableListOf<MutableList<UIMessagePart.Tool>>()
        for (tool in this) {
            val stepId = tool.metadataAs<GoogleThoughtMetadata>()?.providerStepId
            val current = steps.lastOrNull()
            val currentStepId = current?.firstOrNull()
                ?.metadataAs<GoogleThoughtMetadata>()?.providerStepId
            if (current == null || (stepId != currentStepId && (stepId != null || currentStepId != null))) {
                steps += mutableListOf(tool)
            } else {
                current += tool
            }
        }
        return steps
    }

    internal fun parseUsageMeta(jsonObject: JsonObject?): ProviderUsageSnapshot? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.longOrNull
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.longOrNull
        val candidatesTokens = (jsonObject["candidatesTokenCount"] ?: jsonObject["responseTokenCount"])
            ?.jsonPrimitiveOrNull?.longOrNull
        val toolUseInputTokens = jsonObject["toolUsePromptTokenCount"]?.jsonPrimitiveOrNull?.longOrNull
        return ProviderUsageSnapshot(
            inputTokens = promptTokens?.let { sumTokenCountsOrNull(it, toolUseInputTokens ?: 0L) },
            outputTokens = when {
                candidatesTokens != null -> sumTokenCountsOrNull(candidatesTokens, thoughtTokens ?: 0L)
                thoughtTokens != null -> thoughtTokens
                else -> null
            },
            cacheReadInputTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.longOrNull,
            reasoningOutputTokens = thoughtTokens,
            toolUseInputTokens = toolUseInputTokens,
            // Provider total is authoritative and is never recomputed in this adapter.
            totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.longOrNull,
        )
    }
}

private fun GoogleThoughtMetadata.replayableThoughtSignature(
    modelId: String,
    sourceProfile: String,
): String? {
    val signature = thoughtSignature ?: return null
    if (modelId.isBlank() || sourceProfile.isBlank()) return null
    return signature.takeIf { sourceModelId == modelId && this.sourceProfile == sourceProfile }
}

internal fun googleReplaySourceProfile(providerSetting: ProviderSetting.Google): String {
    val host = if (providerSetting.vertexAI) {
        "aiplatform.googleapis.com"
    } else {
        providerSetting.baseUrl.toHttpUrl().host.lowercase()
    }
    val transport = if (providerSetting.vertexAI) "vertex" else "developer"
    return "google:$transport:$host"
}

private fun JsonObject.googlePromptBlockException(): HttpException? {
    val feedback = this["promptFeedback"] as? JsonObject ?: return null
    val reason = feedback["blockReason"]?.jsonPrimitive?.contentOrNull ?: return null
    val message = feedback["blockReasonMessage"]?.jsonPrimitive?.contentOrNull
    return HttpException(
        message = buildString {
            append("Gemini prompt blocked: ").append(reason)
            if (!message.isNullOrBlank()) append(" (").append(message).append(')')
        },
    )
}

private fun validateGeminiFinishReason(finishReason: String?) {
    geminiFinishReasonException(finishReason)?.let { throw it }
}

internal fun geminiFinishReasonException(finishReason: String?): HttpException? {
    if (finishReason == "STOP") return null
    val status = if (finishReason == "MAX_TOKENS") {
        me.rerere.ai.util.ProviderTerminalStatus.INCOMPLETE
    } else {
        me.rerere.ai.util.ProviderTerminalStatus.FAILED
    }
    return HttpException(
        message = "Gemini generation terminated with finishReason=${finishReason ?: "missing"}",
        terminalStatus = status,
    )
}

/** Gemini 2.5 Flash/Flash-Lite reject thinkingBudget above 24576; Pro allows 32768. */
internal fun gemini25ThinkingBudget(modelId: String, level: ReasoningLevel): Int {
    val isGeminiPro = modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))
    val cap = if (isGeminiPro) 32_768 else 24_576
    return level.budgetTokens.coerceAtMost(cap)
}

/**
 * Builder-owned structural keys for Google Gemini Generate Content requests.
 *
 * These fields are exclusively owned by [GoogleProvider.buildCompletionRequestBody]; a
 * [CustomBody] entry attempting to override any of them is rejected before HTTP with a typed
 * [me.rerere.ai.util.CustomBodyReservedKeyException]. The model id is also part of the URL path
 * and is owned by the adapter.
 */
internal val GEMINI_OWNERSHIP = RequestBodyOwnership(
    protocol = "google-gemini",
    reservedKeys = setOf(
        "contents",
        "systemInstruction",
        "tools",
    ),
    reservedPaths = setOf(
        listOf("generationConfig", "candidateCount"),
        listOf("generationConfig", "responseModalities"),
        listOf("toolConfig", "functionCallingConfig", "streamFunctionCallArguments"),
    ),
)
