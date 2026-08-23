package net.weero.measix.pilot.service.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationCheckpoint
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TurnEngine 权威测试（提交协议等价）。
 * fake Flow 驱动：chunk→Streaming、checkpoint→CommitCheckpoint、Finished→FinalizeTurn。
 */
class TurnEngineTest {

    private fun msg(text: String): UIMessage =
        UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun runtime(): ConversationRuntime = mockk<ConversationRuntime>(relaxed = true).also {
        coEvery { it.submit(any()) } returns net.weero.measix.pilot.data.model.Conversation.ofId(Uuid.random()).toSnapshot()
        coEvery { it.isTurnFinalized(any()) } returns false
    }

    @Test
    fun `checkpoint onCheckpoint commits CommitCheckpoint to runtime`() = runTest {
        val runtime = runtime()
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        val engine = TurnEngine(runtime, turnId, assistantId)
        val checkpoint = GenerationCheckpoint(
            kind = CheckpointKind.TERMINAL_STATE,
            messages = listOf(msg("done")),
            toolExecution = null,
        )
        engine.onCheckpoint(checkpoint)
        val cmdSlot = slot<ConversationCommand>()
        coVerify { runtime.submit(capture(cmdSlot)) }
        assertTrue("CommitCheckpoint committed", cmdSlot.captured is CommitCheckpoint)
        val commit = cmdSlot.captured as CommitCheckpoint
        assertEquals(turnId, commit.turnId)
        assertEquals(TurnExecutionStatus.COMPLETED, commit.turnStatus)
    }

    @Test
    fun `bind maps Messages chunk to Streaming event`() = runTest {
        val engine = TurnEngine(runtime(), Uuid.random(), Uuid.random())
        val chunk = GenerationChunk.Messages(listOf(msg("hi")))
        val events = engine.bind(flowOf(chunk)).first()
        assertTrue(events is TurnEvent.Streaming)
        val streaming = events as TurnEvent.Streaming
        assertEquals("hi", streaming.lastMessage?.parts?.filterIsInstance<UIMessagePart.Text>()?.single()?.text)
    }

    @Test
    fun `bind Finished submits FinalizeTurn and emits Finished`() = runTest {
        val runtime = runtime()
        val engine = TurnEngine(runtime, Uuid.random(), Uuid.random())
        val events = engine.bind(flowOf(GenerationChunk.Finished(FinishedReason.COMPLETED))).first()
        assertTrue(events is TurnEvent.Finished)
        assertEquals(FinishedReason.COMPLETED, (events as TurnEvent.Finished).reason)
        val cmdSlot = slot<ConversationCommand>()
        coVerify { runtime.submit(capture(cmdSlot)) }
        assertTrue(cmdSlot.captured is FinalizeTurn)
        assertEquals(TurnExecutionStatus.COMPLETED, (cmdSlot.captured as FinalizeTurn).terminalStatus)
    }

    @Test
    fun `bind exception submits FinalizeTurn FAILED without rethrowing`() = runTest {
        val runtime = runtime()
        val engine = TurnEngine(runtime, Uuid.random(), Uuid.random())
        val boom = RuntimeException("provider failed")
        val events = engine.bind(
            flow {
                emit(GenerationChunk.Messages(listOf(msg("partial"))))
                throw boom
            }
        ).toListSafe()
        val finished = events.filterIsInstance<TurnEvent.Finished>().single()
        assertEquals(boom, finished.error)
        val cmds = mutableListOf<ConversationCommand>()
        coVerify { runtime.submit(capture(cmds)) }
        assertTrue(cmds.any { it is FinalizeTurn && it.terminalStatus == TurnExecutionStatus.FAILED })
    }

    @Test
    fun `bind cancellation submits FinalizeTurn CANCELLED and rethrows`() = runTest {
        val runtime = runtime()
        val engine = TurnEngine(runtime, Uuid.random(), Uuid.random())
        val thrown = runCatching {
            engine.bind(
                flow {
                    emit(GenerationChunk.Messages(listOf(msg("partial"))))
                    throw CancellationException("user stop")
                }
            ).collect { }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        val cmds = mutableListOf<ConversationCommand>()
        coVerify { runtime.submit(capture(cmds)) }
        assertTrue(cmds.any { it is FinalizeTurn && it.terminalStatus == TurnExecutionStatus.CANCELLED })
    }

    @Test
    fun `submitFinalize commits FinalizeTurn and marks turn finalized`() = runTest {
        val runtime = runtime()
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        val engine = TurnEngine(runtime, turnId, assistantId)
        engine.submitFinalize(null, TurnExecutionStatus.CANCELLED, "user_stop", closeInterruptedTools = true)
        coVerify { runtime.markTurnFinalized(turnId) }
    }

    /**
     * Master 与 Target 以相同 fake chunk 序列驱动同一 TurnEngine.bind：
     * onCheckpoint → CommitCheckpoint，Finished → FinalizeTurn。
     * 两者对 Runtime 提交的命令序列完全一致。
     */
    @Test
    fun `T-1 master and target commit identical command sequences for the same chunk stream`() = runTest {
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        val chunks = flowOf(
            GenerationChunk.Messages(listOf(msg("hi"))),
            GenerationChunk.Checkpoint(CheckpointKind.STEP_COMPLETED),
            GenerationChunk.Finished(FinishedReason.COMPLETED),
        )
        val checkpoint = GenerationCheckpoint(
            kind = CheckpointKind.STEP_COMPLETED,
            messages = listOf(msg("hi"), msg("world")),
            toolExecution = null,
        )

        suspend fun drive(): List<ConversationCommand> {
            val runtime = runtime()
            val recorded = mutableListOf<ConversationCommand>()
            coEvery { runtime.submit(any()) } answers {
                recorded.add(invocation.args[0] as ConversationCommand)
                net.weero.measix.pilot.data.model.Conversation.ofId(Uuid.random()).toSnapshot()
            }
            val engine = TurnEngine(runtime, turnId, assistantId)
            engine.onCheckpoint(checkpoint)
            engine.bind(chunks).collect { }
            return recorded
        }

        val masterCommands = drive()
        val targetCommands = drive()

        assertEquals(masterCommands, targetCommands)
        assertEquals(2, masterCommands.size)
        assertTrue(masterCommands[0] is CommitCheckpoint)
        assertTrue(masterCommands[1] is FinalizeTurn)
        assertEquals(TurnExecutionStatus.COMPLETED, (masterCommands[1] as FinalizeTurn).terminalStatus)
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<TurnEvent>.toListSafe(): List<TurnEvent> {
    val out = mutableListOf<TurnEvent>()
    collect { out.add(it) }
    return out
}
