package net.weero.measix.pilot.utils

import android.util.Log
import me.rerere.ai.core.ToolErrorProtocol

private val providerCredential = Regex("""(?<![A-Za-z0-9_-])(?:sk-|xai-)[A-Za-z0-9_-]{8,}""")
private val embeddedImage = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{32,}""", RegexOption.IGNORE_CASE)

/** Full UI diagnostics retain the Provider summary's credential and embedded-payload protection. */
internal fun redactDiagnosticSecrets(text: String): String = ToolErrorProtocol.redactSecrets(text)
    .replace(providerCredential, "…")
    .replace(embeddedImage, "…")

/** Redact before splitting; native log entries cannot hold a full stack or long Unicode message. */
internal fun logDiagnosticFailure(tag: String, message: String, error: Throwable) {
    val diagnostic = redactDiagnosticSecrets("$message\n${error.stackTraceToString()}")
    var start = 0
    while (start < diagnostic.length) {
        // At most 3 KB of UTF-8, leaving native payload space for the tag and entry metadata.
        var end = minOf(start + 1000, diagnostic.length)
        if (end < diagnostic.length && diagnostic[end - 1].isHighSurrogate() && diagnostic[end].isLowSurrogate()) {
            end--
        }
        Log.e(tag, diagnostic.substring(start, end))
        start = end
    }
}

/** Keeps exception identity, causes and cleanup failures while removing credential-like values. */
internal fun Throwable.userVisibleDiagnostic(): String {
    val lines = mutableListOf<String>()
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val pending = java.util.ArrayDeque<Pair<String, Throwable>>()
    pending.addLast("" to this)
    while (pending.isNotEmpty()) {
        val (relationship, error) = pending.removeLast()
        if (!visited.add(error)) continue
        val type = error::class.simpleName ?: error.javaClass.name
        val message = error.message?.trim()?.takeIf(String::isNotEmpty)?.let(::redactDiagnosticSecrets)
        lines += relationship + if (message == null || message == type) type else "$type: $message"
        error.suppressed.reversed().forEach { pending.addLast("Suppressed: " to it) }
        error.cause?.let { pending.addLast("Caused by: " to it) }
    }
    return lines.joinToString("\n")
}
