package me.rerere.ai.testsupport

import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Test-only mirror of the app-layer `RequestAssembler` conversion: a durable [UIMessage] becomes the
 * provider-facing [ModelRequestMessage] carrying only the wire-relevant facts (role, model-visible
 * parts with durable [UIMessagePart.Step] boundaries dropped, providerMetadata, providerReplayProjection).
 */
fun UIMessage.toModelRequest(): ModelRequestMessage = ModelRequestMessage(
    role = role,
    parts = parts.filterNot { it is UIMessagePart.Step },
    modelId = modelId,
    providerMetadata = providerMetadata,
    providerReplayProjection = providerReplayProjection,
)

fun List<UIMessage>.toModelRequests(): List<ModelRequestMessage> = map { it.toModelRequest() }
