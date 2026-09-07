package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.ToolExecutionFact
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.test.replaceToolByLocalCallId
import net.weero.measix.pilot.data.ai.tools.EmptyToolResultStatus
import net.weero.measix.pilot.data.ai.tools.ensureProviderReplayResult
import net.weero.measix.pilot.data.ai.tools.local.buildAskUserTool
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.service.runtime.ModelResponseCheckpoint
import net.weero.measix.pilot.service.runtime.ToolExecutionCheckpoint
import net.weero.measix.pilot.service.runtime.ToolResultCheckpoint
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.test.TurnRunCapture
import net.weero.measix.pilot.test.testPromptInputs
import net.weero.measix.pilot.test.turnRunInputsFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolBatchRunner] 的批次门控与审批/回放语义：一个 step 内多工具批次的 pending 判定、
 * 契约拒绝只落失败结果不建执行事实、暂停经 [TurnPause] 交回且不经流式通道、审批派生与恢复恰好一次。
 */
class ToolBatchRunnerTest {
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
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "bad", toolName = "ask_user", input = "{}", interactionState = ToolInteractionState.AwaitingApproval),
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "yes", toolName = "approved", input = ""),
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "auto", toolName = "automatic", input = """{"approvedByUser":true}"""),
            )),
        )
        val checkpoints = mutableListOf<TurnCheckpoint>()
        val observed = mutableListOf<UIMessage>()
        val capture = TurnRunCapture()
        val request = turnRunInputsFixture(
            conversationId = kotlin.uuid.Uuid.random(),
            settings = harness.settings,
            model = harness.model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = initial,
            assistant = harness.assistant,
            promptInputs = testPromptInputs(),
            tools = listOf(buildAskUserTool(), approved, automatic),
            maxSteps = 1,
            onAssistantObserved = { observed += it },
            onCheckpoint = checkpoints::add,
            capture = capture,
        )
        harness.handler.run(request)
        assertEquals(1, capture.results.size)
        val firstPending = (capture.result as TurnPause).pendingInteractions
        assertEquals(listOf(ToolInteractionState.AwaitingApproval), firstPending.map { it.interaction })
        assertEquals(listOf(initial.last().getTools()[1].localCallId), firstPending.map { it.locator.localCallId })
        assertEquals(initial.last().id, firstPending.single().locator.assistantMessageId)
        assertTrue(executed.isEmpty())
        val sampling = checkpoints.filterIsInstance<ModelResponseCheckpoint>().single()
        assertEquals(TurnExecutionStatus.AWAITING_USER, sampling.turnStatus)
        assertTrue(checkpoints.none { it is ToolResultCheckpoint })
        val resultTools = sampling.assistantMessage.getTools()
        assertFalse(resultTools[0].isPending)
        assertTrue(resultTools[0].hasReplayResult)
        assertEquals(ToolResultStatus.FAILED, resultTools[0].resultStatus)
        assertTrue(resultTools[1].isPending)
        capture.streamDeltas.forEach { message ->
            assertFalse(message.getTools()[1].isPending)
        }
        val awaiting = observed.last()
        assertTrue(awaiting.getTools()[1].isPending)
        assertTrue(checkpoints.filterIsInstance<ToolExecutionCheckpoint>().none { it.toolExecution != null })

        val decision = awaiting.replaceToolByLocalCallId(
            awaiting.getTools()[1].copy(interactionState = ToolInteractionState.Approved),
        )
        harness.handler.run(request.copy(messages = initial.dropLast(1) + decision))
        assertEquals(listOf("approved" to true, "automatic" to false), executed)
        assertEquals(listOf("approved", "automatic"), checkpoints.filterIsInstance<ToolExecutionCheckpoint>().mapNotNull { it.toolExecution }
            .filter { it.status == ToolExecutionStatus.STARTED }.map { it.toolName })
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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-invalid-image",
                    toolName = "ask_user",
                    input = "{}",
                )
            ),
        )
        val harness = createProviderHarness(responseMessage = toolMessage)
        val checkpoints = mutableListOf<TurnCheckpoint>()

        harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("draw")),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(buildAskUserTool()),
                maxSteps = 1,
                onCheckpoint = { checkpoint -> checkpoints += checkpoint },
            )
        )

        val sampling = checkpoints.filterIsInstance<ModelResponseCheckpoint>().single()
        assertEquals(TurnExecutionStatus.RUNNING, sampling.turnStatus)
        assertTrue(checkpoints.none { it is ToolResultCheckpoint })
        val failed = sampling.assistantMessage.getTools().single()
        assertTrue(failed.hasReplayResult)
        assertEquals(ToolResultStatus.FAILED, failed.resultStatus)
        assertEquals(null, sampling.assistantMessage.getTools().single().let { tool ->
            checkpoints.filterIsInstance<ToolExecutionCheckpoint>().firstOrNull { checkpoint ->
                checkpoint.toolExecution?.localCallId == tool.localCallId
            }
        })
    }

    @Test
    fun `contract rejection commits its result when neighboring tool awaits approval`() = runTest {
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "invalid", toolName = "ask_user", input = "{}"),
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "approval", toolName = "approval_tool", input = "{}"),
            ),
        )
        val approvalTool = Tool(
            name = "approval_tool",
            description = "requires approval",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = { listOf(UIMessagePart.Text("done")) },
        )
        val harness = createProviderHarness(responseMessage = toolMessage)
        val checkpoints = mutableListOf<TurnCheckpoint>()
        val observed = mutableListOf<UIMessage>()

        val capture = TurnRunCapture()
        harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("draw then continue")),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(approvalTool, buildAskUserTool()),
                maxSteps = 1,
                onAssistantObserved = { observed += it },
                onCheckpoint = { checkpoint -> checkpoints += checkpoint },
                capture = capture,
            )
        )

        val sampling = checkpoints.filterIsInstance<ModelResponseCheckpoint>().single()
        assertEquals(TurnExecutionStatus.AWAITING_USER, sampling.turnStatus)
        assertTrue(checkpoints.none { it is ToolResultCheckpoint })
        val resultTools = sampling.assistantMessage.getTools()
        assertTrue(resultTools[0].hasReplayResult)
        assertEquals(ToolResultStatus.FAILED, resultTools[0].resultStatus)
        assertTrue(resultTools[1].isPending)
        capture.streamDeltas.forEach { message ->
            val tools = message.getTools()
            if (tools.size > 1) assertFalse(tools[1].isPending)
        }
        assertTrue(observed.last().getTools()[1].isPending)
        assertEquals(1, capture.results.size)
        val pending = (capture.result as TurnPause).pendingInteractions
        assertEquals(listOf(ToolInteractionState.AwaitingApproval), pending.map { it.interaction })
        assertEquals(listOf(resultTools[1].localCallId), pending.map { it.locator.localCallId })
    }

    @Test
    fun `pure pending batch carries its locator and keeps the pause off the streaming channel`() = runTest {
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(),
                    stepId = Uuid.random(),
                    providerCallId = "ask",
                    toolName = "ask_user",
                    input = """{"questions":[{"id":"q","question":"Continue?"}]}""",
                ),
            ),
        )
        val harness = createProviderHarness(responseMessage = toolMessage)
        val observed = mutableListOf<UIMessage>()
        val checkpoints = mutableListOf<TurnCheckpoint>()
        val capture = TurnRunCapture()
        harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("continue")),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(buildAskUserTool()),
                maxSteps = 1,
                onAssistantObserved = { observed += it },
                onCheckpoint = { checkpoint -> checkpoints += checkpoint },
                capture = capture,
            )
        )

        assertEquals(1, capture.results.size)
        val sampling = checkpoints.filterIsInstance<ModelResponseCheckpoint>().single()
        assertEquals(TurnExecutionStatus.AWAITING_USER, sampling.turnStatus)
        val pending = (capture.result as TurnPause).pendingInteractions
        assertEquals(listOf(ToolInteractionState.AwaitingInput), pending.map { it.interaction })
        assertEquals(listOf(observed.last().getTools()[0].localCallId), pending.map { it.locator.localCallId })

        // 暂停投影按设计不经流式通道：最后一个已发布快照里该工具还没有 interaction metadata。
        val lastPublished = capture.streamDeltas.last()
        assertEquals(ToolInteractionState.NotRequired, lastPublished.getTools()[0].interactionState)
        // 但 durable 槽必须拿到完整的挂起投影，且 Finished 自带精确地址。
        val lastObserved = observed.last().getTools()[0]
        assertTrue(lastObserved.isPending)
        assertEquals(ToolInteractionState.AwaitingInput, lastObserved.interactionState)
    }

    @Test
    fun `multiple tools commit started and completed state one by one`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
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
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1", toolName = first.name, input = "{}"),
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-2", toolName = second.name, input = "{}"),
            ),
        )
        val toolEvents = mutableListOf<ToolExecutionFact>()

        handler.run(
            turnRunInputsFixture(
            conversationId = kotlin.uuid.Uuid.random(),
            settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(message),
            assistant = assistant,
            promptInputs = testPromptInputs(),
            tools = listOf(first, second),
            maxSteps = 1,
            onCheckpoint = { checkpoint ->
                (checkpoint as? ToolExecutionCheckpoint)?.toolExecution?.let { event ->
                    toolEvents += event
                    executionOrder += "commit:${event.toolName}:${event.status.name.lowercase()}"
                    // stepId 权威：checkpoint 的 Step 取自该 Call 自身的 stepId（消息里的 Tool part），
                    // 而非累加器当前 step；resume 时累加器可能为 NIL，此断言锁死该链路。
                    val toolPart = checkpoint.assistantMessage.getTools().single { it.localCallId == event.localCallId }
                    assertEquals(toolPart.stepId, checkpoint.step.stepId)
                    if (event.toolName == second.name && event.status == ToolExecutionStatus.STARTED) {
                        assertTrue(checkpoint.assistantMessage.getTools().first().hasReplayResult)
                    }
                }
            },
            )
        )

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
                ToolExecutionStatus.STARTED,
                ToolExecutionStatus.COMPLETED,
                ToolExecutionStatus.STARTED,
                ToolExecutionStatus.COMPLETED,
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
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val toolEvents = mutableListOf<ToolExecutionFact>()
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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-missing",
                    toolName = "missing_tool",
                    input = "{}",
                    interactionState = ToolInteractionState.Approved,
                )
            ),
        )
        val frozenTools = run {
            toolSetBuilds++
            listOf(tool)
        }

        val capture = TurnRunCapture()
        handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(message),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                tools = frozenTools,
                maxSteps = 1,
                onCheckpoint = { checkpoint ->
                    (checkpoint as? ToolExecutionCheckpoint)?.toolExecution?.let(toolEvents::add)
                },
                capture = capture,
            )
        )

        assertFalse(executed)
        assertEquals(1, toolSetBuilds)
        val toolResult = capture.streamDeltas.last().getTools().single()
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
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val toolEvents = mutableListOf<ToolExecutionFact>()
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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-mcp",
                    toolName = tool.name,
                    input = "{}",
                    interactionState = ToolInteractionState.NotRequired,
                )
            ),
        )

        val capture = TurnRunCapture()
        handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(message),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(tool),
                maxSteps = 1,
                onCheckpoint = { checkpoint -> (checkpoint as? ToolExecutionCheckpoint)?.toolExecution?.let(toolEvents::add) },
                capture = capture,
            )
        )

        val result = capture.streamDeltas.last().getTools().single()
        assertEquals(ownerOutput, result.output.single())
        assertEquals(
            listOf(ToolExecutionStatus.STARTED, ToolExecutionStatus.FAILED),
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
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val toolA = Tool(name = "dup", description = "A", execute = { emptyList() })
        val toolB = Tool(name = "dup", description = "B", execute = { emptyList() })

        val failure = runCatching {
            handler.run(
                turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                    model = model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = assistant,
                    promptInputs = testPromptInputs(),
                    tools = listOf(toolA, toolB),
                    maxSteps = 1,
                )
            )
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
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )

        val failure = runCatching {
            handler.run(
                turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = Settings(providers = listOf(providerSetting)),
                    model = model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = Assistant(enableMemory = false),
                    promptInputs = testPromptInputs(),
                    tools = listOf(Tool(name = "", description = "invalid", execute = { emptyList() })),
                    maxSteps = 1,
                )
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("Tool name must not be blank"))
    }

    @Test
    fun `update message parts with executed tools`() {
        val originalMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me help"),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tc1",
                    toolName = "search_web",
                    input = """{"query":"test"}"""
                )
            )
        )

        val executedTool = originalMessage.getTools()[0].copy(
            output = listOf(UIMessagePart.Text("search results")),
        )

        val updatedMessage = originalMessage.replaceToolByLocalCallId(executedTool)
        val tools = updatedMessage.getTools()
        assertEquals(1, tools.size)
        assertTrue(tools[0].hasReplayResult)
        assertEquals("search results", (tools[0].output[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `multiple tool execution keys by localCallId even when provider ids repeat`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "duplicate",
                    toolName = "search_web",
                    input = """{"query":"test1"}"""
                ),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "duplicate",
                    toolName = "search_web",
                    input = """{"query":"test2"}"""
                )
            )
        )

        var updatedMessage = message
        message.getTools().forEachIndexed { ordinal, tool ->
            updatedMessage = updatedMessage.replaceToolByLocalCallId(
                tool.copy(output = listOf(UIMessagePart.Text("result${ordinal + 1}"))),
            )
        }
        assertEquals("result1", (updatedMessage.getTools()[0].output.single() as UIMessagePart.Text).text)
        assertEquals("result2", (updatedMessage.getTools()[1].output.single() as UIMessagePart.Text).text)
    }
}
