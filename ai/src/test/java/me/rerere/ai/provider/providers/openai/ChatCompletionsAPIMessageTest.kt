package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 */
class ChatCompletionsAPIMessageTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper for the compatible-provider default message format.
    private fun invokeBuildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        modelId: String = "test-model",
        host: String = "proxy.example.com",
        includeOpenRouterReasoningDetails: Boolean = false,
    ): JsonArray {
        return api.buildMessages(
            messages = messages,
            includeHistoryReasoning = includeHistoryReasoning,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            requiresToolReasoningReplay = requiresToolReasoningReplay(host, modelId),
            includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
        )
    }

    private fun invokeBuildRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
    ) = ChatCompletionsAPI::class.java.getDeclaredMethod(
        "buildChatCompletionRequest",
        List::class.java,
        TextGenerationParams::class.java,
        ProviderSetting.OpenAI::class.java,
        Boolean::class.javaPrimitiveType,
    ).run {
        isAccessible = true
        invoke(api, messages, params, providerSetting, false) as kotlinx.serialization.json.JsonObject
    }

    @Test
    fun `attachment facts retain user assistant and tool protocol roles`() {
        val userFact = "user attachment fact"
        val assistantFact = "assistant attachment fact"
        val toolFact = "tool attachment fact"
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(userFact))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text(assistantFact),
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "generate_image",
                        input = "{}",
                        output = listOf(UIMessagePart.Text(toolFact)),
                    ),
                ),
            ),
        )

        val result = invokeBuildMessages(messages).map { it.jsonObject }

        assertEquals(listOf("user", "assistant", "tool"), result.map { it["role"]?.jsonPrimitive?.content })
        assertEquals(userFact, result[0]["content"]?.jsonPrimitive?.content)
        assertEquals(assistantFact, result[1]["content"]?.jsonPrimitive?.content)
        assertEquals(toolFact, result[2]["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat completions rejects native tool images under closed capability matrix`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "image",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image("file:///tmp/generated.png")),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            invokeBuildMessages(
                listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))),
            )
        }
        assertTrue(error.message.orEmpty().contains("native tool image"))
    }

    @Test
    fun `multi-round reasoning and tool calls should be correctly ordered`() {
        // Scenario: Assistant message with multiple rounds of reasoning and tool calls
        // [Reasoning1, Text1, Tool1(executed), Reasoning2, Text2, Tool2(executed), Text3]
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("I'll search for information"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result 1"),
                UIMessagePart.Reasoning(reasoning = "Now I need to calculate..."),
                UIMessagePart.Text("Let me calculate that"),
                createExecutedTool("call_2", "calculate", """{"expr": "1+1"}""", "2"),
                UIMessagePart.Text("The final answer is 2")
            )
        )

        val messages = listOf(
            UIMessage.user("What is 1+1?"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Result should contain:
        // 1. User message
        // 2. Assistant message with reasoning_content, content, and tool_calls for search
        // 3. Tool result for search
        // 4. Assistant message with reasoning_content, content, and tool_calls for calculate
        // 5. Tool result for calculate
        // 6. Assistant message with final text

        assertTrue("Should have at least 6 messages", result.size >= 6)

        // Verify user message
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        // Verify first assistant message (with first tool call)
        val assistant1 = result[1].jsonObject
        assertEquals("assistant", assistant1["role"]?.jsonPrimitive?.content)
        assertTrue("First assistant message should have tool_calls", assistant1.containsKey("tool_calls"))
        val toolCalls1 = assistant1["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls1?.size)
        assertEquals("search", toolCalls1?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify first tool result
        val toolResult1 = result[2].jsonObject
        assertEquals("tool", toolResult1["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", toolResult1["tool_call_id"]?.jsonPrimitive?.content)

        // Verify second assistant message (with second tool call)
        val assistant2 = result[3].jsonObject
        assertEquals("assistant", assistant2["role"]?.jsonPrimitive?.content)
        assertTrue("Second assistant message should have tool_calls", assistant2.containsKey("tool_calls"))
        val toolCalls2 = assistant2["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls2?.size)
        assertEquals("calculate", toolCalls2?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify second tool result
        val toolResult2 = result[4].jsonObject
        assertEquals("tool", toolResult2["role"]?.jsonPrimitive?.content)
        assertEquals("call_2", toolResult2["tool_call_id"]?.jsonPrimitive?.content)

        // Verify final assistant message
        val assistant3 = result[5].jsonObject
        assertEquals("assistant", assistant3["role"]?.jsonPrimitive?.content)
        val content = assistant3["content"]
        assertTrue("Final assistant content should contain 'final answer'",
            content?.jsonPrimitive?.content?.contains("final answer") == true ||
            (content is JsonArray && content.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("final answer") == true })
        )
    }

    @Test
    fun `parallel tool calls should be grouped together`() {
        // Scenario: Multiple tools called in parallel
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search multiple sources"),
                createExecutedTool("call_1", "search_web", """{"query": "test1"}""", "Result 1"),
                createExecutedTool("call_2", "search_docs", """{"query": "test2"}""", "Result 2"),
                createExecutedTool("call_3", "search_wiki", """{"query": "test3"}""", "Result 3"),
                UIMessagePart.Text("Combined results show...")
            )
        )

        val messages = listOf(
            UIMessage.user("Search everything"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify parallel tools are in same assistant message
        var foundAssistantWithMultipleTools = false
        for (element in result) {
            val msg = element.jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant") {
                val toolCalls = msg["tool_calls"]?.jsonArray
                if (toolCalls != null && toolCalls.size == 3) {
                    foundAssistantWithMultipleTools = true
                    // Verify all three tool calls are present
                    val toolNames = toolCalls.map {
                        it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    }
                    assertTrue(toolNames.contains("search_web"))
                    assertTrue(toolNames.contains("search_docs"))
                    assertTrue(toolNames.contains("search_wiki"))
                    break
                }
            }
        }
        assertTrue("Should have assistant message with 3 parallel tool calls", foundAssistantWithMultipleTools)

        // Verify 3 separate tool result messages
        val toolResults = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
        }
        assertEquals(3, toolResults.size)
    }

    @Test
    fun `reasoning should be included for all assistant messages when history reasoning enabled`() {
        val messages = createMultiRoundReasoningMessages()

        val result = invokeBuildMessages(messages, includeHistoryReasoning = true)

        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)
        assertEquals("Initial thinking",
            assistantMessages[0].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("Final thinking",
            assistantMessages[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning should be excluded from all assistant messages when history reasoning disabled`() {
        val messages = createMultiRoundReasoningMessages()

        val result = invokeBuildMessages(messages, includeHistoryReasoning = false)

        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)
        assistantMessages.forEach { msg ->
            assertFalse("Assistant should not have reasoning_content",
                msg.jsonObject.containsKey("reasoning_content"))
        }
    }

    @Test
    fun `deepseek v4 tool reasoning should be replayed when history reasoning disabled`() {
        val messages = listOf(
            UIMessage.user("Use a tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Tool-step thinking"),
                    UIMessagePart.Text("Calling the tool"),
                    createExecutedTool("call_1", "lookup", "{}", "result"),
                    UIMessagePart.Reasoning(reasoning = "Final thinking"),
                    UIMessagePart.Text("Done")
                )
            )
        )

        val result = invokeBuildMessages(
            messages = messages,
            includeHistoryReasoning = false,
            modelId = "Pro/deepseek-ai/DeepSeek-V4-Flash"
        )
        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)
        assertEquals(
            "Tool-step thinking",
            assistantMessages[0].jsonObject["reasoning_content"]?.jsonPrimitive?.content
        )
        assertTrue(assistantMessages[0].jsonObject.containsKey("tool_calls"))
        assertFalse(assistantMessages[1].jsonObject.containsKey("reasoning_content"))
    }

    @Test
    fun `deepseek v4 should combine all reasoning fragments before a tool call`() {
        val messages = listOf(
            UIMessage.user("Use a tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "First "),
                    UIMessagePart.Reasoning(reasoning = "second"),
                    createExecutedTool("call_1", "lookup", "{}", "result")
                )
            )
        )

        val result = invokeBuildMessages(
            messages = messages,
            includeHistoryReasoning = false,
            modelId = "deepseek-v4-pro"
        )
        val toolAssistant = result.first {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" &&
                    it.jsonObject.containsKey("tool_calls")
        }.jsonObject

        assertEquals("First second", toolAssistant["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek assistant envelope should keep content before tool boundary`() {
        val parsed = api.parseMessage(
            Json.parseToJsonElement(
                """
                {
                  "role": "assistant",
                  "reasoning_content": "Need lookup",
                  "content": "Calling lookup",
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "lookup", "arguments": "{}"}
                  }]
                }
                """.trimIndent()
            ).jsonObject
        )

        assertEquals(
            listOf(
                UIMessagePart.Reasoning::class,
                UIMessagePart.Text::class,
                UIMessagePart.Tool::class,
            ),
            parsed.parts.map { it::class },
        )

        val executed = parsed.copy(
            parts = parsed.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(output = listOf(UIMessagePart.Text("lookup result")))
                } else {
                    part
                }
            }
        )
        val history = invokeBuildMessages(
            messages = listOf(UIMessage.user("Use a tool"), executed),
            includeHistoryReasoning = false,
            modelId = "deepseek-v4-flash",
            host = "api.deepseek.com",
        )
        val assistant = history.first {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }.jsonObject

        assertEquals("Need lookup", assistant["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("Calling lookup", assistant["content"]?.jsonPrimitive?.content)
        assertEquals("call-1", assistant["tool_calls"]?.jsonArray?.single()
            ?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals("tool", history[2].jsonObject["role"]?.jsonPrimitive?.content)
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
        assertEquals("call-1", tool.toolCallId)
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
    fun `deepseek replay should survive tool delta arriving before reasoning and content`() {
        var messages = listOf(UIMessage.user("Use a tool"))
        listOf(
            """
            {
              "role": "assistant",
              "tool_calls": [{
                "id": "call-1",
                "type": "function",
                "function": {"name": "lookup", "arguments": "{}"}
              }]
            }
            """,
            """{"role":"assistant","reasoning_content":"Late reasoning"}""",
            """{"role":"assistant","content":"Late content"}""",
        ).forEach { deltaJson ->
            messages = messages.handleMessageChunk(
                MessageChunk(
                    id = "chunk",
                    model = "deepseek-v4-flash-free",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = api.parseMessage(
                                Json.parseToJsonElement(deltaJson.trimIndent()).jsonObject
                            ),
                            message = null,
                            finishReason = null,
                        )
                    ),
                )
            )
        }
        messages = messages.dropLast(1) + messages.last().copy(
            parts = messages.last().parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(output = listOf(UIMessagePart.Text("result")))
                } else {
                    part
                }
            }
        )

        val history = invokeBuildMessages(
            messages = messages,
            includeHistoryReasoning = false,
            modelId = "deepseek-v4-flash-free",
            host = "api.deepseek.com",
        )
        val assistant = history.first {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }.jsonObject

        assertEquals("Late reasoning", assistant["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("Late content", assistant["content"]?.jsonPrimitive?.content)
        assertTrue(assistant.containsKey("tool_calls"))
    }

    @Test
    fun `parallel tool argument deltas should merge by official tool call index`() {
        val streamState = ChatCompletionsStreamState()
        var messages = listOf(UIMessage.user("Use parallel tools"))
        listOf(
            """{"role":"assistant","tool_calls":[{"index":0,"id":"call-0","type":"function","function":{"name":"first","arguments":"{"}}]}""",
            """{"role":"assistant","tool_calls":[{"index":1,"id":"call-1","type":"function","function":{"name":"second","arguments":"["}}]}""",
            """{"role":"assistant","tool_calls":[{"index":0,"function":{"arguments":"}"}}]}""",
            """{"role":"assistant","tool_calls":[{"index":1,"function":{"arguments":"]"}}]}""",
        ).forEach { deltaJson ->
            messages = messages.handleMessageChunk(
                MessageChunk(
                    id = "chunk",
                    model = "gpt-5",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = api.parseMessage(
                                Json.parseToJsonElement(deltaJson).jsonObject,
                                streamState,
                            ),
                            message = null,
                            finishReason = null,
                        )
                    ),
                )
            )
        }

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf("call-0", "call-1"), tools.map { it.toolCallId })
        assertEquals(listOf("first", "second"), tools.map { it.toolName })
        assertEquals(listOf("{}", "[]"), tools.map { it.input })
    }

    @Test
    fun `non deepseek tool reasoning should remain excluded when history reasoning disabled`() {
        val messages = listOf(
            UIMessage.user("Use a tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Optional thinking"),
                    createExecutedTool("call_1", "lookup", "{}", "result")
                )
            )
        )

        val result = invokeBuildMessages(
            messages = messages,
            includeHistoryReasoning = false,
            modelId = "gpt-5"
        )
        val toolAssistant = result.first {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }.jsonObject

        assertFalse(toolAssistant.containsKey("reasoning_content"))
    }

    @Test
    fun `direct deepseek host should require tool reasoning for a custom model alias`() {
        val messages = listOf(
            UIMessage.user("Use a tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Required thinking"),
                    createExecutedTool("call_1", "lookup", "{}", "result")
                )
            )
        )

        val result = invokeBuildMessages(
            messages = messages,
            includeHistoryReasoning = false,
            modelId = "company-model-alias",
            host = "api.deepseek.com"
        )
        val toolAssistant = result.first {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }.jsonObject

        assertEquals("Required thinking", toolAssistant["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `compatible proxy replays tool reasoning for deepseek v4 flash vision exp`() {
        val messages = listOf(
            UIMessage.user("Use a tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Required vision thinking"),
                    createExecutedTool("call_1", "inspect_image", "{}", "result"),
                ),
            ),
        )

        val result = invokeBuildMessages(
            messages = messages,
            includeHistoryReasoning = false,
            modelId = "deepseek-v4-flash-vision-exp",
            host = "proxy.example.com",
        )
        val toolAssistant = result.first {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }.jsonObject

        assertEquals(
            "Required vision thinking",
            toolAssistant["reasoning_content"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `official openai chat request should use official fields and suppress compatibility extensions`() {
        val messages = listOf(
            UIMessage.system("Follow the application instructions"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "private reasoning"),
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = "lookup",
                        input = "{}",
                        output = listOf(
                            UIMessagePart.Text("[Attachment ref=attachment:1 type=image input=reference_only]"),
                        ),
                    )
                )
            )
        )
        val body = invokeBuildRequest(
            messages = messages,
            params = TextGenerationParams(
                model = Model(
                    modelId = "gpt-5",
                    displayName = "gpt-5",
                    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    abilities = listOf(ModelAbility.REASONING),
                ),
                maxTokens = 123,
                reasoningLevel = ReasoningLevel.OFF,
            ),
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://api.openai.com/v1",
                includeHistoryReasoning = true,
            ),
        )

        assertEquals(123, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertFalse(body.containsKey("max_tokens"))
        assertEquals("developer", body["messages"]!!.jsonArray[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("minimal", body["reasoning_effort"]?.jsonPrimitive?.content)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertFalse(assistant.containsKey("reasoning_content"))
        val toolResult = body["messages"]!!.jsonArray[2].jsonObject["content"] as JsonPrimitive
        assertEquals("[Attachment ref=attachment:1 type=image input=reference_only]", toolResult.content)
        assertFalse(toolResult.content.contains("Image output omitted"))
        assertFalse(requiresToolReasoningReplay("api.openai.com", "deepseek-v4-flash"))
    }

    @Test
    fun `request media capabilities stay closed unless the adapter proves a container`() {
        val vision = Model(
            modelId = "vision",
            displayName = "vision",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val openai = OpenAIProvider(OkHttpClient())
        val chatOfficial = openai.requestMediaCapabilities(
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            vision,
        )
        assertEquals(RequestImageSupport.STRUCTURED, chatOfficial.userImages)
        assertEquals(RequestImageSupport.NONE, chatOfficial.assistantImages)
        assertEquals(RequestImageSupport.NONE, chatOfficial.toolOutputImages)

        val chatCompatible = openai.requestMediaCapabilities(
            ProviderSetting.OpenAI(baseUrl = "https://proxy.example.com/v1"),
            vision,
        )
        assertEquals(RequestImageSupport.NONE, chatCompatible.userImages)
        assertEquals(RequestImageSupport.NONE, chatCompatible.assistantImages)
        assertEquals(RequestImageSupport.NONE, chatCompatible.toolOutputImages)

        val responsesOfficial = openai.requestMediaCapabilities(
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1", useResponseApi = true),
            vision,
        )
        assertEquals(RequestImageSupport.OPAQUE_REPLAY_ONLY, responsesOfficial.assistantImages)
        assertEquals(RequestImageSupport.STRUCTURED, responsesOfficial.toolOutputImages)

        val responsesCompatible = openai.requestMediaCapabilities(
            ProviderSetting.OpenAI(baseUrl = "https://proxy.example.com/v1", useResponseApi = true),
            vision,
        )
        assertEquals(RequestImageSupport.NONE, responsesCompatible.userImages)
        assertEquals(RequestImageSupport.NONE, responsesCompatible.assistantImages)
        assertEquals(RequestImageSupport.NONE, responsesCompatible.toolOutputImages)

        val claude = ClaudeProvider(OkHttpClient()).requestMediaCapabilities(ProviderSetting.Claude(), vision)
        assertEquals(RequestImageSupport.STRUCTURED, claude.userImages)
        assertEquals(RequestImageSupport.NONE, claude.assistantImages)
        assertEquals(RequestImageSupport.STRUCTURED, claude.toolOutputImages)

        val gemini = GoogleProvider(OkHttpClient()).requestMediaCapabilities(ProviderSetting.Google(), vision)
        assertEquals(RequestImageSupport.STRUCTURED, gemini.userImages)
        assertEquals(RequestImageSupport.STRUCTURED, gemini.assistantImages)
        assertEquals(RequestImageSupport.STRUCTURED, gemini.toolOutputImages)
    }

    @Test
    fun `compatible chat proxy should keep legacy max tokens field`() {
        val body = invokeBuildRequest(
            messages = listOf(UIMessage.system("system prompt"), UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "compatible-model", displayName = "compatible-model"),
                maxTokens = 321,
            ),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://proxy.example.com/v1"),
        )

        assertEquals(321, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertFalse(body.containsKey("max_completion_tokens"))
        assertEquals("system", body["messages"]!!.jsonArray.first().jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `standard gpt5 point releases should use current openai chat behavior`() {
        listOf("gpt-5.1", "gpt-5.2", "gpt-5.4", "gpt-5.5", "gpt-5.6").forEach { modelId ->
            val body = invokeBuildRequest(
                messages = listOf(UIMessage.system("instructions"), UIMessage.user("hello")),
                params = TextGenerationParams(
                    model = Model(
                        modelId = modelId,
                        displayName = modelId,
                        abilities = listOf(ModelAbility.REASONING),
                    ),
                    temperature = 0.7f,
                    topP = 0.8f,
                    reasoningLevel = ReasoningLevel.OFF,
                ),
                providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            )

            assertEquals(
                modelId,
                "developer",
                body["messages"]!!.jsonArray.first().jsonObject["role"]?.jsonPrimitive?.content,
            )
            assertEquals(modelId, "none", body["reasoning_effort"]?.jsonPrimitive?.content)
            assertFalse("$modelId must omit temperature", body.containsKey("temperature"))
            assertFalse("$modelId must omit top_p", body.containsKey("top_p"))
        }
    }

    @Test
    fun `official openai special variants should use only documented reasoning efforts`() {
        assertEquals("minimal", mapOfficialOpenAIReasoningEffort("gpt-5", ReasoningLevel.OFF))
        assertEquals("high", mapOfficialOpenAIReasoningEffort("gpt-5", ReasoningLevel.XHIGH))
        assertEquals("none", mapOfficialOpenAIReasoningEffort("gpt-5.1", ReasoningLevel.OFF))
        assertEquals("high", mapOfficialOpenAIReasoningEffort("gpt-5.1", ReasoningLevel.XHIGH))
        assertEquals("low", mapOfficialOpenAIReasoningEffort("gpt-5.3-codex", ReasoningLevel.OFF))
        assertEquals("xhigh", mapOfficialOpenAIReasoningEffort("gpt-5.3-codex", ReasoningLevel.XHIGH))
        assertEquals("high", mapOfficialOpenAIReasoningEffort("gpt-5-pro", ReasoningLevel.LOW))
        assertEquals("medium", mapOfficialOpenAIReasoningEffort("gpt-5.4-pro", ReasoningLevel.OFF))
        assertEquals("xhigh", mapOfficialOpenAIReasoningEffort("gpt-5.4-pro", ReasoningLevel.XHIGH))
        assertEquals("high", mapOfficialOpenAIReasoningEffort("gpt-5", ReasoningLevel.MAX))
        assertEquals("high", mapOfficialOpenAIReasoningEffort("gpt-5.1", ReasoningLevel.MAX))
        assertEquals("xhigh", mapOfficialOpenAIReasoningEffort("gpt-5.3-codex", ReasoningLevel.MAX))
        assertEquals("xhigh", mapOfficialOpenAIReasoningEffort("gpt-5.4-pro", ReasoningLevel.MAX))
        assertEquals("xhigh", mapOfficialOpenAIReasoningEffort("gpt-5.4", ReasoningLevel.MAX))
        assertNull(mapOfficialOpenAIReasoningEffort("gpt-5.3-chat-latest", ReasoningLevel.HIGH))
        assertEquals("low", mapOfficialOpenAIReasoningEffort("gpt-5.10", ReasoningLevel.OFF))
    }

    @Test
    fun `deepseek chat reasoning levels should map to documented effort values`() {
        val expected = mapOf(
            ReasoningLevel.OFF to null,
            ReasoningLevel.AUTO to null,
            ReasoningLevel.LOW to "low",
            ReasoningLevel.MEDIUM to "high",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "high",
            ReasoningLevel.MAX to "max",
        )

        expected.forEach { (level, effort) ->
            val body = invokeBuildRequest(
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(
                    model = Model(
                        modelId = "deepseek-v4-pro",
                        displayName = "deepseek-v4-pro",
                        abilities = listOf(ModelAbility.REASONING),
                    ),
                    reasoningLevel = level,
                ),
                providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com"),
            )

            assertEquals(level.name, effort, body["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals(
                level.name,
                if (level == ReasoningLevel.OFF) "disabled" else "enabled",
                body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `known openai compatible hosts should resolve through one endpoint registry`() {
        assertEquals(OpenAIEndpointVendor.OPENAI, resolveOpenAIEndpointVendor("api.openai.com"))
        assertEquals(OpenAIEndpointVendor.VOLC_ARK, resolveOpenAIEndpointVendor("ark.cn-beijing.volces.com"))
        assertEquals(OpenAIEndpointVendor.DEEPSEEK, resolveOpenAIEndpointVendor("api.deepseek.com"))
        assertEquals(OpenAIEndpointVendor.MISTRAL, resolveOpenAIEndpointVendor("api.mistral.ai"))
        assertEquals(OpenAIEndpointVendor.COMPATIBLE, resolveOpenAIEndpointVendor("proxy.example.com"))
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

    private fun createMultiRoundReasoningMessages(): List<UIMessage> {
        val assistant1 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Initial thinking"),
                UIMessagePart.Text("Initial response")
            )
        )
        val assistant2 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Final thinking"),
                UIMessagePart.Text("Final response")
            )
        )
        return listOf(
            UIMessage.user("First question"),
            assistant1,
            UIMessage.user("Second question"),
            assistant2
        )
    }

    @Test
    fun `tool_call followed by tool result should maintain correct order`() {
        // Verify the pattern: assistant (with tool_calls) -> tool (result)
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Calling tool"),
                createExecutedTool("call_abc", "my_tool", """{"param": "value"}""", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find the assistant message with tool_calls
        var assistantIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assistantIndex = i
                break
            }
        }

        assertTrue("Should find assistant with tool_calls", assistantIndex >= 0)

        // The next message should be the tool result
        val nextMsg = result[assistantIndex + 1].jsonObject
        assertEquals("tool", nextMsg["role"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("my_tool", nextMsg["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `complex multi-round conversation with interleaved reasoning and tools`() {
        // Complex scenario simulating agent conversation
        val messages = listOf(
            UIMessage.user("Plan and execute a task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Step 1: Analyze the task"),
                    UIMessagePart.Text("First, I'll gather information"),
                    createExecutedTool("call_1", "gather_info", "{}", "Info gathered"),
                    UIMessagePart.Reasoning(reasoning = "Step 2: Process the information"),
                    UIMessagePart.Text("Now processing..."),
                    createExecutedTool("call_2", "process", "{}", "Processed"),
                    UIMessagePart.Reasoning(reasoning = "Step 3: Generate output"),
                    UIMessagePart.Text("Here is the result")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Verify structure:
        // 1. user message
        // 2. assistant (reasoning + text + tool_calls)
        // 3. tool result
        // 4. assistant (reasoning + text + tool_calls)
        // 5. tool result
        // 6. assistant (reasoning + text)

        // Count message types
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val assistantCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        val toolCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }

        assertEquals("Should have 1 user message", 1, userCount)
        assertEquals("Should have 2 tool results", 2, toolCount)
        assertTrue("Should have at least 3 assistant messages", assistantCount >= 3)

        // Verify order: each tool_calls should be immediately followed by tool result
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assertTrue("Index should not be last", i < result.size - 1)
                val nextMsg = result[i + 1].jsonObject
                assertEquals("Tool result should follow tool_calls",
                    "tool", nextMsg["role"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `assistant with only reasoning and empty text should be filtered out when history reasoning disabled`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages, includeHistoryReasoning = false)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 1", result[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 2", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant with only reasoning and empty text should be kept when history reasoning enabled`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages, includeHistoryReasoning = true)

        assertEquals(3, result.size)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `latest assistant with reasoning and empty text should keep reasoning content`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openrouter reasoning details stay host scoped`() {
        val details = buildJsonArray {
            add(buildJsonObject {
                put("type", "reasoning.text")
                put("text", "hidden plan")
            })
        }
        val parsed = api.parseMessage(
            buildJsonObject {
                put("role", "assistant")
                put("content", "answer")
                put("reasoning_content", "visible thought")
                put("reasoning_details", details)
            }
        )
        val reasoning = parsed.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("visible thought", reasoning.reasoning)
        assertEquals(
            details,
            reasoning.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails,
        )

        val history = listOf(
            UIMessage.user("plan"),
            parsed.copy(
                parts = parsed.parts + createExecutedTool("call_1", "lookup", "{}", "ok"),
            ),
        )
        val openRouter = invokeBuildMessages(history, includeOpenRouterReasoningDetails = true)
        val compatible = invokeBuildMessages(history, includeOpenRouterReasoningDetails = false)

        val openRouterAssistant = openRouter.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        val compatibleAssistant = compatible.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals(details, openRouterAssistant.jsonObject["reasoning_details"]?.jsonArray)
        assertNull(openRouterAssistant.jsonObject["reasoning_content"])
        assertNull(compatibleAssistant.jsonObject["reasoning_details"])
        assertEquals("visible thought", compatibleAssistant.jsonObject["reasoning_content"]?.jsonPrimitive?.content)

        val openRouterRequest = invokeBuildRequest(
            messages = history,
            params = TextGenerationParams(model = Model(modelId = "openrouter/auto")),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1"),
        )
        val compatibleRequest = invokeBuildRequest(
            messages = history,
            params = TextGenerationParams(model = Model(modelId = "openrouter/auto")),
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://proxy.example.com/v1"),
        )
        val openRouterMessages = openRouterRequest["messages"]!!.jsonArray
        val compatibleMessages = compatibleRequest["messages"]!!.jsonArray
        assertEquals(
            details,
            openRouterMessages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
                .jsonObject["reasoning_details"]?.jsonArray,
        )
        assertNull(
            compatibleMessages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
                .jsonObject["reasoning_details"],
        )
    }

    @Test
    fun `streamed openrouter reasoning details are concatenated before tool replay`() {
        var messages = listOf(UIMessage.user("plan"))
        listOf(
            """
            {
              "role": "assistant",
              "reasoning_content": "hidden ",
              "reasoning_details": [
                {"id":"rd-1","type":"reasoning.text","text":"hidden ","index":0}
              ]
            }
            """,
            """
            {
              "role": "assistant",
              "reasoning_content": "plan",
              "reasoning_details": [
                {"id":"rd-1","type":"reasoning.text","text":"plan","index":0},
                {"id":"rd-2","type":"reasoning.summary","text":"summary","index":1}
              ]
            }
            """,
            """
            {
              "role": "assistant",
              "tool_calls": [{
                "id": "call_1",
                "type": "function",
                "function": {"name": "lookup", "arguments": "{}"}
              }]
            }
            """,
        ).forEach { deltaJson ->
            messages = messages.handleMessageChunk(
                MessageChunk(
                    id = "chunk",
                    model = "openrouter/auto",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = api.parseMessage(
                                Json.parseToJsonElement(deltaJson.trimIndent()).jsonObject
                            ),
                            message = null,
                            finishReason = null,
                        )
                    ),
                )
            )
        }
        messages = messages.dropLast(1) + messages.last().copy(
            parts = messages.last().parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolCallId == "call_1") {
                    part.copy(output = listOf(UIMessagePart.Text("ok")))
                } else {
                    part
                }
            }
        )

        val rebuilt = invokeBuildMessages(messages, includeOpenRouterReasoningDetails = true)
        val assistant = rebuilt.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals(
            buildJsonArray {
                add(buildJsonObject {
                    put("id", "rd-1")
                    put("type", "reasoning.text")
                    put("text", "hidden plan")
                    put("index", 0)
                })
                add(buildJsonObject {
                    put("id", "rd-2")
                    put("type", "reasoning.summary")
                    put("text", "summary")
                    put("index", 1)
                })
            },
            assistant.jsonObject["reasoning_details"]?.jsonArray,
        )
        assertNull(assistant.jsonObject["reasoning_content"])
    }

    // ==================== Helper Functions ====================

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
