package me.rerere.ai.provider.providers.openai
import me.rerere.ai.testsupport.executedTool
import me.rerere.ai.testsupport.canonicalMultiRoundToolTurn
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.freeze
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.AttachmentProjectionTextMetadata
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.OpenAIResponseMetadata
import me.rerere.ai.ui.OpenAIResponseSourceProfile
import me.rerere.ai.ui.OpenAIResponseWireFormat
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.testsupport.toModelRequest
import me.rerere.ai.testsupport.toModelRequests
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.mergeMessageMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.CustomBodyReservedKeyException
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Response API 请求序列化与 parse→replay 往返契约（serializer 与 parser 分离）：`buildMessages` /
 * `buildRequestBody` 把 `ModelRequestMessage` 投影为 OpenAI Responses wire（function_call /
 * function_call_output 项、reasoning summary/encrypted、endpoint profile、tools 数组、custom body），
 * 以及把已解析的响应项回放进下一次请求的往返契约。
 * 纯响应解析契约在 `ResponseAPIParserTest`。
 */
class ResponseAPISerializerTest {

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
        return api.buildMessages(messages.toModelRequests(), resolveResponseEndpointProfile(host))
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, listOf(UIMessage.user("hello")).toModelRequests(), params, stream)
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
        val messages = canonicalMultiRoundToolTurn()

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
                executedTool("call_abc", "my_tool", """{"x": 1}""", "result")
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
    fun `parallel tool calls should keep all calls before their outputs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                executedTool("call_1", "tool_a", "{}", "Result A"),
                executedTool("call_2", "tool_b", "{}", "Result B"),
                executedTool("call_3", "tool_c", "{}", "Result C"),
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

        val toolItems = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content in setOf("function_call", "function_call_output")
        }.map { it.jsonObject }
        assertEquals(
            listOf(
                "function_call",
                "function_call",
                "function_call",
                "function_call_output",
                "function_call_output",
                "function_call_output",
            ),
            toolItems.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("call_1", "call_2", "call_3", "call_1", "call_2", "call_3"),
            toolItems.map { it["call_id"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                executedTool("call_1", "test", "{}", "output"),
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
                    executedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    executedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    executedTool("step3", "finalize", "{}", "done"),
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
        assertEquals(
            "reasoning.encrypted_content",
            requestBody["include"]?.jsonArray?.single()?.jsonPrimitive?.content,
        )
        assertFalse(resolveResponseEndpointProfile("ark.cn-beijing.volces.com").supportsMultimodalFunctionOutput)
    }

    @Test
    fun `generic compatible responses profile enables multimodal function output`() {
        val profile = resolveResponseEndpointProfile("proxy.example.com")
        assertEquals(ResponseEndpointProfile.OPENAI_COMPATIBLE, profile)
        assertTrue(profile.supportsMultimodalFunctionOutput)
    }

    @Test
    fun `known non-standard responses profiles keep multimodal function output disabled`() {
        assertFalse(resolveResponseEndpointProfile("ark.cn-beijing.volces.com").supportsMultimodalFunctionOutput)
        assertFalse(resolveResponseEndpointProfile("api.deepseek.com").supportsMultimodalFunctionOutput)
        assertFalse(resolveResponseEndpointProfile("api.xiaomimimo.com").supportsMultimodalFunctionOutput)
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
    fun `openai responses should map base gpt5 and old o series to supported off fallbacks`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")
        val gpt5Body = invokeBuildRequestBody(
            providerSetting = provider,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.OFF, modelId = "gpt-5")
        )
        val o3Body = invokeBuildRequestBody(
            providerSetting = provider,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.OFF, modelId = "o3")
        )

        assertEquals("minimal", gpt5Body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
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
        val endpointProfile = resolveResponseEndpointProfile("proxy.example.com")

        assertEquals(ResponseEndpointProfile.OPENAI_COMPATIBLE, endpointProfile)
        assertTrue(endpointProfile.supportsReasoningSummary)
        assertTrue(endpointProfile.supportsEncryptedContent)
        assertFalse(endpointProfile.usesReasoningTextContent)
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
    fun `deepseek responses should map off and supported effort values`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com")
        val expected = mapOf(
            ReasoningLevel.OFF to "none",
            ReasoningLevel.AUTO to null,
            ReasoningLevel.LOW to "low",
            ReasoningLevel.MEDIUM to "high",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "high",
            ReasoningLevel.MAX to "max",
        )

        expected.forEach { (level, effort) ->
            val body = invokeBuildRequestBody(
                providerSetting = provider,
                params = createReasoningParams(level, "deepseek-v4-flash"),
            )
            assertEquals(level.name, effort, body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `responses raw state should not cross endpoint source profiles`() {
        val rawItem = buildJsonObject {
            put("type", "web_search_call")
            put("id", "ws_1")
            put("status", "completed")
        }
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("visible answer")),
            providerMetadata = OpenAIResponseMetadata(
                wireFormat = OpenAIResponseWireFormat.OPENAI,
                outputItemGroups = listOf(listOf(rawItem)),
                sourceProfile = OpenAIResponseSourceProfile.OPENAI,
            ).toMetadata(),
        )

        val arkReplay = invokeBuildMessages(listOf(message), host = "ark.cn-beijing.volces.com")
        assertFalse(arkReplay.any { it.jsonObject["id"]?.jsonPrimitive?.content == "ws_1" })
        assertEquals("assistant", arkReplay.single().jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("visible answer", arkReplay.single().jsonObject["content"]?.jsonPrimitive?.content)

        val openAIReplay = invokeBuildMessages(listOf(message), host = "api.openai.com")
        assertEquals("ws_1", openAIReplay.single().jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `responses part-level opaque reasoning should not cross endpoint source profiles`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "visible summary",
                    metadata = OpenAIReasoningMetadata(
                        reasoningId = "rs_openai",
                        encryptedContent = "encrypted_openai",
                        sourceProfile = OpenAIResponseSourceProfile.OPENAI,
                    ).toMetadata(),
                ),
                UIMessagePart.Text("visible answer"),
            ),
        )

        val arkReasoning = invokeBuildMessages(
            listOf(message),
            host = "ark.cn-beijing.volces.com",
        ).first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }.jsonObject
        assertFalse(arkReasoning.containsKey("id"))
        assertFalse(arkReasoning.containsKey("encrypted_content"))
        assertEquals(
            "visible summary",
            arkReasoning["summary"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content,
        )

        val openAIReasoning = invokeBuildMessages(
            listOf(message),
            host = "api.openai.com",
        ).first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }.jsonObject
        assertEquals("rs_openai", openAIReasoning["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted_openai", openAIReasoning["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `legacy responses metadata without source should remain replayable`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            providerMetadata = OpenAIResponseMetadata(
                wireFormat = OpenAIResponseWireFormat.OPENAI,
                outputItemGroups = listOf(listOf(buildJsonObject {
                    put("type", "message")
                    put("id", "legacy_msg")
                    put("role", "assistant")
                    put("content", JsonArray(emptyList()))
                })),
            ).toMetadata(),
        )

        val replay = invokeBuildMessages(listOf(message), host = "ark.cn-beijing.volces.com")
        assertEquals("legacy_msg", replay.single().jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek response history should encode reasoning as reasoning_text content`() {
        val messages = listOf(
            UIMessage.user("Question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(
                        reasoning = "Thinking",
                        metadata = OpenAIReasoningMetadata(reasoningId = "rs_history").toMetadata(),
                    ),
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
        assertEquals("rs_history", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals(1, reasoningContent?.size)
        assertEquals("reasoning_text", reasoningContent!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Thinking", reasoningContent[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek function output should remain a string when a tool returns an image`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call_image",
                    toolName = "capture",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text("Captured image"),
                        UIMessagePart.Text("[Attachment path=/upload/abc123.png type=image input=reference_only]"),
                    ),
                )
            ),
        )

        val output = invokeBuildMessages(
            listOf(assistant),
            host = "api.deepseek.com",
        ).single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }.jsonObject

        assertEquals(
            "Captured image\n[Attachment path=/upload/abc123.png type=image input=reference_only]",
            output["output"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `standard compatible response encodes multimodal function output array`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call_image",
                    toolName = "capture",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text("Captured image"),
                        UIMessagePart.Image("data:image/png;base64,AQ=="),
                    ),
                ),
            ),
        )
        val capabilities = RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            assistantImages = RequestImageSupport.OPAQUE_REPLAY_ONLY,
            toolOutputImages = RequestImageSupport.STRUCTURED,
        )

        val output = api.buildMessages(
            messages = listOf(assistant.toModelRequest()),
            endpointProfile = resolveResponseEndpointProfile("proxy.example.com"),
            mediaCapabilities = capabilities,
        ).single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }.jsonObject
        val content = output["output"]!!.jsonArray

        assertEquals(listOf("input_text", "input_image"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("Captured image", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,AQ==", content[1].jsonObject["image_url"]!!.jsonPrimitive.content)
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
    fun `deepseek parsed reasoning item should be replayed with tool call and id`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_tool",
              "model": "deepseek-v4-flash",
              "output": [
                {
                  "id": "rs_tool",
                  "type": "reasoning",
                  "content": [
                    {"type": "reasoning_text", "text": "Need the tool"}
                  ]
                },
                {
                  "id": "fc_item",
                  "type": "function_call",
                  "call_id": "call_tool",
                  "name": "lookup",
                  "arguments": "{}"
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        val parsed = api.parseResponseOutput(
            response,
            ResponseEndpointProfile.DEEPSEEK,
        ).choices.single().message!!
        val executedParts = parsed.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                part.copy(output = listOf(UIMessagePart.Text("result")))
            } else {
                part
            }
        }

        val replay = invokeBuildMessages(
            listOf(UIMessage.user("Question"), parsed.copy(parts = executedParts)),
            host = "api.deepseek.com",
        )
        val replayItems = replay.map { it.jsonObject }
        val reasoning = replayItems.first { it["type"]?.jsonPrimitive?.content == "reasoning" }

        assertEquals(
            listOf("message", "reasoning", "function_call", "function_call_output"),
            replayItems.map { it["type"]?.jsonPrimitive?.content ?: "message" },
        )
        assertEquals("rs_tool", reasoning["id"]?.jsonPrimitive?.content)
        assertEquals(
            "Need the tool",
            reasoning["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `openai store false replay should preserve every response output item`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_raw",
              "model": "gpt-5",
              "output": [
                {
                  "id": "ws_1",
                  "type": "web_search_call",
                  "status": "completed",
                  "action": {"type": "search", "query": "official docs"}
                },
                {
                  "id": "fc_1",
                  "type": "function_call",
                  "call_id": "call_1",
                  "name": "lookup",
                  "arguments": "{}",
                  "status": "completed"
                },
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "phase": "final_answer",
                  "status": "completed",
                  "content": [{"type": "output_text", "text": "Result", "annotations": []}]
                },
                {
                  "id": "future_1",
                  "type": "future_output_item",
                  "provider_field": {"kept": true}
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        val parsed = api.parseResponseOutput(response).choices.single().message!!
        val executed = parsed.copy(
            parts = parsed.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(output = listOf(UIMessagePart.Text("tool result")))
                } else {
                    part
                }
            }
        )

        val replay = invokeBuildMessages(listOf(UIMessage.user("Question"), executed))
            .drop(1)
            .map { it.jsonObject }

        assertEquals(
            listOf(
                "web_search_call",
                "function_call",
                "message",
                "future_output_item",
                "function_call_output",
            ),
            replay.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals("official docs", replay[0]["action"]?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals("final_answer", replay[2]["phase"]?.jsonPrimitive?.content)
        assertTrue(replay[3]["provider_field"]?.jsonObject?.get("kept")?.jsonPrimitive?.content == "true")
        assertEquals("call_1", replay[4]["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `raw response replay appends only request projection text in assistant role`() {
        val toolMarker = "[Attachment path=/upload/tool.png type=image input=reference_only]"
        val assistantMarker =
            "[Attachment path=/upload/assistant.png type=image input=reference_only]"
        val rawImageItem = buildJsonObject {
            put("id", "ig_1")
            put("type", "image_generation_call")
            put("status", "completed")
            put("result", "opaque-image-data")
        }
        val rawFunctionCall = buildJsonObject {
            put("id", "fc_1")
            put("type", "function_call")
            put("call_id", "call_1")
            put("name", "generate_image")
            put("arguments", "{}")
        }
        val projectionMetadata =
            AttachmentProjectionTextMetadata(attachmentProjectionText = true).toMetadata()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("visible response already represented by raw output"),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call_1",
                    toolName = "generate_image",
                    input = "{}",
                    output = listOf(UIMessagePart.Text(toolMarker, metadata = projectionMetadata)),
                ),
                UIMessagePart.Text(assistantMarker, metadata = projectionMetadata),
            ),
            providerMetadata = OpenAIResponseMetadata(
                wireFormat = OpenAIResponseWireFormat.OPENAI,
                outputItemGroups = listOf(listOf(rawImageItem, rawFunctionCall)),
                sourceProfile = OpenAIResponseSourceProfile.OPENAI,
            ).toMetadata(),
        )

        val replay = invokeBuildMessages(listOf(message)).map { it.jsonObject }

        assertEquals(
            listOf("image_generation_call", "function_call", "function_call_output", "message"),
            replay.map { it["type"]?.jsonPrimitive?.content ?: "message" },
        )
        assertEquals(toolMarker, replay[2]["output"]?.jsonPrimitive?.content)
        assertEquals("assistant", replay[3]["role"]?.jsonPrimitive?.content)
        assertEquals(assistantMarker, replay[3]["content"]?.jsonPrimitive?.content)
        assertTrue(replay.none { item ->
            item["content"]?.jsonPrimitive?.content ==
                "visible response already represented by raw output"
        })
    }

    @Test
    fun `rebuilt assistant image remains attributed to assistant`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("assistant attachment fact"),
                UIMessagePart.Text("[Attachment path=/upload/abc123.png type=image input=reference_only]"),
            ),
        )

        val replay = invokeBuildMessages(listOf(message)).map { it.jsonObject }

        assertEquals(listOf("assistant"), replay.map { it["role"]?.jsonPrimitive?.content })
        val content = replay.single()["content"]!!.jsonArray
        assertEquals("output_text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("assistant attachment fact", content[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(
            "[Attachment path=/upload/abc123.png type=image input=reference_only]",
            content[1].jsonObject["text"]?.jsonPrimitive?.content,
        )
        assertTrue(content.none { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" })
    }

    @Test
    fun `raw response items should not cross incompatible endpoint wire formats`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_deepseek",
              "model": "deepseek-v4-flash",
              "output": [
                {
                  "id": "rs_deepseek",
                  "type": "reasoning",
                  "content": [{"type": "reasoning_text", "text": "Thinking"}]
                },
                {
                  "id": "msg_deepseek",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "output_text", "text": "Answer"}]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        val parsed = api.parseResponseOutput(
            response,
            ResponseEndpointProfile.DEEPSEEK,
        ).choices.single().message!!

        val replay = invokeBuildMessages(listOf(parsed), host = "api.openai.com")
        val reasoning = replay.single {
            it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning"
        }.jsonObject

        assertTrue(reasoning.containsKey("summary"))
        assertFalse(reasoning.containsKey("content"))
    }

    @Test
    fun `raw replay should keep repeated compatible call ids at their tool boundaries`() {
        val response = Json.parseToJsonElement(
            """
            {
              "id": "resp_reused",
              "model": "proxy-model",
              "output": [
                {
                  "id": "fc_a",
                  "type": "function_call",
                  "call_id": "call_reused",
                  "name": "first",
                  "arguments": "{}"
                },
                {
                  "id": "fc_b",
                  "type": "function_call",
                  "call_id": "call_reused",
                  "name": "second",
                  "arguments": "{}"
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        val parsed = api.parseResponseOutput(response).choices.single().message!!
        val executed = parsed.copy(
            parts = parsed.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(output = listOf(UIMessagePart.Text("${part.toolName} result")))
                } else {
                    part
                }
            }
        )

        val replay = invokeBuildMessages(listOf(executed)).map { it.jsonObject }

        assertEquals(
            listOf("function_call", "function_call", "function_call_output", "function_call_output"),
            replay.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals("first result", replay[2]["output"]?.jsonPrimitive?.content)
        assertEquals("second result", replay[3]["output"]?.jsonPrimitive?.content)
    }

    @Test
    fun `raw replay should keep response batch boundaries across tool continuations`() {
        fun response(id: String, output: String) = Json.parseToJsonElement(
            """
            {
              "id": "$id",
              "model": "gpt-5",
              "output": $output
            }
            """.trimIndent()
        ).jsonObject

        val firstChunk = api.parseResponseOutput(response(
            id = "resp_tools",
            output = """
              [
                {"id":"fc_1","type":"function_call","call_id":"call_1","name":"first","arguments":"{}"},
                {"id":"fc_2","type":"function_call","call_id":"call_2","name":"second","arguments":"{}"}
              ]
            """.trimIndent(),
        ))
        val firstMessage = firstChunk.choices.single().message!!.copy(
            parts = firstChunk.choices.single().message!!.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(output = listOf(UIMessagePart.Text("${part.toolName} result")))
                } else {
                    part
                }
            }
        )
        val secondChunk = api.parseResponseOutput(response(
            id = "resp_answer",
            output = """
              [
                {"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Done"}]}
              ]
            """.trimIndent(),
        ))
        // The merged transcript (tools + trailing answer) is composed directly; streaming merge is
        // the app StepOutputAccumulator's contract. This test locks the Response-API wire replay order,
        // which reads the combined raw output-item groups (batch boundaries preserved).
        val secondMessage = secondChunk.choices.single().message!!
        val merged = firstMessage.copy(
            parts = firstMessage.parts + secondMessage.parts,
            providerMetadata = mergeMessageMetadata(firstMessage.providerMetadata, secondMessage.providerMetadata),
        )

        val replay = invokeBuildMessages(listOf(merged)).map { it.jsonObject }

        assertEquals(
            listOf(
                "function_call",
                "function_call",
                "function_call_output",
                "function_call_output",
                "message",
            ),
            replay.map { it["type"]?.jsonPrimitive?.content ?: "message" },
        )
        assertEquals(
            listOf(listOf("fc_1", "fc_2"), listOf("msg_1")),
            merged.metadataAs<OpenAIResponseMetadata>()?.outputItemGroups?.map { group ->
                group.map { it["id"]?.jsonPrimitive?.content }
            },
        )
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
        assertFalse(replay.containsKey("summary"))
        assertEquals("encrypted-only-state", replay["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `encrypted reasoning should not replay plaintext summary`() {
        val reasoningItem = invokeBuildMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(
                            reasoning = "plaintext reasoning",
                            metadata = OpenAIReasoningMetadata(
                                reasoningId = "rs_1",
                                encryptedContent = "encrypted",
                            ).toMetadata(),
                        )
                    ),
                ),
            )
        ).single().jsonObject

        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_1", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertFalse(reasoningItem.containsKey("summary"))
    }

    @Test
    fun `unencrypted reasoning should replay plaintext summary`() {
        val reasoningItem = invokeBuildMessages(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning(
                            reasoning = "plaintext reasoning",
                            metadata = OpenAIReasoningMetadata(reasoningId = "rs_1").toMetadata(),
                        )
                    ),
                ),
            )
        ).single().jsonObject

        val summary = reasoningItem["summary"]?.jsonArray
        assertEquals(1, summary?.size)
        assertEquals(
            "plaintext reasoning",
            summary?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content,
        )
        assertFalse(reasoningItem.containsKey("encrypted_content"))
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

        // Parser contract: the call_id (not the fc_* item id) is what the parser stamps on every
        // function_call delta, the added event carries the name, and the done event carries the full
        // arguments. Cross-event assembly is the app StepOutputAccumulator's contract.
        val tools = events.flatMap { it.choices.single().delta!!.parts }.filterIsInstance<UIMessagePart.Tool>()
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.all { it.providerCallId == "call_123" })
        assertEquals("lookup", tools.first().toolName)
        assertEquals("{\"query\":\"test\"}", tools.last().input)

        val mergedTool = tools.first().copy(
            input = "{\"query\":\"test\"}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val replay = invokeBuildMessages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(mergedTool)))
        )
        assertEquals(
            listOf("call_123", "call_123"),
            replay.map { it.jsonObject["call_id"]?.jsonPrimitive?.content }
        )
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
    fun `response function tool preserves JSON Schema definitions and references`() {
        val schema = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("\$ref", "#/\$defs/query") })
            })
            put("\$defs", buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
            })
        }
        val tool = Tool(
            name = "search",
            description = "search",
            parameters = { schema },
            execute = { emptyList() },
        )

        val body = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1"),
            params = createToolParams(tools = listOf(tool)),
        )
        val sent = body["tools"]!!.jsonArray.single().jsonObject["parameters"]!!.jsonObject

        assertEquals("#/\$defs/query", sent["properties"]!!.jsonObject["query"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content)
        assertEquals("string", sent["\$defs"]!!.jsonObject["query"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://json-schema.org/draft/2020-12/schema", sent["\$schema"]!!.jsonPrimitive.content)
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
        tools = tools.map { it.freeze() }
    )

    private fun createFunctionTool(name: String): Tool = Tool(
        name = name,
        description = "test tool",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() }
    )

    // ==================== Custom Body Ownership Tests ====================

    private fun invokeBuildRequestBodyWithCustomBody(
        customBody: List<CustomBody>,
    ): JsonObject {
        val model = Model(
            modelId = "gpt-5",
            displayName = "GPT-5",
            abilities = emptyList(),
        )
        val params = TextGenerationParams(
            model = model,
            customBody = customBody,
        )
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1",
            useResponseApi = true,
            models = listOf(model),
        )
        val messages = listOf(UIMessage.user("hello")).toModelRequests()
        return api.buildRequestBody(providerSetting, messages, params, false)
    }

    @Test
    fun `responses custom body with reserved key input throws before request`() {
        var caught: Throwable? = null
        try {
            invokeBuildRequestBodyWithCustomBody(
                listOf(CustomBody("input", JsonPrimitive("[]"))),
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            caught = e.cause
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue(
            "expected CustomBodyReservedKeyException, got $caught",
            caught is CustomBodyReservedKeyException,
        )
        assertTrue(
            (caught as CustomBodyReservedKeyException).conflictingKeys.contains("input"),
        )
    }

    @Test
    fun `responses custom body with reserved key tools throws before request`() {
        var caught: Throwable? = null
        try {
            invokeBuildRequestBodyWithCustomBody(
                listOf(CustomBody("tools", JsonPrimitive("[]"))),
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            caught = e.cause
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue(caught is CustomBodyReservedKeyException)
    }

    @Test
    fun `responses custom body with non-reserved key still merges`() {
        val body = invokeBuildRequestBodyWithCustomBody(
            listOf(CustomBody("temperature", JsonPrimitive(0.5))),
        )
        assertTrue(body.containsKey("temperature"))
    }
}

