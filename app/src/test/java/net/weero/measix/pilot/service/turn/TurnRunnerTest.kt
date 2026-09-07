package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.ToolExecutionStartedCheckpoint
import net.weero.measix.pilot.service.runtime.ToolResultCheckpoint
import net.weero.measix.pilot.test.TurnRunCapture
import net.weero.measix.pilot.test.testPromptInputs
import net.weero.measix.pilot.test.turnRunInputsFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TurnRunner] 的多 Step 循环与用量累计：跨 step 的 provider request usage 恰好累计一次、
 * 达到 step 上限产出非正常完成 [TurnOutcome.Incomplete] TOOL_LOOP_LIMIT、请求摘要先于 provider
 * usage 且闭合记账原子、provider 失败/取消各闭合一次 usage 并保留原错误或传播取消、
 * 子 job 发出的 metadata 保持流透明（checkpoint 先于发布、顺序稳定）、
 * onResult 抛出不得把 Completed 再分类为 Failed、STARTED 交接点取消不得执行副作用。
 */
class TurnRunnerTest {
    @Test
    fun `metadata emitted from child job remains flow transparent`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        every {
            providerManager.getProviderByType(any<ProviderSetting.OpenAI>())
        } returns provider
        val handler = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "provider-call-id",
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
            onCheckpoint = { checkpoint ->
                if (checkpoint is ToolExecutionStartedCheckpoint) {
                    presentationEvents += "checkpoint:started"
                    persistedBeforeExecute.set(true)
                    assertTrue(!executed.get())
                    assertEquals(ToolExecutionStatus.STARTED, checkpoint.toolExecution.status)
                }
                if (checkpoint is ToolResultCheckpoint) {
                    presentationEvents += "checkpoint:result"
                }
            },
            onStreamDelta = { message ->
                val hasReplayResult = message.getTools().singleOrNull()?.hasReplayResult
                presentationEvents += "messages:$hasReplayResult"
            },
            capture = capture,
            )
        )

        assertTrue(capture.phases.contains("tool_executing" to "metadata_tool"))
        val phaseNames = capture.phases.map { it.first }
        assertTrue("tool batch preparation must emit tool_preparing", phaseNames.contains("tool_preparing"))
        assertTrue(
            "tool_preparing must precede tool_executing",
            phaseNames.indexOf("tool_preparing") < phaseNames.indexOf("tool_executing"),
        )
        assertTrue(persistedBeforeExecute.get())
        val finalTool = capture.streamDeltas.last().getTools().single()
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
                                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
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
        val loop = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val tool = Tool(
            name = "echo_tool",
            description = "Returns a stable result.",
            execute = { listOf(UIMessagePart.Text("tool result")) },
        )

        val capture = TurnRunCapture()
        loop.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(tool),
                maxSteps = 2,
                capture = capture,
            )
        )

        val usage = capture.streamDeltas.last().usage
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
    fun `reaching the step limit yields Incomplete TOOL_LOOP_LIMIT without a normal completion`() = runTest {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        fun toolStep(id: String) = MessageChunk(
            id = id,
            model = model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = id,
                                toolName = "echo_tool",
                                input = "{}",
                            )
                        ),
                    ),
                    finishReason = "tool_calls",
                )
            ),
        )
        coEvery {
            provider.generateText(providerSetting, any(), any())
        } returnsMany listOf(toolStep("call-1"), toolStep("call-2"))
        val assistant = Assistant(enableMemory = false, streamOutput = false)
        val loop = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val tool = Tool(
            name = "echo_tool",
            description = "Returns a stable result.",
            execute = { listOf(UIMessagePart.Text("tool result")) },
        )

        val capture = TurnRunCapture()
        loop.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = Settings(providers = listOf(providerSetting), assistants = listOf(assistant)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                tools = listOf(tool),
                maxSteps = 2,
                capture = capture,
            )
        )

        val result = capture.result as TurnOutcome.Incomplete
        assertEquals(TurnTerminalReasons.TOOL_LOOP_LIMIT, result.terminalReason)
        assertEquals(1, capture.results.size)
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
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                maxSteps = 1,
                onAssistantObserved = { assistant -> observedUsage += assistant.usage },
            ),
        )

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
        var observed: UIMessage? = null

        val outcome = harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                maxSteps = 1,
                onAssistantObserved = { observed = it },
            )
        )

        val failure = (outcome as TurnOutcome.Failed).error
        assertTrue(failure === expectedFailure || failure.cause === expectedFailure)
        // 失败展示事件可被终止传播抢先取消；同步 turn-owner 观察才是 finalize 的事实来源。
        val usage = observed!!.usage
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
        var observed: UIMessage? = null

        val failure = runCatching {
            harness.handler.run(
                turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings,
                    model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = harness.assistant,
                    promptInputs = testPromptInputs(),
                    maxSteps = 1,
                    onAssistantObserved = { observed = it },
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(failure === expectedCancellation || failure?.cause === expectedCancellation)
        val usage = observed!!.usage
        assertEquals(100L, usage?.inputTokens)
        assertEquals(20L, usage?.outputTokens)
        assertEquals(120L, usage?.totalTokens)
        assertEquals(1, usage?.observedProviderRequestCount)
        assertEquals(1, usage?.observedUsageReportedRequestCount)
        assertEquals(me.rerere.ai.core.UsageCompleteness.COMPLETE, usage?.coreCompleteness)
    }

    @Test
    fun `no-tool final turn emits a single terminal with no model response checkpoint`() = runTest {
        val harness = createProviderHarness()
        val capture = TurnRunCapture()
        val result = harness.handler.run(
            turnRunInputsFixture(
                conversationId = kotlin.uuid.Uuid.random(),
                settings = harness.settings,
                model = harness.model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello")),
                assistant = harness.assistant,
                promptInputs = testPromptInputs(),
                maxSteps = 1,
                capture = capture,
            )
        )
        // 无 Tool 的 Final step 不发独立 ModelResponseCheckpoint，唯一 durable 写是终态。
        assertTrue(capture.checkpoints.isEmpty())
        val completed = result as TurnOutcome.Completed
        val terminal = requireNotNull(completed.assistantMessage)
        assertTrue(terminal.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("done") })
        assertEquals(1, capture.results.size)
    }

    @Test
    fun `onResult failure after Completed is not reclassified as Failed`() = runTest {
        val harness = createProviderHarness()
        val capture = TurnRunCapture()
        val commitFailure = IllegalStateException("terminal commit failed")
        val thrown = runCatching {
            harness.handler.run(
                turnRunInputsFixture(
                    conversationId = kotlin.uuid.Uuid.random(),
                    settings = harness.settings,
                    model = harness.model,
                    mediaCapabilities = RequestMediaCapabilities.NONE,
                    messages = listOf(UIMessage.user("hello")),
                    assistant = harness.assistant,
                    promptInputs = testPromptInputs(),
                    maxSteps = 1,
                    capture = capture,
                    onResult = { throw commitFailure },
                )
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("terminal commit failed", thrown!!.message)
        assertEquals(1, capture.results.size)
        assertTrue(capture.result is TurnOutcome.Completed)
        assertTrue(capture.results.none { it is TurnOutcome.Failed })
    }

    @Test
    fun `cancel after STARTED checkpoint before execute does not run the tool`() = runTest {
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
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executed = false
        val tool = Tool(
            name = "side_effect",
            description = "Must not run after STARTED if the turn is cancelled at that barrier.",
            execute = {
                executed = true
                listOf(UIMessagePart.Text("executed"))
            },
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
                    toolName = tool.name,
                    input = "{}",
                )
            ),
        )
        val collector = launch {
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
                    onCheckpoint = { checkpoint ->
                        if (checkpoint is ToolExecutionStartedCheckpoint) {
                            started.complete(Unit)
                            release.await()
                        }
                    },
                )
            )
        }
        started.await()
        collector.cancel()
        release.complete(Unit)
        collector.join()

        assertTrue(collector.isCancelled)
        assertFalse(executed)
    }
}
