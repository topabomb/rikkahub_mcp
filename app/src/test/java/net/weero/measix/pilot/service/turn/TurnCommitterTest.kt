package net.weero.measix.pilot.service.turn

import net.weero.measix.pilot.service.runtime.ConversationCommand
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.ModelResponseCheckpoint
import net.weero.measix.pilot.service.runtime.StepHandle
import net.weero.measix.pilot.service.runtime.StreamingDeltaResult
import net.weero.measix.pilot.service.runtime.ToolExecutionCheckpoint
import net.weero.measix.pilot.service.runtime.ToolExecutionStartedCheckpoint
import net.weero.measix.pilot.service.runtime.ToolExecutionUpdatedCheckpoint
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.test.turnRunInputsFixture

import net.weero.measix.pilot.test.testPromptInputs

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderFailureKind
import me.rerere.ai.util.ProviderTerminalStatus
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.PendingToolInteraction
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Turn 提交协议（[TurnCommitter]）权威测试：typed checkpoint→command、流式投影汇、终态汇、
 * 失败分类，以及 STARTED checkpoint 失败不得产生工具副作用、压缩计数只随成功 step checkpoint 落盘。
 * loop 与 committer 的端到端组合由 [TurnRunnerTest]/[StepRunnerTest]/[ToolBatchRunnerTest]/[ToolResourceCommitTest] 覆盖。
 */
class TurnCommitterTest {
    private fun msg(text: String): UIMessage = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private data class Harness(
        val coordinator: ConversationCommandCoordinator,
        val runtime: ConversationRuntime,
        val handle: TurnHandle,
        val finalization: TurnFinalizer,
        val turnCommitter: TurnCommitter,
    )

    private fun harness(): Harness {
        val id = Uuid.random()
        val runtime = mockk<ConversationRuntime>()
        every { runtime.id } returns id
        every { runtime.applyStreamingDelta(any(), any()) } returns StreamingDeltaResult.APPLIED
        every { runtime.peekCancelReason(any()) } returns "user_stop"
        val aggregate = Conversation.ofId(id).toSnapshot()
        every { runtime.snapshot } returns MutableStateFlow(
            ConversationRuntimeSnapshot(durable = aggregate, stream = null),
        )
        every { runtime.durable } returns aggregate
        val coordinator = mockk<ConversationCommandCoordinator>()
        coEvery { coordinator.executeOrThrow(any(), any()) } returns Unit
        val handle = TurnHandle(id, 1, Uuid.random(), Uuid.random())
        val finalization = mockk<TurnFinalizer>()
        coEvery {
            finalization.prepareOwnedAssistantForFailure(any(), any(), any(), any(), any())
        } coAnswers {
            thirdArg<UIMessage?>()
        }
        return Harness(
            coordinator,
            runtime,
            handle,
            finalization,
            TurnCommitter(coordinator, runtime, handle, finalization),
        )
    }

    @Test
    fun `checkpoint commits one typed checkpoint command`() = runTest {
        val harness = harness()
        harness.turnCommitter.onCheckpoint(
            ModelResponseCheckpoint(
                turn = harness.handle,
                step = StepHandle(Uuid.random()),
                assistantMessage = msg("done"),
                turnStatus = TurnExecutionStatus.RUNNING,
            )
        )

        val command = slot<ConversationCommand>()
        coVerify(exactly = 1) { harness.coordinator.executeOrThrow(any(), capture(command)) }
        val checkpoint = command.captured as ModelResponseCheckpoint
        assertEquals(harness.handle, checkpoint.turn)
        assertEquals(TurnExecutionStatus.RUNNING, checkpoint.turnStatus)
    }

    @Test
    fun `metadata-only state change commits an updated checkpoint without an execution fact`() = runTest {
        val harness = harness()
        harness.turnCommitter.onCheckpoint(
            ToolExecutionUpdatedCheckpoint(
                turn = harness.handle,
                step = StepHandle(Uuid.random()),
                assistantMessage = msg("metadata"),
                toolExecution = null,
            )
        )

        val command = slot<ConversationCommand>()
        coVerify(exactly = 1) { harness.coordinator.executeOrThrow(any(), capture(command)) }
        val checkpoint = command.captured as ToolExecutionUpdatedCheckpoint
        assertNull(checkpoint.toolExecution)
    }

    @Test
    fun `finalization cannot overwrite a committed checkpoint before its projection`() = runTest {
        val harness = harness()
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit
        val checkpointAssistant = msg("[Derived tool result folded]").copy(
            id = harness.handle.assistantMessageId,
        )

        harness.turnCommitter.onCheckpoint(
            ModelResponseCheckpoint(
                turn = harness.handle,
                step = StepHandle(Uuid.random()),
                assistantMessage = checkpointAssistant,
                turnStatus = TurnExecutionStatus.RUNNING,
            ),
        )
        harness.turnCommitter.commitRunResult(TurnOutcome.Completed(assistantMessage = checkpointAssistant))

        assertEquals(listOf(ModelResponseCheckpoint::class, FinalizeTurn::class), recorded.map { it::class })
        assertEquals(checkpointAssistant, (recorded.last() as FinalizeTurn).assistantMessage)
    }

    @Test
    fun `streaming delta applies the owned projection before terminal finalization`() = runTest {
        val harness = harness()
        val assistant = msg("hi").copy(id = harness.handle.assistantMessageId)

        harness.turnCommitter.observeAssistant(assistant)
        harness.turnCommitter.publishStream(assistant)
        harness.turnCommitter.commitRunResult(TurnOutcome.Incomplete(TurnTerminalReasons.PROVIDER_INCOMPLETE))

        coVerify(exactly = 1) { harness.coordinator.executeOrThrow(any(), any()) }
        io.mockk.verify(exactly = 1) { harness.runtime.applyStreamingDelta(harness.handle, assistant) }
    }

    @Test
    fun `completed outcome submits the sealed completed finalization`() = runTest {
        val harness = harness()

        harness.turnCommitter.commitRunResult(TurnOutcome.Completed(assistantMessage = msg("done")))

        val command = slot<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(command)) }
        assertEquals(TurnExecutionStatus.COMPLETED, (command.captured as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `runtime failure submits classified detail and retains the exception`() = runTest {
        val harness = harness()
        val failure = IllegalStateException("provider failed")
        val partial = msg("partial after checkpoint").copy(id = harness.handle.assistantMessageId)

        harness.turnCommitter.observeAssistant(partial)
        harness.turnCommitter.publishStream(partial)
        val outcome = TurnOutcome.fromFailure(failure)
        harness.turnCommitter.commitRunResult(outcome)

        outcome as TurnOutcome.Failed
        assertEquals(failure, outcome.error)
        assertEquals(ProviderFailureKind.RUNTIME_ERROR.reason, outcome.terminalReason)
        assertEquals("provider failed", outcome.terminalDetail)
        val commands = mutableListOf<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(commands)) }
        val finalize = commands.filterIsInstance<FinalizeTurn>().single()
        assertEquals(TurnExecutionStatus.FAILED, finalize.terminalStatus)
        assertEquals(partial, finalize.assistantMessage)
        assertEquals(ProviderFailureKind.RUNTIME_ERROR.reason, finalize.terminalReason)
        assertEquals("provider failed", finalize.terminalDetail)
    }

    @Test
    fun `materialize failure finalizes the started turn with the dedicated reason`() = runTest {
        val harness = harness()
        val error = IllegalStateException("pure binding threw")

        // materialize 抛错即编程错误，owner 以专用 reason 收口已启动的 Turn（sink 契约）。
        harness.turnCommitter.finalizeOwnerFailure(
            TurnOutcome.Failed(error, TurnTerminalReasons.TURN_CONTEXT_MATERIALIZE),
        )

        val commands = mutableListOf<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(commands)) }
        val finalize = commands.filterIsInstance<FinalizeTurn>().single()
        assertEquals(TurnExecutionStatus.FAILED, finalize.terminalStatus)
        assertEquals(TurnTerminalReasons.TURN_CONTEXT_MATERIALIZE, finalize.terminalReason)
    }

    @Test
    fun `provider rate limit persists fine grained reason and sanitized detail`() = runTest {
        val harness = harness()
        val failure = HttpException(
            message = "Please retry after 2 seconds. secret sk-abcdefghijklmnop",
            statusCode = 429,
        )

        val outcome = TurnOutcome.fromFailure(failure) as TurnOutcome.Failed
        harness.turnCommitter.commitRunResult(outcome)

        assertEquals(ProviderFailureKind.RATE_LIMITED.reason, outcome.terminalReason)
        assertEquals("Please retry after 2 seconds. secret …", outcome.terminalDetail)
        val command = slot<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(command)) }
        assertEquals(outcome.terminalDetail, (command.captured as FinalizeTurn).terminalDetail)
    }

    @Test
    fun `provider incomplete keeps protocol detail separate from failed`() = runTest {
        val harness = harness()
        val failure = HttpException(
            message = "Response incomplete: max_output_tokens",
            terminalStatus = ProviderTerminalStatus.INCOMPLETE,
        )

        val outcome = TurnOutcome.fromFailure(failure)
        harness.turnCommitter.commitRunResult(outcome)

        outcome as TurnOutcome.Incomplete
        assertEquals(TurnTerminalReasons.PROVIDER_INCOMPLETE, outcome.terminalReason)
        assertEquals("Response incomplete: max_output_tokens", outcome.terminalDetail)
        val command = slot<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(command)) }
        assertEquals(TurnExecutionStatus.INCOMPLETE, (command.captured as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `wrapped provider incomplete keeps incomplete terminal status`() = runTest {
        val harness = harness()
        val providerFailure = HttpException(
            message = "Response incomplete: max_output_tokens",
            terminalStatus = ProviderTerminalStatus.INCOMPLETE,
        )

        val outcome = TurnOutcome.fromFailure(IllegalStateException("provider wrapper", providerFailure))
        harness.turnCommitter.commitRunResult(outcome)

        outcome as TurnOutcome.Incomplete
        assertEquals(TurnTerminalReasons.PROVIDER_INCOMPLETE, outcome.terminalReason)
        assertEquals("Response incomplete: max_output_tokens", outcome.terminalDetail)
        val command = slot<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(command)) }
        assertEquals(TurnExecutionStatus.INCOMPLETE, (command.captured as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `finalization failure propagates without rewriting the outcome`() = runTest {
        val harness = harness()
        val failure = IllegalStateException("commit failed")
        val commands = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(commands)) } throws failure

        val thrown = runCatching { harness.turnCommitter.commitRunResult(TurnOutcome.Completed(assistantMessage = msg("done"))) }.exceptionOrNull()

        assertEquals(failure, thrown)
        assertEquals(1, commands.size)
        assertEquals(TurnExecutionStatus.COMPLETED, (commands.single() as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `cancelled outcome submits cancelled finalization`() = runTest {
        val harness = harness()
        val partial = msg("partial after checkpoint").copy(id = harness.handle.assistantMessageId)

        harness.turnCommitter.observeAssistant(partial)
        harness.turnCommitter.commitRunResult(TurnOutcome.Cancelled(TurnTerminalReasons.USER_STOP))

        val commands = mutableListOf<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(commands)) }
        val finalize = commands.filterIsInstance<FinalizeTurn>().single()
        assertEquals(TurnExecutionStatus.CANCELLED, finalize.terminalStatus)
        assertEquals(partial, finalize.assistantMessage)
    }

    @Test
    fun `master and target use identical command shapes`() = runTest {
        suspend fun drive(): List<ConversationCommand> {
            val harness = harness()
            val recorded = mutableListOf<ConversationCommand>()
            coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit
            harness.turnCommitter.onCheckpoint(
                ModelResponseCheckpoint(
                    turn = harness.handle,
                    step = StepHandle(Uuid.random()),
                    assistantMessage = msg("checkpoint"),
                    turnStatus = TurnExecutionStatus.RUNNING,
                )
            )
            harness.turnCommitter.commitRunResult(TurnOutcome.Completed(assistantMessage = msg("checkpoint")))
            return recorded
        }

        val master = drive()
        val target = drive()
        assertEquals(master.map { it::class }, target.map { it::class })
        assertEquals(listOf(ModelResponseCheckpoint::class, FinalizeTurn::class), master.map { it::class })
    }

    @Test
    fun `approval continuation reuses frozen tool binding on the same handle`() = runTest {
        val harness = harness()
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit

        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getProviderByType(providerSetting) } returns
            mockk<Provider<ProviderSetting.OpenAI>>(relaxed = true)
        val turnRunner = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false)
        val settings = Settings(
            providers = listOf(providerSetting),
            assistants = listOf(assistant),
        )
        var executed = false
        val tool = Tool(
            name = "revocable_tool",
            description = "Requires approval before executing.",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = {
                executed = true
                listOf(UIMessagePart.Text("executed"))
            },
        )
        val waitingMessage = UIMessage(
            id = harness.handle.assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-revocable",
                    toolName = tool.name,
                    input = "{}",
                )
            ),
        )

        val observed = mutableListOf<UIMessage>()
        suspend fun drive(messages: List<UIMessage>) = turnRunner.run(turnRunInputsFixture(
            conversationId = harness.runtime.id,
            settings = settings,
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = messages,
            assistant = assistant,
            promptInputs = testPromptInputs(),
            tools = listOf(tool),
            maxSteps = 1,
            assistantMessageId = waitingMessage.id,
            handle = harness.handle,
            onCheckpoint = harness.turnCommitter::onCheckpoint,
            onAssistantObserved = { message ->
                observed += message
                harness.turnCommitter.observeAssistant(message)
            },
            onStreamDelta = harness.turnCommitter::publishStream,
            onResult = harness.turnCommitter::commitRunResult,
        ))

        assertTrue(drive(listOf(waitingMessage)) is TurnPause)
        val pendingMessage = observed.last { message -> message.getTools().singleOrNull()?.isPending == true }
        assertTrue(pendingMessage.getTools().single().interactionState is ToolInteractionState.AwaitingApproval)

        val approvedMessage = pendingMessage.copy(
            parts = pendingMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(interactionState = ToolInteractionState.Approved)
                } else {
                    part
                }
            },
        )
        drive(listOf(approvedMessage))

        assertTrue(executed)
        val committedResult = recorded.filterIsInstance<TurnCheckpoint>()
            .asSequence()
            .flatMap { checkpoint -> checkpoint.assistantMessage.getTools().asSequence() }
            .first { result -> result.output.filterIsInstance<UIMessagePart.Text>().any { it.text == "executed" } }
        assertEquals("executed", (committedResult.output.single() as UIMessagePart.Text).text)

        val checkpoints = recorded.filterIsInstance<ToolExecutionCheckpoint>()
        assertEquals(
            listOf("STARTED", "COMPLETED"),
            checkpoints.mapNotNull { it.toolExecution }.map { it.status.name },
        )
        assertTrue(recorded.all { command ->
            when (command) {
                is TurnCheckpoint -> command.turn == harness.handle
                is FinalizeTurn -> command.handle == harness.handle
                else -> true
            }
        })
    }

    @Test
    fun `approval pause and continuation keep one handle until terminal finalization`() = runTest {
        val harness = harness()
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit
        val waitingAssistant = msg("waiting").copy(id = harness.handle.assistantMessageId)

        harness.turnCommitter.observeAssistant(waitingAssistant)
        harness.turnCommitter.publishStream(waitingAssistant)
        harness.turnCommitter.onCheckpoint(
            ModelResponseCheckpoint(
                turn = harness.handle,
                step = StepHandle(Uuid.random()),
                assistantMessage = waitingAssistant,
                turnStatus = TurnExecutionStatus.AWAITING_USER,
            )
        )
        harness.turnCommitter.commitRunResult(
            TurnPause(
                pendingInteractions = listOf(
                    PendingToolInteraction(
                        locator = ToolCallLocator(harness.handle.assistantMessageId, Uuid.random(), Uuid.random()),
                        interaction = ToolInteractionState.AwaitingInput,
                    ),
                ),
            ),
        )
        harness.turnCommitter.commitRunResult(TurnOutcome.Completed(assistantMessage = waitingAssistant))

        assertEquals(
            listOf(ModelResponseCheckpoint::class, FinalizeTurn::class),
            recorded.map { it::class },
        )
        assertEquals(
            TurnExecutionStatus.AWAITING_USER,
            (recorded[0] as ModelResponseCheckpoint).turnStatus,
        )
        assertEquals(harness.handle, (recorded[0] as ModelResponseCheckpoint).turn)
        assertEquals(harness.handle, (recorded[1] as FinalizeTurn).handle)
    }

    @Test
    fun `real cancellation after a tool request accounts for the second request without usage`() = runTest {
        val finalized = cancelProviderRequest(afterTool = true, reportPendingUsage = false)
        val usage = finalized.assistantMessage!!.usage!!

        assertEquals(2, usage.observedProviderRequestCount)
        assertEquals(1, usage.observedUsageReportedRequestCount)
        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(120L, usage.totalTokens)
        assertEquals(UsageCompleteness.PARTIAL, usage.coreCompleteness)
        assertEquals(UsageCompleteness.PARTIAL, usage.cacheReadCompleteness)
        assertNull(usage.latestRequestContextTokens)
        assertNull(usage.latestRequestCacheReadInputTokens)
        assertNotNull(usage.providerRequestDurationMillis)
    }

    @Test
    fun `real cancellation of first request without usage still records its closed boundary`() = runTest {
        val finalized = cancelProviderRequest(afterTool = false, reportPendingUsage = false)
        val usage = finalized.assistantMessage!!.usage!!

        assertEquals(1, usage.observedProviderRequestCount)
        assertEquals(0, usage.observedUsageReportedRequestCount)
        assertEquals(UsageCompleteness.NONE, usage.coreCompleteness)
        assertEquals(UsageCompleteness.NONE, usage.cacheReadCompleteness)
        assertNull(usage.inputTokens)
        assertNull(usage.outputTokens)
        assertNotNull(usage.providerRequestDurationMillis)
        assertNotNull(usage.initialRequestTimeToFirstOutputMillis)
    }

    @Test
    fun `real cancellation commits closed latest context and cache`() = runTest {
        val finalized = cancelProviderRequest(afterTool = false, reportPendingUsage = true)
        val usage = finalized.assistantMessage!!.usage!!

        assertEquals(1, usage.observedProviderRequestCount)
        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(100L, usage.latestRequestContextTokens)
        assertEquals(0L, usage.latestRequestCacheReadInputTokens)
        assertEquals(UsageCompleteness.COMPLETE, usage.coreCompleteness)
    }

    @Test
    fun `failed started checkpoint prevents tool side effect`() = runTest {
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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
                    toolName = tool.name,
                    input = "{}",
                )
            ),
        )

        val outcome = handler.run(
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
                        error("checkpoint storage unavailable")
                    }
                },
            )
        )

        assertFalse(executed)
        val failure = (outcome as TurnOutcome.Failed).error
        assertTrue(failure.message?.contains("checkpoint storage unavailable") == true)
    }

    @Test
    fun `tool trim count becomes durable only with its successful step checkpoint`() = runTest {
        suspend fun runCase(failure: Throwable?): UIMessage? {
            val assistantMessageId = kotlin.uuid.Uuid.random()
            val trimStepId = Uuid.random()
            val trimLocalCallId = Uuid.random()
            val store = mockk<ToolOutputStore>()
            val archive = ToolOutputArchive(
                ref = 7,
                artifact = ToolOutputArchiveRef("tool_outputs/trim.txt", "text/plain"),
                characters = 10,
                lines = 1,
            )
            coEvery { store.stageCompaction(any()) } returns ToolOutputStore.StagedCompactionBatch(
                replacements = mapOf(
                    ToolCallLocator(assistantMessageId, trimStepId, trimLocalCallId) to ToolOutputStore.CompactionReplacement(
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
                        localCallId = trimLocalCallId, stepId = trimStepId, providerCallId = "done",
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
                    turnRunInputsFixture(
                        conversationId = kotlin.uuid.Uuid.random(),
                        settings = harness.settings,
                        model = harness.model,
                        mediaCapabilities = RequestMediaCapabilities.NONE,
                        messages = listOf(
                            UIMessage.user("continue"),
                            UIMessage(id = assistantMessageId, role = MessageRole.ASSISTANT, parts = emptyList()),
                        ),
                        assistant = harness.assistant,
                        promptInputs = testPromptInputs(),
                        maxSteps = 1,
                        assistantMessageId = assistantMessageId,
                        onResult = { result ->
                            // 无 Tool Final step 的窄压缩只随唯一终态 rooting 变 durable。
                            if (result is TurnOutcome.Completed) {
                                val message = requireNotNull(result.assistantMessage)
                                assertEquals(
                                    1,
                                    message.usage?.successfulToolOutputCompactionBatchCount,
                                )
                                failure?.let { throw it }
                                durable = message
                            }
                        },
                    ),
                )
            }
            return durable
        }

        val committed = requireNotNull(runCase(null))
        assertEquals(1, committed.usage?.successfulToolOutputCompactionBatchCount)
        assertEquals(null, runCase(IllegalStateException("checkpoint failed")))
        assertEquals(null, runCase(CancellationException("cancelled before checkpoint")))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.cancelProviderRequest(
        afterTool: Boolean,
        reportPendingUsage: Boolean,
    ): FinalizeTurn {
        val harness = harness()
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit
        val model = Model(modelId = "test-model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getProviderByType(providerSetting) } returns provider
        val completeUsage = ProviderUsageSnapshot(
            inputTokens = 100, outputTokens = 20, cacheReadInputTokens = 0, totalTokens = 120,
        )
        var requests = 0
        coEvery { provider.streamText(providerSetting, any(), any()) } coAnswers {
            val firstToolRequest = afterTool && requests++ == 0
            flow {
                val part = if (firstToolRequest) {
                    UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call", toolName = "test_tool", input = "{}")
                } else {
                    UIMessagePart.Text("pending response")
                }
                emit(MessageChunk(
                    id = "response",
                    model = model.modelId,
                    choices = listOf(UIMessageChoice(
                        index = 0,
                        delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
                        message = null,
                        finishReason = null,
                    )),
                    usage = completeUsage.takeIf { firstToolRequest || reportPendingUsage },
                ))
                if (!firstToolRequest) awaitCancellation()
            }
        }
        val turnRunner = TurnRunner(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = true)
        val pendingSeen = CompletableDeferred<Unit>()
        val collector = launch {
            turnRunner.run(turnRunInputsFixture(
                conversationId = harness.runtime.id,
                settings = Settings(providers = listOf(providerSetting)),
                model = model,
                mediaCapabilities = RequestMediaCapabilities.NONE,
                messages = listOf(UIMessage.user("hello"), UIMessage(
                    id = harness.handle.assistantMessageId,
                    role = MessageRole.ASSISTANT,
                    parts = emptyList(),
                )),
                assistant = assistant,
                promptInputs = testPromptInputs(),
                assistantMessageId = harness.handle.assistantMessageId,
                handle = harness.handle,
                tools = listOf(Tool(
                    name = "test_tool",
                    description = "test",
                    execute = { listOf(UIMessagePart.Text("done")) },
                )),
                onCheckpoint = harness.turnCommitter::onCheckpoint,
                onAssistantObserved = harness.turnCommitter::observeAssistant,
                onStreamDelta = { message ->
                    harness.turnCommitter.publishStream(message)
                    if (message.toText().contains("pending response")) {
                        pendingSeen.complete(Unit)
                    }
                },
                onResult = harness.turnCommitter::commitRunResult,
                cancelReason = { harness.runtime.peekCancelReason(harness.handle.turnId) },
            ))
        }
        pendingSeen.await()
        collector.cancelAndJoin()
        assertTrue(collector.isCancelled)
        val finalized = recorded.filterIsInstance<FinalizeTurn>().single()
        assertEquals(TurnExecutionStatus.CANCELLED, finalized.terminalStatus)
        assertTrue(finalized.assistantMessage!!.toText().contains("pending response"))
        return finalized
    }
}
