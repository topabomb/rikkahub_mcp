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

class TokenUsageAccountingTest {
    @Test
    fun `request snapshots overlay explicit zero without retaining an earlier cache hit`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)

        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = 100,
                contextInputTokens = 100,
                outputTokens = 20,
                cacheReadInputTokens = 50,
                totalTokens = 120,
            )
        )
        reducer.accept(ProviderUsageSnapshot(cacheReadInputTokens = 0))

        val completed = reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 25)
        assertEquals(0L, completed.snapshot?.cacheReadInputTokens)
        assertEquals(UsageCompleteness.COMPLETE, completed.coreCompleteness)
        assertEquals(UsageCompleteness.COMPLETE, completed.cacheReadCompleteness)
    }

    @Test
    fun `complementary Claude stream snapshots derive total after overlay without retaining start total`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = 100,
                contextInputTokens = 100,
                outputTokens = 1,
                canDeriveTotalFromInputAndOutput = true,
            )
        )
        reducer.accept(
            ProviderUsageSnapshot(
                outputTokens = 20,
                canDeriveTotalFromInputAndOutput = true,
            )
        )

        val completed = reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 25)

        assertEquals(100L, completed.snapshot?.inputTokens)
        assertEquals(20L, completed.snapshot?.outputTokens)
        assertEquals(120L, completed.snapshot?.totalTokens)
        assertEquals(UsageCompleteness.COMPLETE, completed.coreCompleteness)
    }

    @Test
    fun `corrected terminal snapshot is complete while retaining intermediate diagnostics`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.accept(ProviderUsageSnapshot(inputTokens = 100, outputTokens = 1, totalTokens = 999))
        reducer.accept(ProviderUsageSnapshot(outputTokens = 20, totalTokens = 120))

        val completed = reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 25)

        assertEquals(120L, completed.snapshot?.totalTokens)
        assertEquals(UsageCompleteness.COMPLETE, completed.coreCompleteness)
        assertTrue(UsageDiagnostic.TOTAL_MISMATCH in completed.diagnostics)
    }

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
        assertEquals(2, usage.observedProviderRequestCount)
        assertEquals(2, usage.observedUsageReportedRequestCount)
        assertEquals(100L, usage.providerRequestDurationMillis)
        assertEquals(UsageCompleteness.COMPLETE, usage.coreCompleteness)
        assertEquals(UsageCompleteness.COMPLETE, usage.cacheReadCompleteness)
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
    fun `missing cache breakdown does not downgrade complete core usage`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = 100,
                contextInputTokens = 100,
                outputTokens = 20,
                totalTokens = 120,
            )
        )

        val completed = reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 20)

        assertEquals(UsageCompleteness.COMPLETE, completed.coreCompleteness)
        assertEquals(UsageCompleteness.NONE, completed.cacheReadCompleteness)
    }

    @Test
    fun `legacy baseline remains legacy after an approval continuation request`() {
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
        assertEquals(UsageCompleteness.LEGACY, usage.coreCompleteness)
        assertEquals(UsageCompleteness.LEGACY, usage.cacheReadCompleteness)
        assertEquals(1, usage.observedProviderRequestCount)
    }

    @Test
    fun `invalid usage is diagnostic data and does not throw away valid model accounting`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = 100,
                contextInputTokens = 100,
                outputTokens = 20,
                cacheReadInputTokens = 110,
                totalTokens = 999,
            )
        )

        val completed = reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 1)

        assertEquals(100L, completed.snapshot?.inputTokens)
        assertEquals(20L, completed.snapshot?.outputTokens)
        assertNull(completed.snapshot?.cacheReadInputTokens)
        assertEquals(999L, completed.snapshot?.totalTokens)
        assertEquals(UsageCompleteness.PARTIAL, completed.coreCompleteness)
        assertTrue(UsageDiagnostic.CACHE_READ_EXCEEDS_INPUT in completed.diagnostics)
        assertTrue(UsageDiagnostic.TOTAL_MISMATCH in completed.diagnostics)
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
    fun `request usage can close only once`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 0)

        assertThrows(IllegalStateException::class.java) {
            reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 0)
        }
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
    ): CompletedRequestUsage {
        val reducer = RequestUsageReducer(requestOrdinal = ordinal)
        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = input,
                contextInputTokens = input,
                outputTokens = output,
                cacheReadInputTokens = cacheRead,
                totalTokens = total,
            )
        )
        return reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = duration)
    }
}
