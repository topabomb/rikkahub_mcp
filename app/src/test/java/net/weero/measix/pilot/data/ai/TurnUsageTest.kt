package net.weero.measix.pilot.data.ai

import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn 级 usage 累计：独立请求恰好累计一次、latest-request 摘要的覆盖/重置/缺失传播、
 * legacy 续跑不臆造峰值、Master 与 Child 相互独立、缺失核心保留已知小计并降级为 partial、
 * 溢出只降级受影响项、以及 ordinal 去重与跳号拒绝。
 */
class TurnUsageTest {
    @Test
    fun `turn accumulator sums independent provider requests exactly once`() {
        val accumulator = TurnUsageAccumulator.from(null)
        val first = completed(
            ordinal = 1,
            input = 100,
            output = 20,
            cacheRead = 50,
            total = 120,
            duration = 40,
        )
        val second = completed(
            ordinal = 2,
            input = 200,
            output = 10,
            cacheRead = 0,
            total = 210,
            duration = 60,
        )

        accumulator.apply(first)
        val usage = accumulator.apply(second).usage

        assertEquals(300L, usage.inputTokens)
        assertEquals(30L, usage.outputTokens)
        assertEquals(50L, usage.cacheReadInputTokens)
        assertEquals(330L, usage.totalTokens)
        assertEquals(210L, usage.peakRequestContextTokens)
        assertEquals(200L, usage.latestRequestContextTokens)
        assertEquals(10L, usage.latestRequestOutputTokens)
        assertEquals(0L, usage.latestRequestCacheReadInputTokens)
        assertNull(usage.latestRequestOutputDurationMillis)
        assertEquals(2, usage.observedProviderRequestCount)
        assertEquals(2, usage.observedUsageReportedRequestCount)
        assertEquals(100L, usage.providerRequestDurationMillis)
        assertEquals(UsageCompleteness.COMPLETE, usage.inputCompleteness)
        assertEquals(UsageCompleteness.COMPLETE, usage.coreCompleteness)
        assertEquals(UsageCompleteness.COMPLETE, usage.cacheReadCompleteness)
    }

    @Test
    fun `legacy turn with unknown historical peak never invents a partial peak on continuation`() {
        val legacy = TokenUsage(
            inputTokens = 100,
            outputTokens = 20,
            observedProviderRequestCount = 1,
            observedUsageReportedRequestCount = 1,
            coreCompleteness = UsageCompleteness.COMPLETE,
            semanticsVersion = 2,
        )
        val accumulator = TurnUsageAccumulator.from(legacy)

        val usage = accumulator.apply(
            completed(
                ordinal = 2,
                input = 200,
                output = 10,
                cacheRead = 0,
                total = 210,
                duration = 60,
            ),
        ).usage

        assertNull(usage.peakRequestContextTokens)
        assertEquals(CURRENT_TOKEN_USAGE_SEMANTICS_VERSION, usage.semanticsVersion)
    }

    @Test
    fun `latest request summary fields overwrite together while initial ttft stays on first request`() {
        val accumulator = TurnUsageAccumulator.from(null)
        val first = completed(
            ordinal = 1,
            input = 100,
            output = 20,
            cacheRead = 50,
            total = 120,
            duration = 40,
            timeToFirstOutput = 12,
        )
        val second = completed(
            ordinal = 2,
            input = 200,
            output = 10,
            cacheRead = 0,
            total = 210,
            duration = 60,
            timeToFirstOutput = 30,
        )

        accumulator.apply(first)
        val usage = accumulator.apply(second).usage

        assertEquals(200L, usage.latestRequestContextTokens)
        assertEquals(10L, usage.latestRequestOutputTokens)
        assertEquals(0L, usage.latestRequestCacheReadInputTokens)
        assertEquals(30L, usage.latestRequestOutputDurationMillis)
        assertEquals(12L, usage.initialRequestTimeToFirstOutputMillis)
    }

    @Test
    fun `latest request derived summaries reset together while observed ttft persists`() {
        val accumulator = TurnUsageAccumulator.from(null)

        val sent = accumulator.recordRequestStarted(1_234)
        assertEquals(1_234L, sent.latestRequestEstimatedContextTokens)
        assertNull(sent.observedProviderRequestCount)

        accumulator.recordFirstOutput(10)
        val first = accumulator.apply(
            completed(
                ordinal = 1,
                input = 100,
                output = 20,
                cacheRead = 50,
                total = 120,
                duration = 110,
                timeToFirstOutput = 10,
            ),
        ).usage
        assertEquals(1_234L, first.latestRequestEstimatedContextTokens)
        assertEquals(10L, first.latestRequestTimeToFirstOutputMillis)
        assertEquals(50.0, first.latestRequestCacheHitPercent!!, 0.0)
        assertEquals(200.0, first.latestRequestTokensPerSecond!!, 0.0)
        assertEquals(UsageCompleteness.COMPLETE, first.coreCompleteness)

        accumulator.recordRequestStarted(2_345)
        val missing = RequestUsageReducer(requestOrdinal = 2)
            .close(ProviderRequestOutcome.FAILED, providerRequestDurationMillis = 15)
        val second = accumulator.apply(missing).usage

        // 估算值属于最新一次请求，不受累计缺失影响。
        assertEquals(2_345L, second.latestRequestEstimatedContextTokens)
        // TTFT 表示最近一次实际观测到首个输出的请求，无输出请求不覆盖旧值。
        assertEquals(10L, second.latestRequestTimeToFirstOutputMillis)
        // 命中率与吞吐率只属于最近一次已关闭请求，缺失必须传播，不能沿用上一请求的值。
        assertNull(second.latestRequestCacheHitPercent)
        assertNull(second.latestRequestTokensPerSecond)
        assertEquals(2, second.observedProviderRequestCount)
    }

    @Test
    fun `latest request fields become unknown when a request reports no usage`() {
        val accumulator = TurnUsageAccumulator.from(null)
        accumulator.apply(completed(1, 100, 20, 50, 120, 40, timeToFirstOutput = 12))
        val missing = RequestUsageReducer(requestOrdinal = 2)
            .close(ProviderRequestOutcome.FAILED, providerRequestDurationMillis = 15)

        val usage = accumulator.apply(missing).usage

        assertNull(usage.latestRequestContextTokens)
        assertNull(usage.latestRequestOutputTokens)
        assertNull(usage.latestRequestCacheReadInputTokens)
        assertNull(usage.latestRequestOutputDurationMillis)
        assertEquals(12L, usage.initialRequestTimeToFirstOutputMillis)
    }

    @Test
    fun `master and child turns keep usage independent when child provider fails`() {
        val master = TurnUsageAccumulator.from(null)
        val child = TurnUsageAccumulator.from(null)

        master.apply(completed(1, 16_700, 100, 0, 16_800, 40))
        val childFailure = RequestUsageReducer(requestOrdinal = 1)
            .close(ProviderRequestOutcome.FAILED, providerRequestDurationMillis = 15)
        val childUsage = child.apply(childFailure).usage
        val masterUsage = master.apply(completed(2, 16_500, 50, 0, 16_550, 30)).usage

        assertNull(childUsage.inputTokens)
        assertEquals(UsageCompleteness.NONE, childUsage.coreCompleteness)
        assertEquals(1, childUsage.observedProviderRequestCount)
        assertEquals(33_200L, masterUsage.inputTokens)
        assertEquals(150L, masterUsage.outputTokens)
        assertEquals(2, masterUsage.observedProviderRequestCount)
    }

    @Test
    fun `missing core usage keeps the known subtotal and marks the turn partial`() {
        val accumulator = TurnUsageAccumulator.from(null)
        accumulator.apply(
            completed(
                ordinal = 1,
                input = 100,
                output = 20,
                cacheRead = 10,
                total = 120,
                duration = 30,
            )
        )
        val missing = RequestUsageReducer(requestOrdinal = 2)
            .close(ProviderRequestOutcome.FAILED, providerRequestDurationMillis = 15)

        val usage = accumulator.apply(missing).usage

        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(120L, usage.totalTokens)
        assertEquals(UsageCompleteness.PARTIAL, usage.coreCompleteness)
        assertEquals(UsageCompleteness.PARTIAL, usage.cacheReadCompleteness)
        assertEquals(2, usage.observedProviderRequestCount)
        assertEquals(1, usage.observedUsageReportedRequestCount)
        assertNull(usage.latestRequestContextTokens)
    }

    @Test
    fun `legacy baseline becomes partial after an observed continuation request`() {
        val accumulator = TurnUsageAccumulator.from(
            TokenUsage(
                inputTokens = 100,
                outputTokens = 20,
                cacheReadInputTokens = 30,
                totalTokens = 120,
            )
        )

        val usage = accumulator.apply(
            completed(
                ordinal = 1,
                input = 40,
                output = 5,
                cacheRead = 0,
                total = 45,
                duration = 10,
            )
        ).usage

        assertEquals(140L, usage.inputTokens)
        assertEquals(25L, usage.outputTokens)
        assertEquals(UsageCompleteness.PARTIAL, usage.coreCompleteness)
        assertEquals(UsageCompleteness.PARTIAL, usage.inputCompleteness)
        assertEquals(UsageCompleteness.PARTIAL, usage.cacheReadCompleteness)
        assertEquals(1, usage.observedProviderRequestCount)
    }

    @Test
    fun `aggregate overflow keeps known subtotals and degrades only affected completeness`() {
        val accumulator = TurnUsageAccumulator.from(
            TokenUsage(
                inputTokens = Long.MAX_VALUE,
                outputTokens = 10,
                cacheReadInputTokens = Long.MAX_VALUE,
                totalTokens = Long.MAX_VALUE,
                observedProviderRequestCount = 1,
                observedUsageReportedRequestCount = 1,
                providerRequestDurationMillis = Long.MAX_VALUE,
                coreCompleteness = UsageCompleteness.COMPLETE,
                cacheReadCompleteness = UsageCompleteness.COMPLETE,
                semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
            )
        )

        val applied = accumulator.apply(
            completed(
                ordinal = 2,
                input = 1,
                output = 1,
                cacheRead = 1,
                total = 1,
                duration = 1,
            )
        )

        assertEquals(Long.MAX_VALUE, applied.usage.inputTokens)
        assertEquals(11L, applied.usage.outputTokens)
        assertEquals(Long.MAX_VALUE, applied.usage.cacheReadInputTokens)
        assertNull(applied.usage.providerRequestDurationMillis)
        assertEquals(UsageCompleteness.PARTIAL, applied.usage.coreCompleteness)
        assertEquals(UsageCompleteness.PARTIAL, applied.usage.cacheReadCompleteness)
        assertTrue(UsageDiagnostic.AGGREGATE_OVERFLOW in applied.diagnostics)
    }

    @Test
    fun `turn accumulator rejects duplicate and skipped request ordinals`() {
        val accumulator = TurnUsageAccumulator.from(null)
        val first = completed(1, 10, 2, 0, 12, 5)
        accumulator.apply(first)

        assertThrows(IllegalStateException::class.java) {
            accumulator.apply(first)
        }
        assertThrows(IllegalStateException::class.java) {
            accumulator.apply(completed(3, 20, 3, 0, 23, 5))
        }

        val usage = accumulator.apply(completed(2, 20, 3, 0, 23, 5)).usage
        assertEquals(30L, usage.inputTokens)
        assertEquals(2, usage.observedProviderRequestCount)
    }

    private fun completed(
        ordinal: Int,
        input: Long,
        output: Long,
        cacheRead: Long,
        total: Long,
        duration: Long,
        timeToFirstOutput: Long? = null,
    ): CompletedRequestUsage {
        val reducer = RequestUsageReducer(requestOrdinal = ordinal)
        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = input,
                outputTokens = output,
                cacheReadInputTokens = cacheRead,
                totalTokens = total,
            )
        )
        return reducer.close(
            outcome = ProviderRequestOutcome.COMPLETED,
            providerRequestDurationMillis = duration,
            timeToFirstOutputMillis = timeToFirstOutput,
        )
    }
}
