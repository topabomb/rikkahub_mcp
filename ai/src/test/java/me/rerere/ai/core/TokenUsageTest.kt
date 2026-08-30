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
                latestRequestContextTokens = 200,
                latestRequestCacheReadInputTokens = 150,
                observedProviderRequestCount = 2,
                observedUsageReportedRequestCount = 2,
                providerRequestDurationMillis = 1_000,
                initialRequestTimeToFirstOutputMillis = 125,
                coreCompleteness = UsageCompleteness.COMPLETE,
                cacheReadCompleteness = UsageCompleteness.COMPLETE,
                semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
            )
        )

        assertTrue("\"promptTokens\":300" in encoded)
        assertTrue("\"completionTokens\":30" in encoded)
        assertTrue("\"cachedTokens\":50" in encoded)
        assertTrue("\"latestRequestCacheReadInputTokens\":150" in encoded)
        assertTrue("\"initialRequestTimeToFirstOutputMillis\":125" in encoded)
        assertFalse("\"inputTokens\"" in encoded)
        assertFalse("\"outputTokens\"" in encoded)
    }
}
