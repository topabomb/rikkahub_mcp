package me.rerere.ai.core

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.ProviderReplayProjection
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.partsAreValidToUpload
import me.rerere.ai.ui.partsToText
import kotlin.uuid.Uuid

/**
 * The normalized, provider-facing request message — the ONLY message type a Provider consumes.
 *
 * Produced exclusively by the app-layer `RequestAssembler` from the durable `UIMessage` projection.
 * It carries just the wire-relevant facts and drops every durable-only field (id, timestamps,
 * usage, terminal outcome): [role], model-visible [parts] with durable boundaries
 * ([UIMessagePart.Step]) already removed at assembly time, the lossless replay [providerMetadata],
 * and the request-only [providerReplayProjection]. No adapter re-derives transcript semantics or
 * drops parts itself.
 */
data class ModelRequestMessage(
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val modelId: Uuid? = null,
    val providerMetadata: JsonObject? = null,
    val providerReplayProjection: ProviderReplayProjection? = null,
) {
    init {
        // Durable transcript boundaries never reach the Provider; the assembler drops them before construction.
        require(parts.none { it is UIMessagePart.Step }) {
            "ModelRequestMessage must not carry UIMessagePart.Step"
        }
    }

    /** Whether this message carries at least one non-blank, uploadable part. */
    fun isValidToUpload(): Boolean = partsAreValidToUpload(parts)

    /** Concatenation of the message's text parts; a convenience for assertions and diagnostics. */
    fun toText(): String = partsToText(parts)

    companion object {
        fun system(prompt: String) = ModelRequestMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt)),
        )

        fun user(prompt: String) = ModelRequestMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt)),
        )

        fun assistant(prompt: String) = ModelRequestMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt)),
        )
    }
}
