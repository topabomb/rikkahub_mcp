package net.weero.measix.pilot.data.ai

import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness

internal enum class ProviderRequestOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
}

internal enum class UsageDiagnostic {
    NEGATIVE_VALUE,
    CACHE_READ_EXCEEDS_INPUT,
    CACHE_WRITE_EXCEEDS_INPUT,
    REASONING_EXCEEDS_OUTPUT,
    TOOL_USE_EXCEEDS_INPUT,
    TOTAL_MISMATCH,
    AGGREGATE_OVERFLOW,
}

internal data class CompletedRequestUsage(
    val requestOrdinal: Int,
    val snapshot: ProviderUsageSnapshot?,
    val inputCompleteness: UsageCompleteness,
    val coreCompleteness: UsageCompleteness,
    val cacheReadCompleteness: UsageCompleteness,
    val outcome: ProviderRequestOutcome,
    val providerRequestDurationMillis: Long,
    val timeToFirstOutputMillis: Long?,
    val diagnostics: Set<UsageDiagnostic>,
)

internal data class AppliedTurnUsage(
    val usage: TokenUsage,
    val diagnostics: Set<UsageDiagnostic>,
)

internal class RequestUsageReducer(
    private val requestOrdinal: Int,
) {
    private var current: ProviderUsageSnapshot? = null
    private val diagnostics = linkedSetOf<UsageDiagnostic>()
    private var closed = false

    fun accept(incoming: ProviderUsageSnapshot) {
        check(!closed) { "request usage is already closed" }
        val previous = current ?: ProviderUsageSnapshot()
        current = ProviderUsageSnapshot(
            inputTokens = overlay(previous.inputTokens, incoming.inputTokens),
            outputTokens = overlay(previous.outputTokens, incoming.outputTokens),
            cacheReadInputTokens = overlay(previous.cacheReadInputTokens, incoming.cacheReadInputTokens),
            cacheWriteInputTokens = overlay(previous.cacheWriteInputTokens, incoming.cacheWriteInputTokens),
            reasoningOutputTokens = overlay(previous.reasoningOutputTokens, incoming.reasoningOutputTokens),
            toolUseInputTokens = overlay(previous.toolUseInputTokens, incoming.toolUseInputTokens),
            totalTokens = overlay(previous.totalTokens, incoming.totalTokens),
            canDeriveTotalFromInputAndOutput = previous.canDeriveTotalFromInputAndOutput ||
                incoming.canDeriveTotalFromInputAndOutput,
        ).validated()
    }

    fun close(
        outcome: ProviderRequestOutcome,
        providerRequestDurationMillis: Long,
        timeToFirstOutputMillis: Long? = null,
    ): CompletedRequestUsage {
        check(!closed) { "request usage is already closed" }
        closed = true
        return completed(outcome, providerRequestDurationMillis, timeToFirstOutputMillis)
    }

    private fun completed(
        outcome: ProviderRequestOutcome,
        providerRequestDurationMillis: Long,
        timeToFirstOutputMillis: Long?,
    ): CompletedRequestUsage {
        val snapshot = current?.deriveTotalIfAllowed()?.takeIf(ProviderUsageSnapshot::hasAnyValue)
        val coreValues = listOf(snapshot?.inputTokens, snapshot?.outputTokens, snapshot?.totalTokens)
        val finalCoreMismatch = addExactOrNull(snapshot?.inputTokens, snapshot?.outputTokens)?.let { derived ->
            snapshot?.totalTokens != null && derived != snapshot.totalTokens
        } == true
        val coreCompleteness = when {
            finalCoreMismatch && coreValues.any { it != null } ->
                UsageCompleteness.PARTIAL
            coreValues.all { it != null } -> UsageCompleteness.COMPLETE
            coreValues.any { it != null } -> UsageCompleteness.PARTIAL
            else -> UsageCompleteness.NONE
        }
        val inputCompleteness = if (snapshot?.inputTokens != null) {
            UsageCompleteness.COMPLETE
        } else {
            UsageCompleteness.NONE
        }
        val cacheReadCompleteness = if (snapshot?.cacheReadInputTokens != null) {
            UsageCompleteness.COMPLETE
        } else {
            UsageCompleteness.NONE
        }
        return CompletedRequestUsage(
            requestOrdinal = requestOrdinal,
            snapshot = snapshot,
            inputCompleteness = inputCompleteness,
            coreCompleteness = coreCompleteness,
            cacheReadCompleteness = cacheReadCompleteness,
            outcome = outcome,
            providerRequestDurationMillis = providerRequestDurationMillis.coerceAtLeast(0),
            timeToFirstOutputMillis = timeToFirstOutputMillis?.coerceAtLeast(0),
            diagnostics = diagnostics.toSet(),
        )
    }

    private fun overlay(previous: Long?, incoming: Long?): Long? {
        if (incoming == null) return previous
        if (incoming < 0) {
            diagnostics += UsageDiagnostic.NEGATIVE_VALUE
            return null
        }
        return incoming
    }

    private fun ProviderUsageSnapshot.validated(): ProviderUsageSnapshot {
        var value = this
        if (value.cacheReadInputTokens.exceeds(value.inputTokens)) {
            diagnostics += UsageDiagnostic.CACHE_READ_EXCEEDS_INPUT
            value = value.copy(cacheReadInputTokens = null)
        }
        if (value.cacheWriteInputTokens.exceeds(value.inputTokens)) {
            diagnostics += UsageDiagnostic.CACHE_WRITE_EXCEEDS_INPUT
            value = value.copy(cacheWriteInputTokens = null)
        }
        if (value.reasoningOutputTokens.exceeds(value.outputTokens)) {
            diagnostics += UsageDiagnostic.REASONING_EXCEEDS_OUTPUT
            value = value.copy(reasoningOutputTokens = null)
        }
        if (value.toolUseInputTokens.exceeds(value.inputTokens)) {
            diagnostics += UsageDiagnostic.TOOL_USE_EXCEEDS_INPUT
            value = value.copy(toolUseInputTokens = null)
        }
        val derivedTotal = addExactOrNull(value.inputTokens, value.outputTokens)
        if (derivedTotal != null && value.totalTokens != null && derivedTotal != value.totalTokens) {
            diagnostics += UsageDiagnostic.TOTAL_MISMATCH
        }
        return value
    }

    private fun ProviderUsageSnapshot.deriveTotalIfAllowed(): ProviderUsageSnapshot {
        if (totalTokens != null || !canDeriveTotalFromInputAndOutput) return this
        val input = inputTokens ?: return this
        val output = outputTokens ?: return this
        return try {
            copy(totalTokens = Math.addExact(input, output))
        } catch (_: ArithmeticException) {
            diagnostics += UsageDiagnostic.AGGREGATE_OVERFLOW
            this
        }
    }

    private fun Long?.exceeds(parent: Long?): Boolean = this != null && parent != null && this > parent
}

internal class TurnUsageAccumulator private constructor(
    private var summary: TokenUsage?,
) {
    private var lastAppliedOrdinal = summary?.observedProviderRequestCount ?: 0

    fun nextRequestOrdinal(): Int = Math.addExact(lastAppliedOrdinal, 1)

    /** 请求发出前更新最终请求投影的估算长度；不把尚未关闭的请求计入累计值。 */
    fun recordRequestStarted(estimatedContextTokens: Long): TokenUsage {
        require(estimatedContextTokens >= 0) { "estimated context tokens must be non-negative" }
        return updateSummary { baseline ->
            baseline.copy(
                latestRequestEstimatedContextTokens = estimatedContextTokens,
                semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
            )
        }
    }

    /** 首个有效模型输出到达时刷新本次请求 TTFT；没有首个输出的请求不覆盖历史值。 */
    fun recordFirstOutput(timeToFirstOutputMillis: Long): TokenUsage {
        require(timeToFirstOutputMillis >= 0) { "TTFT must be non-negative" }
        return updateSummary { baseline ->
            baseline.copy(
                latestRequestTimeToFirstOutputMillis = timeToFirstOutputMillis,
                semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
            )
        }
    }

    fun apply(request: CompletedRequestUsage): AppliedTurnUsage {
        check(request.requestOrdinal == nextRequestOrdinal()) {
            "unexpected request usage ordinal: expected=${nextRequestOrdinal()}, actual=${request.requestOrdinal}"
        }
        val updated = aggregate(summary, request)
        summary = updated.usage
        lastAppliedOrdinal = request.requestOrdinal
        return updated
    }

    private fun aggregate(
        baseline: TokenUsage?,
        request: CompletedRequestUsage,
    ): AppliedTurnUsage {
        var coreOverflowed = false
        var inputOverflowed = false
        var cacheReadOverflowed = false
        val diagnostics = linkedSetOf<UsageDiagnostic>()

        fun add(
            left: Long?,
            right: Long?,
            onOverflow: () -> Unit = {},
            retainKnownSubtotal: Boolean = true,
        ): Long? {
            if (left == null) return right
            if (right == null) return left
            return try {
                Math.addExact(left, right)
            } catch (_: ArithmeticException) {
                diagnostics += UsageDiagnostic.AGGREGATE_OVERFLOW
                onOverflow()
                left.takeIf { retainKnownSubtotal }
            }
        }

        val snapshot = request.snapshot
        val baselineHasClosedRequestFacts = baseline?.let { usage ->
            usage.observedProviderRequestCount != null ||
                usage.inputTokens != null ||
                usage.outputTokens != null ||
                usage.totalTokens != null ||
                usage.providerRequestDurationMillis != null
        } == true
        val requestInputTokens = snapshot?.inputTokens
        val requestOutputTokens = snapshot?.outputTokens
        val requestCacheReadTokens = snapshot?.cacheReadInputTokens
        val requestContextTokens = if (requestInputTokens != null && requestOutputTokens != null) {
            try {
                Math.addExact(requestInputTokens, requestOutputTokens)
            } catch (_: ArithmeticException) {
                diagnostics += UsageDiagnostic.AGGREGATE_OVERFLOW
                coreOverflowed = true
                null
            }
        } else {
            null
        }
        val requestOutputDurationMillis = request.timeToFirstOutputMillis?.let { ttft ->
            (request.providerRequestDurationMillis - ttft).coerceAtLeast(0)
        }
        val requestCacheHitPercent = if (
            requestInputTokens != null &&
            requestInputTokens > 0L &&
            requestCacheReadTokens != null &&
            requestCacheReadTokens in 0L..requestInputTokens
        ) {
            requestCacheReadTokens.toDouble() / requestInputTokens * 100.0
        } else {
            null
        }
        val requestTokensPerSecond = if (
            requestOutputTokens != null && requestOutputDurationMillis != null && requestOutputDurationMillis > 0L
        ) {
            requestOutputTokens.toDouble() / requestOutputDurationMillis * 1_000.0
        } else {
            null
        }
        // 旧记录已有请求却没有历史峰值时，后续请求不能冒充整轮峰值；未知必须持续传播。
        val baselinePeakRequestContextTokens = baseline?.peakRequestContextTokens
        val peakRequestContextTokens = when {
            baselinePeakRequestContextTokens != null -> maxOf(
                baselinePeakRequestContextTokens,
                requestContextTokens ?: baselinePeakRequestContextTokens,
            )
            (baseline?.observedProviderRequestCount ?: 0) == 0 -> requestContextTokens
            else -> null
        }
        val updated = TokenUsage(
            inputTokens = add(
                baseline?.inputTokens,
                snapshot?.inputTokens,
                onOverflow = {
                    coreOverflowed = true
                    inputOverflowed = true
                },
            ),
            outputTokens = add(
                baseline?.outputTokens,
                snapshot?.outputTokens,
                onOverflow = { coreOverflowed = true },
            ),
            cacheReadInputTokens = add(
                baseline?.cacheReadInputTokens,
                snapshot?.cacheReadInputTokens,
                onOverflow = { cacheReadOverflowed = true },
            ),
            cacheWriteInputTokens = add(baseline?.cacheWriteInputTokens, snapshot?.cacheWriteInputTokens),
            reasoningOutputTokens = add(baseline?.reasoningOutputTokens, snapshot?.reasoningOutputTokens),
            toolUseInputTokens = add(baseline?.toolUseInputTokens, snapshot?.toolUseInputTokens),
            totalTokens = add(
                baseline?.totalTokens,
                snapshot?.totalTokens,
                onOverflow = { coreOverflowed = true },
            ),
            peakRequestContextTokens = peakRequestContextTokens,
            // 四个 latest 字段来自同一个已收口请求并一起覆盖；缺失不能继承上一请求。
            latestRequestContextTokens = snapshot?.inputTokens,
            latestRequestOutputTokens = snapshot?.outputTokens,
            latestRequestCacheReadInputTokens = snapshot?.cacheReadInputTokens,
            latestRequestOutputDurationMillis = requestOutputDurationMillis,
            latestRequestEstimatedContextTokens = baseline?.latestRequestEstimatedContextTokens,
            latestRequestTimeToFirstOutputMillis = request.timeToFirstOutputMillis
                ?: baseline?.latestRequestTimeToFirstOutputMillis,
            // 命中率与吞吐率只属于最近一次已关闭请求：缺字段写 null，不能继承上一请求。
            // 否则它们的分母会与摘要里显示的上下文（始终不继承）错位到不同请求。
            latestRequestCacheHitPercent = requestCacheHitPercent,
            latestRequestTokensPerSecond = requestTokensPerSecond,
            observedProviderRequestCount = Math.addExact(baseline?.observedProviderRequestCount ?: 0, 1),
            observedUsageReportedRequestCount = Math.addExact(
                baseline?.observedUsageReportedRequestCount ?: 0,
                if (snapshot != null) 1 else 0,
            ),
            providerRequestDurationMillis = add(
                baseline?.providerRequestDurationMillis,
                request.providerRequestDurationMillis,
                retainKnownSubtotal = false,
            ),
            initialRequestTimeToFirstOutputMillis = baseline?.initialRequestTimeToFirstOutputMillis
                ?: request.timeToFirstOutputMillis.takeIf {
                    !baselineHasClosedRequestFacts && request.requestOrdinal == 1
                },
            successfulToolOutputCompactionBatchCount =
                baseline?.successfulToolOutputCompactionBatchCount,
            inputCompleteness = combine(
                baseline?.inputCompleteness.takeIf { baselineHasClosedRequestFacts },
                request.inputCompleteness,
            ),
            coreCompleteness = combine(
                baseline?.coreCompleteness.takeIf { baselineHasClosedRequestFacts },
                request.coreCompleteness,
            ),
            cacheReadCompleteness = combine(
                baseline?.cacheReadCompleteness.takeIf { baselineHasClosedRequestFacts },
                request.cacheReadCompleteness,
            ),
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        )
        return AppliedTurnUsage(
            usage = updated.copy(
                inputCompleteness = if (inputOverflowed) {
                    updated.inputCompleteness.degrade()
                } else {
                    updated.inputCompleteness
                },
                coreCompleteness = if (coreOverflowed) {
                    updated.coreCompleteness.degrade()
                } else {
                    updated.coreCompleteness
                },
                cacheReadCompleteness = if (cacheReadOverflowed) {
                    updated.cacheReadCompleteness.degrade()
                } else {
                    updated.cacheReadCompleteness
                },
            ),
            diagnostics = diagnostics,
        )
    }

    companion object {
        fun from(baseline: TokenUsage?): TurnUsageAccumulator = TurnUsageAccumulator(baseline)

        private fun combine(
            baseline: UsageCompleteness?,
            request: UsageCompleteness,
        ): UsageCompleteness = when {
            baseline == null -> request
            baseline == UsageCompleteness.COMPLETE && request == UsageCompleteness.COMPLETE -> UsageCompleteness.COMPLETE
            baseline == UsageCompleteness.NONE && request == UsageCompleteness.NONE -> UsageCompleteness.NONE
            else -> UsageCompleteness.PARTIAL
        }
    }

    private fun updateSummary(transform: (TokenUsage) -> TokenUsage): TokenUsage {
        val updated = transform(summary ?: TokenUsage())
        summary = updated
        return updated
    }
}

private fun ProviderUsageSnapshot.hasAnyValue(): Boolean =
    inputTokens != null ||
        outputTokens != null ||
        cacheReadInputTokens != null ||
        cacheWriteInputTokens != null ||
        reasoningOutputTokens != null ||
        toolUseInputTokens != null ||
        totalTokens != null

private fun addExactOrNull(left: Long?, right: Long?): Long? {
    if (left == null || right == null) return null
    return try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }
}

private fun UsageCompleteness.degrade(): UsageCompleteness = when (this) {
    UsageCompleteness.NONE -> UsageCompleteness.NONE
    UsageCompleteness.LEGACY,
    UsageCompleteness.PARTIAL,
    UsageCompleteness.COMPLETE,
    -> UsageCompleteness.PARTIAL
}
