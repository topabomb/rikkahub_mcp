package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ChatMessageNerdLineUsageTest {
    @Test
    fun `legacy usage keeps input output and positive cache display`() {
        val display = TokenUsage(
            inputTokens = 531_100,
            outputTokens = 7_300,
            cacheReadInputTokens = 480_000,
        ).toNerdLineDisplay()

        assertEquals(531_100L, display.inputTokens)
        assertEquals(7_300L, display.outputTokens)
        assertEquals(480_000L, display.cacheReadInputTokens)
        assertNull(display.tokensPerSecond)
    }

    @Test
    fun `legacy completeness remains visible after a v2 continuation`() {
        val display = TokenUsage(
            inputTokens = 120,
            outputTokens = 30,
            cacheReadInputTokens = 80,
            coreCompleteness = UsageCompleteness.LEGACY,
            cacheReadCompleteness = UsageCompleteness.LEGACY,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()

        assertEquals(120L, display.inputTokens)
        assertEquals(30L, display.outputTokens)
        assertEquals(80L, display.cacheReadInputTokens)
        assertNull(display.tokensPerSecond)
    }

    @Test
    fun `v2 complete core displays input output and provider request tps`() {
        val display = TokenUsage(
            inputTokens = 300,
            outputTokens = 200,
            cacheReadInputTokens = 100,
            providerRequestDurationMillis = 4_000,
            coreCompleteness = UsageCompleteness.COMPLETE,
            cacheReadCompleteness = UsageCompleteness.COMPLETE,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()

        assertEquals(300L, display.inputTokens)
        assertEquals(200L, display.outputTokens)
        assertEquals(100L, display.cacheReadInputTokens)
        assertEquals(50.0, display.tokensPerSecond!!, 0.0001)
    }

    @Test
    fun `v2 incomplete fields stay hidden independently`() {
        val display = TokenUsage(
            inputTokens = 300,
            outputTokens = 200,
            cacheReadInputTokens = 100,
            coreCompleteness = UsageCompleteness.PARTIAL,
            cacheReadCompleteness = UsageCompleteness.COMPLETE,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()

        assertNull(display.inputTokens)
        assertNull(display.outputTokens)
        assertEquals(100L, display.cacheReadInputTokens)
        assertNull(display.tokensPerSecond)
    }

    @Test
    fun `complete zero cache remains hidden`() {
        val display = TokenUsage(
            inputTokens = 100,
            outputTokens = 20,
            cacheReadInputTokens = 0,
            coreCompleteness = UsageCompleteness.COMPLETE,
            cacheReadCompleteness = UsageCompleteness.COMPLETE,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ).toNerdLineDisplay()

        assertNull(display.cacheReadInputTokens)
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
}
