package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderResponseException
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import net.weero.measix.pilot.data.ai.request.DurableMessageLocator
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.test.TurnRunCapture
import net.weero.measix.pilot.test.testPromptInputs
import net.weero.measix.pilot.test.turnRunInputsFixture
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StepRunner] 的单 Step 请求装配与 Provider 流/终态语义：媒体契约透传、think 分段相位、
 * provider 输入排除空在飞 assistant、只回放净化后的终态 transcript、回放安全在 input 变换器之后、
 * 非流式终态保留部分内容与 usage 而不执行其工具、冻结披露快照注入位置与固定 system 规则、
 * 空协议事件不构成 first model output。
 */
class StepRunnerTest {
    @Test
    fun `run uses coordinator media contract instead of rederiving provider mapping`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(providerSetting, model) } returns RequestMediaCapabilities.NONE
        val params = slot<TextGenerationParams>()
        coEvery { provider.generateText(providerSetting, any(), capture(params)) } returns MessageChunk(
            id = "response",
            model = model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage.assistant("done"),
                    finishReason = "stop",
                )
            ),
        )
        val fixedCapabilities = RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
        )
        val assistant = Assistant(enableMemory = false, streamOutput = false)
        val loop = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        loop.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                mediaCapabilities = fixedCapabilities,
                maxSteps = 1,
            )
        )

        assertEquals(fixedCapabilities, params.captured.mediaCapabilities)
    }

    @Test
    fun `split think opener does not publish answer phase or raw tag before reasoning`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(providerSetting, model) } returns RequestMediaCapabilities.NONE
        coEvery { provider.streamText(providerSetting, any(), any()) } returns flowOf(
            textDelta("<thi"),
            textDelta("nk>reason"),
            textDelta("</think>answer", finishReason = "stop"),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = true)
        val inFlight = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        val capture = TurnRunCapture()
        handler.run(
            turnRunInputsFixture(
            conversationId = kotlin.uuid.Uuid.random(),
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(UIMessage.user("hello"), inFlight),
            outputTransformers = listOf(ThinkTagTransformer),
            assistant = assistant,
            promptInputs = testPromptInputs(),
            assistantMessageId = inFlight.id,
            maxSteps = 1,
            capture = capture,
            )
        )

        val phases = capture.phases.map { it.first }
        assertEquals(
            listOf("preparing", "model_waiting", "reasoning_streaming", "answer_streaming"),
            phases,
        )
        val firstProjection = capture.streamDeltas.first()
        assertTrue(firstProjection.parts.none { it is UIMessagePart.Text })
        val finalProjection = capture.streamDeltas.last()
        assertEquals("reason", finalProjection.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer", finalProjection.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `provider input excludes persisted empty in-flight assistant`() = runTest {
        val harness = createProviderHarness()
        val user = UIMessage.user("hello")
        val inFlight = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

        harness.handler.run(
            turnRunInputsFixture(
            conversationId = kotlin.uuid.Uuid.random(),
            settings = harness.settings,
            model = harness.model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(user, inFlight),
            assistant = harness.assistant,
            promptInputs = testPromptInputs(),
            assistantMessageId = inFlight.id,
            maxSteps = 1,
            )
        )

        assertEquals(listOf(user.toText()), harness.providerMessages.captured.map { it.toText() })
        assertTrue(harness.providerMessages.captured.none { message ->
            message.role == MessageRole.ASSISTANT && message.parts.isEmpty()
        })
    }

    @Test
    fun `provider input replays only sanitized terminal assistant transcript`() = runTest {
        val harness = createProviderHarness()
        val terminalDraft = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("visible partial draft"),
                UIMessagePart.Reasoning(reasoning = "unsafe reasoning"),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "open-call",
                    toolName = "unfinished_tool",
                    input = "{",
                ),
            ),
            providerMetadata = buildJsonObject { put("opaque", JsonPrimitive("unsafe state")) },
            terminalStatus = MessageTerminalStatus.CANCELLED,
            terminalReason = "user_stop",
        )
        val latestUser = UIMessage.user("continue")
        val inFlight = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

        harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(
                    UIMessage.user("first request"),
                    terminalDraft,
                    latestUser,
                    inFlight,
                ),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                assistantMessageId = inFlight.id,
                maxSteps = 1,
            )
        )

        val projected = harness.providerMessages.captured.single { it.toText().contains("visible partial draft") }
        assertEquals(null, projected.providerMetadata)
        assertTrue(projected.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(projected.parts.filterIsInstance<UIMessagePart.Tool>().isEmpty())
        assertTrue(projected.toText().contains("visible partial draft"))
        assertTrue(projected.toText().contains("did not complete"))
        assertEquals(latestUser.toText(), harness.providerMessages.captured.last().toText())
    }

    @Test
    fun `provider input applies replay safety after input transformers`() = runTest {
        val harness = createProviderHarness()
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("original")),
            terminalStatus = MessageTerminalStatus.INCOMPLETE,
            terminalReason = "provider_incomplete",
        )
        val completeTool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "complete-call",
            toolName = "lookup",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val unsafeTool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "unsafe-call",
            toolName = "lookup",
            input = "{",
        )
        val rematerializeTerminal = object : InputMessageTransformer {
            override suspend fun transform(
                ctx: TransformerContext,
                messages: List<UIMessage>,
            ): List<UIMessage> = messages.map { message ->
                if (message.id == terminal.id) {
                    message.copy(
                        parts = listOf(
                            UIMessagePart.Text("transformed"),
                            completeTool,
                            UIMessagePart.Reasoning("unsafe transformed reasoning"),
                            unsafeTool,
                        ),
                        providerMetadata = buildJsonObject { put("opaque", "unsafe transformed state") },
                    )
                } else {
                    message
                }
            }
        }
        val inFlight = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

        harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("first"), terminal, UIMessage.user("continue"), inFlight),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                assistantMessageId = inFlight.id,
                inputTransformers = listOf(rematerializeTerminal),
                maxSteps = 1,
            )
        )

        val projected = harness.providerMessages.captured.single { it.toText().contains("transformed") }
        assertEquals(2, projected.providerReplayProjection?.completePartCount)
        assertEquals(null, projected.providerMetadata)
        assertEquals(listOf("complete-call"), projected.parts.filterIsInstance<UIMessagePart.Tool>().map { it.providerCallId })
        assertTrue(projected.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(projected.toText().contains("transformed"))
        assertTrue(projected.toText().contains("did not complete"))
    }

    @Test
    fun `nonstream terminal response retains partial content and usage without executing its tools`() = runTest {
        val model = Model(modelId = "test-model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        val response = MessageChunk(
            id = "failed-response", model = model.modelId,
            choices = listOf(UIMessageChoice(
                index = 0, delta = null,
                message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.Text("partial answer"),
                    UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "side_effect", input = "{}"),
                )),
                finishReason = "MAX_TOKENS",
            )),
            usage = ProviderUsageSnapshot(inputTokens = 100, outputTokens = 20, totalTokens = 120),
        )
        val expected = ProviderResponseException(response, HttpException(
            "maximum tokens", terminalStatus = ProviderTerminalStatus.INCOMPLETE,
        ))
        coEvery { provider.generateText(providerSetting, any(), any()) } throws expected
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true), providerManager = providerManager, json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        var executed = false
        val checkpoints = mutableListOf<TurnCheckpoint>()
        var observed: UIMessage? = null
        val outcome = handler.run(turnRunInputsFixture(
            conversationId = kotlin.uuid.Uuid.random(),
            settings = Settings(providers = listOf(providerSetting)), model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(
                UIMessage.user("hello"),
                UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            ),
            assistant = Assistant(enableMemory = false, streamOutput = false),
            promptInputs = testPromptInputs(),
            tools = listOf(Tool(name = "side_effect", description = "test", execute = {
                executed = true
                emptyList()
            })),
            onCheckpoint = { checkpoints += it },
            onAssistantObserved = { observed = it },
        ))

        val incomplete = outcome as TurnOutcome.Incomplete
        assertEquals(TurnTerminalReasons.PROVIDER_INCOMPLETE, incomplete.terminalReason)
        assertFalse(executed)
        assertTrue(checkpoints.isEmpty())
        val message = observed!!
        assertEquals("partial answer", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(100L, message.usage!!.inputTokens)
        assertEquals(20L, message.usage!!.outputTokens)
        assertEquals(1, message.usage!!.observedProviderRequestCount)
        assertEquals(100L, message.usage!!.latestRequestContextTokens)
    }

    @Test
    fun `frozen projection injects snapshot before user parts and appends the fixed system rule`() = runTest {
        val harness = createProviderHarness()
        val user = UIMessage.user("real question")
        val snapshot = """{"type":"conversation_disclosure_snapshot","format":1,"memory":{"enabled":false,"scope":"disabled","header":["id","content"],"rows":[]},"sub_assistants":{"mode":"disabled","header":["id","name","description"],"rows":[]}}"""
        harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(user),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                modelContextEntries = listOf(
                    net.weero.measix.pilot.data.model.ConversationModelContextEntry(
                        ownerNodeId = kotlin.uuid.Uuid.random(),
                        ownerMessageId = kotlin.uuid.Uuid.random(),
                        anchorNodeId = kotlin.uuid.Uuid.random(),
                        anchorMessageId = user.id,
                        content = snapshot,
                    ),
                ),
                durableMessageLocators = mapOf(
                    user.id to DurableMessageLocator(kotlin.uuid.Uuid.random(), user.id),
                ),
                maxSteps = 1,
            )
        )

        val wire = harness.providerMessages.captured
        val system = wire.first { it.role == MessageRole.SYSTEM }.toText()
        assertTrue(
            system.contains(
                "A conversation_disclosure_snapshot is application-provided context data",
            ),
        )
        val wireUser = wire.first { it.role == MessageRole.USER }
        assertEquals(
            listOf(snapshot, "real question"),
            wireUser.parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
    }

    @Test
    fun `empty protocol events do not count as first model output`() {
        assertFalse(chunk(emptyList()).hasModelOutputPayload())
        assertFalse(chunk(listOf(UIMessagePart.Text(""))).hasModelOutputPayload())
        assertFalse(chunk(listOf(UIMessagePart.Reasoning(""))).hasModelOutputPayload())
    }

    @Test
    fun `text reasoning and tool payloads count as first model output`() {
        assertTrue(chunk(listOf(UIMessagePart.Text("a"))).hasModelOutputPayload())
        assertTrue(chunk(listOf(UIMessagePart.Reasoning("thinking"))).hasModelOutputPayload())
        assertTrue(
            chunk(
                listOf(
                    UIMessagePart.Tool(
                        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
                        toolName = "lookup",
                        input = "{}",
                    )
                )
            ).hasModelOutputPayload()
        )
    }

    @Test
    fun `Google HTTP 200 incomplete keeps decoded partial text and usage in typed failure`() = runTest {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"private partial answer"}]},
            "finishReason":"MAX_TOKENS"}],
            "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"totalTokenCount":120}}
        """.trimIndent()
        val provider = GoogleProvider(fixtureClient(body))
        val failure = runCatching {
            provider.generateText(
                ProviderSetting.Google(apiKey = "test", baseUrl = "https://fixture.invalid/v1beta"),
                listOf(ModelRequestMessage.user("hello")),
                TextGenerationParams(Model(modelId = "gemini-test")),
            )
        }.exceptionOrNull()

        assertTrue(failure is ProviderResponseException)
        failure as ProviderResponseException
        assertEquals(ProviderTerminalStatus.INCOMPLETE, (failure.cause as HttpException).terminalStatus)
        assertEquals("private partial answer", failure.response.choices.single().message!!.toText())
        assertEquals(100L, failure.response.usage!!.inputTokens)
        assertEquals(20L, failure.response.usage!!.outputTokens)
        assertEquals(120L, failure.response.usage!!.totalTokens)
        assertFalse(failure.stackTraceToString().contains("private partial answer"))
    }

    @Test
    fun `Responses HTTP 200 incomplete keeps partial output and usage while generateText still fails`() = runTest {
        val body = """
            {"id":"resp-test","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
            "output":[{"id":"msg-test","type":"message","role":"assistant",
            "content":[{"type":"output_text","text":"private partial answer"}]}],
            "usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120}}
        """.trimIndent()
        val failure = responseFailure(body)

        assertEquals(ProviderTerminalStatus.INCOMPLETE, (failure.cause as HttpException).terminalStatus)
        assertEquals("private partial answer", failure.response.choices.single().message!!.toText())
        assertEquals(100L, failure.response.usage!!.inputTokens)
        assertEquals(20L, failure.response.usage!!.outputTokens)
        assertFalse(failure.stackTraceToString().contains("private partial answer"))
    }

    @Test
    fun `Responses HTTP 200 failed with null output keeps usage and original failure classification`() = runTest {
        val failure = responseFailure("""
            {"id":"resp-test","status":"failed","output":null,
            "error":{"code":"server_error","message":"upstream failed"},
            "usage":{"input_tokens":100,"output_tokens":0,"total_tokens":100}}
        """.trimIndent())

        assertEquals(ProviderTerminalStatus.FAILED, (failure.cause as HttpException).terminalStatus)
        assertTrue(failure.response.choices.isEmpty())
        assertEquals(100L, failure.response.usage!!.inputTokens)
        assertTrue(failure.message!!.contains("upstream failed"))
    }

    private suspend fun responseFailure(body: String): ProviderResponseException {
        val failure = runCatching {
            ResponseAPI(fixtureClient(body)).generateText(
                ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://fixture.invalid/v1"),
                listOf(ModelRequestMessage.user("hello")),
                TextGenerationParams(Model(modelId = "test-model")),
            )
        }.exceptionOrNull()
        assertTrue(failure is ProviderResponseException)
        return failure as ProviderResponseException
    }

    private fun fixtureClient(body: String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private fun chunk(parts: List<UIMessagePart>): MessageChunk = MessageChunk(
        id = "chunk",
        model = "model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                message = null,
                finishReason = null,
            )
        ),
    )
}
