package me.rerere.ai.provider.images

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.forCredentials
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.json
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.PrivateRequest
import me.rerere.common.http.readResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One typed owner for enterprise image request wires; it never persists provider settings. */
internal class ManagedImageGenerationProvider(
    private val client: OkHttpClient,
    private val download: suspend (String) -> ImageGenerationItem =
        SafeRoutedImageDownloader(client)::downloadAsBase64,
) {
    suspend fun generate(
        protocol: ImageGenerationClientProtocol,
        params: ImageGenerationParams,
        headers: List<CustomHeader>,
        credentials: RequestCredentials.Routed,
    ): Flow<ImageGenerationItem> = flow {
        check(params.customHeaders.isEmpty() && params.customBody.isEmpty()) {
            "enterprise_image_request_override"
        }
        val requestBody = when (protocol) {
            ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS -> buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                put("size", params.size)
            }
            ImageGenerationClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION -> buildJsonObject {
                put("model", params.model.modelId)
                put("input", buildJsonObject {
                    putJsonArray("messages") {
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") { add(buildJsonObject { put("text", params.prompt) }) }
                        })
                    }
                })
                put("parameters", buildJsonObject {
                    check(params.size.matches(Regex("^[1-9][0-9]*x[1-9][0-9]*$"))) {
                        "dashscope_image_invalid_canonical_size"
                    }
                    put("size", params.size.replace('x', '*'))
                    put("n", params.numOfImages)
                    put("watermark", false)
                })
            }
        }
        val request = Request.Builder()
            .url(credentials.endpoint)
            .headers(headers.toHeaders())
            .tag(PrivateRequest::class.java, PrivateRequest)
            .header("Authorization", "Bearer ${credentials.token}")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()
        val parsed = withContext(Dispatchers.IO) {
            client.forCredentials(credentials).newCall(request).readResponse { response ->
                when (protocol) {
                    ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS ->
                        response.readBoundedImageGenerationResponse(params.numOfImages)
                    ImageGenerationClientProtocol.DASHSCOPE_MULTIMODAL_GENERATION ->
                        response.readBoundedDashScopeImageGenerationResponse(params.numOfImages)
                }
            }
        }
        if (parsed.allBlockedByModeration && parsed.items.isEmpty()) {
            throw moderationBlockedImageException()
        }
        parsed.items.forEach { item ->
            emit(when (item) {
                is ParsedImageGenerationItem.Base64Json -> ImageGenerationItem(item.data, item.mimeType)
                is ParsedImageGenerationItem.RemoteUrl -> download(item.url)
            })
        }
    }
}
