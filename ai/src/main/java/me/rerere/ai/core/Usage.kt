package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProviderUsageSnapshot(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val toolUseInputTokens: Long? = null,
    val totalTokens: Long? = null,
    val canDeriveTotalFromInputAndOutput: Boolean = false,
)

@Serializable
enum class UsageCompleteness {
    LEGACY,
    NONE,
    PARTIAL,
    COMPLETE,
}

@Serializable
data class TokenUsage(
    @SerialName("promptTokens")
    val inputTokens: Long? = null,
    @SerialName("completionTokens")
    val outputTokens: Long? = null,
    @SerialName("cachedTokens")
    val cacheReadInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
    val toolUseInputTokens: Long? = null,
    val totalTokens: Long? = null,
    val latestRequestContextTokens: Long? = null,
    val latestRequestCacheReadInputTokens: Long? = null,
    val observedProviderRequestCount: Int? = null,
    val observedUsageReportedRequestCount: Int? = null,
    val providerRequestDurationMillis: Long? = null,
    val initialRequestTimeToFirstOutputMillis: Long? = null,
    val coreCompleteness: UsageCompleteness = UsageCompleteness.LEGACY,
    val cacheReadCompleteness: UsageCompleteness = UsageCompleteness.LEGACY,
    val semanticsVersion: Int = LEGACY_TOKEN_USAGE_SEMANTICS_VERSION,
)

const val LEGACY_TOKEN_USAGE_SEMANTICS_VERSION = 1
const val CURRENT_TOKEN_USAGE_SEMANTICS_VERSION = 2

internal fun sumTokenCountsOrNull(vararg counts: Long?): Long? {
    if (counts.any { it == null }) return null
    return try {
        counts.fold(0L) { total, count -> Math.addExact(total, requireNotNull(count)) }
    } catch (_: ArithmeticException) {
        null
    }
}
