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
import org.junit.Before
import org.junit.Test

class ChatCompletionsAPIMoonshotTest {
    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun buildRequest(
        modelId: String,
        reasoningLevel: ReasoningLevel,
    ): JsonObject {
        val params = TextGenerationParams(
            model = Model(
                modelId = modelId,
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel,
        )
        return api.buildChatCompletionRequest(
            messages = listOf(UIMessage.user("hi")),
            params = params,
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1"),
            stream = true,
        )
    }

    @Test
    fun `k2_6 sends thinking keep all when reasoning enabled`() {
        val thinking = buildRequest("kimi-k2.6", ReasoningLevel.HIGH)["thinking"]?.jsonObject
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertEquals("all", thinking?.get("keep")?.jsonPrimitive?.content)
    }

    @Test
    fun `k2_6 omits keep when reasoning disabled`() {
        val thinking = buildRequest("kimi-k2.6", ReasoningLevel.OFF)["thinking"]?.jsonObject
        assertEquals("disabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertFalse(thinking?.containsKey("keep") == true)
    }

    @Test
    fun `k2_5 never sends keep`() {
        val thinking = buildRequest("kimi-k2.5", ReasoningLevel.HIGH)["thinking"]?.jsonObject
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
        assertFalse(thinking?.containsKey("keep") == true)
    }
}
