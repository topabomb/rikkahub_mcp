package net.weero.measix.pilot.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.FilesManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Model-view only: inserts a short handle line in front of each stamped multimedia part.
 * Must not be persisted back into the Conversation.
 */
object AttachmentRefHintTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val filesManager = runCatching { get<FilesManager>() }.getOrNull()
        return insertAttachmentRefHintsInMessages(messages) { part ->
            resolveDisplayName(part, filesManager, ctx.context.filesDir)
        }
    }
}

internal suspend fun insertAttachmentRefHintsInMessages(
    messages: List<UIMessage>,
    displayNameOf: suspend (UIMessagePart) -> String,
): List<UIMessage> {
    return messages.map { message ->
        message.copy(parts = insertAttachmentRefHintsInParts(message.parts, displayNameOf))
    }
}

internal suspend fun insertAttachmentRefHintsInParts(
    parts: List<UIMessagePart>,
    displayNameOf: suspend (UIMessagePart) -> String,
): List<UIMessagePart> {
    val result = ArrayList<UIMessagePart>(parts.size * 2)
    for (part in parts) {
        when (part) {
            is UIMessagePart.Tool -> {
                result += part.copy(output = insertAttachmentRefHintsInParts(part.output, displayNameOf))
            }
            is UIMessagePart.Image,
            is UIMessagePart.Document,
            is UIMessagePart.Audio,
            is UIMessagePart.Video,
            -> {
                val ref = AttachmentRefs.getRef(part)
                if (ref != null) {
                    result += UIMessagePart.Text(attachmentHintText(ref, displayNameOf(part)))
                }
                result += part
            }
            else -> result += part
        }
    }
    return result
}

internal fun attachmentHintText(ref: String, displayName: String): String =
    "[Attachment $ref, $displayName]"

private suspend fun resolveDisplayName(
    part: UIMessagePart,
    filesManager: FilesManager?,
    filesDir: java.io.File,
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
            if (relative != null && filesManager != null) {
                val entity = filesManager.getByRelativePath(relative)
                val display = entity?.displayName?.trim().orEmpty()
                if (display.isNotEmpty()) return display
            }
            if (file.name.isNotBlank()) return file.name
        }
    }
    return url.substringAfterLast('/').substringBefore('?').ifBlank { "attachment" }
}
