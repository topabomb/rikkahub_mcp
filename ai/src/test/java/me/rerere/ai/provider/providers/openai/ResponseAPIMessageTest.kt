package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ResponseAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI Response API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * ResponseAPI uses a different format than ChatCompletionsAPI:
 * - function_call items for tool invocations
 * - function_call_output items for tool results
 */
class ResponseAPIMessageTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    // Helper to invoke buildMessages method
    private fun invokeBuildMessages(
        messages: List<UIMessage>,
        host: String = "api.openai.com"
    ): JsonArray {
        return api.buildMessages(messages, resolveResponseProviderCapabilities(host))
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, listOf(UIMessage.user("hello")), params, stream)
    }

    private fun createReasoningParams(
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
        modelId: String = "test-model"
    ): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = modelId,
                displayName = modelId,
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel
        )
    }

    @Test
    fun `multi-round tool calls should produce correct function_call and function_call_output pairs`() {
        // Scenario: Multiple tool calls in sequence
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure for ResponseAPI:
        // 1. user message
        // 2. assistant content (text)
        // 3. function_call (search)
        // 4. function_call_output (search result)
        // 5. assistant content (text)
        // 6. function_call (calculate)
        // 7. function_call_output (calculate result)
        // 8. assistant content (final text)

        // Collect function_call items
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertEquals("Should have 2 function_call items", 2, functionCalls.size)

        // Collect function_call_output items
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals("Should have 2 function_call_output items", 2, functionOutputs.size)

        // Verify first function_call
        val call1 = functionCalls[0].jsonObject
        assertEquals("call_1", call1["call_id"]?.jsonPrimitive?.content)
        assertEquals("search", call1["name"]?.jsonPrimitive?.content)

        // Verify first function_call_output
        val output1 = functionOutputs[0].jsonObject
        assertEquals("call_1", output1["call_id"]?.jsonPrimitive?.content)
        assertTrue(output1["output"]?.jsonPrimitive?.content?.contains("Search result") == true)

        // Verify second function_call
        val call2 = functionCalls[1].jsonObject
        assertEquals("call_2", call2["call_id"]?.jsonPrimitive?.content)
        assertEquals("calculate", call2["name"]?.jsonPrimitive?.content)

        // Verify second function_call_output
        val output2 = functionOutputs[1].jsonObject
        assertEquals("call_2", output2["call_id"]?.jsonPrimitive?.content)
        assertTrue(output2["output"]?.jsonPrimitive?.content?.contains("4") == true)
    }

    @Test
    fun `function_call should be immediately followed by function_call_output`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_abc", "my_tool", """{"x": 1}""", "result")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find function_call index
        var functionCallIndex = -1
        for (i in result.indices) {
            if (result[i].jsonObject["type"]?.jsonPrimitive?.content == "function_call") {
                functionCallIndex = i
                break
            }
        }

        assertTrue("Should find function_call", functionCallIndex >= 0)
        assertTrue("function_call_output should follow", functionCallIndex < result.size - 1)

        val nextItem = result[functionCallIndex + 1].jsonObject
        assertEquals("function_call_output", nextItem["type"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextItem["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool calls should produce sequential function_call and output pairs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Should have 3 function_calls and 3 function_call_outputs
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals(3, functionCalls.size)
        assertEquals(3, functionOutputs.size)

        // Verify each function_call is followed by its output (in pairs)
        val callIds = listOf("call_1", "call_2", "call_3")
        for (callId in callIds) {
            var callIndex = -1
            var outputIndex = -1
            for (i in result.indices) {
                val item = result[i].jsonObject
                if (item["type"]?.jsonPrimitive?.content == "function_call" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    callIndex = i
                }
                if (item["type"]?.jsonPrimitive?.content == "function_call_output" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    outputIndex = i
                }
            }
            assertTrue("Should find function_call for $callId", callIndex >= 0)
            assertTrue("Should find function_call_output for $callId", outputIndex >= 0)
            assertEquals("Output should immediately follow call for $callId",
                callIndex + 1, outputIndex)
        }
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                createExecutedTool("call_1", "test", "{}", "output"),
                UIMessagePart.Text("Goodbye")
            )
        )

        val messages = listOf(
            UIMessage.user("Hi"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant content messages
        val assistantContents = result.filter {
            val obj = it.jsonObject
            obj["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertTrue("Should have assistant content messages", assistantContents.isNotEmpty())

        // First assistant message should have "Hello world"
        val firstAssistant = assistantContents[0].jsonObject
        val content = firstAssistant["content"]
        val hasHello = when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.content.contains("Hello")
            content is JsonArray -> content.any {
                it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Hello") == true
            }
            else -> false
        }
        assertTrue("First assistant should contain 'Hello'", hasHello)
    }

    @Test
    fun `complex multi-round scenario with text and tools interleaved`() {
        val messages = listOf(
            UIMessage.user("Execute a complex task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting task"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    createExecutedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed successfully")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Count items
        val userMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }
        val assistantMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }
        val functionCalls = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals("Should have 1 user message", 1, userMessages)
        assertEquals("Should have 3 function_calls", 3, functionCalls)
        assertEquals("Should have 3 function_call_outputs", 3, functionOutputs)
        assertTrue("Should have multiple assistant messages", assistantMessages >= 1)

        // Verify the order: each function_call immediately followed by function_call_output
        var lastCallIndex = -1
        for (i in result.indices) {
            val item = result[i].jsonObject
            if (item["type"]?.jsonPrimitive?.content == "function_call") {
                assertTrue("function_call should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("function_call_output should follow",
                    "function_call_output", next["type"]?.jsonPrimitive?.content)
                assertTrue("call_id should match",
                    item["call_id"]?.jsonPrimitive?.content == next["call_id"]?.jsonPrimitive?.content)
                assertTrue("Order should be maintained", i > lastCallIndex)
                lastCallIndex = i
            }
        }
    }

    @Test
    fun `volc response api should not include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertFalse("volc should not include reasoning.summary", reasoning!!.containsKey("summary"))
    }

    @Test
    fun `openai response api should include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("auto", reasoning!!["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openai responses should keep off semantics for gpt5 and use low fallback for old o series`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")
        val gpt5Body = invokeBuildRequestBody(
            providerSetting = provider,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.OFF, modelId = "gpt-5")
        )
        val o3Body = invokeBuildRequestBody(
            providerSetting = provider,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.OFF, modelId = "o3")
        )

        assertEquals("none", gpt5Body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("low", o3Body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown response proxy should retain openai reasoning format`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://proxy.example.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.HIGH)
        )
        val capabilities = resolveResponseProviderCapabilities("proxy.example.com")

        assertTrue(capabilities.supportsReasoningSummary)
        assertTrue(capabilities.supportsEncryptedContent)
        assertFalse(capabilities.usesReasoningTextContent)
        assertEquals("auto", requestBody["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        assertTrue(requestBody.containsKey("include"))
    }

    @Test
    fun `deepseek response api should omit openai reasoning summary and encrypted include`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams(
                reasoningLevel = ReasoningLevel.HIGH,
                modelId = "deepseek-v4-flash"
            )
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("high", reasoning!!["effort"]?.jsonPrimitive?.content)
        assertFalse("deepseek should not include reasoning.summary", reasoning.containsKey("summary"))
        assertFalse("deepseek should not request encrypted reasoning", requestBody.containsKey("include"))
    }

    @Test
    fun `deepseek response history should encode reasoning as reasoning_text content`() {
        val messages = listOf(
            UIMessage.user("Question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Thinking"),
                    UIMessagePart.Text("Answer")
                )
            )
        )

        val result = invokeBuildMessages(messages, host = "api.deepseek.com")
        val reasoningItem = result.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }.jsonObject
        val reasoningContent = reasoningItem["content"]?.jsonArray

        assertFalse(reasoningItem.containsKey("summary"))
        assertEquals(1, reasoningContent?.size)
        assertEquals("reasoning_text", reasoningContent!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Thinking", reasoningContent[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openai response history should keep summary reasoning format`() {
        val messages = listOf(
            UIMessage.user("Question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(reasoning = "Thinking"))
            )
        )

        val result = invokeBuildMessages(messages)
        val reasoningItem = result.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }.jsonObject

        assertTrue(reasoningItem.containsKey("summary"))
        assertFalse(reasoningItem.containsKey("content"))
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
    fun `openai non streaming response should preserve encrypted reasoning when summary is empty`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_empty_summary",
              "model": "gpt-5",
              "output": [
                {
                  "id": "rs_empty_summary",
                  "type": "reasoning",
                  "encrypted_content": "encrypted-only-state",
                  "summary": []
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

        assertEquals("", reasoning.reasoning)
        assertEquals("rs_empty_summary", metadata?.reasoningId)
        assertEquals("encrypted-only-state", metadata?.encryptedContent)

        val replay = invokeBuildMessages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning)))
        ).single().jsonObject
        assertEquals(0, replay["summary"]?.jsonArray?.size)
        assertEquals("encrypted-only-state", replay["encrypted_content"]?.jsonPrimitive?.content)
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
    fun `responses streaming function call should persist call id instead of output item id`() {
        val streamState = ResponseStreamState()
        val events = listOf(
            """
            {
              "type": "response.output_item.added",
              "item": {
                "id": "fc_123",
                "call_id": "call_123",
                "type": "function_call",
                "name": "lookup",
                "arguments": ""
              }
            }
            """,
            """
            {
              "type": "response.function_call_arguments.done",
              "item_id": "fc_123",
              "arguments": "{\"query\":\"test\"}"
            }
            """
        ).mapNotNull { event ->
            api.parseResponseDelta(Json.parseToJsonElement(event.trimIndent()).jsonObject, streamState)
        }

        val message = events.fold(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        ) { current, chunk ->
            current + chunk
        }
        val tool = message.parts.filterIsInstance<UIMessagePart.Tool>().single()

        assertEquals("call_123", tool.toolCallId)
        assertEquals("lookup", tool.toolName)
        assertEquals("{\"query\":\"test\"}", tool.input)

        val replay = invokeBuildMessages(
            listOf(
                message.copy(
                    parts = message.parts.map {
                        if (it is UIMessagePart.Tool) {
                            it.copy(output = listOf(UIMessagePart.Text("result")))
                        } else {
                            it
                        }
                    }
                )
            )
        )
        assertEquals(
            listOf("call_123", "call_123"),
            replay.map { it.jsonObject["call_id"]?.jsonPrimitive?.content }
        )
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

        val message = events.fold(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        ) { current, chunk ->
            current + chunk
        }
        val tool = message.parts.filterIsInstance<UIMessagePart.Tool>().single()

        assertEquals("call_delta", tool.toolCallId)
        assertEquals("lookup", tool.toolName)
        assertEquals("{\"query\":\"test\"}", tool.input)
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
              "type": "response.output_item.done",
              "item": {"id": "rs_stream", "type": "reasoning"}
            }
            """
        ).mapNotNull { event ->
            api.parseResponseDelta(Json.parseToJsonElement(event.trimIndent()).jsonObject)
        }

        val message = events.fold(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        ) { current, chunk ->
            current + chunk
        }
        val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>().single()

        assertEquals("Streaming thinking", reasoning.reasoning)
        assertEquals("rs_stream", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
    }

    @Test
    fun `volc response api should keep reasoning effort when non auto`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.LOW)
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("low", reasoning!!["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `function tools and built-in tools should coexist in the same tools array`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams(
                tools = listOf(createFunctionTool("get_weather")),
                builtInTools = setOf(BuiltInTools.Search)
            )
        )

        val tools = requestBody["tools"]?.jsonArray
        assertTrue("tools should exist", tools != null)
        val types = tools!!.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue("function tool should not be dropped", types.contains("function"))
        assertTrue("built-in web_search should be present", types.contains("web_search"))
        assertEquals(2, tools.size)
    }

    @Test
    fun `function tools should be sent when no built-in tools configured`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams(tools = listOf(createFunctionTool("get_weather")))
        )

        val tools = requestBody["tools"]?.jsonArray
        assertEquals(1, tools?.size)
        assertEquals("function", tools!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertFalse(tools[0].jsonObject["strict"]?.jsonPrimitive?.content?.toBoolean() ?: true)
    }

    @Test
    fun `tools key should be absent when neither function nor built-in tools exist`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams()
        )

        assertFalse("tools key should not be written", requestBody.containsKey("tools"))
    }

    // ==================== Helper Functions ====================

    private fun createToolParams(
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet()
    ): TextGenerationParams = TextGenerationParams(
        model = Model(
            modelId = "test-model",
            displayName = "test-model",
            abilities = listOf(ModelAbility.TOOL),
            tools = builtInTools
        ),
        tools = tools
    )

    private fun createFunctionTool(name: String): Tool = Tool(
        name = name,
        description = "test tool",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() }
    )

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}
