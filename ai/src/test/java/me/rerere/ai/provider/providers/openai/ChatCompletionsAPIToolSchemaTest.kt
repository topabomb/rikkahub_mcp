package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsAPIToolSchemaTest {
    @Test
    fun `chat completions preserves JSON Schema definitions and references`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val schema = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("type", "object")
            put("properties", buildJsonObject {
                put("value", buildJsonObject { put("\$ref", "#/\$defs/value") })
            })
            put("\$defs", buildJsonObject {
                put("value", buildJsonObject { put("type", "number") })
            })
        }
        val params = TextGenerationParams(
            model = Model(modelId = "grok", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                Tool(
                    name = "calculate",
                    description = "calculate",
                    parameters = { schema },
                    execute = { emptyList() },
                )
            ),
        )
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val body = method.invoke(
            api,
            listOf(UIMessage.user("hello")),
            params,
            ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1"),
            false,
        ) as JsonObject
        val sent = body["tools"]!!.jsonArray.single().jsonObject["function"]!!
            .jsonObject["parameters"]!!.jsonObject

        assertEquals(
            "#/\$defs/value",
            sent["properties"]!!.jsonObject["value"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content,
        )
        assertEquals("number", sent["\$defs"]!!.jsonObject["value"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "https://json-schema.org/draft/2020-12/schema",
            sent["\$schema"]!!.jsonPrimitive.content,
        )
    }
}
