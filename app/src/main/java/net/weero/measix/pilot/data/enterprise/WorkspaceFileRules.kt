package net.weero.measix.pilot.data.enterprise

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** The Client API uses logical relative paths; encoding belongs only to the URL builder. */
internal object WorkspaceFileRules {
    const val MEDIA_LIMIT = 4L * 1024 * 1024 * 1024
    const val TEXT_LIMIT = 2 * 1024 * 1024
    const val PREVIEW_LIMIT = 24L * 1024 * 1024
    private val aliases = Regex("%2e|%2f|%5c|%25|%00", RegexOption.IGNORE_CASE)

    fun path(value: String, rootAllowed: Boolean = false): String {
        require(value.isNotEmpty() || rootAllowed) { "workspace_root_not_allowed" }
        require(value.toByteArray(Charsets.UTF_8).size <= 4096 &&
            value.none { it in "\\\u0000\r\n" } && !aliases.containsMatchIn(value) &&
            (value.isEmpty() || value.split('/').none {
                it.isEmpty() || it == "." || it == ".." || it.startsWith(".agent-space")
            })) { "invalid_workspace_path" }
        return value
    }

    fun child(parent: String, name: String): String {
        path(parent, rootAllowed = true)
        require(name.isNotEmpty() && '/' !in name) { "invalid_workspace_name" }
        return path(if (parent.isEmpty()) name else "$parent/$name")
    }

    fun strongEtag(value: String?): Boolean = value != null &&
        value.toByteArray(Charsets.UTF_8).size in 2..1024 && value.startsWith('"') && value.endsWith('"') &&
        value.none { it in "<>[]()" } && value.substring(1, value.length - 1).none {
            it.code < 0x21 || it.code == 0x7f || it == '"'
        }

    fun requireEtag(value: String): String = value.also {
        require(strongEtag(it)) { "workspace_strong_etag_required" }
    }

    fun relativeImage(document: String, reference: String): String {
        path(document)
        require(!reference.startsWith('/') && '\\' !in reference && ':' !in reference &&
            '?' !in reference && '#' !in reference && !aliases.containsMatchIn(reference)) {
            "invalid_workspace_image_reference"
        }
        val decoded = decodeImageReference(reference)
        require(!decoded.startsWith('/') && '\\' !in decoded && ':' !in decoded && '?' !in decoded && '#' !in decoded) {
            "invalid_workspace_image_reference"
        }
        val segments = document.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty).toMutableList()
        decoded.split('/').forEach {
            when (it) {
                "." -> Unit
                ".." -> { require(segments.isNotEmpty()) { "workspace_image_outside_root" }; segments.removeAt(segments.lastIndex) }
                else -> { require(it.isNotEmpty()); segments += it }
            }
        }
        return path(segments.joinToString("/"))
    }

    /** Markdown destinations are URLs. HTTP callers still pass logical paths without decoding them. */
    private fun decodeImageReference(value: String): String = try {
        val input = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        val bytes = ByteArray(input.remaining())
        var count = 0
        while (input.hasRemaining()) {
            val next = input.get()
            if (next == '%'.code.toByte()) {
                require(input.remaining() >= 2) { "invalid_workspace_image_reference" }
                val high = input.get().toInt().toChar().digitToIntOrNull(16)
                val low = input.get().toInt().toChar().digitToIntOrNull(16)
                require(high != null && low != null) { "invalid_workspace_image_reference" }
                bytes[count++] = ((high shl 4) or low).toByte()
            } else bytes[count++] = next
        }
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("invalid_workspace_image_reference", error)
    }
}

/** Exactly one leading BOM is metadata. A second U+FEFF is real editable content. */
internal data class WorkspaceText(val text: String, val bom: Boolean, val newline: String) {
    fun encode(edited: String): ByteArray {
        require('\u0000' !in edited) { "workspace_binary_text" }
        val normalized = edited.replace("\r\n", "\n").replace('\r', '\n')
        val body = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(normalized.replace("\n", newline)))
        val result = ByteArray(body.remaining() + if (bom) 3 else 0)
        require(result.size <= WorkspaceFileRules.TEXT_LIMIT) { "workspace_text_limit" }
        if (bom) { result[0] = 0xef.toByte(); result[1] = 0xbb.toByte(); result[2] = 0xbf.toByte() }
        body.get(result, if (bom) 3 else 0, body.remaining())
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): WorkspaceText {
            require(bytes.size <= WorkspaceFileRules.TEXT_LIMIT) { "workspace_text_limit" }
            val bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
            val offset = if (bom) 3 else 0
            val raw = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString() }
            catch (error: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("workspace_invalid_utf8", error) }
            require('\u0000' !in raw) { "workspace_binary_text" }
            val endings = Regex("\r\n|\r|\n").findAll(raw).map { it.value }.toSet()
            require(endings.size <= 1) { "workspace_mixed_newlines" }
            return WorkspaceText(raw.replace("\r\n", "\n").replace('\r', '\n'), bom, endings.firstOrNull() ?: "\n")
        }
    }
}
