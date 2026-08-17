package me.rerere.ai.util

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

const val PROVIDER_FAILURE_DETAIL_MAX_CHARS = 480

const val CONTENT_BLOCKED_MODEL_DETAIL =
    "The image or text request was blocked by the model usage policy. Rephrase without prohibited content."

enum class ProviderFailureKind(val reason: String) {
    CONTENT_BLOCKED("content_blocked"),
    RATE_LIMITED("rate_limited"),
    QUOTA_EXHAUSTED("quota_exhausted"),
    AUTH_FAILED("auth_failed"),
    PERMISSION_DENIED("permission_denied"),
    INVALID_REQUEST("invalid_request"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    PROVIDER_ERROR("provider_error"),
    RUNTIME_ERROR("runtime_error"),
}

data class ClassifiedProviderFailure(
    val kind: ProviderFailureKind,
    val detail: String,
)

private const val MAX_CAUSE_DEPTH = 6

private val CONTENT_POLICY_CODES = setOf(
    "moderation_blocked",
    "content_policy_violation",
    "content_filter",
    "content_policy",
    "responsible_ai_policy_violation",
    "safety_check",
    "safety",
)

private val QUOTA_CODES = setOf(
    "insufficient_quota",
    "credit_balance_exhausted",
    "billing_hard_limit_reached",
    "billing_not_active",
    "organization_spend_limit_exceeded",
    "project_spend_limit_exceeded",
    "organization_usage_limit_exceeded",
    "spend_limit_exceeded",
    "usage_limit_exceeded",
)

private val RATE_LIMIT_CODES = setOf(
    "rate_limit_exceeded",
    "rate_limit_error",
    "too_many_requests",
    "insufficient_quota_for_model",
)

private val AUTH_CODES = setOf(
    "invalid_api_key",
    "invalid_authentication",
    "authentication_error",
    "unauthorized",
)

private val PERMISSION_CODES = setOf(
    "permission_denied",
    "country_not_supported",
    "access_denied",
    "forbidden",
)

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
    "moderation_blocked",
    "respect_moderation",
    "filtered by moderation",
    "image filtered by moderation",
    "not allowed by our safety",
    "rejected as a result of our safety",
    "rejected as a result of the safety",
)

private val QUOTA_MARKERS = arrayOf(
    "insufficient_quota",
    "insufficient quota",
    "exceeded your current quota",
    "credit balance exhausted",
    "no prepaid credits",
    "billing hard limit",
    "spend limit",
    "usage limit reached",
    "quota exceeded",
    "out of credits",
)

private val RATE_LIMIT_MARKERS = arrayOf(
    "rate limit",
    "rate_limit",
    "too many requests",
    "retry after",
    "retry-after",
    "images per min",
    "tokens per min",
    "requests per min",
    "resource has been exhausted",
    "resource_exhausted",
)

private val AUTH_MARKERS = arrayOf(
    "incorrect api key",
    "invalid api key",
    "invalid authentication",
    "invalid authorization",
    "missing authorization",
    "you must be a member of an organization",
    "api key provided",
)

private val PERMISSION_MARKERS = arrayOf(
    "does not have permission",
    "not allowed to use",
    "country, region, or territory not supported",
    "ip not authorized",
    "your team is blocked",
    "doesn't have permission",
    "does not have access",
)

private val UNAVAILABLE_MARKERS = arrayOf(
    "overloaded",
    "engine is currently overloaded",
    "service unavailable",
    "bad gateway",
    "gateway timeout",
    "temporarily unavailable",
    "internal server error",
    "slow down",
)

private val INVALID_REQUEST_MARKERS = arrayOf(
    "invalid_request_error",
    "invalid request",
    "invalid argument",
    "invalid_argument",
    "unprocessable",
    "unknown parameter",
    "unsupported value",
    "is not supported",
    "must be one of",
    "required property",
    "prompt is too long",
    "string too long",
    "invalid size",
    "model_not_found",
    "does not exist",
)

private val HTTP_STATUS_IN_MESSAGE = Regex(
    """(?:failed to get response|unknown error|failed to (?:generate|edit|download generated) image)\s*:\s*(\d{3})""",
    RegexOption.IGNORE_CASE,
)

private val SECRET_PATTERNS = listOf(
    Regex("""sk-[A-Za-z0-9_-]{8,}"""),
    Regex("""xai-[A-Za-z0-9_-]{8,}"""),
    Regex("""Bearer\s+[A-Za-z0-9._\-+=]+""", RegexOption.IGNORE_CASE),
    Regex("""data:image\/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\s]{32,}""", RegexOption.IGNORE_CASE),
)

fun looksLikeContentPolicy(text: String): Boolean {
    val normalized = text.lowercase()
    return CONTENT_POLICY_MARKERS.any { it in normalized }
}

fun classifyProviderFailure(
    error: Throwable,
    detailMaxChars: Int = PROVIDER_FAILURE_DETAIL_MAX_CHARS,
): ClassifiedProviderFailure {
    val signals = collectFailureSignals(error)
    val kind = decideKind(signals)
    return ClassifiedProviderFailure(
        kind = kind,
        detail = buildModelVisibleDetail(kind, signals, detailMaxChars),
    )
}

private data class FailureSignals(
    val statusCode: Int?,
    val errorCode: String?,
    val errorType: String?,
    val texts: List<String>,
    val classNames: List<String>,
    val hasHttpException: Boolean,
    val networkKind: NetworkKind?,
) {
    val haystack: String by lazy { (texts + classNames).joinToString("\n").lowercase() }
    val hasProviderSignal: Boolean
        get() = hasHttpException || statusCode != null || errorCode != null || errorType != null
}

private enum class NetworkKind {
    TIMEOUT,
    CONNECTIVITY,
}

private fun collectFailureSignals(error: Throwable): FailureSignals {
    val texts = ArrayList<String>()
    val classNames = ArrayList<String>()
    var statusCode: Int? = null
    var errorCode: String? = null
    var errorType: String? = null
    var hasHttpException = false
    var networkKind: NetworkKind? = null
    val seen = HashSet<Throwable>()
    var current: Throwable? = error
    var depth = 0
    while (current != null && seen.add(current) && depth < MAX_CAUSE_DEPTH) {
        if (current is HttpException) {
            hasHttpException = true
            if (statusCode == null) statusCode = current.statusCode
            if (errorCode == null) errorCode = current.errorCode?.trim()?.takeIf { it.isNotEmpty() }
            if (errorType == null) errorType = current.errorType?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (networkKind == null) {
            networkKind = when (current) {
                is SocketTimeoutException, is InterruptedIOException -> NetworkKind.TIMEOUT
                is UnknownHostException, is ConnectException, is SSLException -> NetworkKind.CONNECTIVITY
                else -> null
            }
        }
        current.message?.trim()?.takeIf { it.isNotEmpty() }?.let { message ->
            texts += message
            if (statusCode == null) {
                HTTP_STATUS_IN_MESSAGE.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                    statusCode = it
                }
            }
            if (errorCode == null || errorType == null) {
                embeddedJson(message)?.let(::parseProviderErrorEnvelope)?.let { envelope ->
                    if (errorCode == null) errorCode = envelope.code
                    if (errorType == null) errorType = envelope.type
                    envelope.message?.let(texts::add)
                }
            }
        }
        classNames += current::class.java.simpleName
        current = current.cause
        depth++
    }
    return FailureSignals(
        statusCode = statusCode,
        errorCode = errorCode?.lowercase(),
        errorType = errorType?.lowercase(),
        texts = texts,
        classNames = classNames,
        hasHttpException = hasHttpException,
        networkKind = networkKind,
    )
}

private fun decideKind(signals: FailureSignals): ProviderFailureKind {
    val code = signals.errorCode
    val type = signals.errorType
    val status = signals.statusCode
    if (code in CONTENT_POLICY_CODES ||
        type in CONTENT_POLICY_CODES ||
        looksLikeContentPolicy(signals.haystack)
    ) {
        return ProviderFailureKind.CONTENT_BLOCKED
    }
    if (code in QUOTA_CODES ||
        type == "insufficient_quota" ||
        (signals.hasProviderSignal && containsAny(signals.haystack, QUOTA_MARKERS))
    ) {
        return ProviderFailureKind.QUOTA_EXHAUSTED
    }
    if (code in RATE_LIMIT_CODES ||
        type == "rate_limit_error" ||
        (signals.hasProviderSignal && containsAny(signals.haystack, RATE_LIMIT_MARKERS)) ||
        (status == 429 && code !in QUOTA_CODES)
    ) {
        return ProviderFailureKind.RATE_LIMITED
    }
    if (code in AUTH_CODES ||
        type == "authentication_error" ||
        status == 401 ||
        (signals.hasProviderSignal && containsAny(signals.haystack, AUTH_MARKERS))
    ) {
        return ProviderFailureKind.AUTH_FAILED
    }
    if (code in PERMISSION_CODES ||
        type == "permission_error" ||
        status == 403 ||
        (signals.hasProviderSignal && containsAny(signals.haystack, PERMISSION_MARKERS))
    ) {
        return ProviderFailureKind.PERMISSION_DENIED
    }
    if (signals.networkKind == NetworkKind.TIMEOUT ||
        status in UNAVAILABLE_STATUSES ||
        (signals.hasProviderSignal && containsAny(signals.haystack, UNAVAILABLE_MARKERS))
    ) {
        return ProviderFailureKind.PROVIDER_UNAVAILABLE
    }
    if (status == 400 ||
        status == 422 ||
        type == "invalid_request_error" ||
        (signals.hasProviderSignal && containsAny(signals.haystack, INVALID_REQUEST_MARKERS))
    ) {
        return ProviderFailureKind.INVALID_REQUEST
    }
    if (signals.networkKind == NetworkKind.CONNECTIVITY) {
        return ProviderFailureKind.PROVIDER_UNAVAILABLE
    }
    if (signals.hasHttpException || status != null) {
        return ProviderFailureKind.PROVIDER_ERROR
    }
    if (containsAny(signals.haystack, arrayOf("httpexception", "failed to get response", "unknown error:"))) {
        return ProviderFailureKind.PROVIDER_ERROR
    }
    return ProviderFailureKind.RUNTIME_ERROR
}

private val UNAVAILABLE_STATUSES = setOf(408, 500, 502, 503, 504)

private fun buildModelVisibleDetail(
    kind: ProviderFailureKind,
    signals: FailureSignals,
    maxChars: Int,
): String {
    if (kind == ProviderFailureKind.CONTENT_BLOCKED) {
        return CONTENT_BLOCKED_MODEL_DETAIL
    }
    val extracted = signals.texts.firstNotNullOfOrNull { text ->
        val cleaned = sanitizeProviderDetail(text)
        cleaned.takeIf { it.isNotEmpty() && isHighValueDetail(it) }
    }
    if (!extracted.isNullOrEmpty()) {
        return clipProviderDetail(extracted, maxChars)
    }
    return cannedDetail(kind)
}

private fun cannedDetail(kind: ProviderFailureKind): String = when (kind) {
    ProviderFailureKind.CONTENT_BLOCKED -> CONTENT_BLOCKED_MODEL_DETAIL
    ProviderFailureKind.RATE_LIMITED ->
        "The image service rate-limited the request. Wait briefly and retry."
    ProviderFailureKind.QUOTA_EXHAUSTED ->
        "The image service has no remaining quota or credits. Ask the user to check billing."
    ProviderFailureKind.AUTH_FAILED ->
        "The image service rejected the API key. Ask the user to check provider settings."
    ProviderFailureKind.PERMISSION_DENIED ->
        "The image service denied access to this model or account."
    ProviderFailureKind.INVALID_REQUEST ->
        "The image service rejected the request parameters."
    ProviderFailureKind.PROVIDER_UNAVAILABLE ->
        "The image service is temporarily unavailable. Retry later."
    ProviderFailureKind.PROVIDER_ERROR ->
        "The image service returned an error."
    ProviderFailureKind.RUNTIME_ERROR ->
        "Image generation failed unexpectedly."
}

internal fun sanitizeProviderDetail(text: String): String {
    var cleaned = collapseProviderErrorText(redactProviderSecrets(text))
    cleaned = cleaned.replace(HTTP_STATUS_IN_MESSAGE, "")
    cleaned = cleaned.replace(Regex("""^Failed to (?:generate|edit|download generated) image:\s*""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""^(?:[A-Za-z_][\w.$]*\.)?[A-Za-z_][\w$]*Exception:\s+"""), "")
    cleaned = cleaned.replace(Regex("""^\d{3}\s+"""), "")
    return collapseProviderErrorText(cleaned)
}

internal fun redactProviderSecrets(text: String): String {
    var result = text
    SECRET_PATTERNS.forEach { pattern ->
        result = pattern.replace(result, "…")
    }
    return result
}

internal fun collapseProviderErrorText(text: String): String =
    text.replace(Regex("[\\t\\r\\n]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

internal fun clipProviderDetail(text: String, maxChars: Int = PROVIDER_FAILURE_DETAIL_MAX_CHARS): String {
    val cleaned = collapseProviderErrorText(text)
    if (cleaned.isEmpty() || maxChars <= 0) return ""
    if (cleaned.length <= maxChars) return cleaned
    if (maxChars <= 1) return "…"
    return takeHeadByCodePointCount(cleaned, maxChars - 1).trimEnd() + "…"
}

private fun isHighValueDetail(text: String): Boolean {
    if (text.length < 4) return false
    if (text.all { it.isDigit() || it.isWhitespace() }) return false
    if (looksLikeContentPolicy(text)) return false
    val letters = text.count { it.isLetter() }
    return letters >= 4
}

private fun containsAny(haystack: String, markers: Array<String>): Boolean =
    markers.any { it in haystack }

private fun embeddedJson(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}

private fun takeHeadByCodePointCount(text: String, maxCodePoints: Int): String {
    if (maxCodePoints <= 0 || text.isEmpty()) return ""
    val codePoints = text.codePoints().toArray()
    if (codePoints.size <= maxCodePoints) return text
    return String(codePoints, 0, maxCodePoints)
}
