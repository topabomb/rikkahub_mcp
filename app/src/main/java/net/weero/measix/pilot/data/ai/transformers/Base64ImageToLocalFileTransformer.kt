package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.files.ArtifactStore

class Base64ImageToLocalFileTransformer(
    private val artifactStore: ArtifactStore,
) : OutputMessageTransformer, StreamingMessageTransformer {
    // 流式期间不转换（chunk 可能携带不完整 base64）；终态统一落盘
    override suspend fun transformStreaming(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage?,
    ): UIMessage = message

    override suspend fun onStreamingFinish(
        ctx: TransformerContext,
        message: UIMessage,
        previousProjection: UIMessage?,
    ): UIMessage {
        val persisted = artifactStore.persistBase64Images(message)
        persisted.ownedArtifacts.forEach { owned ->
            ctx.registerUnpublishedResource(artifactStore.unpublishedLease(owned))
        }
        return persisted.message
    }
}
