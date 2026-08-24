package net.weero.measix.pilot.data.ai.transformers

import java.io.File
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.ArtifactStore

/**
 * Request-only 附件投影（见 multimodal-attachment-context-and-analysis-design.md）：
 *
 * 1. 递归遍历顶层消息与 `Tool.output` 中的 media part，在 model view 中加入短稳定引用行；
 * 2. 当前 resolved model 原生支持 IMAGE 输入时保留 Image part（NATIVE），否则仅保留引用文本（REFERENCE_ONLY）；
 * 3. model view 确实存在 reference-only Image 时，在最后一条消息尾部追加一次 request-scoped capability hint。
 *
 * 禁止：调用附件识别模型、读写分析缓存、写回 Conversation、因模型不能看图抛 turn-level failure。
 */
class AttachmentProjectionTransformer(
    private val artifactStore: ArtifactStore,
) : InputMessageTransformer {
    companion object {
        /** B/C 共用同一提示；A 不注入。 */
        const val CAPABILITY_HINT =
            "Attached images are not directly visible in this run. Do not infer visual details from attachment references alone."

        /** 未解析出 stable ref 的 Image 在 reference-only 模式下的退化占位。 */
        private const val IMAGE_PLACEHOLDER = "[Image]"
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val nativeImageSupported = ctx.model.inputModalities.contains(Modality.IMAGE)
        var sawReferenceOnlyImage = false

        val projected = messages.map { message ->
            message.copy(
                parts = projectParts(
                    parts = message.parts,
                    nativeImageSupported = nativeImageSupported,
                    artifactStore = artifactStore,
                    filesDir = ctx.context.filesDir,
                    onReferenceOnlyImage = { sawReferenceOnlyImage = true },
                ),
            )
        }

        if (!nativeImageSupported && sawReferenceOnlyImage && projected.isNotEmpty()) {
            val last = projected.last()
            return projected.dropLast(1) +
                last.copy(parts = last.parts + UIMessagePart.Text(CAPABILITY_HINT))
        }
        return projected
    }

    private suspend fun projectParts(
        parts: List<UIMessagePart>,
        nativeImageSupported: Boolean,
        artifactStore: ArtifactStore,
        filesDir: File,
        onReferenceOnlyImage: () -> Unit,
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
                        onReferenceOnlyImage = onReferenceOnlyImage,
                    ),
                )

                is UIMessagePart.Image -> {
                    val ref = AttachmentRefs.getRef(part)
                    if (nativeImageSupported) {
                        ref?.let { refValue ->
                            result += UIMessagePart.Text(
                                attachmentRefLine(refValue, "image", displayNameOf(part, artifactStore, filesDir)),
                            )
                        }
                        result += part
                    } else {
                        onReferenceOnlyImage()
                        if (ref != null) {
                            result += UIMessagePart.Text(
                                attachmentRefLine(refValue = ref, type = "image", displayName = displayNameOf(part, artifactStore, filesDir)),
                            )
                        } else {
                            result += UIMessagePart.Text(IMAGE_PLACEHOLDER)
                        }
                    }
                }

                is UIMessagePart.Document -> {
                    AttachmentRefs.getRef(part)?.let { ref ->
                        result += UIMessagePart.Text(
                            attachmentRefLine(ref, "document", displayNameOf(part, artifactStore, filesDir)),
                        )
                    }
                    result += part
                }

                is UIMessagePart.Audio -> {
                    AttachmentRefs.getRef(part)?.let { ref ->
                        result += UIMessagePart.Text(
                            attachmentRefLine(ref, "audio", displayNameOf(part, artifactStore, filesDir)),
                        )
                    }
                    result += part
                }

                is UIMessagePart.Video -> {
                    AttachmentRefs.getRef(part)?.let { ref ->
                        result += UIMessagePart.Text(
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

/** `[Attachment ref=attachment:8f2... type=image name="screenshot.png"]` — mime/path 不进入 manifest。 */
internal fun attachmentRefLine(refValue: String, type: String, displayName: String): String =
    "[Attachment ref=$refValue type=$type name=\"$displayName\"]"

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
