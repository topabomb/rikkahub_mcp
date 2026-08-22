package net.weero.measix.pilot.data.ai.transformers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R

/**
 * Replaces file-backed Image parts with a NATIVE / DERIVED / UNAVAILABLE model view.
 * Conversation persistence is not modified.
 */
object AttachmentInputTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val capability = ImageInputAdapter.resolveCapability(ctx.model, ctx.settings)
        if (capability == ImageAdaptCapability.NATIVE) return messages

        val hasLocalImages = messages.any { message -> message.parts.hasLocalImage() }
        if (!hasLocalImages) return messages

        if (capability == ImageAdaptCapability.UNAVAILABLE) {
            return messages.map { message ->
                message.copy(parts = adaptParts(ctx, message, message.parts, capability))
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = runCatching {
                    ctx.context.getString(R.string.image_observation_processing)
                }.getOrDefault("Reading image...")
                messages.map { message ->
                    message.copy(parts = adaptParts(ctx, message, message.parts, capability))
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    private fun List<UIMessagePart>.hasLocalImage(): Boolean = any { part ->
        when (part) {
            is UIMessagePart.Image -> part.url.startsWith("file:")
            is UIMessagePart.Tool -> part.output.hasLocalImage()
            else -> false
        }
    }

    private suspend fun adaptParts(
        ctx: TransformerContext,
        message: UIMessage,
        parts: List<UIMessagePart>,
        capability: ImageAdaptCapability,
    ): List<UIMessagePart> = parts.map { part ->
        when {
            part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                ImageInputAdapter.adaptImage(ctx, message, part, capability)
            }
            part is UIMessagePart.Tool -> part.copy(
                output = adaptParts(ctx, message, part.output, capability),
            )
            else -> part
        }
    }
}
