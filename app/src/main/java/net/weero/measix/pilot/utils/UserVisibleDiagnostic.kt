package net.weero.measix.pilot.utils

import me.rerere.ai.core.ToolErrorProtocol

/** Keeps actionable exception identity while removing credential-like values from user-visible text. */
internal fun Throwable.userVisibleDiagnostic(): String {
    val lines = mutableListOf<String>()
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && lines.size < 4 && visited.add(current)) {
        val type = current::class.simpleName ?: current.javaClass.name
        val message = current.message?.trim()?.takeIf(String::isNotEmpty)?.let(ToolErrorProtocol::redactSecrets)
        val line = if (message == null || message == type) type else "$type: $message"
        if (lines.lastOrNull() != line) lines += line
        current = current.cause
    }
    return lines.joinToString(separator = "\nCaused by: ")
}
