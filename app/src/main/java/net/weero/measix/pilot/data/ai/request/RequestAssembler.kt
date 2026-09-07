package net.weero.measix.pilot.data.ai.request

import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * The single `UIMessage` → [ModelRequestMessage] conversion boundary。
 *
 * Owns the one place durable transcript parts are hidden from the Provider: [UIMessagePart.Step] is
 * a durable boundary fact and never model-visible content. The Step-dropped
 * [AssembledRequest.providerVisibleMessages] is the exact projection the receipt and token estimate
 * are computed from, and [AssembledRequest.providerMessages] is what the Provider consumes — so no
 * adapter re-derives transcript semantics or drops parts itself.
 */
internal class RequestAssembler {

    fun assemble(projectedMessages: List<UIMessage>): AssembledRequest {
        val providerVisible = projectedMessages.map { message ->
            message.copy(parts = message.parts.filterNot { it is UIMessagePart.Step })
        }
        return AssembledRequest(
            providerVisibleMessages = providerVisible,
            providerMessages = providerVisible.map {
                ModelRequestMessage(
                    role = it.role,
                    parts = it.parts,
                    modelId = it.modelId,
                    providerMetadata = it.providerMetadata,
                    providerReplayProjection = it.providerReplayProjection,
                )
            },
        )
    }
}

/**
 * 一次请求装配的产物：Provider 可见的 durable 投影（供 receipt/token 估算复用同一事实）与
 * Provider 唯一消费的 [ModelRequestMessage] 序列。二者来自同一次 Step 丢弃，绝不各算各的。
 */
internal data class AssembledRequest(
    val providerVisibleMessages: List<UIMessage>,
    val providerMessages: List<ModelRequestMessage>,
)
