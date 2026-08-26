package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * OpenRouter session_id wire contract tests for Chat Completions and Responses.
 *
 * Validates that only OpenRouter host writes session_id, null/blank/over-256 values
 * are omitted, and non-OpenRouter hosts never carry the field.
 */
class OpenRouterSessionIdTest {
    private val chatApi = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val responseApi = ResponseAPI(OkHttpClient(), KeyRoulette.default())

    private val model = Model(modelId = "gpt-4o", displayName = "GPT-4o", abilities = listOf(ModelAbility.TOOL))

    private val openRouterProvider = ProviderSetting.OpenAI(
        baseUrl = "https://openrouter.ai/api/v1",
        models = listOf(model),
    )

    private val openAiProvider = ProviderSetting.OpenAI(
        baseUrl = "https://api.openai.com/v1",
        models = listOf(model),
    )

    private val compatibleProvider = ProviderSetting.OpenAI(
        baseUrl = "https://proxy.example.com/v1",
        models = listOf(model),
    )

    private fun buildChatRequest(
        provider: ProviderSetting.OpenAI,
        sessionId: String?,
    ): JsonObject {
        val params = TextGenerationParams(
            model = model,
            providerSessionId = sessionId,
        )
        return ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        ).run {
            isAccessible = true
            invoke(chatApi, listOf(UIMessage.user("hi")), params, provider, false) as JsonObject
        }
    }

    private fun buildResponsesRequest(
        provider: ProviderSetting.OpenAI,
        sessionId: String?,
    ): JsonObject {
        val params = TextGenerationParams(
            model = model,
            providerSessionId = sessionId,
        )
        return responseApi.buildRequestBody(
            provider,
            listOf(UIMessage.user("hi")),
            params,
            false,
        )
    }

    @Test
    fun `OpenRouter Chat writes session_id when non-null`() {
        val body = buildChatRequest(openRouterProvider, "conv-12345")
        assertEquals("conv-12345", body["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OpenRouter Responses writes session_id when non-null`() {
        val body = buildResponsesRequest(openRouterProvider, "conv-12345")
        assertEquals("conv-12345", body["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OpenRouter Chat omits session_id when null`() {
        val body = buildChatRequest(openRouterProvider, null)
        assertFalse(body.containsKey("session_id"))
    }

    @Test
    fun `OpenRouter Chat omits session_id when blank`() {
        val body = buildChatRequest(openRouterProvider, "  ")
        assertFalse(body.containsKey("session_id"))
    }

    @Test
    fun `OpenRouter Chat omits session_id when over 256 chars`() {
        val body = buildChatRequest(openRouterProvider, "a".repeat(257))
        assertFalse(body.containsKey("session_id"))
    }

    @Test
    fun `OpenRouter Chat writes session_id when exactly 256 chars`() {
        val sid = "a".repeat(256)
        val body = buildChatRequest(openRouterProvider, sid)
        assertEquals(sid, body["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OpenAI host does not write session_id`() {
        val body = buildChatRequest(openAiProvider, "conv-12345")
        assertFalse(body.containsKey("session_id"))
    }

    @Test
    fun `compatible host does not write session_id`() {
        val body = buildChatRequest(compatibleProvider, "conv-12345")
        assertFalse(body.containsKey("session_id"))
    }

    @Test
    fun `OpenAI Responses host does not write session_id`() {
        val body = buildResponsesRequest(openAiProvider, "conv-12345")
        assertFalse(body.containsKey("session_id"))
    }

    @Test
    fun `compatible Responses host does not write session_id`() {
        val body = buildResponsesRequest(compatibleProvider, "conv-12345")
        assertFalse(body.containsKey("session_id"))
    }
}
