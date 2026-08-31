package net.weero.measix.pilot.data.ai.attachments

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonPrimitiveOrNull
import net.weero.measix.pilot.data.model.MessageNode
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

    /** Returns the canonical stable handle, ignoring malformed provider/legacy metadata. */
    fun getStableRef(part: UIMessagePart): String? =
        getRef(part)?.let { raw -> parse(raw)?.let(::format) }

    /** Escapes values embedded in the model-visible attachment marker grammar. */
    fun escapeMarkerValue(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

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
     * Stamp [METADATA_KEY] when missing. Existing refs and non-media parts are returned unchanged
     * as an allocation optimization. Durable write protocols must use an explicit typed patch;
     * object identity is never a semantic change signal because projections may allocate
     * equivalent message instances.
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

    /** Plans exact metadata-only writes; the command path never accepts a replacement tree. */
    fun planBackfills(nodes: List<MessageNode>): List<AttachmentRefBackfill> = buildList {
        nodes.forEach { node ->
            node.messages.forEach { message ->
                collectBackfills(
                    nodeId = node.id,
                    messageId = message.id,
                    parts = message.parts,
                    parentPath = emptyList(),
                    destination = this,
                )
            }
        }
    }

    fun applyBackfills(
        nodes: List<MessageNode>,
        backfills: List<AttachmentRefBackfill>,
    ): List<MessageNode> = backfills.fold(nodes) { current, backfill ->
        val nodeIndex = current.indexOfFirst { it.id == backfill.nodeId }
        require(nodeIndex >= 0) { "attachment backfill node is missing: ${backfill.nodeId}" }
        val node = current[nodeIndex]
        val messageIndex = node.messages.indexOfFirst { it.id == backfill.messageId }
        require(messageIndex >= 0) { "attachment backfill message is missing: ${backfill.messageId}" }
        val message = node.messages[messageIndex]
        val updatedParts = applyBackfill(message.parts, backfill.partPath, backfill.attachmentRef)
        if (updatedParts === message.parts) {
            current
        } else {
            current.toMutableList().apply {
                set(
                    nodeIndex,
                    node.copy(
                        messages = node.messages.toMutableList().apply {
                            set(messageIndex, message.copy(parts = updatedParts))
                        },
                    ),
                )
            }
        }
    }

    private fun collectBackfills(
        nodeId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
        parentPath: List<Int>,
        destination: MutableList<AttachmentRefBackfill>,
    ) {
        parts.forEachIndexed { index, part ->
            val path = parentPath + index
            if (part is UIMessagePart.Tool) {
                collectBackfills(nodeId, messageId, part.output, path, destination)
            } else if (isMultimedia(part) && getRef(part)?.let(::parse) == null) {
                destination += AttachmentRefBackfill(
                    nodeId = nodeId,
                    messageId = messageId,
                    partPath = path,
                    attachmentRef = format(Uuid.random()),
                )
            }
        }
    }

    private fun applyBackfill(
        parts: List<UIMessagePart>,
        path: List<Int>,
        attachmentRef: String,
    ): List<UIMessagePart> {
        require(path.isNotEmpty()) { "attachment backfill path is empty" }
        require(parse(attachmentRef) != null) { "attachment backfill ref is invalid" }
        val index = path.first()
        require(index in parts.indices) { "attachment backfill part is missing at $path" }
        val part = parts[index]
        val updated = if (path.size == 1) {
            require(isMultimedia(part)) { "attachment backfill target is not multimedia" }
            val existing = getRef(part)
            when {
                existing == attachmentRef -> part
                existing?.let(::parse) != null -> error("attachment backfill cannot overwrite a stable ref")
                else -> withMetadata(
                    part,
                    mergeMetadata(part.metadata, mapOf(METADATA_KEY to JsonPrimitive(attachmentRef))),
                )
            }
        } else {
            require(part is UIMessagePart.Tool) { "attachment backfill path crosses a non-tool part" }
            val output = applyBackfill(part.output, path.drop(1), attachmentRef)
            if (output === part.output) part else part.copy(output = output)
        }
        if (updated === part) return parts
        return parts.toMutableList().apply { set(index, updated) }
    }

    fun fileToFileUrl(file: File): String {
        val path = file.absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    fun parseFileUrl(url: String): File? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("file:", ignoreCase = true)) return null
        return runCatching {
            val withoutScheme = trimmed.substring("file:".length)
            val rawPath = when {
                withoutScheme.startsWith("//") -> withoutScheme.removePrefix("//")
                else -> withoutScheme
            }
            // Android uses absolute Unix paths; JVM/imported data may use file://C:/... or
            // file:///C:/.... Keep the drive letter instead of treating it as a root segment.
            val normalizedRawPath = if (
                rawPath.length >= 3 && rawPath[0] == '/' && rawPath[2] == ':'
            ) {
                rawPath.removePrefix("/")
            } else {
                rawPath
            }
            val parsedUri = runCatching { URI(trimmed) }.getOrNull()
            // URI.path is decoded (unlike the raw fallback) and therefore preserves spaces
            // and other escaped characters in imported cross-platform file URLs. A Windows
            // drive may be parsed as the URI authority (`file://C:/...`), so put it back.
            val uriPath = parsedUri?.path.orEmpty()
            val authority = parsedUri?.rawAuthority.orEmpty()
            val path = when {
                authority.length == 2 && authority[1] == ':' -> authority + uriPath
                uriPath.length >= 3 && uriPath[0] == '/' && uriPath[2] == ':' ->
                    uriPath.substring(1)
                uriPath.isNotBlank() -> uriPath
                else -> normalizedRawPath
            }
            File(path)
        }.getOrNull()
    }

}

data class AttachmentRefBackfill(
    val nodeId: Uuid,
    val messageId: Uuid,
    val partPath: List<Int>,
    val attachmentRef: String,
)
