package net.weero.measix.pilot.data.ai.transformers

import java.io.File
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.AttachmentProjectionTextMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.ArtifactStore

/**
 * Request-only 附件投影（见 multimodal-context-and-turn-durability.md）：
 *
 * 1. 递归遍历顶层消息与 `Tool.output` 中的 media part，在原来源容器内加入短稳定事实行；
 * 2. 当前 resolved model 原生支持 IMAGE 输入时保留 Image part（native），否则只保留引用事实
 *    （reference_only）；
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
        val nativeImageSupported = ctx.model.inputModalities.contains(Modality.IMAGE)
        return messages.map { message ->
            message.copy(
                parts = projectParts(
                    parts = message.parts,
                    nativeImageSupported = nativeImageSupported,
                    artifactStore = artifactStore,
                    filesDir = ctx.context.filesDir,
                ),
            )
        }
    }

    private suspend fun projectParts(
        parts: List<UIMessagePart>,
        nativeImageSupported: Boolean,
        artifactStore: ArtifactStore,
        filesDir: File,
    ): List<UIMessagePart> {
        val result = ArrayList<UIMessagePart>(parts.size + 2)
        for (part in parts) {
            when (part) {
                is UIMessagePart.Tool -> result += part.copy(
                    output = projectParts(
                        parts = part.output,
                        nativeImageSupported = nativeImageSupported,
                        artifactStore = artifactStore,
                        filesDir = filesDir,
                    ),
                )

                is UIMessagePart.Image -> {
                    val ref = AttachmentRefs.getRef(part)
                    if (nativeImageSupported) {
                        ref?.let { refValue ->
                            result += attachmentProjectionText(
                                attachmentRefLine(
                                    refValue = refValue,
                                    type = "image",
                                    displayName = displayNameOf(part, artifactStore, filesDir),
                                    imageInput = ImageInputProjection.NATIVE,
                                ),
                            )
                        }
                        result += part
                    } else {
                        if (ref != null) {
                            result += attachmentProjectionText(
                                attachmentRefLine(
                                    refValue = ref,
                                    type = "image",
                                    displayName = displayNameOf(part, artifactStore, filesDir),
                                    imageInput = ImageInputProjection.REFERENCE_ONLY,
                                ),
                            )
                        } else {
                            result += attachmentProjectionText(
                                "[Attachment ref=unavailable type=image input=unavailable]",
                            )
                        }
                    }
                }

                is UIMessagePart.Document -> {
                    AttachmentRefs.getRef(part)?.let { ref ->
                        result += attachmentProjectionText(
                            attachmentRefLine(ref, "document", displayNameOf(part, artifactStore, filesDir)),
                        )
                    }
                    result += part
                }

                is UIMessagePart.Audio -> {
                    AttachmentRefs.getRef(part)?.let { ref ->
                        result += attachmentProjectionText(
                            attachmentRefLine(ref, "audio", displayNameOf(part, artifactStore, filesDir)),
                        )
                    }
                    result += part
                }

                is UIMessagePart.Video -> {
                    AttachmentRefs.getRef(part)?.let { ref ->
                        result += attachmentProjectionText(
                            attachmentRefLine(ref, "video", displayNameOf(part, artifactStore, filesDir)),
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

internal enum class ImageInputProjection(val markerValue: String) {
    NATIVE("native"),
    REFERENCE_ONLY("reference_only"),
}

/** `[Attachment ref=attachment:8f2... type=image name="screenshot.png" input=native]`。 */
internal fun attachmentRefLine(
    refValue: String,
    type: String,
    displayName: String,
    imageInput: ImageInputProjection? = null,
): String {
    val input = imageInput?.let { " input=${it.markerValue}" }.orEmpty()
    return "[Attachment ref=$refValue type=$type name=\"$displayName\"$input]"
}

private fun attachmentProjectionText(text: String): UIMessagePart.Text = UIMessagePart.Text(
    text = text,
    metadata = AttachmentProjectionTextMetadata(attachmentProjectionText = true).toMetadata(),
)

private suspend fun displayNameOf(
    part: UIMessagePart,
    artifactStore: ArtifactStore,
    filesDir: File,
): String {
    val url = when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Document -> part.fileName.ifBlank { part.url }
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Video -> part.url
        else -> return "attachment"
    }
    if (part is UIMessagePart.Document && part.fileName.isNotBlank()) {
        return part.fileName
    }
    if (url.startsWith("file:")) {
        val file = AttachmentRefs.parseFileUrl(url)
        if (file != null) {
            val relative = FileUtils.getRelativePathInFilesDir(filesDir, file)
            if (relative != null) {
                val entity = artifactStore.getByRelativePath(relative)
                val display = entity?.displayName?.trim().orEmpty()
                if (display.isNotEmpty()) return display
            }
            if (file.name.isNotBlank()) return file.name
        }
    }
    return url.substringAfterLast('/').substringBefore('?').ifBlank { "attachment" }
}
