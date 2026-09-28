package net.weero.measix.pilot.utils

import me.rerere.ai.core.ToolErrorProtocol

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
        val message = error.message?.trim()?.takeIf(String::isNotEmpty)?.let(ToolErrorProtocol::redactSecrets)
        lines += relationship + if (message == null || message == type) type else "$type: $message"
        error.suppressed.reversed().forEach { pending.addLast("Suppressed: " to it) }
        error.cause?.let { pending.addLast("Caused by: " to it) }
    }
    return lines.joinToString("\n")
}
