package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import net.weero.measix.pilot.service.runtime.ContextCacheDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import kotlin.uuid.Uuid

class ChatMessageNerdLineUsageTest {
    @Test
    fun `compact summary uses latest request scope and turn request count`() {
        val display = completeUsage().toNerdLineDisplay()

        assertEquals(23_000L, display.latestContextTokens)
        assertEquals(78.26086956521739, display.latestCachePercent!!, 0.0001)
        assertEquals(2, display.requestCount)
        assertEquals("Context 23K · Cache 78% · Req 2", display.compactText())
    }

    @Test
    fun `cache zero is explicit while missing cache stays unknown`() {
        val zero = completeUsage().copy(latestRequestCacheReadInputTokens = 0).toNerdLineDisplay()
        val missing = completeUsage().copy(latestRequestCacheReadInputTokens = null).toNerdLineDisplay()

        assertEquals(0.0, zero.latestCachePercent!!, 0.0001)
        assertNull(missing.latestCachePercent)
        assertEquals("Context 23K · Cache — · Req 2", missing.compactText())
    }

    @Test
    fun `invalid latest cache ratio is not displayed`() {
        val display = completeUsage()
            .copy(latestRequestCacheReadInputTokens = 24_000)
            .toNerdLineDisplay()

        assertNull(display.latestCachePercent)
    }

    @Test
    fun `cache percentage precision increases near a full hit`() {
        assertEquals("89", 89.49.formatCachePercent())
        assertEquals("90.0", 90.0.formatCachePercent())
        assertEquals("90.5", 90.45.formatCachePercent())
        assertEquals("99.0", 98.99.formatCachePercent())
        assertEquals("99.00", 99.0.formatCachePercent())
        assertEquals("99.95", 99.95.formatCachePercent())
        assertEquals("100.00", 100.0.formatCachePercent())
    }

    @Test
    fun `small positive hit rates never round down to explicit zero`() {
        assertEquals("0", 0.0.formatCachePercent())
        assertEquals("<1", 0.000001.formatCachePercent())
        assertEquals("<1", 0.499999.formatCachePercent())
        assertEquals("1", 0.5.formatCachePercent())
        assertEquals("1", 0.999999.formatCachePercent())
        assertEquals("1", 1.0.formatCachePercent())
        assertEquals(
            "Context 23K · Cache <1% · Req 2",
            completeUsage().copy(latestRequestCacheReadInputTokens = 1).toNerdLineDisplay().compactText(),
        )
    }

    @Test
    fun `active display carry replaces context and cache together but never turn details`() {
        val carry = ContextCacheDisplay(Uuid.random(), 20_000, 15_000)
        val usage = completeUsage().copy(latestRequestContextTokens = 40_000, latestRequestCacheReadInputTokens = null)

        val display = usage.toNerdLineDisplay(carry)

        assertEquals("Context 20K · Cache 75% · Req 2", display.compactText())
        assertEquals(43_000L, display.inputTokens)
        assertEquals(18_000L, display.cacheReadInputTokens)
        assertEquals(12.0, display.tokensPerSecond!!, 0.0001)
        assertEquals(800L, display.initialTtftMillis)
        assertNull(usage.latestRequestCacheReadInputTokens)
        assertEquals("Context 40K · Cache — · Req 2", usage.toNerdLineDisplay().compactText())
    }

    @Test
    fun `a new assistant can display carried context before having any usage without inventing counters`() {
        val display = (null as TokenUsage?).toNerdLineDisplay(ContextCacheDisplay(Uuid.random(), 20_000, 0))

        assertEquals("Context 20K · Cache 0% · Req —", display.compactText())
        assertFalse(display.hasTokenDetails)
        assertNull(display.tokensPerSecond)
        assertNull(display.initialTtftMillis)
        assertFalse((null as TokenUsage?).toNerdLineDisplay().hasCompactSummary)
    }

    @Test
    fun `only complete turn totals participate in expanded details and speed`() {
        val complete = completeUsage().toNerdLineDisplay()
        val partial = completeUsage()
            .copy(coreCompleteness = UsageCompleteness.PARTIAL)
            .toNerdLineDisplay()

        assertEquals(43_000L, complete.inputTokens)
        assertEquals(1_200L, complete.outputTokens)
        assertEquals(12.0, complete.tokensPerSecond!!, 0.0001)
        assertEquals(800L, complete.initialTtftMillis)
        assertNull(partial.inputTokens)
        assertNull(partial.outputTokens)
        assertNull(partial.tokensPerSecond)
    }

    @Test
    fun `legacy data has no special compact or expanded fallback`() {
        val display = TokenUsage(
            inputTokens = 531_100,
            outputTokens = 7_300,
            cacheReadInputTokens = 480_000,
        ).toNerdLineDisplay()

        assertFalse(display.hasCompactSummary)
        assertFalse(display.hasTokenDetails)
        assertNull(display.tokensPerSecond)
    }

    @Test
    fun `long token counts retain compact formatting`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("531.1K", 531_100L.formatTokenCount())
            assertEquals("7.3K", 7_300L.formatTokenCount())
            assertEquals("2M", 2_000_000L.formatTokenCount())
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun completeUsage() = TokenUsage(
        inputTokens = 43_000,
        outputTokens = 1_200,
        cacheReadInputTokens = 18_000,
        totalTokens = 44_200,
        latestRequestContextTokens = 23_000,
        latestRequestCacheReadInputTokens = 18_000,
        observedProviderRequestCount = 2,
        observedUsageReportedRequestCount = 2,
        providerRequestDurationMillis = 100_000,
        initialRequestTimeToFirstOutputMillis = 800,
        coreCompleteness = UsageCompleteness.COMPLETE,
        cacheReadCompleteness = UsageCompleteness.COMPLETE,
        semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
    )
}
