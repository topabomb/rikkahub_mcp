package net.weero.measix.pilot.data.ai.subassistant

/** `assistant_call` 的 `runtime_error.detail` 字符上限，避免撑满 Caller 上下文。 */
internal const val RUNTIME_ERROR_DETAIL_MAX_CHARS = 8 * 1024

private const val MAX_CAUSE_DEPTH = 6
private const val MAX_MESSAGE_CHARS = 2 * 1024
private const val MAX_HEAD_FRAMES = 4
private const val MAX_TOTAL_FRAMES = 16

private val APP_FRAME_PREFIXES = arrayOf(
    "net.weero.",
    "me.rerere.",
)

private val NOISY_FRAME_PREFIXES = arrayOf(
    "kotlin.coroutines.",
    "kotlinx.coroutines.",
    "jdk.internal.",
    "java.lang.reflect.",
    "java.lang.invoke.",
    "sun.reflect.",
    "dalvik.system.",
    "android.os.Handler",
    "android.os.Looper",
    "android.app.ActivityThread",
    "com.android.internal.",
)

/**
 * 把未分类异常提炼成给 Caller 看的 `detail`：类型、消息、因果链和精简堆栈。
 */
internal fun formatRuntimeErrorDetail(
    error: Throwable,
    maxChars: Int = RUNTIME_ERROR_DETAIL_MAX_CHARS,
): String {
    val seen = HashSet<Throwable>()
    val lines = ArrayList<String>()
    var current: Throwable? = error
    var depth = 0
    while (current != null && seen.add(current) && depth < MAX_CAUSE_DEPTH) {
        val prefix = if (depth == 0) "" else "Caused by: "
        lines += prefix + formatThrowableHeadline(current)
        lines.addAll(selectStackFrames(current.stackTrace))
        current = current.cause
        depth++
    }
    return clipRuntimeErrorDetail(lines.joinToString("\n"), maxChars)
}

internal fun clipRuntimeErrorDetail(
    text: String,
    maxChars: Int = RUNTIME_ERROR_DETAIL_MAX_CHARS,
): String {
    val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (cleaned.isEmpty() || maxChars <= 0) return ""
    if (cleaned.length <= maxChars) return cleaned
    val ellipsis = "\n…"
    if (maxChars <= ellipsis.length) return "…"
    val budget = maxChars - ellipsis.length
    val newline = cleaned.lastIndexOf('\n', budget)
    val cut = if (newline >= 16) newline else budget
    return cleaned.substring(0, cut).trimEnd() + ellipsis
}

private fun formatThrowableHeadline(error: Throwable): String {
    val name = error::class.java.simpleName.ifBlank { error::class.java.name }
    val message = error.message?.trim().orEmpty()
    if (message.isEmpty()) return name
    if (message.length <= MAX_MESSAGE_CHARS) return "$name: $message"
    return "$name: ${message.substring(0, MAX_MESSAGE_CHARS).trimEnd()}…"
}

private fun selectStackFrames(frames: Array<StackTraceElement>): List<String> {
    if (frames.isEmpty()) return emptyList()
    val picked = ArrayList<StackTraceElement>(MAX_TOTAL_FRAMES)
    var headTaken = 0
    for (frame in frames) {
        if (picked.size >= MAX_TOTAL_FRAMES) break
        val noisy = isNoisyFrame(frame)
        if (headTaken < MAX_HEAD_FRAMES && !noisy) {
            picked += frame
            headTaken++
            continue
        }
        if (isAppFrame(frame) && frame !in picked) {
            picked += frame
        }
    }
    if (picked.isEmpty()) {
        frames.take(6).forEach { picked += it }
    }
    return picked.map { "\tat $it" }
}

private fun isAppFrame(frame: StackTraceElement): Boolean {
    val name = frame.className
    return APP_FRAME_PREFIXES.any { name.startsWith(it) }
}

private fun isNoisyFrame(frame: StackTraceElement): Boolean {
    val name = frame.className
    return NOISY_FRAME_PREFIXES.any { name.startsWith(it) }
}
