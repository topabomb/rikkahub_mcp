package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.OpenAIResponseMetadata
import me.rerere.ai.ui.OpenAIResponseWireFormat
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Response API 响应解析契约（parser 与 serializer 分离）：`parseResponseOutput` /
 * `parseResponseDelta` / `parseResponseStreamError` / `parseResponseObjectError` 与 `ResponseStreamState`
 * 对 reasoning_text/summary/encrypted、function_call delta、terminal 事件与 refusal 的解析。
 * 请求序列化与 parse→replay 往返契约在 `ResponseAPISerializerTest`；跨事件拼装归 app `StepOutputAccumulator`。
 */
class ResponseAPIParserTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    @Test
    fun `deepseek non streaming response should parse reasoning_text content`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_1",
              "model": "deepseek-v4-flash",
              "output": [
                {
                  "id": "rs_1",
                  "type": "reasoning",
                  "content": [
                    {"type": "reasoning_text", "text": "DeepSeek "},
                    {"type": "reasoning_text", "text": "thinking"}
                  ],
                  "summary": []
                },
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {"type": "output_text", "text": "Final answer"}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val result = api.parseResponseOutput(response)
        val message = result.choices.single().message!!
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().single()

        assertEquals("DeepSeek thinking", reasoning.reasoning)
        assertEquals("rs_1", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("Final answer", text.text)
    }

    @Test
    fun `openai non streaming response should combine summary and preserve metadata once`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_2",
              "model": "gpt-5",
              "output": [
                {
                  "id": "rs_2",
                  "type": "reasoning",
                  "encrypted_content": "encrypted-state",
                  "summary": [
                    {"type": "summary_text", "text": "First "},
                    {"type": "summary_text", "text": "summary"}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val result = api.parseResponseOutput(response)
        val reasoning = result.choices.single().message!!.parts
            .filterIsInstance<UIMessagePart.Reasoning>()
            .single()
        val metadata = reasoning.metadataAs<OpenAIReasoningMetadata>()

        assertEquals("First summary", reasoning.reasoning)
        assertEquals("rs_2", metadata?.reasoningId)
        assertEquals("encrypted-state", metadata?.encryptedContent)
    }

    @Test
    fun `responses parser should preserve refusal and ignore unknown message parts`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_refusal",
              "model": "gpt-5",
              "output": [
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {"type": "unsupported_future_part", "value": "ignored"},
                    {"type": "refusal", "refusal": "I cannot help with that."}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val message = api.parseResponseOutput(response).choices.single().message!!

        assertEquals("I cannot help with that.", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `responses streaming function arguments should merge deltas once and not duplicate done value`() {
        val streamState = ResponseStreamState()
        val events = listOf(
            """
            {
              "type": "response.output_item.added",
              "item": {
                "id": "fc_delta",
                "call_id": "call_delta",
                "type": "function_call",
                "name": "lookup",
                "arguments": ""
              }
            }
            """,
            """
            {
              "type": "response.function_call_arguments.delta",
              "item_id": "fc_delta",
              "delta": "{\"query\":"
            }
            """,
            """
            {
              "type": "response.function_call_arguments.delta",
              "item_id": "fc_delta",
              "delta": "\"test\"}"
            }
            """,
            """
            {
              "type": "response.function_call_arguments.done",
              "item_id": "fc_delta",
              "arguments": "{\"query\":\"test\"}"
            }
            """
        ).mapNotNull { event ->
            api.parseResponseDelta(Json.parseToJsonElement(event.trimIndent()).jsonObject, streamState)
        }

        // Parser contract: the added event carries the name; the two argument deltas carry fragments;
        // the done event is suppressed once deltas were streamed (no duplicate full value). The
        // concatenation of fragments reconstructs the arguments exactly once. Streaming assembly of
        // the final tool is the app StepOutputAccumulator's contract.
        val tools = events.flatMap { it.choices.single().delta!!.parts }.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(3, events.size) // added + 2 deltas; done filtered out
        assertEquals("call_delta", tools.first().providerCallId)
        assertEquals("lookup", tools.first().toolName)
        assertEquals("{\"query\":\"test\"}", tools.drop(1).joinToString(separator = "") { it.input })
        assertTrue(streamState.toolCallIdsByItemId.isEmpty())
        assertTrue(streamState.toolArgumentDeltasSeenByItemId.isEmpty())
    }

    @Test
    fun `responses terminal statuses should expose failed incomplete and generic stream errors`() {
        val failed = Json.parseToJsonElement(
            """
            {
              "type": "response.failed",
              "response": {
                "status": "failed",
                "error": {"code": "server_error", "message": "generation failed"}
              }
            }
            """.trimIndent()
        ).jsonObject
        val incomplete = Json.parseToJsonElement(
            """
            {
              "type": "response.incomplete",
              "response": {
                "status": "incomplete",
                "incomplete_details": {"reason": "max_output_tokens"}
              }
            }
            """.trimIndent()
        ).jsonObject
        val genericError = Json.parseToJsonElement(
            """
            {
              "type": "error",
              "error": {"message": "stream error"}
            }
            """.trimIndent()
        ).jsonObject
        val completed = Json.parseToJsonElement(
            """
            {
              "type": "response.completed",
              "response": {"status": "completed"}
            }
            """.trimIndent()
        ).jsonObject

        assertEquals("generation failed", api.parseResponseStreamError(failed)?.message)
        assertEquals("Response incomplete: max_output_tokens", api.parseResponseStreamError(incomplete)?.message)
        assertEquals("stream error", api.parseResponseStreamError(genericError)?.message)
        assertEquals(null, api.parseResponseStreamError(completed))
    }

    @Test
    fun `response stream close requires an explicit terminal marker`() {
        val state = ResponseStreamState()

        assertEquals(
            "Response stream closed before a terminal event",
            state.prematureCloseError()?.message,
        )

        state.markTerminal()
        assertEquals(null, state.prematureCloseError())
    }

    @Test
    fun `stream completed event should persist full output for the next stateless request`() {
        val state = ResponseStreamState()
        val added = Json.parseToJsonElement(
            """
            {
              "type": "response.output_item.added",
              "item": {
                "id": "ws_stream",
                "type": "web_search_call",
                "status": "in_progress"
              }
            }
            """.trimIndent()
        ).jsonObject
        val completed = Json.parseToJsonElement(
            """
            {
              "type": "response.completed",
              "response": {
                "id": "resp_stream",
                "status": "completed",
                "output": [
                  {
                    "id": "ws_stream",
                    "type": "web_search_call",
                    "status": "completed",
                    "action": {"type": "search", "query": "Responses API"}
                  },
                  {
                    "id": "msg_stream",
                    "type": "message",
                    "role": "assistant",
                    "phase": "final_answer",
                    "status": "completed",
                    "content": [{"type": "output_text", "text": "Done"}]
                  }
                ],
                "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3}
              }
            }
            """.trimIndent()
        ).jsonObject

        api.parseResponseDelta(added, state)
        val terminalChunk = api.parseResponseDelta(completed, state)!!
        val message = (terminalChunk.choices.single().message ?: terminalChunk.choices.single().delta)!!
        val metadata = message.metadataAs<OpenAIResponseMetadata>()!!

        assertEquals(OpenAIResponseWireFormat.OPENAI, metadata.wireFormat)
        val outputItems = metadata.outputItemGroups.single()
        assertEquals(listOf("web_search_call", "message"), outputItems.map {
            it["type"]?.jsonPrimitive?.content
        })
        assertEquals("final_answer", outputItems[1]["phase"]?.jsonPrimitive?.content)
        assertEquals(3L, terminalChunk.usage?.totalTokens)
    }

    @Test
    fun `responses non streaming incomplete object should report protocol reason`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_incomplete",
              "status": "incomplete",
              "incomplete_details": {"reason": "content_filter"},
              "output": []
            }
            """.trimIndent()
        ).jsonObject

        assertEquals("Response incomplete: content_filter", api.parseResponseObjectError(response)?.message)
    }

    @Test
    fun `deepseek streaming reasoning events should merge text with item metadata`() {
        val streamState = ResponseStreamState()
        val events = listOf(
            """
            {
              "type": "response.output_item.added",
              "item": {"id": "rs_stream", "type": "reasoning"}
            }
            """,
            """
            {
              "type": "response.reasoning_text.delta",
              "item_id": "rs_stream",
              "delta": "Streaming thinking"
            }
            """,
            """
            {
              "type": "response.reasoning_text.done",
              "item_id": "rs_stream",
              "text": "Streaming thinking"
            }
            """,
            """
            {
              "type": "response.output_item.done",
              "item": {
                "id": "rs_stream",
                "type": "reasoning",
                "content": [
                  {"type": "reasoning_text", "text": "Streaming thinking"}
                ]
              }
            }
            """
        ).mapNotNull { event ->
            api.parseResponseDelta(
                Json.parseToJsonElement(event.trimIndent()).jsonObject,
                streamState,
            )
        }

        // Parser contract: the reasoning_text.delta event emits the text; the output_item.done event
        // emits the reasoningId (with empty text once deltas were seen). The reasoning_text.done
        // duplicate is suppressed. Merging text + id onto one part is the app StepOutputAccumulator's
        // contract.
        val reasonings = events.flatMap { it.choices.single().delta!!.parts }.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals("Streaming thinking", reasonings.first { it.reasoning.isNotEmpty() }.reasoning)
        assertEquals(
            "rs_stream",
            reasonings.first { it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId != null }
                .metadataAs<OpenAIReasoningMetadata>()!!.reasoningId,
        )
        assertTrue(streamState.reasoningTextEmittedByItemId.isEmpty())
    }

    @Test
    fun `deepseek streaming reasoning done should supply text when deltas are absent`() {
        val streamState = ResponseStreamState()
        val events = listOf(
            """
            {
              "type": "response.output_item.added",
              "item": {"id": "rs_done", "type": "reasoning"}
            }
            """,
            """
            {
              "type": "response.reasoning_text.done",
              "item_id": "rs_done",
              "text": "Done-only thinking"
            }
            """,
            """
            {
              "type": "response.output_item.done",
              "item": {"id": "rs_done", "type": "reasoning"}
            }
            """,
        ).mapNotNull { event ->
            api.parseResponseDelta(
                Json.parseToJsonElement(event.trimIndent()).jsonObject,
                streamState,
            )
        }

        // Parser contract: with no reasoning_text.delta events, the reasoning_text.done event supplies
        // the text and the output_item.done event supplies the reasoningId. Merging them onto one part
        // is the app StepOutputAccumulator's contract.
        val reasonings = events.flatMap { it.choices.single().delta!!.parts }.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals("Done-only thinking", reasonings.first { it.reasoning.isNotEmpty() }.reasoning)
        assertEquals(
            "rs_done",
            reasonings.first { it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId != null }
                .metadataAs<OpenAIReasoningMetadata>()!!.reasoningId,
        )
        assertTrue(streamState.reasoningTextEmittedByItemId.isEmpty())
    }
}
