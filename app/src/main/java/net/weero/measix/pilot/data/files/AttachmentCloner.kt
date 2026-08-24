package net.weero.measix.pilot.data.files

import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart

/**
 * 附件克隆唯一实现，供 Master fork 与 Child clone 共用。
 *
 * 语义：`file:` 引用的本地附件复制为新文件（内容级复制，脱离原会话的 GC 生命周期），
 * 其余引用原样返回；Tool part 递归克隆其 output。fork 场景注入 [ToolArtifactRewriter]
 * 时优先重写 artifact 引用（产物归属新会话）。
 */
internal object AttachmentCloner {

    suspend fun clonePart(
        part: UIMessagePart,
        artifactStore: ArtifactStore,
        createdArtifacts: MutableList<OwnedArtifact>,
        toolArtifactRewriter: ToolArtifactRewriter? = null,
    ): UIMessagePart {
        suspend fun copyUrl(url: String): String {
            if (!url.startsWith("file:")) return url
            val owned = artifactStore.createFromUri(url.toUri())
            createdArtifacts += owned
            return owned.uri.toString()
        }
        return when (part) {
            is UIMessagePart.Image -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Document -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Video -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Audio -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Tool -> {
                val rewriter = toolArtifactRewriter
                val sourceRef = part.metadata?.let { rewriter?.decodeArtifactRef(it) }
                if (rewriter != null && sourceRef != null) {
                    val rewritten = rewriter.rewriteToolOutput(part.output, part.metadata)
                    rewritten.ownedArtifact?.let(createdArtifacts::add)
                    part.copy(output = rewritten.output, metadata = rewritten.metadata)
                } else {
                    part.copy(output = cloneParts(part.output, artifactStore, createdArtifacts))
                }
            }
            else -> part
        }
    }

    suspend fun cloneParts(
        parts: List<UIMessagePart>,
        artifactStore: ArtifactStore,
        createdArtifacts: MutableList<OwnedArtifact>,
        toolArtifactRewriter: ToolArtifactRewriter? = null,
    ): List<UIMessagePart> = parts.map {
        clonePart(it, artifactStore, createdArtifacts, toolArtifactRewriter)
    }
}
