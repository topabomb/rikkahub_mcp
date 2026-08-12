package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.coroutines.test.runTest
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计文档 §13.1 — SubAssistantRunStateReducerTest
 *
 * 覆盖：phase/preview 连续更新不丢字段、状态单向转换、
 * terminal 不被迟到进度覆盖、active_tool_name 清空逻辑。
 */
class SubAssistantRunStateReducerTest {
    private fun makeInitial(): SubAssistantCallMetadata = SubAssistantCallMetadata(
        runId = "run-1",
        previousRunId = "run-0",
        targetAssistantId = Uuid.random().toString(),
        targetNameSnapshot = "Test Assistant",
        state = SubAssistantCallState.STARTING,
    )

    @Test
    fun `snapshot returns initial state`() {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        val snap = reducer.snapshot()
        assertEquals("run-1", snap.runId)
        assertEquals("run-0", snap.previousRunId)
        assertEquals(SubAssistantCallState.STARTING, snap.state)
    }

    @Test
    fun `updatePhase preserves previous_run_id and child link`() = runTest {
        val initial = makeInitial().copy(
            childConversationId = "child-1",
            childTaskNodeId = "task-1",
        )
        val reducer = SubAssistantRunStateReducer(initial)

        reducer.updatePhase(SubAssistantCallPhase.ANSWER_STREAMING)

        val snap = reducer.snapshot()
        assertEquals("run-0", snap.previousRunId) // 不丢字段
        assertEquals("child-1", snap.childConversationId)
        assertEquals(SubAssistantCallPhase.ANSWER_STREAMING, snap.phase)
    }

    @Test
    fun `updatePhase tool_executing retains active_tool_name`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()

        reducer.updatePhase(SubAssistantCallPhase.TOOL_EXECUTING, "workspace_read_file")

        val snap = reducer.snapshot()
        assertEquals("workspace_read_file", snap.activeToolName)
    }

    @Test
    fun `updatePhase non-tool phase clears active_tool_name`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()
        reducer.updatePhase(SubAssistantCallPhase.TOOL_EXECUTING, "workspace_read_file")

        reducer.updatePhase(SubAssistantCallPhase.ANSWER_STREAMING)

        val snap = reducer.snapshot()
        assertNull(snap.activeToolName)
    }

    @Test
    fun `updatePreview - content change updates`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()

        reducer.updatePreview("new preview content")

        assertEquals("new preview content", reducer.snapshot().preview)
    }

    @Test
    fun `updatePreview - same content does not update`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()
        reducer.updatePreview("preview A")
        val snap1 = reducer.snapshot()

        reducer.updatePreview("preview A") // 相同内容

        val snap2 = reducer.snapshot()
        // revision 不增加（内容未变化）
        assertEquals(snap1.preview, snap2.preview)
    }

    @Test
    fun `terminal state ignored after terminal`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()
        reducer.updateTerminalState(SubAssistantCallState.COMPLETED, reason = null, preview = "final")

        // 迟到的 phase
        reducer.updatePhase(SubAssistantCallPhase.ANSWER_STREAMING)
        // 迟到的 preview
        reducer.updatePreview("late preview")

        val snap = reducer.snapshot()
        assertEquals(SubAssistantCallState.COMPLETED, snap.state)
        assertEquals("final", snap.preview) // 不被迟到进度覆盖
        assertNull(snap.phase) // phase 被清空
    }

    @Test
    fun `terminal idempotent - first terminal wins`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()

        reducer.updateTerminalState(SubAssistantCallState.COMPLETED, reason = null, preview = "first")
        reducer.updateTerminalState(SubAssistantCallState.FAILED, reason = "runtime_error")

        val snap = reducer.snapshot()
        assertEquals(SubAssistantCallState.COMPLETED, snap.state) // 先到先得
        assertEquals("first", snap.preview)
    }

    @Test
    fun `running cannot go back to starting`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()

        // 尝试回到 starting（不应改变）
        val snap = reducer.snapshot()
        assertEquals(SubAssistantCallState.RUNNING, snap.state)
    }

    @Test
    fun `setChildLink preserves existing fields`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()
        reducer.updatePhase(SubAssistantCallPhase.ANSWER_STREAMING)
        reducer.updatePreview("some preview")

        reducer.setChildLink("child-conv-1", "task-node-1")

        val snap = reducer.snapshot()
        assertEquals("child-conv-1", snap.childConversationId)
        assertEquals("task-node-1", snap.childTaskNodeId)
        assertEquals("run-0", snap.previousRunId) // 不丢字段
        assertEquals("some preview", snap.preview)
        assertEquals(SubAssistantCallPhase.ANSWER_STREAMING, snap.phase)
    }

    @Test
    fun `setChildLink ignored after terminal`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()
        reducer.updateTerminalState(SubAssistantCallState.FAILED, reason = "error")

        reducer.setChildLink("child-conv-1", "task-node-1")

        val snap = reducer.snapshot()
        assertNull(snap.childConversationId) // 终态后不更新
    }

    @Test
    fun `terminal clears phase and active_tool_name`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())
        reducer.updateRunningState()
        reducer.updatePhase(SubAssistantCallPhase.TOOL_EXECUTING, "some_tool")

        reducer.updateTerminalState(SubAssistantCallState.STOPPED, reason = "user_cancelled")

        val snap = reducer.snapshot()
        assertNull(snap.phase)
        assertNull(snap.activeToolName)
        assertEquals("user_cancelled", snap.reason)
    }

    @Test
    fun `ask-user interaction preserves lineage and is cleared after answer`() = runTest {
        val reducer = SubAssistantRunStateReducer(
            makeInitial().copy(
                childConversationId = "child-1",
                childTaskNodeId = "task-1",
                state = SubAssistantCallState.RUNNING,
            )
        )
        val interaction = SubAssistantUserInteraction(
            interactionId = "interaction-1",
            messageId = Uuid.random().toString(),
            toolOrdinal = 1,
            toolName = "ask_user",
            input = "{}",
        )

        reducer.awaitUserInteraction(interaction, "waiting preview")

        val waiting = reducer.snapshot()
        assertEquals("run-0", waiting.previousRunId)
        assertEquals("child-1", waiting.childConversationId)
        assertEquals(SubAssistantCallPhase.AWAITING_USER, waiting.phase)
        assertEquals(interaction, waiting.userInteraction)

        reducer.clearUserInteraction()

        val resumed = reducer.snapshot()
        assertEquals(SubAssistantCallPhase.BETWEEN_STEPS, resumed.phase)
        assertNull(resumed.userInteraction)
        assertEquals("waiting preview", resumed.preview)
    }

    @Test
    fun `terminal state removes an unanswered interaction`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial().copy(state = SubAssistantCallState.RUNNING))
        reducer.awaitUserInteraction(
            interaction = SubAssistantUserInteraction(
                interactionId = "interaction-1",
                messageId = Uuid.random().toString(),
                toolOrdinal = 0,
                toolName = "ask_user",
                input = "{}",
            ),
            preview = null,
        )

        reducer.updateTerminalState(SubAssistantCallState.STOPPED, reason = "target_access_revoked")

        assertNull(reducer.snapshot().userInteraction)
        assertNull(reducer.snapshot().phase)
    }

    @Test
    fun `state transition - starting to unavailable directly`() = runTest {
        val reducer = SubAssistantRunStateReducer(makeInitial())

        reducer.updateTerminalState(SubAssistantCallState.UNAVAILABLE, reason = "target_model_unavailable")

        val snap = reducer.snapshot()
        assertEquals(SubAssistantCallState.UNAVAILABLE, snap.state)
        assertEquals("target_model_unavailable", snap.reason)
    }
}
