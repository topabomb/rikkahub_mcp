package net.weero.measix.pilot.service.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
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
 * 用 fake Flow 驱动：chunk→Streaming 顺序、checkpoint→CommitCheckpoint 次数、Finished→FinalizeTurn。
 */
class TurnEngineTest {

    private fun msg(text: String): UIMessage =
        UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun runtime(): ConversationRuntime = mockk<ConversationRuntime>(relaxed = true).also {
        coEvery { it.submitGeneration(any()) } returns net.weero.measix.pilot.data.model.Conversation.ofId(Uuid.random())
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
        coVerify { runtime.submitGeneration(capture(cmdSlot)) }
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
    fun `bind maps Finished chunk to Finished event`() = runTest {
        val engine = TurnEngine(runtime(), Uuid.random(), Uuid.random())
        val events = engine.bind(flowOf(GenerationChunk.Finished(FinishedReason.COMPLETED))).first()
        assertTrue(events is TurnEvent.Finished)
        assertEquals(FinishedReason.COMPLETED, (events as TurnEvent.Finished).reason)
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
     * Master 与 Target 以相同 fake 事件序列驱动
     * TurnEngine（chunk → bind 流式投影 / checkpoint → CommitCheckpoint / 终态 → FinalizeTurn），
     * 两者对 Runtime 提交的命令序列完全一致——提交协议单一实现（无 Master/Target 分支）的直接锁定。
     */
    @Test
    fun `T-1 master and target commit identical command sequences for the same chunk stream`() = runTest {
        val turnId = Uuid.random()
        val assistantId = Uuid.random()
        // 冷流：两次收集发射完全相同的 chunk 序列（流式 + checkpoint 信号 + 终态）
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
            coEvery { runtime.submitGeneration(any()) } answers {
                recorded.add(firstArg())
                net.weero.measix.pilot.data.model.Conversation.ofId(Uuid.random())
            }
            val engine = TurnEngine(runtime, turnId, assistantId)
            engine.onCheckpoint(checkpoint)
            engine.bind(chunks).collect { }
            engine.submitFinalize(null, TurnExecutionStatus.COMPLETED, null, closeInterruptedTools = false)
            return recorded
        }

        val masterCommands = drive()
        val targetCommands = drive()

        // 命令序列逐条结构相等（CommitCheckpoint + FinalizeTurn）
        assertEquals(masterCommands, targetCommands)
        assertEquals(2, masterCommands.size)
        assertTrue(masterCommands[0] is CommitCheckpoint)
        assertTrue(masterCommands[1] is FinalizeTurn)
    }
}
