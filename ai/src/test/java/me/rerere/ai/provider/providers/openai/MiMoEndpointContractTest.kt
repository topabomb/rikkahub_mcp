package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiMo Chat Completions and Responses endpoint contract tests.
 *
 * Validates thinking.type, sampling omission, token field, reasoning effort mapping,
 * tool continuation reasoning replay, source profile isolation, and that unrelated
 * compatible hosts are not reclassified as MiMo.
 */
class MiMoEndpointContractTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val responseApi = ResponseAPI(OkHttpClient(), KeyRoulette.default())

    private val mimoModel = Model(
        modelId = "mimo-v3",
        displayName = "MiMo V3",
        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
    )

    private val mimoProvider = ProviderSetting.OpenAI(
        baseUrl = "https://api.xiaomimimo.com/v1",
        models = listOf(mimoModel),
    )

    private val mimoTokenPlanProvider = ProviderSetting.OpenAI(
        baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
        models = listOf(mimoModel),
    )

    private val compatibleProvider = ProviderSetting.OpenAI(
        baseUrl = "https://proxy.example.com/v1",
        models = listOf(mimoModel),
    )

    private fun buildChatRequest(
        provider: ProviderSetting.OpenAI,
        level: ReasoningLevel,
        model: Model = mimoModel,
        stream: Boolean = false,
    ): JsonObject {
        val params = TextGenerationParams(
            model = model,
            reasoningLevel = level,
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 4096,
        )
        return ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        ).run {
            isAccessible = true
            invoke(api, listOf(UIMessage.user("hi")), params, provider, stream) as JsonObject
        }
    }

    private fun buildResponsesRequest(
        provider: ProviderSetting.OpenAI,
        level: ReasoningLevel,
        model: Model = mimoModel,
        stream: Boolean = false,
    ): JsonObject {
        val params = TextGenerationParams(
            model = model,
            reasoningLevel = level,
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 4096,
        )
        return responseApi.buildRequestBody(
            provider,
            listOf(UIMessage.user("hi")),
            params,
            stream,
        )
    }

    // --- Chat Completions ---

    @Test
    fun `MiMo Chat thinking enabled sends thinking type enabled and omits temperature top_p`() {
        val body = buildChatRequest(mimoProvider, ReasoningLevel.HIGH)

        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)

        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        // MiMo uses max_completion_tokens
        assertEquals(4096, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `MiMo Chat thinking disabled sends thinking type disabled and includes temperature`() {
        val body = buildChatRequest(mimoProvider, ReasoningLevel.OFF)

        val thinking = body["thinking"]!!.jsonObject
        assertEquals("disabled", thinking["type"]!!.jsonPrimitive.content)

        // temperature/top_p are allowed when thinking is disabled
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun `MiMo Chat AUTO sends thinking enabled`() {
        val body = buildChatRequest(mimoProvider, ReasoningLevel.AUTO)

        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        // AUTO is enabled → temperature omitted
        assertFalse(body.containsKey("temperature"))
    }

    @Test
    fun `MiMo Chat token-plan-cn host is also recognized`() {
        val body = buildChatRequest(mimoTokenPlanProvider, ReasoningLevel.HIGH)

        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `both MiMo hosts preserve endpoint contract in streaming and non-streaming requests`() {
        listOf(mimoProvider, mimoTokenPlanProvider).forEach { provider ->
            listOf(false, true).forEach { stream ->
                val chat = buildChatRequest(provider, ReasoningLevel.HIGH, stream = stream)
                assertEquals(stream, chat["stream"]!!.jsonPrimitive.content.toBoolean())
                assertEquals("enabled", chat["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)

                val responses = buildResponsesRequest(provider, ReasoningLevel.HIGH, stream = stream)
                assertEquals(stream, responses["stream"]!!.jsonPrimitive.content.toBoolean())
                assertEquals("high", responses["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `unrelated compatible host is not reclassified as MiMo`() {
        val body = buildChatRequest(compatibleProvider, ReasoningLevel.HIGH)

        // Compatible gateways don't get MiMo thinking.type
        assertFalse(body.containsKey("thinking"))
    }

    @Test
    fun `MiMo Chat non-reasoning model keeps sampling and omits thinking`() {
        val model = Model(modelId = "mimo-chat", displayName = "MiMo Chat")

        val body = buildChatRequest(mimoProvider, ReasoningLevel.AUTO, model = model)

        assertFalse(body.containsKey("thinking"))
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.9, body["top_p"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    // --- Responses ---

    @Test
    fun `MiMo Responses OFF maps to reasoning effort none`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.OFF)

        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("none", reasoning["effort"]!!.jsonPrimitive.content)
        // No summary (supportsReasoningSummary = false)
        assertFalse(reasoning.containsKey("summary"))
        // No encrypted_content include (supportsEncryptedContent = false)
        assertFalse(body.containsKey("include"))
    }

    @Test
    fun `MiMo Responses AUTO omits reasoning object and store`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.AUTO)

        assertFalse(body.containsKey("reasoning"))
        assertFalse(body.containsKey("store"))
    }

    @Test
    fun `MiMo Responses HIGH maps to reasoning effort high`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.HIGH)

        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("high", reasoning["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MiMo Responses XHIGH caps to high`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.XHIGH)

        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("high", reasoning["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MiMo Responses MAX caps to high`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.MAX)

        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("high", reasoning["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MiMo Responses LOW maps to reasoning effort low`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.LOW)

        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("low", reasoning["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MiMo Responses MEDIUM maps to reasoning effort medium`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.MEDIUM)

        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("medium", reasoning["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MiMo Responses thinking enabled omits temperature top_p`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.MEDIUM)

        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
    }

    @Test
    fun `MiMo Responses thinking disabled includes temperature`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.OFF)

        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun `MiMo Responses uses max_output_tokens not max_completion_tokens`() {
        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.HIGH)

        assertEquals(4096, body["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertFalse(body.containsKey("max_completion_tokens"))
    }

    @Test
    fun `MiMo Responses non-reasoning model keeps sampling and omits reasoning`() {
        val model = Model(modelId = "mimo-chat", displayName = "MiMo Chat")

        val body = buildResponsesRequest(mimoProvider, ReasoningLevel.AUTO, model = model)

        assertFalse(body.containsKey("reasoning"))
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(0.9, body["top_p"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    // --- Tool continuation reasoning replay ---

    @Test
    fun `MiMo tool continuation requires reasoning replay`() {
        assertTrue(requiresToolReasoningReplay("api.xiaomimimo.com", "mimo-v3"))
        assertTrue(requiresToolReasoningReplay("token-plan-cn.xiaomimimo.com", "mimo-v3"))
    }

    @Test
    fun `official OpenAI host never requires reasoning replay`() {
        assertFalse(requiresToolReasoningReplay("api.openai.com", "mimo-v3"))
    }

    @Test
    fun `unrelated compatible host does not require reasoning replay`() {
        assertFalse(requiresToolReasoningReplay("proxy.example.com", "mimo-v3"))
    }
}
