package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ClaudeProviderUsageTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    @Test
    fun `usage normalizes uncached cache read and cache creation input`() {
        val usage = provider.parseTokenUsage(
            Json.parseToJsonElement(
                """
                {
                  "usage": {
                    "input_tokens": 10,
                    "cache_read_input_tokens": 20,
                    "cache_creation_input_tokens": 30,
                    "output_tokens": 4
                  }
                }
                """.trimIndent()
            ).jsonObject
        )!!

        assertEquals(60L, usage.inputTokens)
        assertEquals(4L, usage.outputTokens)
        assertEquals(20L, usage.cacheReadInputTokens)
        assertEquals(30L, usage.cacheWriteInputTokens)
        assertEquals(64L, usage.totalTokens)
    }

    @Test
    fun `stream delta and explicit zero preserve field presence`() {
        val delta = provider.parseTokenUsage(
            Json.parseToJsonElement("""{"type":"message_delta","usage":{"output_tokens":7}}""").jsonObject
        )!!
        assertNull(delta.inputTokens)
        assertEquals(7L, delta.outputTokens)
        assertNull(delta.cacheReadInputTokens)
        assertNull(delta.totalTokens)
        assertEquals(true, delta.canDeriveTotalFromInputAndOutput)

        val zero = provider.parseTokenUsage(
            Json.parseToJsonElement(
                """{"usage":{"input_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":0}}"""
            ).jsonObject
        )!!
        assertEquals(0L, zero.inputTokens)
        assertEquals(0L, zero.outputTokens)
        assertEquals(0L, zero.cacheReadInputTokens)
        assertEquals(0L, zero.cacheWriteInputTokens)
        assertEquals(0L, zero.totalTokens)
    }
}
