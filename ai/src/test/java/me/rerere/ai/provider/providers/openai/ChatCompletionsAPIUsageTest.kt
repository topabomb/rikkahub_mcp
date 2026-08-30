package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChatCompletionsAPIUsageTest {
    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `cached tokens fall back across provider dialects`() {
        fun usage(json: String) = api.parseTokenUsage(Json.parseToJsonElement(json).jsonObject)

        assertEquals(
            12L,
            usage(
                """{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12}}"""
            )?.cacheReadInputTokens
        )
        assertEquals(
            7L,
            api.parseTokenUsage(
                Json.parseToJsonElement(
                    """{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"cached_tokens":7}"""
                ).jsonObject,
                OpenAIEndpointVendor.MOONSHOT,
            )
                ?.cacheReadInputTokens
        )
        assertEquals(
            5L,
            api.parseTokenUsage(
                Json.parseToJsonElement(
                    """{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":3}"""
                ).jsonObject,
                OpenAIEndpointVendor.DEEPSEEK,
            )?.cacheReadInputTokens
        )
        assertEquals(
            12L,
            api.parseTokenUsage(
                Json.parseToJsonElement(
                    """{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"prompt_tokens_details":{"cached_tokens":12},"cached_tokens":7}"""
                ).jsonObject,
                OpenAIEndpointVendor.MOONSHOT,
            )?.cacheReadInputTokens
        )
        assertNull(
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}""")
                ?.cacheReadInputTokens
        )
        assertNull(
            usage("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"cached_tokens":7,"prompt_cache_hit_tokens":5}""")
                ?.cacheReadInputTokens
        )
    }

    @Test
    fun `usage preserves cache write reasoning and provider total`() {
        val usage = api.parseTokenUsage(
            Json.parseToJsonElement(
                """
                {
                  "prompt_tokens": 100,
                  "completion_tokens": 20,
                  "total_tokens": 999,
                  "prompt_tokens_details": {"cached_tokens": 60, "cache_write_tokens": 30},
                  "completion_tokens_details": {"reasoning_tokens": 15}
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
                """{"prompt_tokens":0,"completion_tokens":0,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":0}}"""
            ).jsonObject
        )!!
        assertEquals(0L, zero.inputTokens)
        assertEquals(0L, zero.outputTokens)
        assertEquals(0L, zero.cacheReadInputTokens)
        assertEquals(0L, zero.cacheWriteInputTokens)
        assertEquals(0L, zero.totalTokens)
    }
}
