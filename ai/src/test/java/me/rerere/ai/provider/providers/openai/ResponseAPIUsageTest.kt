package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ResponseAPIUsageTest {
    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    @Test
    fun `usage preserves cache write reasoning and provider total`() {
        val usage = api.parseTokenUsage(
            Json.parseToJsonElement(
                """
                {
                  "input_tokens": 100,
                  "output_tokens": 20,
                  "total_tokens": 999,
                  "input_tokens_details": {"cached_tokens": 60, "cache_write_tokens": 30},
                  "output_tokens_details": {"reasoning_tokens": 15}
                }
                """.trimIndent()
            ).jsonObject
        )!!

        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(60L, usage.cacheReadInputTokens)
        assertEquals(30L, usage.cacheWriteInputTokens)
        assertEquals(15L, usage.reasoningOutputTokens)
        assertEquals(999L, usage.totalTokens)
    }

    @Test
    fun `usage distinguishes absent fields from explicit zero and derives missing total`() {
        val absent = api.parseTokenUsage(Json.parseToJsonElement("{}").jsonObject)!!
        assertNull(absent.inputTokens)
        assertNull(absent.outputTokens)
        assertNull(absent.cacheReadInputTokens)
        assertNull(absent.cacheWriteInputTokens)
        assertNull(absent.totalTokens)

        val zero = api.parseTokenUsage(
            Json.parseToJsonElement(
                """{"input_tokens":0,"output_tokens":0,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0}}"""
            ).jsonObject
        )!!
        assertEquals(0L, zero.inputTokens)
        assertEquals(0L, zero.outputTokens)
        assertEquals(0L, zero.cacheReadInputTokens)
        assertEquals(0L, zero.cacheWriteInputTokens)
        assertEquals(0L, zero.totalTokens)
    }

    @Test
    fun `all terminal response events use the same usage decoder`() {
        listOf("response.completed", "response.incomplete", "response.failed").forEach { type ->
            val event = Json.parseToJsonElement(
                """
                {
                  "type": "$type",
                  "response": {
                    "output": [],
                    "usage": {
                      "input_tokens": 8,
                      "output_tokens": 3,
                      "total_tokens": 11,
                      "input_tokens_details": {"cached_tokens": 4, "cache_write_tokens": 2}
                    }
                  }
                }
                """.trimIndent()
            ).jsonObject

            val usage = api.parseResponseDelta(event)!!.usage!!
            assertEquals(type, 8L, usage.inputTokens)
            assertEquals(type, 3L, usage.outputTokens)
            assertEquals(type, 4L, usage.cacheReadInputTokens)
            assertEquals(type, 2L, usage.cacheWriteInputTokens)
            assertEquals(type, 11L, usage.totalTokens)
        }
    }
}
