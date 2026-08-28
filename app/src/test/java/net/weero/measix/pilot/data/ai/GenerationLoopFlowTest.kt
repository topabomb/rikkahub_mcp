package net.weero.measix.pilot.data.ai

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.transformers.OutputMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.StreamingMessageTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationLoopFlowTest {
    @Test
    fun `memory owner policy supports Master refresh and enforces Target run ceiling`() {
        val assistant = Assistant(enableMemory = false, useGlobalMemory = false)

        assertEquals(null, resolveGenerationMemoryOwner(assistant))
        assertEquals(assistant.id.toString(), resolveGenerationMemoryOwner(assistant.copy(enableMemory = true)))
        assertEquals(
            MemoryRepository.GLOBAL_MEMORY_ID,
            resolveGenerationMemoryOwner(assistant.copy(enableMemory = true, useGlobalMemory = true)),
        )

        val targetStartDisabled = assistant
        assertEquals(
            null,
            resolveGenerationMemoryOwner(targetStartDisabled.copy(enableMemory = true), targetStartDisabled),
        )
        val targetStartLocal = assistant.copy(enableMemory = true)
        val capturedOwner = resolveGenerationMemoryOwner(targetStartLocal, targetStartLocal)
        assertEquals(targetStartLocal.id.toString(), capturedOwner)
        assertEquals(null, resolveGenerationMemoryOwner(targetStartLocal.copy(enableMemory = false), targetStartLocal))
        assertEquals(
            null,
            resolveGenerationMemoryOwner(targetStartLocal.copy(useGlobalMemory = true), targetStartLocal),
        )
        // The write-time guard compares the captured owner with a fresh policy result.
        assertFalse(resolveGenerationMemoryOwner(targetStartLocal.copy(useGlobalMemory = true)) == capturedOwner)
    }

    @Test
    fun `step memory context controls both prompt and tool schema`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        val providerMessages = slot<List<UIMessage>>()
        val params = slot<TextGenerationParams>()
        coEvery { provider.generateText(providerSetting, capture(providerMessages), capture(params)) } returns MessageChunk(
            id = "response",
            model = model.modelId,
            choices = listOf(
                UIMessageChoice(0, null, UIMessage.assistant("done"), "stop")
            ),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = false)
        val loop = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )

        loop.run(
            GenerationRequest(
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                memoryContextProvider = {
                    GenerationMemoryContext(assistant.id.toString(), listOf(AssistantMemory(1, "step memory")))
                },
                maxSteps = 1,
            )
        ).toList()

        assertTrue(providerMessages.captured.first().toText().contains("step memory"))
        assertTrue(params.captured.tools.any { it.name == "memory_tool" })
    }

    @Test
    fun `revoked step memory removes prompt and tool schema together`() = runTest {
        val harness = createProviderHarness()

        harness.handler.run(
            GenerationRequest(
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = harness.assistant,
                memoryContextProvider = {
                    GenerationMemoryContext(
                        harness.assistant.id.toString(),
                        listOf(AssistantMemory(1, "must not be visible")),
                    )
                },
                memoryToolAllowed = { false },
                maxSteps = 1,
            )
        ).toList()

        assertFalse(harness.providerMessages.captured.any { it.toText().contains("must not be visible") })
        assertFalse(harness.providerParams.captured.tools.any { it.name == "memory_tool" })
    }

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
        val loop = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )

        loop.run(
            GenerationRequest(
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                mediaCapabilities = fixedCapabilities,
                maxSteps = 1,
            )
        ).toList()

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
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )

        val chunks = handler.run(
            GenerationRequest(
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(UIMessage.user("hello"), inFlight),
            outputTransformers = listOf(ThinkTagTransformer),
            assistant = assistant,
            assistantMessageId = inFlight.id,
            maxSteps = 1,
            )
        ).toList()

        val phases = chunks.filterIsInstance<GenerationChunk.Phase>().map { it.phase }
        assertEquals(
            listOf("preparing", "model_waiting", "reasoning_streaming", "answer_streaming"),
            phases,
        )
        val firstProjection = chunks.filterIsInstance<GenerationChunk.Messages>().first().messages.last()
        assertTrue(firstProjection.parts.none { it is UIMessagePart.Text })
        val finalProjection = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last()
        assertEquals("reason", finalProjection.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer", finalProjection.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `provider input excludes persisted empty in-flight assistant`() = runTest {
        val harness = createProviderHarness()
        val user = UIMessage.user("hello")
        val inFlight = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

        harness.handler.run(
            GenerationRequest(
            settings = harness.settings,
            model = harness.model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(user, inFlight),
            assistant = harness.assistant,
            assistantMessageId = inFlight.id,
            maxSteps = 1,
            )
        ).toList()

        assertEquals(listOf(user.id), harness.providerMessages.captured.map { it.id })
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
                    toolCallId = "open-call",
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
            GenerationRequest(
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
                assistantMessageId = inFlight.id,
                maxSteps = 1,
            )
        ).toList()

        val projected = harness.providerMessages.captured.single { it.id == terminalDraft.id }
        assertEquals(null, projected.terminalStatus)
        assertEquals(null, projected.terminalReason)
        assertEquals(null, projected.providerMetadata)
        assertTrue(projected.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(projected.getTools().isEmpty())
        assertTrue(projected.toText().contains("visible partial draft"))
        assertTrue(projected.toText().contains("did not complete"))
        assertEquals(latestUser.id, harness.providerMessages.captured.last().id)
    }

    @Test
    fun `metadata emitted from child job remains flow transparent`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every {
            providerManager.getProviderByType(any<ProviderSetting.OpenAI>())
        } returns provider
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "provider-call-id",
                    toolName = "metadata_tool",
                    input = "{}",
                )
            ),
        )
        val persistedBeforeExecute = AtomicBoolean(false)
        val executed = AtomicBoolean(false)
        val tool = Tool(
            name = "metadata_tool",
            description = "Reports metadata from a cancellable child run.",
            contextualExecute = {
                executed.set(true)
                assertTrue(persistedBeforeExecute.get())
                val childJob = Job(currentCoroutineContext()[Job])
                try {
                    withContext(childJob) {
                        reportMetadata(
                            buildJsonObject { put("phase", JsonPrimitive("running")) },
                            true,
                        )
                    }
                } finally {
                    childJob.cancel()
                }
                listOf(UIMessagePart.Text("ok"))
            },
            execute = { emptyList() },
        )

        val chunks = handler.run(
            GenerationRequest(
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(message),
            assistant = assistant,
            tools = listOf(tool),
            maxSteps = 1,
            onCheckpoint = { checkpoint ->
                if (checkpoint.kind == CheckpointKind.TOOL_EXECUTION_STARTED) {
                    persistedBeforeExecute.set(true)
                    assertTrue(!executed.get())
                    assertEquals(ToolExecutionEventStatus.STARTED, checkpoint.toolExecution?.status)
                }
            },
            )
        ).toList()

        assertTrue(chunks.any { it == GenerationChunk.Phase("tool_executing", "metadata_tool") })
        assertTrue(persistedBeforeExecute.get())
        val finalTool = chunks.filterIsInstance<GenerationChunk.Messages>()
            .last().messages.last().getTools().single()
        assertEquals("running", finalTool.metadata?.get("phase")?.jsonPrimitive?.content)
        assertEquals("ok", (finalTool.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `failed started checkpoint prevents tool side effect`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        var executed = false
        val tool = Tool(
            name = "side_effect",
            description = "Must not run unless its STARTED fact is durable.",
            execute = {
                executed = true
                listOf(UIMessagePart.Text("executed"))
            },
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = tool.name,
                    input = "{}",
                )
            ),
        )

        val failure = runCatching {
            handler.run(
                GenerationRequest(
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(message),
                assistant = assistant,
                tools = listOf(tool),
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    if (checkpoint.kind == CheckpointKind.TOOL_EXECUTION_STARTED) {
                        error("checkpoint storage unavailable")
                    }
                },
                )
            ).toList()
        }.exceptionOrNull()

        assertFalse(executed)
        assertTrue(failure?.message?.contains("checkpoint storage unavailable") == true)
    }

    @Test
    fun `tool output resource is discarded when completed checkpoint fails`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns
            mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val discarded = AtomicBoolean(false)
        val tool = Tool(
            name = "resource_tool",
            description = "Creates an output resource.",
            execute = { error("context required") },
            contextualExecute = {
                registerUnpublishedResource(
                    ToolResourceLease(publish = {}, discard = { discarded.set(true) })
                )
                listOf(UIMessagePart.Text("resource result"))
            },
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = "call", toolName = tool.name, input = "{}")),
        )

        val failure = runCatching {
            handler.run(
                GenerationRequest(
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(message),
                assistant = assistant,
                tools = listOf(tool),
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    if (checkpoint.kind == CheckpointKind.TOOL_RESULT_COMPLETED) {
                        error("durable checkpoint failed")
                    }
                },
                )
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("durable checkpoint failed") == true)
        assertTrue(discarded.get())
    }

    @Test
    fun `terminal transformer resource is discarded when step checkpoint fails`() = runTest {
        val harness = createProviderHarness()
        val discarded = AtomicBoolean(false)
        val transformer = terminalResourceTransformer(discarded)

        val failure = runCatching {
            harness.handler.run(
                GenerationRequest(
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("create image")),
                outputTransformers = listOf(transformer),
                assistant = harness.assistant,
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    if (checkpoint.kind == CheckpointKind.STEP_COMPLETED) {
                        error("step checkpoint failed")
                    }
                },
                )
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("step checkpoint failed") == true)
        assertTrue(discarded.get())
    }

    @Test
    fun `terminal transformer resource is discarded when checkpoint is cancelled`() = runTest {
        val harness = createProviderHarness()
        val discarded = AtomicBoolean(false)
        val transformer = terminalResourceTransformer(discarded)

        val failure = runCatching {
            harness.handler.run(
                GenerationRequest(
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("create image")),
                outputTransformers = listOf(transformer),
                assistant = harness.assistant,
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    if (checkpoint.kind == CheckpointKind.STEP_COMPLETED) {
                        throw kotlinx.coroutines.CancellationException("collector cancelled")
                    }
                },
                )
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(discarded.get())
    }

    @Test
    fun `multiple tools commit started and completed state one by one`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val executionOrder = mutableListOf<String>()
        val first = Tool(
            name = "first_tool",
            description = "First side effect.",
            execute = {
                executionOrder += "execute:first_tool"
                listOf(UIMessagePart.Text("first result"))
            },
        )
        val second = Tool(
            name = "second_tool",
            description = "Second side effect.",
            execute = {
                executionOrder += "execute:second_tool"
                listOf(UIMessagePart.Text("second result"))
            },
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "call-1", toolName = first.name, input = "{}"),
                UIMessagePart.Tool(toolCallId = "call-2", toolName = second.name, input = "{}"),
            ),
        )
        val toolEvents = mutableListOf<ToolExecutionEvent>()

        handler.run(
            GenerationRequest(
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(message),
            assistant = assistant,
            tools = listOf(first, second),
            maxSteps = 1,
            onCheckpoint = { checkpoint ->
                checkpoint.toolExecution?.let { event ->
                    toolEvents += event
                    executionOrder += "commit:${event.toolName}:${event.status.name.lowercase()}"
                    if (event.toolName == second.name && event.status == ToolExecutionEventStatus.STARTED) {
                        assertTrue(checkpoint.messages.last().getTools().first().isExecuted)
                    }
                }
            },
            )
        ).toList()

        assertEquals(
            listOf(
                "commit:first_tool:started",
                "execute:first_tool",
                "commit:first_tool:completed",
                "commit:second_tool:started",
                "execute:second_tool",
                "commit:second_tool:completed",
            ),
            executionOrder,
        )
        assertEquals(
            listOf(
                ToolExecutionEventStatus.STARTED,
                ToolExecutionEventStatus.COMPLETED,
                ToolExecutionEventStatus.STARTED,
                ToolExecutionEventStatus.COMPLETED,
            ),
            toolEvents.map { it.status },
        )
    }

    @Test
    fun `approved unregistered tool returns tool_not_available without side effects`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val toolEvents = mutableListOf<ToolExecutionEvent>()
        var toolSetBuilds = 0
        var executed = false
        val tool = Tool(
            name = "side_effect",
            description = "Must not run.",
            execute = {
                executed = true
                listOf(UIMessagePart.Text("executed"))
            },
        )
        // Message references a tool that is NOT in the tools list.
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-missing",
                    toolName = "missing_tool",
                    input = "{}",
                    approvalState = ToolApprovalState.Approved,
                )
            ),
        )

        val chunks = handler.run(
            GenerationRequest(
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(message),
                assistant = assistant,
                toolProvider = {
                    toolSetBuilds++
                    listOf(tool)
                },
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    checkpoint.toolExecution?.let(toolEvents::add)
                },
            )
        ).toList()

        assertFalse(executed)
        assertEquals(1, toolSetBuilds)
        val toolResult = chunks.filterIsInstance<GenerationChunk.Messages>()
            .last().messages.last().getTools().single()
        val outputText = (toolResult.output.single() as UIMessagePart.Text).text
        assertTrue(outputText.contains("tool_not_available"))
        assertFalse(outputText.contains("Tool missing_tool not found"))
        assertEquals(
            listOf(ToolExecutionEventStatus.STARTED, ToolExecutionEventStatus.FAILED),
            toolEvents.map { it.status },
        )
        assertTrue(toolEvents.all { it.toolName == "missing_tool" })
    }

    @Test
    fun `duplicate tool names are rejected before provider request`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val toolA = Tool(name = "dup", description = "A", execute = { emptyList() })
        val toolB = Tool(name = "dup", description = "B", execute = { emptyList() })

        val failure = runCatching {
            handler.run(
                GenerationRequest(
                    settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                    model = model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = assistant,
                    tools = listOf(toolA, toolB),
                    maxSteps = 1,
                )
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("Duplicate tool name"))
    }

    @Test
    fun `blank tool name is rejected before provider request`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
        )

        val failure = runCatching {
            handler.run(
                GenerationRequest(
                    settings = Settings(providers = listOf(providerSetting)),
                    model = model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = Assistant(enableMemory = false),
                    tools = listOf(Tool(name = "", description = "invalid", execute = { emptyList() })),
                    maxSteps = 1,
                )
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("Tool name must not be blank"))
    }

    private fun createProviderHarness(): ProviderHarness {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every {
            providerManager.getProviderByType(any<ProviderSetting.OpenAI>())
        } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        val providerMessages = slot<List<UIMessage>>()
        val providerParams = slot<TextGenerationParams>()
        coEvery {
            provider.generateText(
                providerSetting = providerSetting,
                messages = capture(providerMessages),
                params = capture(providerParams),
            )
        } returns MessageChunk(
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
        val assistant = Assistant(enableMemory = false, streamOutput = false)
        return ProviderHarness(
            handler = GenerationLoop(
                context = mockk<Context>(relaxed = true),
                providerManager = providerManager,
                json = Json,
                memoryRepo = mockk<MemoryRepository>(relaxed = true),
                attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            ),
            settings = Settings(
                providers = listOf(providerSetting),
                assistants = listOf(assistant),
            ),
            model = model,
            assistant = assistant,
            providerMessages = providerMessages,
            providerParams = providerParams,
        )
    }

    private fun textDelta(text: String, finishReason: String? = null) = MessageChunk(
        id = "response",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
                message = null,
                finishReason = finishReason,
            ),
        ),
    )

    private fun terminalResourceTransformer(discarded: AtomicBoolean) = object :
        OutputMessageTransformer,
        StreamingMessageTransformer {
        override suspend fun onStreamingFinish(
            ctx: TransformerContext,
            message: UIMessage,
            previousProjection: UIMessage?,
        ): UIMessage {
            ctx.registerUnpublishedResource(
                ToolResourceLease(publish = {}, discard = { discarded.set(true) })
            )
            return message
        }
    }

    private data class ProviderHarness(
        val handler: GenerationLoop,
        val settings: Settings,
        val model: Model,
        val assistant: Assistant,
        val providerMessages: io.mockk.CapturingSlot<List<UIMessage>>,
        val providerParams: io.mockk.CapturingSlot<TextGenerationParams>,
    )
}
