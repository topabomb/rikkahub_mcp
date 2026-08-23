package net.weero.measix.pilot.service.runtime

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.canResumeToolExecution
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * I9 turn 骨架唯一实现契约（V1 正式阶段·架构收敛 §12 工作流 O / M1）。
 *
 * Master 与 Target 经 [TurnEngine.start] 的提交序列形状一致：
 * BeginTurn（开槽）→ 空 CommitCheckpoint（RUNNING turn 事实）。
 * 差异只允许出现在 resume 语义参数上（Master 含审批恢复语义），
 * 命令种类与顺序由骨架唯一实现保证（T-1 的启动段扩展）。
 */
class TurnSkeletonEquivalenceTest {

    private suspend fun recordedStart(
        messages: List<UIMessage>,
        resumeFilter: (UIMessage) -> Boolean,
    ): List<ConversationCommand> {
        val runtime = mockk<ConversationRuntime>(relaxed = true)
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { runtime.submit(any()) } answers {
            recorded.add(invocation.args[0] as ConversationCommand)
            Conversation.ofId(Uuid.random()).toSnapshot()
        }
        TurnEngine.start(
            runtime = runtime,
            turnId = Uuid.random(),
            messages = messages,
            resumeFilter = resumeFilter,
        )
        return recorded
    }

    @Test
    fun `I9 master and target skeletons submit identical command shapes`() = runTest {
        val masterCommands = recordedStart(
            messages = listOf(UIMessage.user("task")),
            resumeFilter = { message ->
                message.role == MessageRole.ASSISTANT &&
                    message.getTools().any { !it.isExecuted && it.approvalState.canResumeToolExecution() }
            },
        )
        val targetCommands = recordedStart(
            messages = listOf(UIMessage.user("task")),
            resumeFilter = { message ->
                message.role == MessageRole.ASSISTANT && message.getTools().any { !it.isExecuted }
            },
        ) // Target 语义（默认）

        assertEquals(2, masterCommands.size)
        assertEquals(2, targetCommands.size)
        // 骨架命令种类与顺序唯一：BeginTurn → 空 CommitCheckpoint(RUNNING)
        assertTrue(masterCommands[0] is BeginTurn)
        assertTrue(targetCommands[0] is BeginTurn)
        assertTrue(masterCommands[1] is CommitCheckpoint)
        assertTrue(targetCommands[1] is CommitCheckpoint)
        assertEquals(
            TurnExecutionStatus.RUNNING,
            (masterCommands[1] as CommitCheckpoint).turnStatus,
        )
        assertEquals(
            TurnExecutionStatus.RUNNING,
            (targetCommands[1] as CommitCheckpoint).turnStatus,
        )
        // RUNNING checkpoint 携带空消息（turn 事实行的时点快照，消息由后续 checkpoint 提供）
        assertTrue((masterCommands[1] as CommitCheckpoint).messages.isEmpty())
        assertTrue((targetCommands[1] as CommitCheckpoint).messages.isEmpty())
    }

    @Test
    fun `I9 fresh turn opens a new slot and running checkpoint carries it`() = runTest {
        val runtime = mockk<ConversationRuntime>(relaxed = true)
        val recorded = mutableListOf<ConversationCommand>()
        coEvery { runtime.submit(any()) } answers {
            recorded.add(invocation.args[0] as ConversationCommand)
            Conversation.ofId(Uuid.random()).toSnapshot()
        }
        val turnId = Uuid.random()
        val started = TurnEngine.start(
            runtime = runtime,
            turnId = turnId,
            messages = listOf(UIMessage.user("task")),
        )
        val begin = recorded.filterIsInstance<BeginTurn>().single()
        val checkpoint = recorded.filterIsInstance<CommitCheckpoint>().single()
        // 新 turn：开新槽，BeginTurn 与 RUNNING checkpoint 绑定同一槽位 id
        assertTrue(!begin.resume)
        assertEquals(started.assistantMessageId, begin.assistantMessageId)
        assertEquals(started.assistantMessageId, checkpoint.assistantMessageId)
        assertEquals(turnId, begin.turnId)
        assertEquals(turnId, checkpoint.turnId)
        assertTrue(started.resumableMessage == null)
    }
}
