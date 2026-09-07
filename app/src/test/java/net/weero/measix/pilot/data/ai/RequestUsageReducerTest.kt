package net.weero.measix.pilot.data.ai

import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.UsageCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单次 Provider 请求的 usage presence-overlay 与关闭语义：显式零值覆盖、互补流片段派生 total、
 * 终态修正保留中间诊断、非法值降级为诊断而非丢弃有效核算、且一次请求只能关闭一次。
 */
class RequestUsageReducerTest {
    @Test
    fun `request snapshots overlay explicit zero without retaining an earlier cache hit`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)

        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = 100,
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
    fun `missing cache breakdown does not downgrade complete core usage`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = 100,
                outputTokens = 20,
                totalTokens = 120,
            )
        )

        val completed = reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 20)

        assertEquals(UsageCompleteness.COMPLETE, completed.coreCompleteness)
        assertEquals(UsageCompleteness.NONE, completed.cacheReadCompleteness)
    }

    @Test
    fun `invalid usage is diagnostic data and does not throw away valid model accounting`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.accept(
            ProviderUsageSnapshot(
                inputTokens = 100,
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
    fun `request usage can close only once`() {
        val reducer = RequestUsageReducer(requestOrdinal = 1)
        reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 0)

        assertThrows(IllegalStateException::class.java) {
            reducer.close(ProviderRequestOutcome.COMPLETED, providerRequestDurationMillis = 0)
        }
    }
}
