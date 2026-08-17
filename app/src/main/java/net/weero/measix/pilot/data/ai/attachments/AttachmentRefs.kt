package net.weero.measix.pilot.data.ai.attachments

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonPrimitiveOrNull
import net.weero.measix.pilot.data.model.Conversation
import java.io.File
import java.net.URI
import kotlin.uuid.Uuid

/**
 * Stable attachment handles stored in [UIMessagePart.metadata].
 *
 * The prefix and metadata key live here so callers do not scatter string literals.
 */
object AttachmentRefs {
    const val PREFIX = "attachment:"
    const val METADATA_KEY = "attachment_ref"

    fun format(id: Uuid): String = "$PREFIX$id"

    fun parse(raw: String): Uuid? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        return runCatching { Uuid.parse(trimmed.substring(PREFIX.length)) }.getOrNull()
    }

    fun getRef(part: UIMessagePart): String? =
        part.metadata?.get(METADATA_KEY)?.jsonPrimitiveOrNull?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun isMultimedia(part: UIMessagePart): Boolean = when (part) {
        is UIMessagePart.Image,
        is UIMessagePart.Document,
        is UIMessagePart.Audio,
        is UIMessagePart.Video,
        -> true
        else -> false
    }

    fun mergeMetadata(existing: JsonObject?, extra: Map<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        val map = existing?.toMutableMap() ?: mutableMapOf()
        map.putAll(extra)
        return JsonObject(map)
    }

    fun withMetadata(part: UIMessagePart, metadata: JsonObject): UIMessagePart = when (part) {
        is UIMessagePart.Text -> part.copy(metadata = metadata)
        is UIMessagePart.Image -> part.copy(metadata = metadata)
        is UIMessagePart.Document -> part.copy(metadata = metadata)
        is UIMessagePart.Audio -> part.copy(metadata = metadata)
        is UIMessagePart.Video -> part.copy(metadata = metadata)
        is UIMessagePart.Reasoning -> part.copy(metadata = metadata)
        is UIMessagePart.Tool -> part.copy(metadata = metadata)
    }

    /**
     * Stamp [METADATA_KEY] when missing. Existing refs and non-media parts are unchanged
     * (same instance), so callers can use identity to detect writes.
     *
     * 只有能被 [parse] 解析的 ref 才被视为已存在；导入/旧数据/异常 Provider metadata 里
     * 的非法值会被重建为合法 UUID，避免模型拿到永远无法解析的 handle。
     */
    fun ensureAttachmentRef(part: UIMessagePart): UIMessagePart {
        if (!isMultimedia(part)) return part
        val existing = getRef(part)?.takeIf { raw -> parse(raw) != null }
        if (existing != null) return part
        val merged = mergeMetadata(
            part.metadata,
            mapOf(METADATA_KEY to JsonPrimitive(format(Uuid.random()))),
        )
        return withMetadata(part, merged)
    }

    fun walkParts(parts: List<UIMessagePart>): Sequence<UIMessagePart> = sequence {
        for (part in parts) {
            yield(part)
            if (part is UIMessagePart.Tool) {
                yieldAll(walkParts(part.output))
            }
        }
    }

    fun walkMessageParts(messages: List<UIMessage>): Sequence<UIMessagePart> = sequence {
        for (message in messages) {
            yieldAll(walkParts(message.parts))
        }
    }

    fun backfillParts(parts: List<UIMessagePart>): List<UIMessagePart> {
        var changed = false
        val mapped = parts.map { part ->
            when (part) {
                is UIMessagePart.Tool -> {
                    val newOutput = backfillParts(part.output)
                    if (newOutput !== part.output) {
                        changed = true
                        part.copy(output = newOutput)
                    } else {
                        part
                    }
                }
                else -> {
                    val stamped = ensureAttachmentRef(part)
                    if (stamped !== part) {
                        changed = true
                        stamped
                    } else {
                        part
                    }
                }
            }
        }
        return if (changed) mapped else parts
    }

    fun backfillMessages(messages: List<UIMessage>): List<UIMessage> {
        var changed = false
        val mapped = messages.map { message ->
            val newParts = backfillParts(message.parts)
            if (newParts !== message.parts) {
                changed = true
                message.copy(parts = newParts)
            } else {
                message
            }
        }
        return if (changed) mapped else messages
    }

    fun backfillConversation(conversation: Conversation): Conversation {
        var changed = false
        val newNodes = conversation.messageNodes.map { node ->
            val newMessages = backfillMessages(node.messages)
            if (newMessages !== node.messages) {
                changed = true
                node.copy(messages = newMessages)
            } else {
                node
            }
        }
        return if (changed) conversation.copy(messageNodes = newNodes) else conversation
    }

    fun fileToFileUrl(file: File): String {
        val path = file.absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    fun parseFileUrl(url: String): File? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("file:", ignoreCase = true)) return null
        return runCatching {
            val uri = URI(trimmed)
            val path = uri.path
            when {
                !path.isNullOrBlank() -> File(path)
                else -> File(trimmed.removePrefix("file://").removePrefix("file:"))
            }
        }.getOrNull()
    }
}
