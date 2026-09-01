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
    /** 本 turn 已关闭 Provider 请求中 canonical input + output 的峰值。 */
    val peakRequestContextTokens: Long? = null,
    /** 最近一次已关闭 Provider 请求的 canonical input；不继承更早请求。 */
    val latestRequestContextTokens: Long? = null,
    /** 最近一次已关闭 Provider 请求的 output；不继承更早请求。 */
    val latestRequestOutputTokens: Long? = null,
    /** 最近一次已关闭 Provider 请求的 cache read；显式零保留为零。 */
    val latestRequestCacheReadInputTokens: Long? = null,
    /** 最近一次已关闭请求从首个模型输出到响应流关闭的输出阶段时间；用于 tok/s，不包含 TTFT。 */
    val latestRequestOutputDurationMillis: Long? = null,
    /** 最近一次 Provider 请求发出前，对最终请求投影做出的稳定 token 估算。 */
    val latestRequestEstimatedContextTokens: Long? = null,
    /** 本 turn 最近一次实际观测到首个模型输出的 Provider 请求 TTFT。 */
    val latestRequestTimeToFirstOutputMillis: Long? = null,
    /** 本 turn 最近一次由 Provider 明确 input/cache-read 数据得出的缓存命中率。 */
    val latestRequestCacheHitPercent: Double? = null,
    /** 本 turn 最近一次由 Provider 明确 output 数据和输出阶段时长得出的吞吐率。 */
    val latestRequestTokensPerSecond: Double? = null,
    val observedProviderRequestCount: Int? = null,
    val observedUsageReportedRequestCount: Int? = null,
    val providerRequestDurationMillis: Long? = null,
    val initialRequestTimeToFirstOutputMillis: Long? = null,
    /** 本 turn 成功随 checkpoint 提交的 Tool Output 滚动裁剪批次数。 */
    val successfulToolOutputCompactionBatchCount: Int? = null,
    /** canonical input 是否覆盖本 turn 的每一个已关闭 Provider 请求。 */
    val inputCompleteness: UsageCompleteness = UsageCompleteness.LEGACY,
    val coreCompleteness: UsageCompleteness = UsageCompleteness.LEGACY,
    val cacheReadCompleteness: UsageCompleteness = UsageCompleteness.LEGACY,
    val semanticsVersion: Int = LEGACY_TOKEN_USAGE_SEMANTICS_VERSION,
)

const val LEGACY_TOKEN_USAGE_SEMANTICS_VERSION = 1
const val CURRENT_TOKEN_USAGE_SEMANTICS_VERSION = 5

internal fun sumTokenCountsOrNull(vararg counts: Long?): Long? {
    if (counts.any { it == null }) return null
    return try {
        counts.fold(0L) { total, count -> Math.addExact(total, requireNotNull(count)) }
    } catch (_: ArithmeticException) {
        null
    }
}
