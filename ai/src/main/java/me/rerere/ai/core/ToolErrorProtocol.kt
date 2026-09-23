package me.rerere.ai.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.IdentityHashMap

/** One bounded explanation for App-produced Tool errors; remote MCP content is a separate fact. */
object ToolErrorProtocol {
    const val MAX_DETAIL_CODE_POINTS = 128

    fun envelope(status: String, reason: String, detail: String? = null): JsonObject {
        require(status == "failed" || status == "unavailable" || status == "unknown")
        require(reason.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid Tool failure reason: $reason" }
        return buildJsonObject {
            put("status", status)
            put("reason", reason)
            detail?.takeIf(String::isNotBlank)?.let { put("detail", boundedDetail(it)) }
        }
    }

    fun boundedDetail(raw: String): String {
        val normalized = redactSecrets(raw).replace(Regex("\\s+"), " ").trim()
        require(normalized.isNotEmpty()) { "Tool failure detail must not be empty" }
        if (normalized.codePointCount(0, normalized.length) <= MAX_DETAIL_CODE_POINTS) return normalized
        val end = normalized.offsetByCodePoints(0, MAX_DETAIL_CODE_POINTS - 1)
        return normalized.substring(0, end) + "…"
    }

    fun exceptionDetail(error: Throwable): String {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cursor: Throwable? = error
        var selected = error
        while (cursor != null && visited.add(cursor)) {
            if (!cursor.message.isNullOrBlank()) selected = cursor
            cursor = cursor.cause
        }
        fun describe(value: Throwable): String {
            val type = value.javaClass.simpleName.ifBlank { value.javaClass.name }
            val message = value.message?.trim().orEmpty()
            return if (message.isBlank() || message == type) type else "$type: $message"
        }
        val root = describe(selected)
        val outerMessage = error.message?.trim().orEmpty()
        val outer = if (selected !== error && outerMessage.isNotBlank() &&
            outerMessage != selected.toString()) {
            " (from ${describe(error)})"
        } else ""
        return boundedDetail(root + outer)
    }

    fun redactSecrets(text: String): String {
        val withoutHeaders = SENSITIVE_HEADERS.replace(text) { match ->
            match.groupValues[1] + match.groupValues[2] + "<redacted>"
        }
        val withoutBearer = SENSITIVE_BEARER.replace(withoutHeaders) { match ->
            match.groupValues[1] + "<redacted>"
        }
        val withoutPassphrases = SENSITIVE_PASSPHRASES.replace(withoutBearer) { match ->
            match.groupValues[1] + "<redacted>"
        }
        return SENSITIVE_ASSIGNMENTS.replace(withoutPassphrases) { match ->
            match.groupValues[1] + "<redacted>"
        }
    }

    private val SENSITIVE_HEADERS = Regex("(?im)\\b(authorization|cookie|set-cookie)(\\s*[:=]\\s*)[^\\r\\n]+")
    private val SENSITIVE_BEARER = Regex("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/-]+")
    private val SENSITIVE_PASSPHRASES = Regex(
        "(?i)([\\\"']?(?:password|secret)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^,;&\\r\\n]+)",
    )
    private val SENSITIVE_ASSIGNMENTS = Regex(
        "(?i)([\\\"']?(?:api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|token)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\\"'\\s,;&]+)",
    )
}
