package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.files.ToolArtifactRewriter

class ToolArtifactReplayTransformer(
    private val rewriter: ToolArtifactRewriter,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val reads = requireNotNull(ctx.artifactReads)
        return messages.map { message ->
            message.copy(parts = message.parts.map { rematerializePart(it, reads) })
        }
    }

    private suspend fun rematerializePart(part: UIMessagePart, reads: ArtifactReadLease): UIMessagePart {
        return when (part) {
            is UIMessagePart.Tool -> part.copy(
                output = rewriter.materializeToolOutput(reads, part.output, part.metadata)
                    .map { rematerializePart(it, reads) },
            )
            else -> part
        }
    }
}
