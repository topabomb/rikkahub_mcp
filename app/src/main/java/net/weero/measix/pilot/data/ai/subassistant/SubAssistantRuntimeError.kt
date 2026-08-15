package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException

/** `assistant_call` 的 `detail` 字符上限，避免撑满 Caller 上下文。 */
internal const val RUNTIME_ERROR_DETAIL_MAX_CHARS = 8 * 1024

internal const val SUB_ASSISTANT_REASON_CONTENT_BLOCKED = "content_blocked"
internal const val SUB_ASSISTANT_REASON_PROVIDER_ERROR = "provider_error"
internal const val SUB_ASSISTANT_REASON_RUNTIME_ERROR = "runtime_error"

internal const val USER_FACING_ERROR_SUMMARY_MAX_CHARS = 240
internal const val CONTENT_BLOCKED_MODEL_DETAIL =
    "The image or text request was blocked by the model usage policy. Rephrase without prohibited content."

private const val MAX_CAUSE_DEPTH = 6
private const val MAX_MESSAGE_CHARS = 2 * 1024

private val CONTENT_POLICY_MARKERS = arrayOf(
    "usage guidelines",
    "safety_check",
    "safety check",
    "content violates",
    "violates usage",
    "csam",
    "child sexual",
    "prohibited content",
    "content policy",
    "content_policy",
    "content_filter",
    "content filter",
    "safety system",
    "blocked by our safety",
    "blocked by the safety",
    "failed check: safety",
    "prompt feedback",
    "blockreason",
    "block reason",
    "finishreason: safety",
    "finish reason: safety",
    "blockedreason",
)

private val HTTP_FAILURE_MARKERS = arrayOf(
    "httpexception",
    "failed to get response",
    "unknown error:",
    "rate limit",
    "too many requests",
    "service unavailable",
    "bad gateway",
    "gateway timeout",
)

private val OBFUSCATED_TYPE_PREFIX = Regex("^[A-Za-z][A-Za-z0-9\$_]{0,3}:\\s+")
private val JAVA_TYPE_PREFIX = Regex("^(?:[A-Za-z_][\\w.$]*\\.)?[A-Za-z_][\\w$]*Exception:\\s+")

/**
 * 把失败提炼成给 Caller 和用户摘要共用的单行 `detail`：类型 + 消息。
 * 分类仍会遍历因果链；文本本身不附带因果链或堆栈。包装了 [HttpException] 时用它作为代表，避免丢掉 HTTP 状态。
 */
internal fun formatRuntimeErrorDetail(
    error: Throwable,
    maxChars: Int = RUNTIME_ERROR_DETAIL_MAX_CHARS,
): String {
    val representative = generateSequence(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .firstOrNull { it is HttpException }
        ?: error
    return clipRuntimeErrorDetail(formatThrowableHeadline(representative), maxChars)
}

internal fun collapseRuntimeErrorText(text: String): String =
    text.replace(Regex("[\\t\\r\\n]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

internal fun clipRuntimeErrorDetail(
    text: String,
    maxChars: Int = RUNTIME_ERROR_DETAIL_MAX_CHARS,
): String {
    val cleaned = collapseRuntimeErrorText(text)
    if (cleaned.isEmpty() || maxChars <= 0) return ""
    if (cleaned.length <= maxChars) return cleaned
    if (maxChars <= 1) return "…"
    return takeHeadByCodePoints(cleaned, maxChars - 1).trimEnd() + "…"
}

internal fun classifySubAssistantFailure(error: Throwable): String {
    val texts = collectFailureTexts(error)
    if (texts.any(::looksLikeContentPolicy)) return SUB_ASSISTANT_REASON_CONTENT_BLOCKED
    if (generateSequence(error) { it.cause }.any { it is HttpException }) {
        return SUB_ASSISTANT_REASON_PROVIDER_ERROR
    }
    if (texts.any(::looksLikeHttpFailure)) return SUB_ASSISTANT_REASON_PROVIDER_ERROR
    return SUB_ASSISTANT_REASON_RUNTIME_ERROR
}

internal fun modelVisibleFailureDetail(
    reason: String,
    error: Throwable,
): String = if (reason == SUB_ASSISTANT_REASON_CONTENT_BLOCKED) {
    CONTENT_BLOCKED_MODEL_DETAIL
} else {
    formatRuntimeErrorDetail(error)
}

internal fun shouldAttachFailureDetail(reason: String?): Boolean = when (reason) {
    SUB_ASSISTANT_REASON_CONTENT_BLOCKED,
    SUB_ASSISTANT_REASON_PROVIDER_ERROR,
    SUB_ASSISTANT_REASON_RUNTIME_ERROR,
    -> true
    else -> false
}

internal data class SubAssistantToolResultFields(
    val status: String? = null,
    val reason: String? = null,
    val content: String? = null,
    val detail: String? = null,
)

internal fun parseSubAssistantToolResultFields(
    tool: UIMessagePart.Tool,
    json: Json,
): SubAssistantToolResultFields {
    val text = tool.output.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (text.isEmpty()) return SubAssistantToolResultFields()
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return SubAssistantToolResultFields()
    return SubAssistantToolResultFields(
        status = obj["status"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        reason = obj["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        content = obj["content"]?.jsonPrimitive?.contentOrNull,
        detail = obj["detail"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun parseRuntimeErrorDetailFromToolOutput(
    tool: UIMessagePart.Tool,
    json: Json,
): String? = parseSubAssistantToolResultFields(tool, json).detail

internal fun fallbackSubAssistantOutputText(
    fields: SubAssistantToolResultFields,
    localizedReason: String?,
    localizedContentBlocked: String,
    rawOutput: String,
): String? {
    val errorBody = resolveSubAssistantErrorBody(
        reason = fields.reason,
        detail = fields.detail,
        localizedContentBlocked = localizedContentBlocked,
    )
    if (!fields.reason.isNullOrBlank()) {
        val reasonText = localizedReason?.takeIf { it.isNotBlank() } ?: fields.reason
        return if (!errorBody.isNullOrBlank() && errorBody != reasonText) {
            "$reasonText\n$errorBody"
        } else {
            reasonText
        }
    }
    if (fields.status == "completed") {
        return fields.content?.trim()?.takeIf { it.isNotEmpty() } ?: fields.status
    }
    if (!fields.status.isNullOrBlank()) return fields.status
    return rawOutput.trim().take(200).takeIf { it.isNotEmpty() }
}

internal fun userFacingRuntimeErrorSummary(
    detail: String?,
    maxChars: Int = USER_FACING_ERROR_SUMMARY_MAX_CHARS,
): String? {
    val firstLine = firstUsefulDetailLine(detail) ?: return null
    if (looksLikeContentPolicy(firstLine)) return null
    val stripped = stripExceptionTypePrefix(firstLine)
    if (stripped.isBlank() || looksLikeContentPolicy(stripped)) return null
    return takeHeadByCodePoints(stripped, maxChars).trim().takeIf { it.isNotEmpty() }
}

internal fun looksLikeContentPolicy(text: String): Boolean {
    val normalized = text.lowercase()
    return CONTENT_POLICY_MARKERS.any { it in normalized }
}

internal fun resolveSubAssistantErrorBody(
    reason: String?,
    detail: String?,
    localizedContentBlocked: String,
): String? {
    if (reason == SUB_ASSISTANT_REASON_CONTENT_BLOCKED || looksLikeContentPolicy(detail.orEmpty())) {
        return localizedContentBlocked
    }
    return userFacingRuntimeErrorSummary(detail)
}

private fun collectFailureTexts(error: Throwable): List<String> {
    val seen = HashSet<Throwable>()
    val texts = ArrayList<String>()
    var current: Throwable? = error
    var depth = 0
    while (current != null && seen.add(current) && depth < MAX_CAUSE_DEPTH) {
        current.message?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        texts += current::class.java.simpleName
        texts += current::class.java.name
        current = current.cause
        depth++
    }
    return texts
}

private fun looksLikeHttpFailure(text: String): Boolean {
    val normalized = text.lowercase()
    return HTTP_FAILURE_MARKERS.any { it in normalized }
}

private fun firstUsefulDetailLine(detail: String?): String? {
    if (detail.isNullOrBlank()) return null
    return detail.replace("\r\n", "\n").replace('\r', '\n')
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            line.isNotEmpty() && !line.startsWith("at ") && !line.startsWith("Caused by:")
        }
}

private fun stripExceptionTypePrefix(line: String): String {
    return line
        .replace(JAVA_TYPE_PREFIX, "")
        .replace(OBFUSCATED_TYPE_PREFIX, "")
        .trim()
}

private fun formatThrowableHeadline(error: Throwable): String {
    val name = error::class.java.simpleName.ifBlank { error::class.java.name }
    val message = collapseRuntimeErrorText(error.message.orEmpty())
    if (message.isEmpty()) return name
    if (message.length <= MAX_MESSAGE_CHARS) return "$name: $message"
    return "$name: ${takeHeadByCodePoints(message, MAX_MESSAGE_CHARS).trimEnd()}…"
}
