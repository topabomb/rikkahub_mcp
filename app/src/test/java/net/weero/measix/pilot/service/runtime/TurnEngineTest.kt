package net.weero.measix.pilot.service.runtime

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
import me.rerere.ai.core.MessageRole
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
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.TurnFinalization
import org.junit.Assert.assertEquals
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
    fun `streaming chunks update the owned projection before incomplete close`() = runTest {
        val harness = harness()
        val messages = listOf(msg("hi"))

        val events = harness.engine.bind(flowOf(GenerationChunk.Messages(messages))).toListSafe()

        assertTrue(events.first() is TurnEvent.Streaming)
        assertTrue((events.last() as TurnEvent.Finished).outcome is TurnOutcome.Incomplete)
        coVerify(exactly = 1) { harness.coordinator.executeOrThrow(any(), any()) }
        io.mockk.verify(exactly = 1) { harness.runtime.applyStreamingDelta(harness.handle, messages) }
    }

    @Test
    fun `finished chunk submits the sealed completed outcome`() = runTest {
        val harness = harness()

        val events = harness.engine.bind(
            flowOf(GenerationChunk.Finished(FinishedReason.COMPLETED))
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
        val partial = listOf(msg("partial after checkpoint"))

        val events = harness.engine.bind(
            flow {
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
    fun `finalization failure propagates without rewriting the outcome`() = runTest {
        val harness = harness()
        val failure = IllegalStateException("commit failed")
        val commands = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(commands)) } throws failure

        val thrown = runCatching {
            harness.engine.bind(
                flowOf(GenerationChunk.Finished(FinishedReason.COMPLETED)),
            ).collect { }
        }.exceptionOrNull()

        assertEquals(failure, thrown)
        assertEquals(1, commands.size)
        assertEquals(TurnExecutionStatus.COMPLETED, (commands.single() as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `cancellation submits cancelled once and rethrows`() = runTest {
        val harness = harness()
        val partial = listOf(msg("partial after checkpoint"))

        val thrown = runCatching {
            harness.engine.bind(
                flow {
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
                flowOf(GenerationChunk.Finished(FinishedReason.COMPLETED))
            ).collect { }
            return recorded
        }

        val master = drive()
        val target = drive()
        assertEquals(master.map { it::class }, target.map { it::class })
        assertEquals(listOf(CommitCheckpoint::class, FinalizeTurn::class), master.map { it::class })
    }

    @Test
    fun `approval pause and continuation keep one handle until terminal finalization`() = runTest {
        val harness = harness()
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { harness.coordinator.executeOrThrow(any(), capture(recorded)) } returns Unit
        val waitingMessages = listOf(msg("waiting"))

        harness.engine.bind(
            flowOf(
                GenerationChunk.Messages(waitingMessages),
                GenerationChunk.Finished(FinishedReason.AWAITING_APPROVAL),
            )
        ).collect { }
        harness.engine.onCheckpoint(
            GenerationCheckpoint(
                kind = CheckpointKind.STEP_COMPLETED,
                messages = waitingMessages,
                toolExecution = null,
            )
        )
        harness.engine.bind(
            flowOf(GenerationChunk.Finished(FinishedReason.COMPLETED))
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
}

private suspend fun kotlinx.coroutines.flow.Flow<TurnEvent>.toListSafe(): List<TurnEvent> {
    val events = mutableListOf<TurnEvent>()
    collect(events::add)
    return events
}
