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
    fun `first request in flight shows estimated context and no turn totals yet`() {
        val display = TokenUsage(
            latestRequestEstimatedContextTokens = 30_000,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()!!

        assertEquals(30_000L, display.context!!.tokens)
        assertFalse(display.context.exact)
        // 还没有已关闭请求，累计 input / cache read 都未知，命中率不可用。
        assertNull(display.cacheHitPercent)
        assertEquals("~30K · 5s", display.summaryText("5s"))
        val context = display.summaryItems("5s").single { it.icon == UsageSummaryIcon.CONTEXT }
        assertTrue(context.muted)
        assertFalse(context.highlighted)
    }

    @Test
    fun `closed turn shows exact context and the turn wide cache hit rate`() {
        val display = closedTurn().toNerdLineDisplay()!!

        assertEquals(28_000L, display.context!!.tokens)
        assertTrue(display.context.exact)
        // 累计 30_000 / 52_000 = 57.7%
        assertEquals(57.7, display.cacheHitPercent!!, 0.05)
        assertEquals("28K · 57.7% · 2 · 45s", display.summaryText("45s"))
        val context = display.summaryItems("45s").single { it.icon == UsageSummaryIcon.CONTEXT }
        assertFalse(context.muted)
    }

    @Test
    fun `cache hit rate comes from turn totals not from the latest request value`() {
        // lastRequestCacheHitPercent 是审计字段，UI 不得使用它：整轮可能远差于最后一次。
        val display = closedTurn(latestRequestCacheHitPercent = 90.0).toNerdLineDisplay()!!

        assertEquals(57.7, display.cacheHitPercent!!, 0.05)
        assertFalse(display.summaryText("45s").contains("90.0%"))
    }

    @Test
    fun `cache hit rate denominator is the turn input shown on the second line`() {
        val display = closedTurn().toNerdLineDisplay()!!

        val denominator = display.inputTokens
        val numerator = display.cacheReadTokens
        assertEquals(52_000L, denominator)
        assertEquals(30_000L, numerator)
        assertEquals(numerator!!.toDouble() / denominator!! * 100.0, display.cacheHitPercent!!, 0.0001)
    }

    @Test
    fun `cache hit rate is absent whenever its numerator or denominator is unavailable`() {
        val withoutInput = closedTurn().copy(
            inputCompleteness = UsageCompleteness.PARTIAL,
        ).toNerdLineDisplay()!!
        assertNull(withoutInput.cacheHitPercent)
        assertNull(withoutInput.inputTokens)

        val withoutCacheRead = closedTurn().copy(
            cacheReadCompleteness = UsageCompleteness.PARTIAL,
        ).toNerdLineDisplay()!!
        assertNull(withoutCacheRead.cacheHitPercent)
        assertNull(withoutCacheRead.cacheReadTokens)
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
            "52K · 3K · Cached 30K · Provider 12s · Req 3 · tok/s 428.6 · TTFT 500ms",
            display.detailsText(),
        )
        assertEquals(
            listOf(
                UsageDetailIcon.INPUT,
                UsageDetailIcon.OUTPUT,
                UsageDetailIcon.CACHED,
                UsageDetailIcon.PROVIDER,
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

        // 命中率随分子分母一起消失，不留下无法验算的比例。
        assertEquals("28K · 2 · 45s", display.summaryText("45s"))
        assertEquals("Provider 12s · Req 3 · tok/s 428.6 · TTFT 500ms", display.detailsText())
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
        latestRequestCacheHitPercent: Double? = 90.0,
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
        latestRequestCacheHitPercent = latestRequestCacheHitPercent,
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
