package net.weero.measix.pilot.ui.components.message

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
    fun `first request in flight shows estimated context and hides cache hit`() {
        val display = TokenUsage(
            latestRequestEstimatedContextTokens = 30_000,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()!!

        assertEquals(30_000L, display.context!!.tokens)
        assertFalse(display.context.exact)
        assertNull(display.cacheHitPercent)
        assertEquals("~30K · 5s", display.summaryText("5s"))
        val context = display.summaryItems("5s").single { it.icon == UsageSummaryIcon.CONTEXT }
        assertTrue(context.muted)
        assertFalse(context.highlighted)
    }

    @Test
    fun `closed request shows exact context with its own cache hit rate`() {
        val display = closedTurn().toNerdLineDisplay()!!

        assertEquals(28_000L, display.context!!.tokens)
        assertTrue(display.context.exact)
        assertEquals(90.0, display.cacheHitPercent!!, 0.0001)
        assertEquals("28K · 90.0% · 2 · 45s", display.summaryText("45s"))
        val context = display.summaryItems("45s").single { it.icon == UsageSummaryIcon.CONTEXT }
        assertFalse(context.muted)
    }

    @Test
    fun `cache hit percent denominator is the displayed context`() {
        val usage = closedTurn()
        val display = usage.toNerdLineDisplay()!!
        val expected = usage.latestRequestCacheReadInputTokens!!.toDouble() /
            display.context!!.tokens * 100.0

        assertEquals(expected, display.cacheHitPercent!!, 0.0001)
    }

    @Test
    fun `stale cache hit is never paired with an estimated context`() {
        // 第二次请求未报告 usage：账本把命中率写为 null，上下文回落到本次请求的估算。
        val display = TokenUsage(
            latestRequestEstimatedContextTokens = 35_000,
            latestRequestContextTokens = null,
            latestRequestCacheHitPercent = null,
            latestRequestTimeToFirstOutputMillis = 500,
            observedProviderRequestCount = 2,
            inputCompleteness = UsageCompleteness.PARTIAL,
            coreCompleteness = UsageCompleteness.PARTIAL,
            cacheReadCompleteness = UsageCompleteness.PARTIAL,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()!!

        assertFalse(display.context!!.exact)
        assertEquals("~35K · 12s", display.summaryText("12s"))
        // 即使残留旧值，估算上下文也不允许配对出无法验算的比例。
        val withStalePercent = display.copy(cacheHitPercent = 90.0)
        assertEquals("~35K · 12s", withStalePercent.summaryText("12s"))
    }

    @Test
    fun `trim batches appear only when non zero and are highlighted`() {
        val withTrims = closedTurn(trims = 2).toNerdLineDisplay()!!
        val metric = withTrims.summaryItems("45s").single { it.icon == UsageSummaryIcon.TRIM }
        assertEquals("2", metric.text)
        assertTrue(metric.highlighted)
        assertFalse(metric.muted)

        val zero = closedTurn(trims = 0).toNerdLineDisplay()!!
        assertTrue(zero.summaryItems("45s").none { it.icon == UsageSummaryIcon.TRIM })
        val absent = closedTurn(trims = null).toNerdLineDisplay()!!
        assertTrue(absent.summaryItems("45s").none { it.icon == UsageSummaryIcon.TRIM })
    }

    @Test
    fun `second line lists turn totals first then latest request performance`() {
        val display = closedTurn().toNerdLineDisplay()!!

        assertEquals(
            "52K · 3K · Cached 30K · Provider 12s · Peak 35K · Req 3 · tok/s 428.6 · TTFT 500ms",
            display.detailsText(),
        )
        assertEquals(
            listOf(
                UsageDetailIcon.INPUT,
                UsageDetailIcon.OUTPUT,
                UsageDetailIcon.CACHED,
                UsageDetailIcon.PROVIDER,
                UsageDetailIcon.CONTEXT,
                UsageDetailIcon.REQUESTS,
                UsageDetailIcon.SPEED,
                UsageDetailIcon.TTFT,
            ),
            display.detailItems().map { it.icon },
        )
    }

    @Test
    fun `partial turn totals are omitted while latest request performance stays`() {
        val display = closedTurn().copy(
            inputCompleteness = UsageCompleteness.PARTIAL,
            coreCompleteness = UsageCompleteness.PARTIAL,
            cacheReadCompleteness = UsageCompleteness.PARTIAL,
        ).toNerdLineDisplay()!!

        assertEquals("28K · 90.0% · 2 · 45s", display.summaryText("45s"))
        assertEquals(
            "Provider 12s · Peak 35K · Req 3 · tok/s 428.6 · TTFT 500ms",
            display.detailsText(),
        )
    }

    @Test
    fun `empty usage renders only the turn elapsed time`() {
        val display = TokenUsage(
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()!!

        assertNull(display.context)
        assertEquals("7s", display.summaryText("7s"))
        assertTrue(display.detailItems().isEmpty())
        assertNull((null as TokenUsage?).toNerdLineDisplay())
    }

    @Test
    fun `elapsed text ticks whole seconds in flight and keeps precision when finished`() {
        val created = localDateTime(0)

        assertEquals("12s", elapsedText(created, null, 12_400, turnFinished = false))
        assertEquals("45.2s", elapsedText(created, localDateTime(45_200), 0, turnFinished = true))
        assertNull(elapsedText(created, null, 0, turnFinished = true))
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

    private fun localDateTime(epochMillis: Long) =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())

    private fun closedTurn(
        trims: Int? = 2,
        cacheHitPercent: Double? = 90.0,
    ) = TokenUsage(
        inputTokens = 52_000,
        outputTokens = 3_000,
        cacheReadInputTokens = 30_000,
        totalTokens = 55_000,
        peakRequestContextTokens = 35_000,
        latestRequestContextTokens = 28_000,
        latestRequestOutputTokens = 600,
        latestRequestCacheReadInputTokens = 25_200,
        latestRequestOutputDurationMillis = 1_400,
        latestRequestEstimatedContextTokens = 30_000,
        latestRequestTimeToFirstOutputMillis = 500,
        latestRequestCacheHitPercent = cacheHitPercent,
        latestRequestTokensPerSecond = 428.5714285714,
        observedProviderRequestCount = 3,
        observedUsageReportedRequestCount = 3,
        providerRequestDurationMillis = 12_000,
        initialRequestTimeToFirstOutputMillis = 500,
        successfulToolOutputCompactionBatchCount = trims,
        inputCompleteness = UsageCompleteness.COMPLETE,
        coreCompleteness = UsageCompleteness.COMPLETE,
        cacheReadCompleteness = UsageCompleteness.COMPLETE,
        semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
    )
}
