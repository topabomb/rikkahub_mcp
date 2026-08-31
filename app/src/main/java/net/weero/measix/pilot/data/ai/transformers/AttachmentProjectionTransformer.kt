package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.AttachmentProjectionTextMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.ArtifactStore

/**
 * Request-only 附件投影（见 multimodal-context-and-turn-durability.md）：
 *
 * 1. 递归遍历顶层消息与 `Tool.output` 中的 media part，在原来源容器内加入短稳定事实行；
 * 2. 按请求级 [RequestMediaCapabilities] 决定 native / reference_only / unavailable，
 *    不单独使用 model.inputModalities；
 * 3. 不跨消息追加提示，不改变消息 role，也不把请求级投影写回 durable Conversation。
 *
 * 禁止：调用附件识别模型、读写分析缓存、写回 Conversation、因模型不能看图抛 turn-level failure。
 */
class AttachmentProjectionTransformer(
    private val artifactStore: ArtifactStore,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            message.copy(
                parts = projectParts(
                    parts = message.parts,
                    role = message.role,
                    capabilities = ctx.mediaCapabilities,
                    artifactStore = artifactStore,
                ),
            )
        }
    }

    private suspend fun projectParts(
        parts: List<UIMessagePart>,
        role: MessageRole,
        capabilities: RequestMediaCapabilities,
        artifactStore: ArtifactStore,
        insideToolOutput: Boolean = false,
    ): List<UIMessagePart> {
        val result = ArrayList<UIMessagePart>(parts.size + 2)
        for (part in parts) {
            when (part) {
                is UIMessagePart.Tool -> result += part.copy(
                    output = projectParts(
                        parts = part.output,
                        role = role,
                        capabilities = capabilities,
                        artifactStore = artifactStore,
                        insideToolOutput = true,
                    ),
                )

                is UIMessagePart.Image -> {
                    val support = capabilities.supportFor(role, insideToolOutput)
                    val native = support == RequestImageSupport.STRUCTURED
                    val path = pathOf(part, artifactStore)
                    result += attachmentProjectionText(
                        attachmentPathLine(
                            path = path,
                            type = "image",
                            imageInput = when {
                                native -> AttachmentInputMode.NATIVE
                                path != null -> AttachmentInputMode.REFERENCE_ONLY
                                else -> AttachmentInputMode.UNAVAILABLE
                            },
                        ),
                    )
                    if (native) {
                        result += part
                    }
                }

                is UIMessagePart.Document -> {
                    pathOf(part, artifactStore)?.let { path ->
                        result += attachmentProjectionText(
                            attachmentPathLine(path, "document"),
                        )
                    }
                    result += part
                }

                is UIMessagePart.Audio -> {
                    pathOf(part, artifactStore)?.let { path ->
                        result += attachmentProjectionText(
                            attachmentPathLine(path, "audio"),
                        )
                    }
                    result += part
                }

                is UIMessagePart.Video -> {
                    pathOf(part, artifactStore)?.let { path ->
                        result += attachmentProjectionText(
                            attachmentPathLine(path, "video"),
                        )
                    }
                    result += part
                }

                else -> result += part
            }
        }
        return result
    }
}

internal enum class AttachmentInputMode(val markerValue: String) {
    NATIVE("native"),
    REFERENCE_ONLY("reference_only"),
    UNAVAILABLE("unavailable"),
}

/** Paths are disclosed only when the managed file has an actual tool-readable location. */
internal fun attachmentPathLine(
    path: String?,
    type: String,
    imageInput: AttachmentInputMode? = null,
): String {
    val input = imageInput?.let { " input=${it.markerValue}" }.orEmpty()
    val location = path?.let { " path=${AttachmentRefs.escapeMarkerValue(it)}" }.orEmpty()
    return "[Attachment$location type=${AttachmentRefs.escapeMarkerValue(type)}$input]"
}

private fun attachmentProjectionText(text: String): UIMessagePart.Text = UIMessagePart.Text(
    text = text,
    metadata = AttachmentProjectionTextMetadata(attachmentProjectionText = true).toMetadata(),
)

private fun mediaUrl(part: UIMessagePart): String? = when (part) {
    is UIMessagePart.Image -> part.url
    is UIMessagePart.Document -> part.url
    is UIMessagePart.Audio -> part.url
    is UIMessagePart.Video -> part.url
    else -> null
}

private suspend fun pathOf(part: UIMessagePart, artifactStore: ArtifactStore): String? {
    val url = mediaUrl(part) ?: return null
    val file = AttachmentRefs.parseFileUrl(url) ?: return null
    val managed = artifactStore.resolveManagedReference(file) ?: return null
    return managed.toolPath()
}
