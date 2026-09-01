package me.rerere.ai.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `legacy json keeps stored keys and decodes as legacy semantics`() {
        val usage = json.decodeFromString<TokenUsage>(
            """{"promptTokens":100,"completionTokens":20,"cachedTokens":40,"totalTokens":120}"""
        )

        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(40L, usage.cacheReadInputTokens)
        assertEquals(120L, usage.totalTokens)
        assertEquals(UsageCompleteness.LEGACY, usage.coreCompleteness)
        assertEquals(UsageCompleteness.LEGACY, usage.cacheReadCompleteness)
        assertEquals(LEGACY_TOKEN_USAGE_SEMANTICS_VERSION, usage.semanticsVersion)
        assertNull(usage.latestRequestCacheReadInputTokens)
        assertNull(usage.latestRequestOutputTokens)
        assertNull(usage.latestRequestOutputDurationMillis)
        assertNull(usage.latestRequestEstimatedContextTokens)
        assertNull(usage.latestRequestTimeToFirstOutputMillis)
        assertNull(usage.latestRequestCacheHitPercent)
        assertNull(usage.latestRequestTokensPerSecond)
        assertNull(usage.initialRequestTimeToFirstOutputMillis)
    }

    @Test
    fun `current json preserves storage keys without runtime alias fields`() {
        val encoded = json.encodeToString(
            TokenUsage(
                inputTokens = 300,
                outputTokens = 30,
                cacheReadInputTokens = 50,
                totalTokens = 330,
                peakRequestContextTokens = 230,
                latestRequestContextTokens = 200,
                latestRequestOutputTokens = 25,
                latestRequestCacheReadInputTokens = 150,
                latestRequestOutputDurationMillis = 750,
                latestRequestEstimatedContextTokens = 210,
                latestRequestTimeToFirstOutputMillis = 125,
                latestRequestCacheHitPercent = 75.0,
                latestRequestTokensPerSecond = 33.3,
                observedProviderRequestCount = 2,
                observedUsageReportedRequestCount = 2,
                providerRequestDurationMillis = 1_000,
                initialRequestTimeToFirstOutputMillis = 125,
                successfulToolOutputCompactionBatchCount = 2,
                inputCompleteness = UsageCompleteness.COMPLETE,
                coreCompleteness = UsageCompleteness.COMPLETE,
                cacheReadCompleteness = UsageCompleteness.COMPLETE,
                semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
            )
        )

        assertTrue("\"promptTokens\":300" in encoded)
        assertTrue("\"completionTokens\":30" in encoded)
        assertTrue("\"cachedTokens\":50" in encoded)
        assertTrue("\"latestRequestCacheReadInputTokens\":150" in encoded)
        assertTrue("\"latestRequestOutputTokens\":25" in encoded)
        assertTrue("\"latestRequestOutputDurationMillis\":750" in encoded)
        assertTrue("\"latestRequestEstimatedContextTokens\":210" in encoded)
        assertTrue("\"latestRequestTimeToFirstOutputMillis\":125" in encoded)
        assertTrue("\"latestRequestCacheHitPercent\":75.0" in encoded)
        assertTrue("\"latestRequestTokensPerSecond\":33.3" in encoded)
        assertTrue("\"initialRequestTimeToFirstOutputMillis\":125" in encoded)
        assertTrue("\"peakRequestContextTokens\":230" in encoded)
        assertTrue("\"successfulToolOutputCompactionBatchCount\":2" in encoded)
        assertTrue("\"inputCompleteness\":\"COMPLETE\"" in encoded)
        assertFalse("\"inputTokens\"" in encoded)
        assertFalse("\"outputTokens\"" in encoded)
        val decoded = json.decodeFromString<TokenUsage>(encoded)
        assertEquals(230L, decoded.peakRequestContextTokens)
        assertEquals(25L, decoded.latestRequestOutputTokens)
        assertEquals(750L, decoded.latestRequestOutputDurationMillis)
        assertEquals(210L, decoded.latestRequestEstimatedContextTokens)
        assertEquals(125L, decoded.latestRequestTimeToFirstOutputMillis)
        assertEquals(75.0, decoded.latestRequestCacheHitPercent!!, 0.0)
        assertEquals(33.3, decoded.latestRequestTokensPerSecond!!, 0.0)
        assertEquals(2, decoded.successfulToolOutputCompactionBatchCount)
        assertEquals(UsageCompleteness.COMPLETE, decoded.inputCompleteness)
        assertEquals(CURRENT_TOKEN_USAGE_SEMANTICS_VERSION, decoded.semanticsVersion)
    }
}
