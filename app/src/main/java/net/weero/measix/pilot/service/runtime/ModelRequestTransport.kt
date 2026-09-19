package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.configuration.ModelSelectionRole
import kotlin.uuid.Uuid

internal suspend fun ModelRequestTarget.streamText(
    providers: ProviderManager,
    messages: List<ModelRequestMessage>,
    params: TextGenerationParams,
): Flow<MessageChunk> = when (this) {
    is ModelRequestTarget.Remote -> providers.getProviderByType(provider).streamText(provider, messages, requestParams(params))
}

internal suspend fun ModelRequestTarget.generateText(
    providers: ProviderManager,
    messages: List<ModelRequestMessage>,
    params: TextGenerationParams,
    role: ModelSelectionRole = ModelSelectionRole.CHAT,
): MessageChunk {
    @Suppress("UNUSED_VARIABLE") val purpose = role
    return when (this) {
        is ModelRequestTarget.Remote -> if (credentials is RequestCredentials.Routed) {
            collectRoutedText(streamText(providers, messages, params), params)
        } else providers.getProviderByType(provider).generateText(provider, messages, requestParams(params))
    }
}

private suspend fun collectRoutedText(chunks: Flow<MessageChunk>, params: TextGenerationParams): MessageChunk {
    val accumulator = net.weero.measix.pilot.service.turn.StepOutputAccumulator()
    val usage = net.weero.measix.pilot.data.ai.RequestUsageReducer(1)
    val started = kotlin.time.TimeSource.Monotonic.markNow()
    var message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Step(stepId = Uuid.random(), ordinal = 0, startedAt = kotlin.time.Clock.System.now()),
    ))
    var last: MessageChunk? = null
    var finishReason: String? = null
    chunks.collect { chunk ->
        message = accumulator.accumulate(message, chunk, params.model)
        chunk.usage?.let(usage::accept)
        chunk.choices.firstOrNull()?.finishReason?.let { finishReason = it }
        last = chunk
    }
    check(!finishReason.isNullOrBlank()) { "platform_model_stream_missing_terminal" }
    val completedUsage = usage.close(net.weero.measix.pilot.data.ai.ProviderRequestOutcome.COMPLETED,
        started.elapsedNow().inWholeMilliseconds)
    if (completedUsage.diagnostics.isNotEmpty()) android.util.Log.w("ModelRequestTransport",
        "Provider usage normalization diagnostics: ${completedUsage.diagnostics.joinToString()}")
    return requireNotNull(last) { "platform_model_stream_empty" }.copy(
        choices = listOf(UIMessageChoice(index = 0, delta = null,
            message = message.copy(parts = message.parts.filterNot { it is UIMessagePart.Step }), finishReason = finishReason)),
        usage = completedUsage.snapshot,
    )
}

internal suspend fun ModelRequestTarget.generateImage(
    providers: ProviderManager,
    params: ImageGenerationParams,
): Flow<ImageGenerationItem> {
    return when (this) {
        is ModelRequestTarget.Remote -> {
            validateOverrides(params.customHeaders, params.customBody)
            providers.getProviderByType(provider).generateImage(provider,
                params.copy(customHeaders = params.customHeaders + headers, credentials = credentials))
        }
    }
}

internal suspend fun ModelRequestTarget.editImage(
    providers: ProviderManager,
    params: ImageEditParams,
): Flow<ImageGenerationItem> {
    return when (this) {
        is ModelRequestTarget.Remote -> {
            validateOverrides(params.customHeaders, params.customBody)
            providers.getProviderByType(provider).editImage(provider,
                params.copy(customHeaders = params.customHeaders + headers, credentials = credentials))
        }
    }
}

private fun ModelRequestTarget.Remote.requestParams(params: TextGenerationParams): TextGenerationParams {
    validateOverrides(params.customHeaders, params.customBody)
    return params.copy(customHeaders = params.customHeaders + headers, credentials = credentials)
}

private fun ModelRequestTarget.Remote.validateOverrides(customHeaders: List<CustomHeader>, customBody: List<CustomBody>) {
    if (credentials != RequestCredentials.UserSettings) {
        val ownedHeaders = headers.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet() + setOf(
            "authorization", "proxy-authorization", "x-api-key", "x-goog-api-key", "host",
            "content-length", "transfer-encoding", "connection",
        )
        check(customHeaders.none { it.name.lowercase(java.util.Locale.ROOT) in ownedHeaders }) {
            "enterprise_request_header_conflict"
        }
        check(customBody.none { it.key in setOf("model", "models", "route", "provider", "stream", "store", "previous_response_id") }) {
            "enterprise_request_routing_override"
        }
    }
}
