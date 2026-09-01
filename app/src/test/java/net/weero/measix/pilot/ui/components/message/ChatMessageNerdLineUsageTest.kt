package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChatMessageNerdLineUsageTest {
    @Test
    fun `summary uses triggered request snapshots while details use turn totals`() {
        val display = completeUsage().toNerdLineDisplay()

        assertEquals(10_000L, display.latestContextTokens)
        assertEquals(90.0, display.latestCacheHitPercent!!, 0.0001)
        assertEquals(428.5714285714, display.latestTokensPerSecond!!, 0.0001)
        assertEquals(500L, display.latestTtftMillis)
        assertEquals(
            "10K · 90.0% · 428.6 tok/s · TTFT 500ms · 2",
            display.summaryText(),
        )
        assertEquals(
            "Input 52K · Output 3K · Cached 30K · Provider 7s · Total 30s · Req 3",
            display.detailsText(30_000),
        )
    }

    @Test
    fun `raw latest request accounting does not replace independently triggered summaries`() {
        val changedLatest = completeUsage().copy(
            latestRequestContextTokens = 1_000,
            latestRequestOutputTokens = 20,
            latestRequestCacheReadInputTokens = 0,
            latestRequestOutputDurationMillis = 100,
        ).toNerdLineDisplay()

        assertEquals(
            "10K · 90.0% · 428.6 tok/s · TTFT 500ms · 2",
            changedLatest.summaryText(),
        )
    }

    @Test
    fun `partial turn totals do not hide a complete latest request`() {
        val display = completeUsage().copy(
            inputCompleteness = UsageCompleteness.PARTIAL,
            coreCompleteness = UsageCompleteness.PARTIAL,
            cacheReadCompleteness = UsageCompleteness.PARTIAL,
        ).toNerdLineDisplay()

        assertEquals(10_000L, display.latestContextTokens)
        assertEquals(90.0, display.latestCacheHitPercent!!, 0.0001)
        assertEquals(428.5714285714, display.latestTokensPerSecond!!, 0.0001)
        assertEquals(
            "10K · 90.0% · 428.6 tok/s · TTFT 500ms · 2",
            display.summaryText(),
        )
        assertEquals(
            "Input — · Output — · Cached — · Provider 7s · Total — · Req 3",
            display.detailsText(null),
        )
    }

    @Test
    fun `missing triggered summary fields remain unknown rather than inheriting totals`() {
        val display = completeUsage().copy(
            latestRequestEstimatedContextTokens = null,
            latestRequestCacheHitPercent = null,
            latestRequestTokensPerSecond = null,
            latestRequestTimeToFirstOutputMillis = null,
        ).toNerdLineDisplay()

        assertNull(display.latestContextTokens)
        assertNull(display.latestCacheHitPercent)
        assertNull(display.latestTokensPerSecond)
        assertEquals("2", display.summaryText())
    }

    @Test
    fun `zero tool trims are hidden and empty turn has no footer`() {
        val zero = completeUsage().copy(successfulToolOutputCompactionBatchCount = 0).toNerdLineDisplay()
        assertFalse(zero.summaryItems().any { it.icon == UsageSummaryIcon.TRIM })
        assertTrue(zero.hasSummary)
        assertFalse((null as TokenUsage?).toNerdLineDisplay().hasSummary)
    }

    @Test
    fun `missing cache or throughput trigger hides the whole metric`() {
        val noCache = completeUsage().copy(latestRequestCacheHitPercent = null).toNerdLineDisplay()
        val noThroughput = completeUsage().copy(latestRequestTokensPerSecond = null).toNerdLineDisplay()

        assertEquals("10K · 428.6 tok/s · TTFT 500ms · 2", noCache.summaryText())
        assertEquals("10K · 90.0% · TTFT 500ms · 2", noThroughput.summaryText())
    }

    @Test
    fun `only ambiguous first line metrics retain visible words`() {
        val items = completeUsage().toNerdLineDisplay().summaryItems()

        assertEquals("10K", items[0].text)
        assertEquals("90.0%", items[1].text)
        assertEquals("428.6 tok/s", items[2].text)
        assertEquals("TTFT 500ms", items[3].text)
        assertEquals(UsageSummaryIcon.TRIM, items.last().icon)
        assertEquals("2", items.last().text)
        assertTrue(items.last().highlighted)
        assertTrue(items.dropLast(1).none { it.highlighted })
    }

    @Test
    fun `second line uses icons except for ambiguous request count`() {
        val items = completeUsage().toNerdLineDisplay().detailItems(30_000)

        assertEquals(
            listOf(
                UsageDetailIcon.INPUT,
                UsageDetailIcon.OUTPUT,
                UsageDetailIcon.CACHED,
                UsageDetailIcon.PROVIDER,
                UsageDetailIcon.TOTAL,
                null,
            ),
            items.map { it.icon },
        )
        assertEquals("Req", items.last().label)
    }

    @Test
    fun `cache percentage keeps one decimal and tiny hits stay visible`() {
        assertEquals("0.0", 0.0.formatCachePercent())
        assertEquals("<0.1", 0.000001.formatCachePercent())
        assertEquals("57.7", 57.65.formatCachePercent())
        assertEquals("99.5", 99.5.formatCachePercent())
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
        inputTokens = 52_000,
        outputTokens = 3_000,
        cacheReadInputTokens = 30_000,
        totalTokens = 55_000,
        peakRequestContextTokens = 30_500,
        latestRequestContextTokens = 10_000,
        latestRequestOutputTokens = 600,
        latestRequestCacheReadInputTokens = 9_000,
        latestRequestOutputDurationMillis = 1_400,
        latestRequestEstimatedContextTokens = 10_000,
        latestRequestTimeToFirstOutputMillis = 500,
        latestRequestCacheHitPercent = 90.0,
        latestRequestTokensPerSecond = 428.5714285714,
        observedProviderRequestCount = 3,
        observedUsageReportedRequestCount = 3,
        providerRequestDurationMillis = 7_000,
        initialRequestTimeToFirstOutputMillis = 500,
        successfulToolOutputCompactionBatchCount = 2,
        inputCompleteness = UsageCompleteness.COMPLETE,
        coreCompleteness = UsageCompleteness.COMPLETE,
        cacheReadCompleteness = UsageCompleteness.COMPLETE,
        semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
    )
}
