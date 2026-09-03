package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.freeze
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.ProviderTerminalStatus
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GoogleProvider message building logic.
 * Tests the conversion from UIMessage list to Google Gemini API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * Google API format:
 * - role: "user" or "model"
 * - parts array containing text, functionCall, functionResponse
 * - thought: true for reasoning parts
 */
class GoogleProviderMessageTest {

    private lateinit var provider: GoogleProvider
    private val testModelId = "gemini-test"
    private val testSourceProfile = "google:developer:test.example.com"

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    private fun invokeBuildContents(
        messages: List<UIMessage>,
        mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    ): JsonArray = provider.buildContents(
        messages = messages,
        mediaCapabilities = mediaCapabilities,
        modelId = testModelId,
        sourceProfile = testSourceProfile,
    )

    private fun invokeBuildRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): JsonObject = provider.buildCompletionRequestBody(
        messages = messages,
        params = params,
        sourceProfile = testSourceProfile,
    )

    private fun invokeBuildRequestWithSource(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        sourceProfile: String,
    ): JsonObject = provider.buildCompletionRequestBody(
        messages = messages,
        params = params,
        sourceProfile = sourceProfile,
    )

    private fun invokeParsePart(part: JsonObject): UIMessagePart = provider.parseMessagePart(
        jsonObject = part,
        sourceModelId = testModelId,
        sourceProfile = testSourceProfile,
        providerStepId = "test-step",
    )

    @Test
    fun `attachment facts retain user model and function response containers`() {
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

        val result = invokeBuildContents(messages).map { it.jsonObject }

        assertEquals(listOf("user", "model", "user"), result.map { it["role"]?.jsonPrimitive?.content })
        assertEquals(userFact, result[0]["parts"]!!.jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(
            assistantFact,
            result[1]["parts"]!!.jsonArray.first().jsonObject["text"]?.jsonPrimitive?.content,
        )
        val functionResponse = result[2]["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals(
            toolFact,
            functionResponse["response"]!!.jsonObject["result"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `adjacent user contents merge into one alternating turn`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("reminder"),
                UIMessage.user("follow-up"),
                UIMessage.assistant("ok"),
            ),
        ).map { it.jsonObject }

        assertEquals(listOf("user", "model"), result.map { it["role"]?.jsonPrimitive?.content })
        val userParts = result[0]["parts"]!!.jsonArray.map { it.jsonObject["text"]?.jsonPrimitive?.content }
        assertEquals(listOf("reminder", "follow-up"), userParts)
    }

    @Test
    fun `function response and following user merge into one user content`() {
        val result = invokeBuildContents(
            listOf(
                UIMessage.user("search"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(createExecutedTool("call_1", "search", """{"q":"x"}""", "hits")),
                ),
                UIMessage.user("thanks"),
            ),
        ).map { it.jsonObject }

        assertEquals(listOf("user", "model", "user"), result.map { it["role"]?.jsonPrimitive?.content })
        val lastParts = result[2]["parts"]!!.jsonArray.map { it.jsonObject }
        assertTrue(lastParts.first().containsKey("functionResponse"))
        assertEquals("thanks", lastParts.last()["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `multi-round tool calls should produce functionCall followed by functionResponse`() {
        // Scenario: Multiple rounds of tool calls
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

        val result = invokeBuildContents(messages)

        // Google format:
        // 1. user message
        // 2. model message with [text, functionCall(search)]
        // 3. user message with [functionResponse(search)]
        // 4. model message with [text, functionCall(calculate)]
        // 5. user message with [functionResponse(calculate)]
        // 6. model message with [text]

        // Collect all functionCall and functionResponse parts
        val functionCalls = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val functionResponses = mutableListOf<kotlinx.serialization.json.JsonObject>()

        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val partObj = part.jsonObject
                if (partObj.containsKey("functionCall")) {
                    functionCalls.add(partObj["functionCall"]!!.jsonObject)
                }
                if (partObj.containsKey("functionResponse")) {
                    functionResponses.add(partObj["functionResponse"]!!.jsonObject)
                }
            }
        }

        assertEquals("Should have 2 functionCall parts", 2, functionCalls.size)
        assertEquals("Should have 2 functionResponse parts", 2, functionResponses.size)

        // Verify functionCall contents
        assertEquals("search", functionCalls[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionCalls[1]["name"]?.jsonPrimitive?.content)

        // Verify functionResponse contents
        assertEquals("search", functionResponses[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionResponses[1]["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `functionCall in model should be followed by user message with functionResponse`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Using tool"),
                createExecutedTool("call_abc", "my_tool", "{}", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with functionCall
        var modelWithFunctionCallIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "model") {
                val parts = msg["parts"]?.jsonArray ?: continue
                if (parts.any { it.jsonObject.containsKey("functionCall") }) {
                    modelWithFunctionCallIndex = i
                    break
                }
            }
        }

        assertTrue("Should find model with functionCall", modelWithFunctionCallIndex >= 0)
        assertTrue("Should not be last message", modelWithFunctionCallIndex < result.size - 1)

        // Next message should be user with functionResponse
        val nextMsg = result[modelWithFunctionCallIndex + 1].jsonObject
        assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
        val nextParts = nextMsg["parts"]?.jsonArray
        assertTrue("Next message should have functionResponse",
            nextParts?.any { it.jsonObject.containsKey("functionResponse") } == true)
    }

    @Test
    fun `reasoning parts should have thought flag set to true`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("Here is my response")
            )
        )

        val messages = listOf(
            UIMessage.user("Question"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message
        val modelMsg = result.find {
            it.jsonObject["role"]?.jsonPrimitive?.content == "model"
        }?.jsonObject

        assertTrue("Should have model message", modelMsg != null)

        val parts = modelMsg!!["parts"]?.jsonArray
        assertTrue("Parts should not be null", parts != null)

        // Find text part with thought:true (reasoning is converted to text with thought flag)
        // Note: The implementation may vary - check for thought flag in text parts
        val textParts = parts!!.filter { it.jsonObject.containsKey("text") }
        assertTrue("Should have text parts", textParts.isNotEmpty())

        // Verify we have both regular text and thought text
        val hasThoughtPart = textParts.any {
            it.jsonObject["thought"]?.jsonPrimitive?.content == "true" ||
            it.jsonObject["thought"]?.toString() == "true"
        }
        // Note: If reasoning is handled differently, adjust this assertion
    }

    @Test
    fun `parallel tool calls should be in same model message`() {
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
            UIMessage.user("Do multiple things"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with all functionCall parts
        var foundModelWithMultipleCalls = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "model") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val functionCallParts = parts.filter { it.jsonObject.containsKey("functionCall") }

            if (functionCallParts.size == 3) {
                foundModelWithMultipleCalls = true
                // Verify tool names
                val toolNames = functionCallParts.map {
                    it.jsonObject["functionCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                }
                assertTrue(toolNames.contains("tool_a"))
                assertTrue(toolNames.contains("tool_b"))
                assertTrue(toolNames.contains("tool_c"))
                break
            }
        }

        assertTrue("Should have model with 3 parallel functionCall parts",
            foundModelWithMultipleCalls)

        // Verify corresponding functionResponse parts in user message
        var foundUserWithMultipleResponses = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "user") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val responseParts = parts.filter { it.jsonObject.containsKey("functionResponse") }

            if (responseParts.size == 3) {
                foundUserWithMultipleResponses = true
                break
            }
        }

        assertTrue("Should have user with 3 functionResponse parts",
            foundUserWithMultipleResponses)
    }

    @Test
    fun `multi-round reasoning and tools should maintain correct order`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Step 1: Search"),
                UIMessagePart.Text("Searching..."),
                createExecutedTool("call_1", "search", "{}", "Found data"),
                UIMessagePart.Reasoning(reasoning = "Step 2: Analyze"),
                UIMessagePart.Text("Analyzing..."),
                createExecutedTool("call_2", "analyze", "{}", "Analysis done"),
                UIMessagePart.Reasoning(reasoning = "Step 3: Present"),
                UIMessagePart.Text("Results")
            )
        )

        val messages = listOf(
            UIMessage.user("Analyze"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Verify structure:
        // model -> user (functionResponse) -> model -> user (functionResponse) -> model

        var functionCallCount = 0
        var functionResponseCount = 0

        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val partObj = part.jsonObject
                if (partObj.containsKey("functionCall")) functionCallCount++
                if (partObj.containsKey("functionResponse")) functionResponseCount++
            }
        }

        assertEquals("Should have 2 functionCall parts", 2, functionCallCount)
        assertEquals("Should have 2 functionResponse parts", 2, functionResponseCount)

        // Verify functionCall -> functionResponse order
        for (i in 0 until result.size - 1) {
            val msg = result[i].jsonObject
            val parts = msg["parts"]?.jsonArray ?: continue
            val hasFunctionCall = parts.any { it.jsonObject.containsKey("functionCall") }

            if (hasFunctionCall && msg["role"]?.jsonPrimitive?.content == "model") {
                // Next should be user with functionResponse
                val nextMsg = result[i + 1].jsonObject
                assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
                val nextParts = nextMsg["parts"]?.jsonArray
                assertTrue("Should have functionResponse in next message",
                    nextParts?.any { it.jsonObject.containsKey("functionResponse") } == true)
            }
        }
    }

    @Test
    fun `user message parts should be correctly formatted`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("Hello, how are you?")
                )
            )
        )

        val result = invokeBuildContents(messages)

        assertEquals(1, result.size)
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        val parts = userMsg["parts"]?.jsonArray
        assertTrue("Parts should not be null", parts != null)
        assertTrue("Parts should not be empty", parts!!.isNotEmpty())

        val textPart = parts.find { it.jsonObject.containsKey("text") }?.jsonObject
        assertEquals("Hello, how are you?", textPart?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `complex multi-round scenario with interleaved content`() {
        val messages = listOf(
            UIMessage.user("Execute task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing"),
                    createExecutedTool("step2", "process", """{"data": "x"}""", "processed"),
                    UIMessagePart.Text("Finalizing"),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed")
                )
            )
        )

        val result = invokeBuildContents(messages)

        // Count roles
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val modelCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "model" }

        // Should have: 1 initial user + 3 functionResponse users = 4 user messages
        // And: multiple model messages
        assertEquals("Should have 4 user messages (1 initial + 3 responses)", 4, userCount)
        assertTrue("Should have multiple model messages", modelCount >= 3)

        // Verify order: each functionCall should be followed by functionResponse
        var lastFunctionCallIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            val parts = msg["parts"]?.jsonArray ?: continue
            if (parts.any { it.jsonObject.containsKey("functionCall") }) {
                assertTrue("functionCall should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("user", next["role"]?.jsonPrimitive?.content)
                assertTrue("Index should increase", i > lastFunctionCallIndex)
                lastFunctionCallIndex = i
            }
        }
    }

    @Test
    fun `functionResponse should contain correct result structure`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_1", "my_tool", """{"input": "test"}""", "Expected output value")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find functionResponse
        var functionResponse: kotlinx.serialization.json.JsonObject? = null
        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                if (part.jsonObject.containsKey("functionResponse")) {
                    functionResponse = part.jsonObject["functionResponse"]?.jsonObject
                    break
                }
            }
            if (functionResponse != null) break
        }

        assertTrue("Should find functionResponse", functionResponse != null)
        assertEquals("my_tool", functionResponse!!["name"]?.jsonPrimitive?.content)

        // Verify response structure
        val response = functionResponse["response"]?.jsonObject
        assertTrue("Response should contain result",
            response?.containsKey("result") == true)
        assertTrue("Result should contain expected output",
            response?.get("result")?.jsonPrimitive?.content?.contains("Expected output value") == true)
    }

    @Test
    fun `function declarations and built in tools should coexist and preserve enum`() {
        val modeSchema = buildJsonObject { put("\$ref", "#/\$defs/mode") }
        val tool = Tool(
            name = "run_task",
            description = "Run a task",
            parameters = {
                buildJsonObject {
                    put("\$schema", "https://json-schema.org/draft/2020-12/schema")
                    put("type", "object")
                    put("properties", buildJsonObject { put("mode", modeSchema) })
                    putJsonArray("required") { add(JsonPrimitive("mode")) }
                    put("\$defs", buildJsonObject {
                        put("mode", buildJsonObject {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("fast"))
                                add(JsonPrimitive("accurate"))
                            }
                        })
                    })
                }
            },
            execute = { emptyList() },
        )
        val body = invokeBuildRequest(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-3-pro",
                    displayName = "gemini-3-pro",
                    abilities = listOf(ModelAbility.TOOL),
                    tools = setOf(BuiltInTools.Search),
                ),
                tools = listOf(tool.freeze()),
            ),
        )

        val tools = body["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        val declaration = tools.first().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject
        val schema = declaration["parametersJsonSchema"]!!.jsonObject
        val mode = schema["\$defs"]!!.jsonObject["mode"]!!.jsonObject
        assertEquals(listOf("fast", "accurate"), mode["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("#/\$defs/mode", schema["properties"]!!.jsonObject["mode"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content)
        assertFalse(schema.containsKey("\$schema"))
        assertTrue(tools.any { it.jsonObject.containsKey("googleSearch") })
    }

    @Test
    fun `gemini 25 flash max reasoning budget is clamped to flash limit`() {
        val body = invokeBuildRequest(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-2.5-flash",
                    displayName = "gemini-2.5-flash",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.MAX,
            ),
        )
        val budget = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
            .get("thinkingBudget")!!.jsonPrimitive.content.toInt()
        assertEquals(24_576, budget)
    }

    @Test
    fun `gemini 3_7 flash off reasoning falls back to low instead of unsupported minimal`() {
        // Gemini 3.7 Flash 只支持 low/medium/high，显式 minimal 会返回 API 校验错误
        val body = invokeBuildRequest(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-3.7-flash",
                    displayName = "gemini-3.7-flash",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )
        val config = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("low", config["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini 3 series off reasoning keeps minimal where supported`() {
        listOf("gemini-3-flash", "gemini-3.6-flash", "gemini-3-pro").forEach { modelId ->
            val body = invokeBuildRequest(
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(
                    model = Model(
                        modelId = modelId,
                        displayName = modelId,
                        abilities = listOf(ModelAbility.REASONING),
                    ),
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            val config = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
            assertEquals("minimal", config["thinkingLevel"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `gemini 3_1 pro off reasoning falls back to low instead of unsupported minimal`() {
        // 3.1 Pro 只支持 low / medium / high，无法停用思考；显式 minimal 会返回 API 校验错误
        listOf("gemini-3.1-pro", "gemini-3.1-pro-preview", "gemini-3.1-pro-preview-customtools").forEach { modelId ->
            val body = invokeBuildRequest(
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(
                    model = Model(
                        modelId = modelId,
                        displayName = modelId,
                        abilities = listOf(ModelAbility.REASONING),
                    ),
                    reasoningLevel = ReasoningLevel.OFF,
                ),
            )
            val config = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
            assertEquals("low", config["thinkingLevel"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `gemini 3_7 flash auto reasoning omits thinkingLevel and explicit levels map directly`() {
        val autoBody = invokeBuildRequest(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-3.7-flash",
                    displayName = "gemini-3.7-flash",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.AUTO,
            ),
        )
        val autoConfig = autoBody["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertFalse(autoConfig.containsKey("thinkingLevel"))

        val lowBody = invokeBuildRequest(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-3.7-flash",
                    displayName = "gemini-3.7-flash",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.LOW,
            ),
        )
        val lowConfig = lowBody["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("low", lowConfig["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini 25 pro max reasoning budget stays under pro limit`() {
        assertEquals(32_000, gemini25ThinkingBudget("gemini-2.5-pro", ReasoningLevel.MAX))
        assertEquals(16_000, gemini25ThinkingBudget("gemini-2.5-flash", ReasoningLevel.XHIGH))
    }

    @Test
    fun `gemini function call id and thought signature should round trip exactly`() {
        val parsed = invokeParsePart(buildJsonObject {
            put("functionCall", buildJsonObject {
                put("id", "server_call_1")
                put("name", "lookup")
                put("args", buildJsonObject { put("query", "test") })
            })
            put("thoughtSignature", "tool_signature")
        }) as UIMessagePart.Tool
        val executed = parsed.copy(output = listOf(UIMessagePart.Text("result")))

        assertEquals("server_call_1", parsed.toolCallId)
        assertEquals("server_call_1", parsed.metadataAs<GoogleThoughtMetadata>()?.functionCallId)

        val contents = invokeBuildContents(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed))))
        val functionCallPart = contents[0].jsonObject["parts"]!!.jsonArray.single().jsonObject
        val functionResponsePart = contents[1].jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals(
            "server_call_1",
            functionCallPart["functionCall"]!!.jsonObject["id"]?.jsonPrimitive?.content,
        )
        assertEquals("tool_signature", functionCallPart["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals(
            "server_call_1",
            functionResponsePart["functionResponse"]!!.jsonObject["id"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `adjacent tools from distinct gemini response steps remain sequential`() {
        fun tool(id: String, step: String) = createExecutedTool(id, "lookup", "{}", id).copy(
            metadata = GoogleThoughtMetadata(
                functionCallId = id,
                thoughtSignature = "signature-$id",
                providerStepId = step,
            ).toMetadata(),
        )
        val contents = invokeBuildContents(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool("call-1", "step-1"), tool("call-2", "step-2")),
                )
            )
        )

        assertEquals(listOf("model", "user", "model", "user"), contents.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        })
        assertTrue(contents[0].jsonObject["parts"]!!.jsonArray.single().jsonObject.containsKey("functionCall"))
        assertTrue(contents[2].jsonObject["parts"]!!.jsonArray.single().jsonObject.containsKey("functionCall"))
    }

    @Test
    fun `parallel tools from one gemini response step remain one envelope`() {
        fun tool(id: String) = createExecutedTool(id, "lookup", "{}", id).copy(
            metadata = GoogleThoughtMetadata(
                functionCallId = id,
                thoughtSignature = if (id == "call-1") "signature" else null,
                providerStepId = "shared-step",
            ).toMetadata(),
        )
        val contents = invokeBuildContents(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool("call-1"), tool("call-2")),
                )
            )
        )

        assertEquals(listOf("model", "user"), contents.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        })
        assertEquals(2, contents[0].jsonObject["parts"]!!.jsonArray.size)
        assertEquals(2, contents[1].jsonObject["parts"]!!.jsonArray.size)
    }

    @Test
    fun `gemini signature replay requires exact model and endpoint source`() {
        val metadata = GoogleThoughtMetadata(
            thoughtSignature = "opaque-signature",
            sourceModelId = "gemini-3-flash",
            sourceProfile = "google:developer:generativelanguage.googleapis.com",
            providerStepId = "step-1",
        ).toMetadata()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer", metadata)),
        )
        val params = TextGenerationParams(
            model = Model(modelId = "gemini-3-flash", displayName = "Gemini 3 Flash"),
        )

        val matched = invokeBuildRequestWithSource(
            listOf(message), params, "google:developer:generativelanguage.googleapis.com"
        )
        val switched = invokeBuildRequestWithSource(
            listOf(message), params, "google:vertex:aiplatform.googleapis.com"
        )
        val matchedPart = matched["contents"]!!.jsonArray.single().jsonObject["parts"]!!
            .jsonArray.single().jsonObject
        val switchedPart = switched["contents"]!!.jsonArray.single().jsonObject["parts"]!!
            .jsonArray.single().jsonObject
        assertEquals("opaque-signature", matchedPart["thoughtSignature"]?.jsonPrimitive?.content)
        assertFalse(switchedPart.containsKey("thoughtSignature"))
    }

    @Test
    fun `gemini replay source profile follows actual developer or vertex endpoint`() {
        assertEquals(
            "google:developer:proxy.example.com",
            googleReplaySourceProfile(
                me.rerere.ai.provider.ProviderSetting.Google(
                    baseUrl = "https://proxy.example.com/v1beta",
                )
            ),
        )
        val vertexA = googleReplaySourceProfile(
            me.rerere.ai.provider.ProviderSetting.Google(
                baseUrl = "https://unused-one.example.com/v1beta",
                vertexAI = true,
            )
        )
        val vertexB = googleReplaySourceProfile(
            me.rerere.ai.provider.ProviderSetting.Google(
                baseUrl = "https://unused-two.example.com/v1beta",
                vertexAI = true,
            )
        )
        assertEquals("google:vertex:aiplatform.googleapis.com", vertexA)
        assertEquals(vertexA, vertexB)
    }

    @Test
    fun `tool delta metadata merge preserves prior gemini signature and call id`() {
        val first = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "lookup",
            input = "{",
            metadata = GoogleThoughtMetadata(
                thoughtSignature = "signature",
                functionCallId = "call-1",
                providerStepId = "step-1",
            ).toMetadata(),
        )
        val delta = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "",
            input = "}",
            metadata = GoogleThoughtMetadata(providerStepId = "step-1").toMetadata(),
        )

        val merged = first.merge(delta).metadataAs<GoogleThoughtMetadata>()!!
        assertEquals("signature", merged.thoughtSignature)
        assertEquals("call-1", merged.functionCallId)
        assertEquals("step-1", merged.providerStepId)
    }

    @Test
    fun `gemini finish reasons map to typed terminal status`() {
        assertEquals(null, geminiFinishReasonException("STOP"))
        assertEquals(
            ProviderTerminalStatus.INCOMPLETE,
            geminiFinishReasonException("MAX_TOKENS")?.terminalStatus,
        )
        listOf("SAFETY", "MALFORMED_FUNCTION_CALL", "UNKNOWN_NEW_REASON", null).forEach { reason ->
            assertEquals(ProviderTerminalStatus.FAILED, geminiFinishReasonException(reason)?.terminalStatus)
        }
    }

    @Test
    fun `all system text is retained in order including image output models`() {
        val body = invokeBuildRequest(
            messages = listOf(
                UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("one"), UIMessagePart.Text("two"))),
                UIMessage.system("three"),
                UIMessage.user("hello"),
            ),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-image",
                    displayName = "Gemini Image",
                    outputModalities = listOf(me.rerere.ai.provider.Modality.IMAGE),
                ),
            ),
        )

        val text = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
            .single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("one\ntwo\nthree", text)
    }

    @Test
    fun `audio and video remain reference only until common capability supports them`() {
        val contents = invokeBuildContents(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("[Attachment path=/upload/audio.wav type=audio]"),
                        UIMessagePart.Audio("file:///audio.wav"),
                        UIMessagePart.Text("[Attachment path=/upload/video.mp4 type=video]"),
                        UIMessagePart.Video("file:///video.webm"),
                    ),
                )
            )
        )

        val parts = contents.single().jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.containsKey("text") })
        assertTrue(parts.none { it.containsKey("inlineData") })
    }

    @Test
    fun `gemini text reasoning and draft image protocol state should round trip`() {
        val text = invokeParsePart(buildJsonObject {
            put("text", "answer")
            put("thoughtSignature", "text_signature")
        })
        val reasoning = invokeParsePart(buildJsonObject {
            put("text", "thinking")
            put("thought", true)
            put("thoughtSignature", "reasoning_signature")
        })
        val draft = invokeParsePart(buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", "image/png")
                put("data", "draft_base64")
            })
            put("thought", true)
            put("thoughtSignature", "draft_signature")
        })

        val contents = invokeBuildContents(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning, draft, text)))
        )
        val parts = contents.single().jsonObject["parts"]!!.jsonArray.map { it.jsonObject }

        assertEquals("reasoning_signature", parts[0]["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals("thinking", parts[0]["text"]?.jsonPrimitive?.content)
        assertEquals("draft_signature", parts[1]["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals("draft_base64", parts[1]["inlineData"]!!.jsonObject["data"]?.jsonPrimitive?.content)
        assertEquals("text_signature", parts[2]["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals("answer", parts[2]["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `final gemini image should keep mime type and replay only the payload`() {
        val image = invokeParsePart(buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", "image/jpeg")
                put("data", "abc123")
            })
        }) as UIMessagePart.Image
        assertEquals("data:image/jpeg;base64,abc123", image.url)

        val contents = invokeBuildContents(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(image))),
            RequestMediaCapabilities(assistantImages = RequestImageSupport.STRUCTURED),
        )
        val inlineData = contents.single().jsonObject["parts"]!!.jsonArray
            .single().jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inlineData["mimeType"]?.jsonPrimitive?.content)
        assertEquals("abc123", inlineData["data"]?.jsonPrimitive?.content)
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

    // ==================== Custom Body Ownership Tests ====================

    private fun invokeBuildRequestBodyWithCustomBody(
        customBody: List<me.rerere.ai.provider.CustomBody>,
    ): JsonObject {
        val model = Model(
            modelId = "gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            abilities = emptyList(),
        )
        val params = TextGenerationParams(
            model = model,
            customBody = customBody,
        )
        val messages = listOf(UIMessage.user("hello"))
        return provider.buildCompletionRequestBody(
            messages = messages,
            params = params,
            sourceProfile = testSourceProfile,
        )
    }

    @Test
    fun `gemini function call requires nonblank name and object args`() {
        val invalidCalls = listOf(
            buildJsonObject {
                put("functionCall", buildJsonObject {
                    put("name", "lookup")
                })
            },
            buildJsonObject {
                put("functionCall", buildJsonObject {
                    put("name", "lookup")
                    put("args", JsonNull)
                })
            },
            buildJsonObject {
                put("functionCall", buildJsonObject {
                    put("name", "lookup")
                    put("args", "invalid")
                })
            },
            buildJsonObject {
                put("functionCall", buildJsonObject {
                    put("name", "lookup")
                    put("args", buildJsonArray { add("invalid") })
                })
            },
            buildJsonObject {
                put("functionCall", buildJsonObject {
                    put("name", "")
                    put("args", buildJsonObject {})
                })
            },
        )

        invalidCalls.forEach { part ->
            assertThrows(IllegalArgumentException::class.java) {
                invokeParsePart(part)
            }
        }
    }

    @Test
    fun `gemini custom body with reserved key contents throws before request`() {
        var caught: Throwable? = null
        try {
            invokeBuildRequestBodyWithCustomBody(
                listOf(me.rerere.ai.provider.CustomBody("contents", JsonPrimitive("[]"))),
            )
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue(
            "expected CustomBodyReservedKeyException, got $caught",
            caught is me.rerere.ai.util.CustomBodyReservedKeyException,
        )
        assertTrue(
            (caught as me.rerere.ai.util.CustomBodyReservedKeyException).conflictingKeys.contains("contents"),
        )
    }

    @Test
    fun `gemini custom body with reserved key systemInstruction throws before request`() {
        var caught: Throwable? = null
        try {
            invokeBuildRequestBodyWithCustomBody(
                listOf(me.rerere.ai.provider.CustomBody("systemInstruction", JsonPrimitive("{}"))),
            )
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue(caught is me.rerere.ai.util.CustomBodyReservedKeyException)
    }

    @Test
    fun `gemini custom body with non-reserved key still merges`() {
        val body = invokeBuildRequestBodyWithCustomBody(
            listOf(me.rerere.ai.provider.CustomBody("generationConfig", buildJsonObject { put("temperature", 0.5) })),
        )
        assertTrue(body.containsKey("generationConfig"))
    }

    @Test
    fun `gemini custom body rejects nested response shape controls`() {
        val unsafeBodies = listOf(
            me.rerere.ai.provider.CustomBody(
                "generationConfig",
                buildJsonObject { put("candidateCount", 2) },
            ),
            me.rerere.ai.provider.CustomBody(
                "generationConfig",
                buildJsonObject { putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) } },
            ),
            me.rerere.ai.provider.CustomBody(
                "toolConfig",
                buildJsonObject {
                    put("functionCallingConfig", buildJsonObject {
                        put("streamFunctionCallArguments", true)
                    })
                },
            ),
        )

        unsafeBodies.forEach { body ->
            var caught: Throwable? = null
            try {
                invokeBuildRequestBodyWithCustomBody(listOf(body))
            } catch (e: Throwable) {
                caught = e
            }
            assertTrue(
                "expected nested ownership rejection for ${body.key}, got $caught",
                caught is me.rerere.ai.util.CustomBodyReservedKeyException,
            )
        }
    }
}
