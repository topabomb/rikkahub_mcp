package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.ui.UIMessage

/**
 * Compatibility alias. Image adaptation lives in [AttachmentInputTransformer].
 */
object OcrTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = AttachmentInputTransformer.transform(ctx, messages)
}
