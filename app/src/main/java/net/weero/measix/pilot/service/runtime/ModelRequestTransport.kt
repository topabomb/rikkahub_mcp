package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart

/** External I/O selection consumes the same assembled request for real and local example models. */
internal suspend fun ModelRequestTarget.streamText(
    providers: ProviderManager,
    messages: List<ModelRequestMessage>,
    params: TextGenerationParams,
): Flow<MessageChunk> = when (this) {
    is ModelRequestTarget.Remote -> providers.getProviderByType(provider).streamText(
        provider, messages, requestParams(params),
    )
    ModelRequestTarget.LocalExample -> flow {
        exampleResponse(messages).chunked(12).forEach { text ->
            yield()
            emit(exampleChunk(params, text, stream = true, finished = false))
        }
        emit(exampleChunk(params, "", stream = true, finished = true))
    }
}

internal suspend fun ModelRequestTarget.generateText(
    providers: ProviderManager,
    messages: List<ModelRequestMessage>,
    params: TextGenerationParams,
): MessageChunk = when (this) {
    is ModelRequestTarget.Remote -> providers.getProviderByType(provider).generateText(
        provider, messages, requestParams(params),
    )
    ModelRequestTarget.LocalExample -> exampleChunk(params, exampleResponse(messages), stream = false, finished = true)
}

internal suspend fun ModelRequestTarget.generateImage(
    providers: ProviderManager,
    params: ImageGenerationParams,
): Flow<ImageGenerationItem> = when (this) {
    is ModelRequestTarget.Remote -> {
        validateOverrides(params.customHeaders, params.customBody)
        providers.getProviderByType(provider).generateImage(provider,
            params.copy(customHeaders = params.customHeaders + headers, credentials = credentials))
    }
    ModelRequestTarget.LocalExample -> exampleImages(params.model.displayName, params.prompt, params.numOfImages, params.size, 0)
}

internal suspend fun ModelRequestTarget.editImage(
    providers: ProviderManager,
    params: ImageEditParams,
): Flow<ImageGenerationItem> = when (this) {
    is ModelRequestTarget.Remote -> {
        validateOverrides(params.customHeaders, params.customBody)
        providers.getProviderByType(provider).editImage(provider,
            params.copy(customHeaders = params.customHeaders + headers, credentials = credentials))
    }
    ModelRequestTarget.LocalExample -> {
        require(params.images.isNotEmpty() && params.images.all { java.io.File(it).isFile }) { "image_edit_input_unavailable" }
        exampleImages(params.model.displayName, params.prompt, params.numOfImages, params.size, params.images.size)
    }
}

/** A visible simulation result exercises the ordinary image delivery and persistence path. */
private fun exampleImages(model: String, prompt: String, count: Int, size: String, inputCount: Int): Flow<ImageGenerationItem> = flow {
    require(count in 1..4) { "invalid_image_count" }
    val selected = requireNotNull(ImageGenSize.entries.firstOrNull { it.value == size }) { "invalid_image_size" }
    val dimensions = (if (selected == ImageGenSize.AUTO) ImageGenSize.SQUARE_1024.value else selected.value).split('x')
    val width = dimensions[0].toInt()
    val height = dimensions[1].toInt()
    repeat(count) { index ->
        yield()
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.rgb(231, 239, 247))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(34, 66, 94)
                textSize = width / 28f
            }
            val margin = width / 16f
            canvas.drawText("Enterprise local example", margin, height / 3f, paint)
            paint.textSize = width / 38f
            val operation = if (inputCount == 0) "Simulated generation" else "Simulated edit: $inputCount input images"
            canvas.drawText("$operation (${index + 1}/$count)", margin, height / 2f, paint)
            canvas.drawText(model.take(42), margin, height * 0.62f, paint)
            canvas.drawText(prompt.take(42), margin, height * 0.74f, paint)
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "example_image_encoding_failed" }
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        emit(ImageGenerationItem(data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), mimeType = "image/png", partial = false))
    }
}

private fun ModelRequestTarget.Remote.requestParams(params: TextGenerationParams): TextGenerationParams {
    validateOverrides(params.customHeaders, params.customBody)
    return params.copy(customHeaders = params.customHeaders + headers, credentials = credentials)
}

private fun ModelRequestTarget.Remote.validateOverrides(customHeaders: List<CustomHeader>, customBody: List<CustomBody>) {
    if (credentials is RequestCredentials.Fixed) {
        val ownedHeaders = headers.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet() + setOf(
            "authorization", "proxy-authorization", "x-api-key", "x-goog-api-key", "host",
            "content-length", "transfer-encoding", "connection",
        )
        check(customHeaders.none { it.name.lowercase(java.util.Locale.ROOT) in ownedHeaders }) {
            "enterprise_request_header_conflict"
        }
        check(customBody.none { it.key in setOf("models", "route", "provider") }) {
            "enterprise_request_routing_override"
        }
    }
}

private fun exampleResponse(messages: List<ModelRequestMessage>): String {
    val user = messages.lastOrNull { it.role == MessageRole.USER }
    val images = user?.parts?.count { it is UIMessagePart.Image } ?: 0
    return buildString {
        append("企业本地示例已收到你的请求。")
        if (images > 0) append("已接收 $images 张图片；本地示例返回模拟结果。")
        append("这是一条本地模拟回复，用于验证企业空间中的对话流程。")
    }
}

private fun exampleChunk(params: TextGenerationParams, text: String, stream: Boolean, finished: Boolean): MessageChunk {
    val message = UIMessage.assistant(text)
    return MessageChunk(
        id = "local-example-response",
        model = params.model.modelId,
        choices = listOf(UIMessageChoice(
            index = 0,
            delta = message.takeIf { stream },
            message = message.takeUnless { stream },
            finishReason = "stop".takeIf { finished },
        )),
    )
}
