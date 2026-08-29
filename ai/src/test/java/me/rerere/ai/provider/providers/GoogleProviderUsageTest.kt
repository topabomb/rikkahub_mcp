package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GoogleProviderUsageTest {
    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    @Test
    fun `usage includes tool prompt in input and preserves provider total`() {
        val usage = provider.parseUsageMeta(
            Json.parseToJsonElement(
                """
                {
                  "promptTokenCount": 27,
                  "cachedContentTokenCount": 10,
                  "candidatesTokenCount": 45,
                  "toolUsePromptTokenCount": 10309,
                  "thoughtsTokenCount": 31,
                  "totalTokenCount": 10412
                }
                """.trimIndent()
            ).jsonObject
        )!!

        assertEquals(10336L, usage.inputTokens)
        assertEquals(27L, usage.contextInputTokens)
        assertEquals(76L, usage.outputTokens)
        assertEquals(10L, usage.cacheReadInputTokens)
        assertEquals(10309L, usage.toolUseInputTokens)
        assertEquals(31L, usage.reasoningOutputTokens)
        assertEquals(10412L, usage.totalTokens)
    }

    @Test
    fun `usage distinguishes absent fields from explicit zero`() {
        val absent = provider.parseUsageMeta(Json.parseToJsonElement("{}").jsonObject)!!
        assertNull(absent.inputTokens)
        assertNull(absent.contextInputTokens)
        assertNull(absent.outputTokens)
        assertNull(absent.cacheReadInputTokens)
        assertNull(absent.toolUseInputTokens)
        assertNull(absent.totalTokens)

        val zero = provider.parseUsageMeta(
            Json.parseToJsonElement(
                """
                {
                  "promptTokenCount": 0,
                  "cachedContentTokenCount": 0,
                  "candidatesTokenCount": 0,
                  "toolUsePromptTokenCount": 0,
                  "thoughtsTokenCount": 0,
                  "totalTokenCount": 0
                }
                """.trimIndent()
            ).jsonObject
        )!!
        assertEquals(0L, zero.inputTokens)
        assertEquals(0L, zero.contextInputTokens)
        assertEquals(0L, zero.outputTokens)
        assertEquals(0L, zero.cacheReadInputTokens)
        assertEquals(0L, zero.toolUseInputTokens)
        assertEquals(0L, zero.totalTokens)
    }
}
