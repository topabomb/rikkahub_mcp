package net.weero.measix.pilot.data.ai

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolResourceLease
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderResponseException
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
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.EmptyToolResultStatus
import net.weero.measix.pilot.data.ai.tools.ToolOutputArchive
import net.weero.measix.pilot.data.ai.tools.ToolOutputArchiveRef
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.ai.tools.ensureProviderReplayResult
import net.weero.measix.pilot.data.ai.tools.local.buildAskUserTool
import net.weero.measix.pilot.data.ai.transformers.InputMessageTransformer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationLoopFlowTest {
    @Test
    fun `invalid pending closes while valid batch waits and resumes exactly once with derived approval`() = runTest {
        val executed = mutableListOf<Pair<String, Boolean>>()
        val approved = Tool(
            name = "approved", description = "approved", interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { error("context required") },
            contextualExecute = {
                executed += "approved" to approvedByUser
                listOf(UIMessagePart.Text("ok"))
            },
        )
        val automatic = Tool(
            name = "automatic", description = "automatic",
            execute = { error("context required") },
            contextualExecute = {
                executed += "automatic" to approvedByUser
                listOf(UIMessagePart.Text("ok"))
            },
        )
        val harness = createProviderHarness()
        val initial = listOf(
            UIMessage.user("continue"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("bad", "ask_user", "{}", approvalState = ToolApprovalState.Pending),
                UIMessagePart.Tool("yes", "approved", ""),
                UIMessagePart.Tool("auto", "automatic", """{"approvedByUser":true}"""),
            )),
        )
        val checkpoints = mutableListOf<GenerationCheckpoint>()
        val request = GenerationRequest(
            conversationId = kotlin.uuid.Uuid.random(),
            settings = harness.settings,
            model = harness.model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = initial,
            assistant = harness.assistant,
            tools = listOf(buildAskUserTool(), approved, automatic),
            maxSteps = 1,
            onCheckpoint = checkpoints::add,
        )
        val first = harness.handler.run(request).toList()
        assertEquals(FinishedReason.AWAITING_APPROVAL, first.filterIsInstance<GenerationChunk.Finished>().single().reason)
        assertTrue(executed.isEmpty())
        val awaiting = first.filterIsInstance<GenerationChunk.Messages>().last().messages
        assertFalse(awaiting.last().getTools()[0].isPending)
        assertTrue(awaiting.last().getTools()[0].hasReplayResult)
        assertTrue(checkpoints.none { it.toolExecution != null })
        assertEquals(listOf(ToolResultEventStatus.FAILED), checkpoints.flatMap { it.toolResults }.map { it.status })

        val decision = awaiting.last().replaceToolsAtOrdinals(mapOf(
            1 to awaiting.last().getTools()[1].copy(approvalState = ToolApprovalState.Approved),
        ))
        harness.handler.run(request.copy(messages = awaiting.dropLast(1) + decision)).toList()
        assertEquals(listOf("approved" to true, "automatic" to false), executed)
        assertEquals(listOf(1, 2), checkpoints.mapNotNull { it.toolExecution }
            .filter { it.status == ToolExecutionEventStatus.STARTED }.map { it.toolOrdinal })
    }

    @Test
    fun `empty tool outputs become explicit Provider replay results`() {
        val successful = ensureProviderReplayResult(emptyList(), EmptyToolResultStatus.COMPLETED)
        val failed = ensureProviderReplayResult(
            ToolExecutionFailure(emptyList(), "empty failure").output,
            EmptyToolResultStatus.FAILED,
        )

        assertEquals(
            listOf(UIMessagePart.Text("{\"status\":\"completed\",\"result\":null}")),
            successful,
        )
        assertEquals(
            listOf(UIMessagePart.Text("{\"status\":\"failed\",\"reason\":\"tool_failed_without_output\"}")),
            failed,
        )
    }

    @Test
    fun `contract rejection emits typed failed result without execution fact`() = runTest {
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-invalid-image",
                    toolName = "ask_user",
                    input = "{}",
                )
            ),
        )
        val harness = createProviderHarness(responseMessage = toolMessage)
        var resultCheckpoint: GenerationCheckpoint? = null

        harness.handler.run(
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("draw")),
                assistant = harness.assistant,
                tools = listOf(buildAskUserTool()),
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    if (checkpoint.kind == CheckpointKind.TOOL_RESULT_COMPLETED) {
                        resultCheckpoint = checkpoint
                    }
                },
            )
        ).toList()

        val committed = requireNotNull(resultCheckpoint)
        assertEquals(null, committed.toolExecution)
        assertEquals(1, committed.toolResults.size)
        assertEquals(0, committed.toolResults.single().toolOrdinal)
        assertEquals(committed.messages.last().id, committed.toolResults.single().messageId)
        assertEquals(ToolResultEventStatus.FAILED, committed.toolResults.single().status)
    }

    @Test
    fun `contract rejection commits its result when neighboring tool awaits approval`() = runTest {
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("invalid", "ask_user", "{}"),
                UIMessagePart.Tool("approval", "approval_tool", "{}"),
            ),
        )
        val approvalTool = Tool(
            name = "approval_tool",
            description = "requires approval",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { listOf(UIMessagePart.Text("done")) },
        )
        val harness = createProviderHarness(responseMessage = toolMessage)
        val resultCheckpoints = mutableListOf<GenerationCheckpoint>()

        val chunks = harness.handler.run(
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("draw then continue")),
                assistant = harness.assistant,
                tools = listOf(approvalTool, buildAskUserTool()),
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    if (checkpoint.kind == CheckpointKind.TOOL_RESULT_COMPLETED) {
                        resultCheckpoints += checkpoint
                    }
                },
            )
        ).toList()

        val committed = resultCheckpoints.single()
        assertEquals(null, committed.toolExecution)
        assertEquals(listOf(0), committed.toolResults.map(ToolResultEvent::toolOrdinal))
        assertEquals(ToolResultEventStatus.FAILED, committed.toolResults.single().status)
        val finished = chunks.filterIsInstance<GenerationChunk.Finished>().single()
        assertEquals(FinishedReason.AWAITING_APPROVAL, finished.reason)
    }

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
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        loop.run(
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
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
                conversationId = kotlin.uuid.Uuid.random(),
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        loop.run(
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        val chunks = handler.run(
            GenerationRequest(
            conversationId = kotlin.uuid.Uuid.random(),
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
            conversationId = kotlin.uuid.Uuid.random(),
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
    fun `provider input applies replay safety after input transformers`() = runTest {
        val harness = createProviderHarness()
        val terminal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("original")),
            terminalStatus = MessageTerminalStatus.INCOMPLETE,
            terminalReason = "provider_incomplete",
        )
        val completeTool = UIMessagePart.Tool(
            toolCallId = "complete-call",
            toolName = "lookup",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val unsafeTool = UIMessagePart.Tool(
            toolCallId = "unsafe-call",
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
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("first"), terminal, UIMessage.user("continue"), inFlight),
                assistant = harness.assistant,
                assistantMessageId = inFlight.id,
                inputTransformers = listOf(rematerializeTerminal),
                maxSteps = 1,
            )
        ).toList()

        val projected = harness.providerMessages.captured.single { it.id == terminal.id }
        assertEquals(2, projected.providerReplayProjection?.completePartCount)
        assertEquals(null, projected.providerMetadata)
        assertEquals(listOf("complete-call"), projected.getTools().map { it.toolCallId })
        assertTrue(projected.parts.none { it is UIMessagePart.Reasoning })
        assertTrue(projected.toText().contains("transformed"))
        assertTrue(projected.toText().contains("did not complete"))
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
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
        val presentationEvents = mutableListOf<String>()
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
                            ToolMetadataDelivery.CHECKPOINT,
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
            conversationId = kotlin.uuid.Uuid.random(),
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(message),
            assistant = assistant,
            tools = listOf(tool),
            maxSteps = 1,
            onCheckpoint = { checkpoint ->
                if (checkpoint.kind == CheckpointKind.TOOL_EXECUTION_STARTED) {
                    presentationEvents += "checkpoint:started"
                    persistedBeforeExecute.set(true)
                    assertTrue(!executed.get())
                    assertEquals(ToolExecutionEventStatus.STARTED, checkpoint.toolExecution?.status)
                }
                if (checkpoint.kind == CheckpointKind.TOOL_RESULT_COMPLETED) {
                    presentationEvents += "checkpoint:result"
                }
            },
            )
        ).onEach { chunk ->
            if (chunk is GenerationChunk.Messages) {
                val hasReplayResult = chunk.messages.lastOrNull()?.getTools()?.singleOrNull()?.hasReplayResult
                presentationEvents += "messages:$hasReplayResult"
            }
        }.toList()

        assertTrue(chunks.any { it == GenerationChunk.Phase("tool_executing", "metadata_tool") })
        assertTrue(persistedBeforeExecute.get())
        val finalTool = chunks.filterIsInstance<GenerationChunk.Messages>()
            .last().messages.last().getTools().single()
        assertEquals("running", finalTool.metadata?.get("phase")?.jsonPrimitive?.content)
        assertEquals("ok", (finalTool.output.single() as UIMessagePart.Text).text)
        val startedIndex = presentationEvents.indexOf("checkpoint:started")
        val resultIndex = presentationEvents.indexOf("checkpoint:result")
        assertTrue(startedIndex >= 0)
        assertTrue(resultIndex > startedIndex)
        assertTrue(presentationEvents.drop(startedIndex + 1).take(resultIndex - startedIndex - 1)
            .contains("messages:false"))
        assertTrue(presentationEvents.drop(resultIndex + 1).contains("messages:true"))
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
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
                conversationId = kotlin.uuid.Uuid.random(),
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
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
                conversationId = kotlin.uuid.Uuid.random(),
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
    fun `tool trim count becomes durable only with its successful step checkpoint`() = runTest {
        suspend fun runCase(failure: Throwable?): UIMessage? {
            val assistantMessageId = kotlin.uuid.Uuid.random()
            val store = mockk<ToolOutputStore>()
            val archive = ToolOutputArchive(
                ref = 7,
                artifact = ToolOutputArchiveRef("tool_outputs/trim.txt", "text/plain"),
                characters = 10,
                lines = 1,
            )
            coEvery { store.stageCompaction(any()) } returns ToolOutputStore.StagedCompactionBatch(
                replacements = mapOf(
                    ToolCallLocator(assistantMessageId, 0) to ToolOutputStore.CompactionReplacement(
                        marker = UIMessagePart.Text("[archived]"),
                        archive = archive,
                    ),
                ),
                lease = null,
            )
            val response = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "done",
                        toolName = "historical_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("large result")),
                    ),
                ),
            )
            val harness = createProviderHarness(responseMessage = response, toolOutputStore = store)
            var durable: UIMessage? = null
            runCatching {
                harness.handler.run(
                    GenerationRequest(
                        conversationId = kotlin.uuid.Uuid.random(),
                        settings = harness.settings,
                        model = harness.model,
                        mediaCapabilities = RequestMediaCapabilities.NONE,
                        messages = listOf(UIMessage.user("continue")),
                        assistant = harness.assistant,
                        maxSteps = 1,
                        assistantMessageId = assistantMessageId,
                        onCheckpoint = { checkpoint ->
                            if (checkpoint.kind == CheckpointKind.STEP_COMPLETED) {
                                assertEquals(
                                    1,
                                    checkpoint.messages.last().usage
                                        ?.successfulToolOutputCompactionBatchCount,
                                )
                                failure?.let { throw it }
                                durable = checkpoint.messages.last()
                            }
                        },
                    ),
                ).toList()
            }
            return durable
        }

        val committed = requireNotNull(runCase(null))
        assertEquals(1, committed.usage?.successfulToolOutputCompactionBatchCount)
        assertEquals(null, runCase(IllegalStateException("checkpoint failed")))
        assertEquals(null, runCase(CancellationException("cancelled before checkpoint")))
    }

    @Test
    fun `terminal transformer resource is discarded when step checkpoint fails`() = runTest {
        val harness = createProviderHarness()
        val discarded = AtomicBoolean(false)
        val transformer = terminalResourceTransformer(discarded)

        val failure = runCatching {
            harness.handler.run(
                GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
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
                conversationId = kotlin.uuid.Uuid.random(),
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
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
            conversationId = kotlin.uuid.Uuid.random(),
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
                        assertTrue(checkpoint.messages.last().getTools().first().hasReplayResult)
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
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
                conversationId = kotlin.uuid.Uuid.random(),
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
        assertTrue(toolEvents.isEmpty())
    }

    @Test
    fun `typed tool failure preserves owner output and records failed terminal`() = runTest {
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val toolEvents = mutableListOf<ToolExecutionEvent>()
        val ownerOutput = UIMessagePart.Text("{\"status\":\"failed\",\"reason\":\"remote_error\"}")
        val tool = Tool(
            name = "mcp__server__action",
            description = "Remote action",
            execute = { throw ToolExecutionFailure(listOf(ownerOutput), "remote failure") },
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-mcp",
                    toolName = tool.name,
                    input = "{}",
                    approvalState = ToolApprovalState.Auto,
                )
            ),
        )

        val chunks = handler.run(
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(message),
                assistant = assistant,
                toolProvider = { listOf(tool) },
                maxSteps = 1,
                onCheckpoint = { checkpoint -> checkpoint.toolExecution?.let(toolEvents::add) },
            )
        ).toList()

        val result = chunks.filterIsInstance<GenerationChunk.Messages>()
            .last().messages.last().getTools().single()
        assertEquals(ownerOutput, result.output.single())
        assertEquals(
            listOf(ToolExecutionEventStatus.STARTED, ToolExecutionEventStatus.FAILED),
            toolEvents.map { it.status },
        )
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val toolA = Tool(name = "dup", description = "A", execute = { emptyList() })
        val toolB = Tool(name = "dup", description = "B", execute = { emptyList() })

        val failure = runCatching {
            handler.run(
                GenerationRequest(
                    conversationId = kotlin.uuid.Uuid.random(),
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
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        val failure = runCatching {
            handler.run(
                GenerationRequest(
                    conversationId = kotlin.uuid.Uuid.random(),
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

    @Test
    fun `tool continuation accumulates each provider request usage once`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        coEvery {
            provider.generateText(providerSetting, any(), any())
        } returnsMany listOf(
            MessageChunk(
                id = "tool-step",
                model = model.modelId,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Tool(
                                    toolCallId = "call-1",
                                    toolName = "echo_tool",
                                    input = "{}",
                                )
                            ),
                        ),
                        finishReason = "tool_calls",
                    )
                ),
                usage = ProviderUsageSnapshot(
                    inputTokens = 100,
                    outputTokens = 20,
                    cacheReadInputTokens = 50,
                    totalTokens = 120,
                ),
            ),
            MessageChunk(
                id = "answer-step",
                model = model.modelId,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = UIMessage.assistant("done"),
                        finishReason = "stop",
                    )
                ),
                usage = ProviderUsageSnapshot(
                    inputTokens = 200,
                    outputTokens = 10,
                    cacheReadInputTokens = 0,
                    totalTokens = 210,
                ),
            ),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = false)
        val loop = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val tool = Tool(
            name = "echo_tool",
            description = "Returns a stable result.",
            execute = { listOf(UIMessagePart.Text("tool result")) },
        )

        val chunks = loop.run(
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                tools = listOf(tool),
                maxSteps = 2,
            )
        ).toList()

        val usage = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last().usage
        assertEquals(300L, usage?.inputTokens)
        assertEquals(30L, usage?.outputTokens)
        assertEquals(50L, usage?.cacheReadInputTokens)
        assertEquals(330L, usage?.totalTokens)
        assertEquals(200L, usage?.latestRequestContextTokens)
        assertEquals(2, usage?.observedProviderRequestCount)
        assertEquals(2, usage?.observedUsageReportedRequestCount)
        assertEquals(me.rerere.ai.core.UsageCompleteness.COMPLETE, usage?.coreCompleteness)
        assertEquals(me.rerere.ai.core.UsageCompleteness.COMPLETE, usage?.cacheReadCompleteness)
    }

    @Test
    fun `request summary starts before provider usage and closed accounting remains atomic`() = runTest {
        val harness = createUsageStreamHarness(
            snapshots = listOf(
                ProviderUsageSnapshot(
                    inputTokens = 100,
                    outputTokens = 5,
                    cacheReadInputTokens = 10,
                    totalTokens = 105,
                ),
                ProviderUsageSnapshot(
                    inputTokens = 100,
                    outputTokens = 20,
                    cacheReadInputTokens = 90,
                    totalTokens = 120,
                ),
            ),
        )
        val observedUsage = mutableListOf<me.rerere.ai.core.TokenUsage?>()

        harness.handler.run(
            GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = harness.assistant,
                maxSteps = 1,
                onMessagesObserved = { messages -> observedUsage += messages.lastOrNull()?.usage },
            ),
        ).toList()

        val requestStartedIndex = observedUsage.indexOfFirst {
            it?.latestRequestEstimatedContextTokens != null
        }
        assertTrue(requestStartedIndex >= 0)
        val requestStarted = requireNotNull(observedUsage[requestStartedIndex])
        assertNull(requestStarted.observedProviderRequestCount)
        assertNull(requestStarted.latestRequestContextTokens)

        val closed = observedUsage.filterNotNull().last { it.observedProviderRequestCount == 1 }
        assertEquals(100L, closed.latestRequestContextTokens)
        assertEquals(20L, closed.latestRequestOutputTokens)
        assertEquals(90L, closed.latestRequestCacheReadInputTokens)
        assertEquals(90.0, closed.latestRequestCacheHitPercent!!, 0.0)
        assertTrue(closed.latestRequestTimeToFirstOutputMillis != null)
    }

    @Test
    fun `provider failure closes observed usage once and preserves the original error`() = runTest {
        val expectedFailure = IllegalStateException("provider stream failed")
        val harness = createUsageStreamHarness(expectedFailure)
        val chunks = mutableListOf<GenerationChunk>()
        var observed = emptyList<UIMessage>()

        val failure = runCatching {
            harness.handler.run(
                GenerationRequest(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings,
                    model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = harness.assistant,
                    maxSteps = 1,
                    onMessagesObserved = { observed = it },
                )
            ).collect(chunks::add)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure === expectedFailure || failure?.cause === expectedFailure)
        // 失败展示事件可被终止传播抢先取消；同步 turn-owner 观察才是 finalize 的事实来源。
        val usage = observed.last().usage
        assertEquals(100L, usage?.inputTokens)
        assertEquals(20L, usage?.outputTokens)
        assertEquals(120L, usage?.totalTokens)
        assertEquals(1, usage?.observedProviderRequestCount)
        assertEquals(1, usage?.observedUsageReportedRequestCount)
        assertEquals(me.rerere.ai.core.UsageCompleteness.COMPLETE, usage?.coreCompleteness)
    }

    @Test
    fun `provider cancellation closes observed usage once and propagates cancellation`() = runTest {
        val expectedCancellation = CancellationException("provider stream cancelled")
        val harness = createUsageStreamHarness(expectedCancellation)
        val chunks = mutableListOf<GenerationChunk>()
        var observed = emptyList<UIMessage>()

        val failure = runCatching {
            harness.handler.run(
                GenerationRequest(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings,
                    model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = harness.assistant,
                    maxSteps = 1,
                    onMessagesObserved = { observed = it },
                )
            ).collect(chunks::add)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(failure === expectedCancellation || failure?.cause === expectedCancellation)
        val usage = observed.last().usage
        assertEquals(100L, usage?.inputTokens)
        assertEquals(20L, usage?.outputTokens)
        assertEquals(120L, usage?.totalTokens)
        assertEquals(1, usage?.observedProviderRequestCount)
        assertEquals(1, usage?.observedUsageReportedRequestCount)
        assertEquals(me.rerere.ai.core.UsageCompleteness.COMPLETE, usage?.coreCompleteness)
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
                    UIMessagePart.Tool("call", "side_effect", "{}"),
                )),
                finishReason = "MAX_TOKENS",
            )),
            usage = ProviderUsageSnapshot(inputTokens = 100, outputTokens = 20, totalTokens = 120),
        )
        val expected = ProviderResponseException(response, HttpException(
            "maximum tokens", terminalStatus = ProviderTerminalStatus.INCOMPLETE,
        ))
        coEvery { provider.generateText(providerSetting, any(), any()) } throws expected
        val handler = GenerationLoop(
            context = mockk<Context>(relaxed = true), providerManager = providerManager, json = Json,
            memoryRepo = mockk<MemoryRepository>(relaxed = true),
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        var executed = false
        val chunks = mutableListOf<GenerationChunk>()
        val checkpoints = mutableListOf<GenerationCheckpoint>()
        var observed = emptyList<UIMessage>()
        val failure = runCatching {
            handler.run(GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting)), model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = Assistant(enableMemory = false, streamOutput = false),
                tools = listOf(Tool(name = "side_effect", description = "test", execute = {
                    executed = true
                    emptyList()
                })),
                onCheckpoint = { checkpoints += it },
                onMessagesObserved = { observed = it },
            )).collect(chunks::add)
        }.exceptionOrNull()

        assertTrue(failure === expected || failure?.cause === expected)
        assertFalse(executed)
        assertTrue(checkpoints.isEmpty())
        assertTrue(chunks.none { it is GenerationChunk.Finished })
        val message = observed.last()
        assertEquals("partial answer", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(100L, message.usage!!.inputTokens)
        assertEquals(20L, message.usage!!.outputTokens)
        assertEquals(1, message.usage!!.observedProviderRequestCount)
        assertEquals(100L, message.usage!!.latestRequestContextTokens)
    }

    @Test
    fun `cancelled output transformer cannot erase closed usage and discards its unpublished lease`() = runTest {
        val harness = createUsageStreamHarness()
        val entered = CompletableDeferred<Unit>()
        val discarded = AtomicBoolean(false)
        val published = AtomicBoolean(false)
        val registered = AtomicBoolean(false)
        val transformer = object : OutputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                if (messages.last().usage?.latestRequestContextTokens != null) {
                    if (registered.compareAndSet(false, true)) {
                        ctx.registerUnpublishedResource(ToolResourceLease(
                            publish = { published.set(true) },
                            discard = { discarded.set(true) },
                        ))
                    }
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return messages
            }
        }
        var observed = emptyList<UIMessage>()
        val collector = launch {
            harness.handler.run(GenerationRequest(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings, model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")), assistant = harness.assistant,
                outputTransformers = listOf(transformer),
                onMessagesObserved = { observed = it },
            )).collect { }
        }
        entered.await()
        collector.cancelAndJoin()

        assertTrue(collector.isCancelled)
        assertTrue(discarded.get())
        assertFalse(published.get())
        assertEquals(100L, observed.last().usage!!.latestRequestContextTokens)
        assertEquals(1, observed.last().usage!!.observedProviderRequestCount)
    }

    @Test
    fun `cancellation before output checkpoint never observes the discarded local payload`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.BEFORE_COMMIT)
    }

    @Test
    fun `cancellation during output checkpoint completes root lease and owner handoff`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.DURING_COMMIT)
    }

    @Test
    fun `failed output checkpoint discards its lease without observing the local payload`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.COMMIT_FAILED)
    }

    @Test
    fun `lease publication failure retains the committed local payload in the owner slot`() = runTest {
        checkOutputResourceHandoff(OutputHandoffInterruption.PUBLISH_FAILED)
    }

    private enum class OutputHandoffInterruption { BEFORE_COMMIT, DURING_COMMIT, COMMIT_FAILED, PUBLISH_FAILED }

    private suspend fun kotlinx.coroutines.test.TestScope.checkOutputResourceHandoff(
        interruption: OutputHandoffInterruption,
    ) {
        val rawUrl = "data:image/png;base64,uncommitted"
        val localUrl = "file:///upload/owned-result.png"
        val harness = createProviderHarness(UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image(rawUrl)),
        ))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val discarded = AtomicBoolean(false)
        val published = AtomicBoolean(false)
        val observations = mutableListOf<List<UIMessage>>()
        var committedMessages: List<UIMessage>? = null
        var failure: Throwable? = null
        val transformer = object : OutputMessageTransformer, StreamingMessageTransformer {
            override suspend fun onStreamingFinish(
                ctx: TransformerContext,
                message: UIMessage,
                previousProjection: UIMessage?,
            ): UIMessage {
                ctx.registerUnpublishedResource(ToolResourceLease(
                    publish = {
                        if (interruption == OutputHandoffInterruption.PUBLISH_FAILED) error("publish failed")
                        published.set(true)
                    },
                    discard = {
                        check(committedMessages == null) { "durable root prevents discard" }
                        discarded.set(true)
                    },
                ))
                if (interruption == OutputHandoffInterruption.BEFORE_COMMIT) {
                    // Model a completed owned IO operation returning just after cancellation.
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                return message.copy(parts = listOf(UIMessagePart.Image(localUrl)))
            }
        }
        val collector = launch {
            try {
                harness.handler.run(GenerationRequest(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings, model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("image")), assistant = harness.assistant,
                    outputTransformers = listOf(transformer),
                    onMessagesObserved = { observations += it },
                    onCheckpoint = { checkpoint ->
                        if (checkpoint.kind == CheckpointKind.STEP_COMPLETED) {
                            if (interruption == OutputHandoffInterruption.COMMIT_FAILED) error("commit failed")
                            committedMessages = checkpoint.messages
                            if (interruption == OutputHandoffInterruption.DURING_COMMIT) {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    },
                )).collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            }
        }
        if (interruption in setOf(OutputHandoffInterruption.BEFORE_COMMIT, OutputHandoffInterruption.DURING_COMMIT)) {
            entered.await()
            collector.cancel()
            release.complete(Unit)
        }
        collector.join()

        fun List<UIMessage>.hasLocalImage() = last().parts.filterIsInstance<UIMessagePart.Image>()
            .any { it.url == localUrl }
        if (interruption in setOf(OutputHandoffInterruption.DURING_COMMIT, OutputHandoffInterruption.PUBLISH_FAILED)) {
            assertTrue(committedMessages!!.hasLocalImage())
            assertFalse(discarded.get())
            assertTrue(observations.last().hasLocalImage())
            if (interruption == OutputHandoffInterruption.DURING_COMMIT) {
                assertTrue(collector.isCancelled)
                assertTrue(published.get())
            } else {
                assertFalse(published.get())
                assertTrue(failure?.message?.contains("publish failed") == true)
            }
        } else {
            assertTrue(discarded.get())
            assertFalse(published.get())
            assertTrue(observations.none { it.hasLocalImage() })
            assertTrue(committedMessages == null)
            if (interruption == OutputHandoffInterruption.COMMIT_FAILED) {
                assertTrue(failure?.message?.contains("commit failed") == true)
            } else {
                assertTrue(collector.isCancelled)
            }
        }
    }

    @Test
    fun `deferred tool metadata never escapes a failed result checkpoint`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.COMMIT_FAILED)
    }

    @Test
    fun `real cancellation of deferred tool metadata discards its unobserved resource`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.CANCELLED)
    }

    @Test
    fun `progress checkpoints leave resources unpublished until the result is rooted`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.NONE)
    }

    @Test
    fun `tool result assembly failure keeps its unrooted lease available for compensation`() = runTest {
        checkToolMetadataHandoff(ToolMetadataInterruption.ASSEMBLY_FAILED)
    }

    private enum class ToolMetadataInterruption { NONE, COMMIT_FAILED, CANCELLED, ASSEMBLY_FAILED }

    private suspend fun kotlinx.coroutines.test.TestScope.checkToolMetadataHandoff(
        interruption: ToolMetadataInterruption,
    ) {
        val harness = createProviderHarness()
        val entered = CompletableDeferred<Unit>()
        val observations = mutableListOf<List<UIMessage>>()
        var committedTool: UIMessagePart.Tool? = null
        var published = false
        var discarded = false
        var failure: Throwable? = null
        fun UIMessagePart.Tool.hasArtifact() = metadata?.containsKey("artifact") == true
        val tool = Tool(
            name = "metadata_tool", description = "test", execute = { emptyList() },
            contextualExecute = {
                registerUnpublishedResource(ToolResourceLease(
                    publish = {
                        check(committedTool?.hasArtifact() == true) { "resource has no durable root" }
                        published = true
                    },
                    discard = {
                        check(committedTool?.hasArtifact() != true) { "durable root prevents discard" }
                        discarded = true
                    },
                ))
                reportMetadata(buildJsonObject { put("phase", "setting_background") }, ToolMetadataDelivery.CHECKPOINT)
                assertFalse(published)
                if (interruption == ToolMetadataInterruption.ASSEMBLY_FAILED) error("output assembly failed")
                reportMetadata(buildJsonObject {
                    put("phase", "completed")
                    put("artifact", "file:///upload/result.png")
                }, ToolMetadataDelivery.DEFERRED)
                assertTrue(observations.none { it.last().getTools().single().hasArtifact() })
                if (interruption == ToolMetadataInterruption.CANCELLED) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                listOf(UIMessagePart.Text("done"))
            },
        )
        val collector = launch {
            try {
                harness.handler.run(GenerationRequest(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings, model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                        UIMessagePart.Tool("call", tool.name, "{}"),
                    ))),
                    assistant = harness.assistant, tools = listOf(tool), maxSteps = 1,
                    onMessagesObserved = { observations += it },
                    onCheckpoint = { checkpoint ->
                        if (checkpoint.kind == CheckpointKind.TOOL_STATE_CHANGED) {
                            assertFalse(published)
                            assertFalse(checkpoint.messages.last().getTools().single().hasArtifact())
                        }
                        if (checkpoint.kind == CheckpointKind.TOOL_RESULT_COMPLETED) {
                            if (interruption == ToolMetadataInterruption.COMMIT_FAILED) error("result commit failed")
                            committedTool = checkpoint.messages.last().getTools().single()
                        }
                    },
                )).collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            }
        }
        if (interruption == ToolMetadataInterruption.CANCELLED) {
            entered.await()
            collector.cancelAndJoin()
        } else {
            collector.join()
        }
        if (interruption == ToolMetadataInterruption.NONE) {
            assertTrue(failure == null)
            assertTrue(published)
            assertFalse(discarded)
            assertTrue(observations.last().last().getTools().single().hasArtifact())
        } else {
            assertFalse(published)
            assertTrue(discarded)
            assertTrue(observations.none { it.last().getTools().single().hasArtifact() })
            when (interruption) {
                ToolMetadataInterruption.CANCELLED -> assertTrue(collector.isCancelled)
                ToolMetadataInterruption.COMMIT_FAILED -> assertTrue(failure?.message?.contains("result commit failed") == true)
                ToolMetadataInterruption.ASSEMBLY_FAILED -> assertTrue(failure?.message?.contains("resource has no durable root") == true)
                ToolMetadataInterruption.NONE -> error("unreachable")
            }
        }
    }

    private fun createUsageStreamHarness(
        failure: Throwable? = null,
        snapshots: List<ProviderUsageSnapshot> = listOf(
            ProviderUsageSnapshot(
                inputTokens = 100,
                outputTokens = 20,
                cacheReadInputTokens = 0,
                totalTokens = 120,
            ),
        ),
    ): ProviderHarness {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        coEvery { provider.streamText(providerSetting, any(), any()) } returns flow {
            snapshots.forEachIndexed { index, snapshot ->
                emit(textDelta(if (index == 0) "partial" else " continued").copy(usage = snapshot))
            }
            failure?.let { throw it }
        }
        val assistant = Assistant(enableMemory = false, streamOutput = true)
        val toolOutputStore = mockk<ToolOutputStore>()
        coEvery { toolOutputStore.stageCompaction(any()) } returns ToolOutputStore.StagedCompactionBatch(
            replacements = emptyMap(),
            lease = null,
        )
        return ProviderHarness(
            handler = GenerationLoop(
                context = mockk<Context>(relaxed = true),
                providerManager = providerManager,
                json = Json,
                memoryRepo = mockk<MemoryRepository>(relaxed = true),
                attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
                toolOutputStore = toolOutputStore,
            ),
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            assistant = assistant,
            providerMessages = slot(),
            providerParams = slot(),
        )
    }

    private fun createProviderHarness(
        responseMessage: UIMessage = UIMessage.assistant("done"),
        toolOutputStore: ToolOutputStore = io.mockk.mockk(relaxed = true),
    ): ProviderHarness {
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
                    message = responseMessage,
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
                toolOutputStore = toolOutputStore,
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
