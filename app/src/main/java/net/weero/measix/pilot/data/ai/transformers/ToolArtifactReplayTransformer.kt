package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.files.ToolArtifactRewriter

class ToolArtifactReplayTransformer(
    private val rewriter: ToolArtifactRewriter,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            message.copy(parts = message.parts.map { rematerializePart(it) })
        }
    }

    private suspend fun rematerializePart(part: UIMessagePart): UIMessagePart {
        return when (part) {
            is UIMessagePart.Tool -> part.copy(
                output = rewriter.materializeToolOutput(part.output, part.metadata)
                    .map { rematerializePart(it) },
            )
            else -> part
        }
    }
}
