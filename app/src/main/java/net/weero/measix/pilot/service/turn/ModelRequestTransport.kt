package net.weero.measix.pilot.service.turn

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.service.runtime.ModelRequestTarget

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

private fun ModelRequestTarget.Remote.requestParams(params: TextGenerationParams): TextGenerationParams {
    if (credentials is RequestCredentials.Fixed) {
        val ownedHeaders = headers.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet() + setOf(
            "authorization", "proxy-authorization", "x-api-key", "x-goog-api-key", "host",
            "content-length", "transfer-encoding", "connection",
        )
        check(params.customHeaders.none { it.name.lowercase(java.util.Locale.ROOT) in ownedHeaders }) {
            "enterprise_request_header_conflict"
        }
        check(params.customBody.none { it.key in setOf("models", "route", "provider") }) {
            "enterprise_request_routing_override"
        }
    }
    return params.copy(customHeaders = params.customHeaders + headers, credentials = credentials)
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
