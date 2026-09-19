package net.weero.measix.pilot.utils

private val sensitiveHeaders = Regex(
    pattern = "(?im)\\b(authorization|cookie|set-cookie)(\\s*[:=]\\s*)[^\\r\\n]+",
)
private val sensitiveAssignments = Regex(
    pattern = "(?i)([\\\"']?(?:api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|token|password|secret)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\\"'\\s,;&]+)",
)

/** Keeps actionable exception identity while removing credential-like values from user-visible text. */
internal fun Throwable.userVisibleDiagnostic(): String {
    val lines = mutableListOf<String>()
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && lines.size < 4 && visited.add(current)) {
        val type = current::class.simpleName ?: current.javaClass.name
        val message = current.message?.trim()?.takeIf(String::isNotEmpty)?.redactDiagnosticSecrets()
        val line = if (message == null || message == type) type else "$type: $message"
        if (lines.lastOrNull() != line) lines += line
        current = current.cause
    }
    return lines.joinToString(separator = "\nCaused by: ")
}

internal fun String.redactDiagnosticSecrets(): String {
    val withoutHeaders = sensitiveHeaders.replace(this) { match ->
        match.groupValues[1] + match.groupValues[2] + "<redacted>"
    }
    return sensitiveAssignments.replace(withoutHeaders) { match ->
        match.groupValues[1] + "<redacted>"
    }
}
