package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Chat Completions 响应解析契约（parser 与 serializer 分离）：`parseMessage` / `parseStreamPayload`
 * 对畸形/空/null 元素的健壮性、tool-call index→provider-call-id 投影、以及 refusal 文本保留。
 * 请求序列化契约在 `ChatCompletionsAPISerializerTest`；跨 chunk 参数拼接归 app `StepOutputAccumulator`。
 */
class ChatCompletionsAPIParserTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `parseMessage accepts explicit null tool_calls`() {
        val parsed = api.parseMessage(
            buildJsonObject {
                put("role", "assistant")
                put("content", "hello")
                put("tool_calls", JsonNull)
            }
        )

        assertEquals("hello", parsed.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertTrue(parsed.parts.none { it is UIMessagePart.Tool })
    }

    @Test
    fun `parseStreamPayload ignores null choices delta and tool_calls`() {
        val emptyChoices = api.parseStreamPayload(
            buildJsonObject {
                put("id", "chunk-1")
                put("model", "compatible")
                put("choices", JsonNull)
            }
        )
        assertTrue(emptyChoices.choices.isEmpty())

        val emptyDelta = api.parseStreamPayload(
            buildJsonObject {
                put("id", "chunk-2")
                put("model", "compatible")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", JsonNull)
                        put("message", JsonNull)
                    })
                })
            }
        )
        assertTrue(emptyDelta.choices.isEmpty())

        val nullTools = api.parseStreamPayload(
            buildJsonObject {
                put("id", "chunk-3")
                put("model", "compatible")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "partial")
                            put("tool_calls", JsonNull)
                        })
                        put("finish_reason", JsonNull)
                    })
                })
            }
        )
        assertEquals("partial", nullTools.choices.single().delta?.parts
            ?.filterIsInstance<UIMessagePart.Text>()?.single()?.text)
    }

    @Test
    fun `parseMessage skips null tool call elements and null function objects`() {
        val skippedNullElement = api.parseMessage(
            buildJsonObject {
                put("role", "assistant")
                put("content", "hello")
                put("tool_calls", buildJsonArray { add(JsonNull) })
            }
        )
        assertEquals("hello", skippedNullElement.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertTrue(skippedNullElement.parts.none { it is UIMessagePart.Tool })

        val nullFunction = api.parseMessage(
            buildJsonObject {
                put("role", "assistant")
                put("content", "hello")
                put("tool_calls", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "call-1")
                        put("type", "function")
                        put("function", JsonNull)
                    })
                })
            }
        )
        val tool = nullFunction.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call-1", tool.providerCallId)
        assertEquals("", tool.toolName)
        assertEquals("", tool.input)
    }

    @Test
    fun `parseMessage ignores non-object first content element when extracting mistral thinking`() {
        val parsed = api.parseMessage(
            buildJsonObject {
                put("role", "assistant")
                put("content", buildJsonArray { add(JsonNull) })
            }
        )
        assertTrue(parsed.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(parsed.parts.none { it is UIMessagePart.Text })
    }

    @Test
    fun `parseStreamPayload ignores null tool call elements`() {
        val chunk = api.parseStreamPayload(
            buildJsonObject {
                put("id", "chunk-4")
                put("model", "compatible")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject {
                            put("content", "partial")
                            put("tool_calls", buildJsonArray { add(JsonNull) })
                        })
                    })
                })
            }
        )
        val delta = chunk.choices.single().delta
        assertEquals("partial", delta?.parts?.filterIsInstance<UIMessagePart.Text>()?.single()?.text)
        assertTrue(delta?.parts?.none { it is UIMessagePart.Tool } == true)
    }

    @Test
    fun `parallel tool argument deltas should merge by official tool call index`() {
        // The provider stream state maps each official tool-call index to a stable provider call id.
        // Cross-chunk argument concatenation is the app StepOutputAccumulator's contract (covered in
        // StepOutputAccumulatorTest); here we lock the parser's index -> call-id/name projection.
        val streamState = ChatCompletionsStreamState()
        val parsed = listOf(
            """{"role":"assistant","tool_calls":[{"index":0,"id":"call-0","type":"function","function":{"name":"first","arguments":"{"}}]}""",
            """{"role":"assistant","tool_calls":[{"index":1,"id":"call-1","type":"function","function":{"name":"second","arguments":"["}}]}""",
        ).map { api.parseMessage(Json.parseToJsonElement(it).jsonObject, streamState) }
        val tools = parsed.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf("call-0", "call-1"), tools.map { it.providerCallId })
        assertEquals(listOf("first", "second"), tools.map { it.toolName })
        assertEquals(listOf("{", "["), tools.map { it.input })
    }

    @Test
    fun `indexed continuation of reused tool id preserves earlier request arguments`() {
        // The parser maps each official index to its provider call id even when the id is reused from
        // an earlier completed call. That the completed tool is never reopened is the app
        // StepOutputAccumulator's contract (covered in StepOutputAccumulatorTest).
        val streamState = ChatCompletionsStreamState()
        val parsed = api.parseMessage(
            Json.parseToJsonElement(
                """{"tool_calls":[{"index":0,"id":"call-0","function":{"name":"next","arguments":"{"}},{"index":1,"id":"call-1","function":{"name":"parallel","arguments":"["}}]}""",
            ).jsonObject,
            streamState,
        )
        val tools = parsed.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf("call-0", "call-1"), tools.map { it.providerCallId })
        assertEquals(listOf("next", "parallel"), tools.map { it.toolName })
        assertEquals(listOf("{", "["), tools.map { it.input })
    }

    @Test
    fun `chat parser should preserve official refusal text`() {
        val message = api.parseMessage(
            Json.parseToJsonElement(
                """{"role":"assistant","content":null,"refusal":"I cannot help with that."}"""
            ).jsonObject
        )

        assertEquals("I cannot help with that.", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }
}
