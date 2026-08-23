package net.weero.measix.pilot.data.files

import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart

/**
 * 附件克隆唯一实现（Master fork 与 Child clone 共用，V1 正式阶段·架构收敛 §11.3 块 3）。
 *
 * 语义：`file:` 引用的本地附件复制为新文件（内容级复制，脱离原会话的 GC 生命周期），
 * 其余引用原样返回；Tool part 递归克隆其 output。fork 场景注入 [ToolArtifactRewriter]
 * 时优先重写 artifact 引用（产物归属新会话）。
 */
internal object AttachmentCloner {

    suspend fun clonePart(
        part: UIMessagePart,
        filesManager: FilesManager,
        toolArtifactRewriter: ToolArtifactRewriter? = null,
    ): UIMessagePart {
        suspend fun copyUrl(url: String): String {
            if (!url.startsWith("file:")) return url
            return filesManager.createChatFilesByContents(listOf(url.toUri()))
                .firstOrNull()?.toString() ?: url
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
                    val (newOutput, newMetadata) = rewriter.rewriteToolOutput(part.output, part.metadata)
                    part.copy(output = newOutput, metadata = newMetadata)
                } else {
                    part.copy(output = cloneParts(part.output, filesManager))
                }
            }
            else -> part
        }
    }

    suspend fun cloneParts(
        parts: List<UIMessagePart>,
        filesManager: FilesManager,
        toolArtifactRewriter: ToolArtifactRewriter? = null,
    ): List<UIMessagePart> = parts.map { clonePart(it, filesManager, toolArtifactRewriter) }
}
