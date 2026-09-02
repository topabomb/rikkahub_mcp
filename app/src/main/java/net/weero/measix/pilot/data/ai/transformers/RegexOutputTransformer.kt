package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.replaceRegexes

object RegexOutputTransformer : OutputMessageTransformer, StreamingMessageTransformer {
    override suspend fun transformStreaming(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage?,
    ): UIMessage = applyRegexes(ctx, message)

    override suspend fun onStreamingFinish(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage?,
    ): UIMessage = applyRegexes(ctx, message)

    private fun applyRegexes(ctx: TransformerContext, message: UIMessage): UIMessage {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return message
        val scope = when (message.role) {
            MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
            else -> return message // Skip non-assistant messages
        }
        return message.copy(
            parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        part.copy(text = part.text.replaceRegexes(assistant.regexes, scope, visual = false))
                    }

                    is UIMessagePart.Reasoning -> {
                        part.copy(reasoning = part.reasoning.replaceRegexes(assistant.regexes, scope, visual = false))
                    }

                    else -> part
                }
            }
        )
    }
}
