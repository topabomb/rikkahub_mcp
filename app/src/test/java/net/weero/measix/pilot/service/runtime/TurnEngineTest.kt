package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.test.generationRequestFixture

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
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
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderFailureKind
import me.rerere.ai.util.ProviderTerminalStatus
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationCheckpoint
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.ai.GenerationLoop
import net.weero.measix.pilot.data.ai.ToolExecutionEventStatus
import net.weero.measix.pilot.data.ai.GenerationRequest
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.tools.PendingInteraction
import net.weero.measix.pilot.data.ai.tools.ToolInteractionKind
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.TurnFinalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TurnEngineTest {
    private fun msg(text: String): UIMessage = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private data class Harness(
        val coordinator: ConversationCommandCoordinator,
        val runtime: ConversationRuntime,
        val handle: TurnHandle,
        val finalization: TurnFinalization,
        val engine: TurnEngine,
    )

    private fun harness(): Harness {
        val id = Uuid.random()
        val runtime = mockk<ConversationRuntime>()
        every { runtime.id } returns id
        every { runtime.applyStreamingDelta(any(), any()) } returns StreamingDeltaResult.APPLIED
        every { runtime.peekCancelReason(any()) } returns "user_stop"
        every { runtime.snapshot } returns MutableStateFlow(Conversation.ofId(id).toSnapshot())
        val coordinator = mockk<ConversationCommandCoordinator>()
        coEvery { coordinator.executeOrThrow(any(), any()) } returns Unit
        val handle = TurnHandle(id, 1, Uuid.random(), Uuid.random())
        val finalization = mockk<TurnFinalization>()
        coEvery {
            finalization.prepareOwnedTurnMessagesForFailure(any(), any(), any(), any(), any())
        } coAnswers {
            thirdArg<List<UIMessage>>()
        }
        return Harness(
            coordinator,
            runtime,
            handle,
            finalization,
            TurnEngine(coordinator, runtime, handle, finalization),
        )
    }

    @Test
    fun `checkpoint commits one typed checkpoint command`() = runTest {
        val harness = harness()
        harness.engine.onCheckpoint(
            GenerationCheckpoint(
                kind = CheckpointKind.STEP_COMPLETED,
                messages = listOf(msg("done")),
                toolExecution = null,
            )
        )

        val command = slot<ConversationCommand>()
        coVerify(exactly = 1) { harness.coordinator.executeOrThrow(any(), capture(command)) }
        val checkpoint = command.captured as CommitCheckpoint
        assertEquals(harness.handle, checkpoint.handle)
        assertEquals(TurnExecutionStatus.RUNNING, checkpoint.turnStatus)
    }

    @Test
    fun `finalization cannot overwrite a committed checkpoint before its projection chunk`() = runTest {
        val harness = harness()
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit
        val checkpointMessages = listOf(msg("[Derived tool result folded]").copy(
            id = harness.handle.assistantMessageId,
        ))

        harness.engine.onCheckpoint(
            GenerationCheckpoint(
                kind = CheckpointKind.STEP_COMPLETED,
                messages = checkpointMessages,
            ),
        )
        harness.engine.bind(emptyFlow()).collect { }

        assertEquals(listOf(CommitCheckpoint::class, FinalizeTurn::class), recorded.map { it::class })
        assertEquals(checkpointMessages, (recorded.last() as FinalizeTurn).messages)
    }

    @Test
    fun `streaming chunks update the owned projection before incomplete close`() = runTest {
        val harness = harness()
        val messages = listOf(msg("hi").copy(id = harness.handle.assistantMessageId))

        val events = harness.engine.bind(flow {
            harness.engine.observeMessages(messages)
            emit(GenerationChunk.Messages(messages))
        }).toListSafe()

        assertTrue(events.first() is TurnEvent.Streaming)
        assertTrue((events.last() as TurnEvent.Finished).outcome is TurnOutcome.Incomplete)
        coVerify(exactly = 1) { harness.coordinator.executeOrThrow(any(), any()) }
        io.mockk.verify(exactly = 1) { harness.runtime.applyStreamingDelta(harness.handle, messages) }
    }

    @Test
    fun `finished chunk submits the sealed completed outcome`() = runTest {
        val harness = harness()

        val events = harness.engine.bind(
            flowOf(GenerationChunk.Finished(FinishedReason.Completed))
        ).toListSafe()

        val finished = events.single() as TurnEvent.Finished
        assertEquals(TurnOutcome.Completed, finished.outcome)
        val command = slot<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(command)) }
        assertEquals(TurnExecutionStatus.COMPLETED, (command.captured as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `runtime failure submits classified detail and retains the exception`() = runTest {
        val harness = harness()
        val failure = IllegalStateException("provider failed")
        val partial = listOf(msg("partial after checkpoint").copy(id = harness.handle.assistantMessageId))

        val events = harness.engine.bind(
            flow {
                harness.engine.observeMessages(partial)
                emit(GenerationChunk.Messages(partial))
                throw failure
            }
        ).toListSafe()

        val outcome = (events.last() as TurnEvent.Finished).outcome as TurnOutcome.Failed
        assertEquals(failure, outcome.error)
        assertEquals(ProviderFailureKind.RUNTIME_ERROR.reason, outcome.terminalReason)
        assertEquals("provider failed", outcome.terminalDetail)
        val commands = mutableListOf<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(commands)) }
        val finalize = commands.filterIsInstance<FinalizeTurn>().single()
        assertEquals(TurnExecutionStatus.FAILED, finalize.terminalStatus)
        assertEquals(partial, finalize.messages)
        assertEquals(ProviderFailureKind.RUNTIME_ERROR.reason, finalize.terminalReason)
        assertEquals("provider failed", finalize.terminalDetail)
    }

    @Test
    fun `provider rate limit persists fine grained reason and sanitized detail`() = runTest {
        val harness = harness()
        val failure = HttpException(
            message = "Please retry after 2 seconds. secret sk-abcdefghijklmnop",
            statusCode = 429,
        )

        val outcome = (harness.engine.bind(flow { throw failure }).toListSafe().single() as TurnEvent.Finished)
            .outcome as TurnOutcome.Failed

        assertEquals(ProviderFailureKind.RATE_LIMITED.reason, outcome.terminalReason)
        assertEquals("Please retry after 2 seconds. secret …", outcome.terminalDetail)
        val command = slot<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(command)) }
        assertEquals(outcome.terminalDetail, (command.captured as FinalizeTurn).terminalDetail)
    }

    @Test
    fun `stream close without terminal event is finalized as incomplete`() = runTest {
        val harness = harness()

        val event = harness.engine.bind(emptyFlow()).toListSafe().single() as TurnEvent.Finished
        val outcome = event.outcome as TurnOutcome.Incomplete

        assertEquals(TurnTerminalReasons.PROVIDER_INCOMPLETE, outcome.terminalReason)
        assertEquals("Response stream ended without a terminal event.", outcome.terminalDetail)
        val command = slot<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(command)) }
        assertEquals(TurnExecutionStatus.INCOMPLETE, (command.captured as FinalizeTurn).terminalStatus)
        coVerify(exactly = 1) {
            harness.finalization.prepareOwnedTurnMessagesForFailure(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `provider incomplete keeps protocol detail separate from failed`() = runTest {
        val harness = harness()
        val failure = HttpException(
            message = "Response incomplete: max_output_tokens",
            terminalStatus = ProviderTerminalStatus.INCOMPLETE,
        )

        val event = harness.engine.bind(flow { throw failure }).toListSafe().single() as TurnEvent.Finished
        val outcome = event.outcome as TurnOutcome.Incomplete

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

        val event = harness.engine.bind(
            flow { throw IllegalStateException("provider wrapper", providerFailure) },
        ).toListSafe().single() as TurnEvent.Finished
        val outcome = event.outcome as TurnOutcome.Incomplete

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

        val thrown = runCatching {
            harness.engine.bind(
                flowOf(GenerationChunk.Finished(FinishedReason.Completed)),
            ).collect { }
        }.exceptionOrNull()

        assertEquals(failure, thrown)
        assertEquals(1, commands.size)
        assertEquals(TurnExecutionStatus.COMPLETED, (commands.single() as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `cancellation submits cancelled once and rethrows`() = runTest {
        val harness = harness()
        val partial = listOf(msg("partial after checkpoint").copy(id = harness.handle.assistantMessageId))

        val thrown = runCatching {
            harness.engine.bind(
                flow {
                    harness.engine.observeMessages(partial)
                    emit(GenerationChunk.Messages(partial))
                    throw CancellationException("stop")
                }
            ).collect { }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        val commands = mutableListOf<ConversationCommand>()
        coVerify { harness.coordinator.executeOrThrow(any(), capture(commands)) }
        val finalize = commands.filterIsInstance<FinalizeTurn>().single()
        assertEquals(TurnExecutionStatus.CANCELLED, finalize.terminalStatus)
        assertEquals(partial, finalize.messages)
    }

    @Test
    fun `cancelling collector job durably submits cancelled outcome`() = runTest {
        val harness = harness()
        val started = CompletableDeferred<Unit>()
        val collector = launch {
            harness.engine.bind(
                flow {
                    started.complete(Unit)
                    awaitCancellation()
                }
            ).collect { }
        }

        started.await()
        collector.cancelAndJoin()

        val commands = mutableListOf<ConversationCommand>()
        coVerify(exactly = 1) { harness.coordinator.executeOrThrow(any(), capture(commands)) }
        assertEquals(TurnExecutionStatus.CANCELLED, (commands.single() as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `master and target use identical command shapes`() = runTest {
        suspend fun drive(): List<ConversationCommand> {
            val harness = harness()
            val recorded = mutableListOf<ConversationCommand>()
            coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit
            harness.engine.onCheckpoint(
                GenerationCheckpoint(
                    kind = CheckpointKind.STEP_COMPLETED,
                    messages = listOf(msg("checkpoint")),
                    toolExecution = null,
                )
            )
            harness.engine.bind(
                flowOf(GenerationChunk.Finished(FinishedReason.Completed))
            ).collect { }
            return recorded
        }

        val master = drive()
        val target = drive()
        assertEquals(master.map { it::class }, target.map { it::class })
        assertEquals(listOf(CommitCheckpoint::class, FinalizeTurn::class), master.map { it::class })
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
        val generationLoop = GenerationLoop(
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
                    toolCallId = "call-revocable",
                    toolName = tool.name,
                    input = "{}",
                )
            ),
        )

        val frozenRequest = generationRequestFixture(
            conversationId = kotlin.uuid.Uuid.random(),
            settings = settings,
            model = model,
            mediaCapabilities = RequestMediaCapabilities.NONE,
            messages = listOf(waitingMessage),
            assistant = assistant,
            promptInputs = testPromptInputs(),
            tools = listOf(tool),
            maxSteps = 1,
            assistantMessageId = waitingMessage.id,
            onCheckpoint = harness.engine::onCheckpoint,
            onMessagesObserved = harness.engine::observeMessages,
        )
        val waitingEvents = harness.engine.bind(
            generationLoop.run(frozenRequest)
        ).toListSafe()
        assertTrue((waitingEvents.last() as TurnEvent.Finished).outcome is TurnOutcome.AwaitingApproval)
        val pendingMessage = recorded.filterIsInstance<CommitCheckpoint>()
            .asSequence()
            .map { it.messages.last() }
            .first { message -> message.getTools().singleOrNull()?.isPending == true }
        assertTrue(pendingMessage.getTools().single().approvalState is ToolApprovalState.Pending)

        val approvedMessage = pendingMessage.copy(
            parts = pendingMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(approvalState = ToolApprovalState.Approved)
                } else {
                    part
                }
            },
        )
        harness.engine.bind(
            generationLoop.run(frozenRequest.copy(messages = listOf(approvedMessage)))
        ).toListSafe()

        assertTrue(executed)
        val committedResult = recorded.filterIsInstance<CommitCheckpoint>()
            .asSequence()
            .flatMap { checkpoint -> checkpoint.messages.last().getTools().asSequence() }
            .first { result -> result.output.filterIsInstance<UIMessagePart.Text>().any { it.text == "executed" } }
        assertEquals("executed", (committedResult.output.single() as UIMessagePart.Text).text)

        val checkpoints = recorded.filterIsInstance<CommitCheckpoint>()
        assertEquals(
            listOf("STARTED", "COMPLETED"),
            checkpoints.mapNotNull { it.toolExecution }.map { it.status.name },
        )
        assertTrue(recorded.all { command ->
            when (command) {
                is CommitCheckpoint -> command.handle == harness.handle
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
        val waitingMessages = listOf(msg("waiting").copy(id = harness.handle.assistantMessageId))

        harness.engine.bind(
            flow {
                harness.engine.observeMessages(waitingMessages)
                emit(GenerationChunk.Messages(waitingMessages))
                emit(
                    GenerationChunk.Finished(
                        FinishedReason.AwaitingApproval(
                            pending = listOf(
                                PendingInteraction(
                                    locator = ToolCallLocator(harness.handle.assistantMessageId, 0),
                                    kind = ToolInteractionKind.USER_INPUT,
                                ),
                            ),
                        ),
                    )
                )
            }
        ).collect { }
        harness.engine.onCheckpoint(
            GenerationCheckpoint(
                kind = CheckpointKind.STEP_COMPLETED,
                messages = waitingMessages,
                toolExecution = null,
            )
        )
        harness.engine.bind(
            flowOf(GenerationChunk.Finished(FinishedReason.Completed))
        ).collect { }

        assertEquals(
            listOf(CommitCheckpoint::class, CommitCheckpoint::class, FinalizeTurn::class),
            recorded.map { it::class },
        )
        assertEquals(
            TurnExecutionStatus.AWAITING_APPROVAL,
            (recorded[0] as CommitCheckpoint).turnStatus,
        )
        assertEquals(TurnExecutionStatus.RUNNING, (recorded[1] as CommitCheckpoint).turnStatus)
        assertEquals(harness.handle, (recorded[0] as CommitCheckpoint).handle)
        assertEquals(harness.handle, (recorded[1] as CommitCheckpoint).handle)
        assertEquals(harness.handle, (recorded[2] as FinalizeTurn).handle)
    }

    @Test
    fun `real cancellation after a tool request accounts for the second request without usage`() = runTest {
        val finalized = cancelProviderRequest(afterTool = true, reportPendingUsage = false)
        val usage = finalized.messages!!.last().usage!!

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
        val usage = finalized.messages!!.last().usage!!

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
        val usage = finalized.messages!!.last().usage!!

        assertEquals(1, usage.observedProviderRequestCount)
        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(100L, usage.latestRequestContextTokens)
        assertEquals(0L, usage.latestRequestCacheReadInputTokens)
        assertEquals(UsageCompleteness.COMPLETE, usage.coreCompleteness)
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
                    UIMessagePart.Tool("call", "test_tool", "{}")
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
        val generationLoop = GenerationLoop(
            context = mockk<Context>(relaxed = true),
            providerManager = providerManager,
            json = Json,
            attachmentResolver = mockk<AttachmentResolver>(relaxed = true),
            toolOutputStore = io.mockk.mockk(relaxed = true),
        )
        val assistant = Assistant(enableMemory = false, streamOutput = true)
        val pendingSeen = CompletableDeferred<Unit>()
        val collector = launch {
            harness.engine.bind(generationLoop.run(generationRequestFixture(
                conversationId = kotlin.uuid.Uuid.random(),
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
                tools = listOf(Tool(
                    name = "test_tool",
                    description = "test",
                    execute = { listOf(UIMessagePart.Text("done")) },
                )),
                onCheckpoint = harness.engine::onCheckpoint,
                onMessagesObserved = harness.engine::observeMessages,
            ))).collect { event ->
                if (event is TurnEvent.Streaming && event.lastMessage?.toText()?.contains("pending response") == true) {
                    pendingSeen.complete(Unit)
                }
            }
        }
        pendingSeen.await()
        collector.cancelAndJoin()
        assertTrue(collector.isCancelled)
        val finalized = recorded.filterIsInstance<FinalizeTurn>().single()
        assertEquals(TurnExecutionStatus.CANCELLED, finalized.terminalStatus)
        assertTrue(finalized.messages!!.last().toText().contains("pending response"))
        return finalized
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<TurnEvent>.toListSafe(): List<TurnEvent> {
    val events = mutableListOf<TurnEvent>()
    collect(events::add)
    return events
}
