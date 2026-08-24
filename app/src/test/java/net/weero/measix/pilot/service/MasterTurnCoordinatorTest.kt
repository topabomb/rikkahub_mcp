package net.weero.measix.pilot.service

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MasterTurnCoordinatorTest {
    @Test
    fun `invalid open-tool nodes are pruned while terminal and resumable nodes remain`() {
        val openTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "generate_image",
            input = "{}",
        )
        val cancelled = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool),
            terminalStatus = MessageTerminalStatus.CANCELLED,
            terminalReason = "user_stop",
        ).toMessageNode()
        val resumable = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool.copy(approvalState = ToolApprovalState.Approved)),
        ).toMessageNode()
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool.copy(approvalState = ToolApprovalState.Pending)),
        ).toMessageNode()
        val illegal = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(openTool)).toMessageNode()

        val retained = retainValidMessageNodes(listOf(cancelled, resumable, pending, illegal))

        assertEquals(listOf(cancelled.id, resumable.id, pending.id), retained.map { it.id })
    }

    @Test
    fun `superseded turn is cancelled and durably finalized before the next mutation`() = runTest {
        val events = mutableListOf<String>()
        val previousTurnId = Uuid.random()
        val previousJob = launch {
            try {
                awaitCancellation()
            } finally {
                events += "previous-finished"
            }
        }
        runCurrent()

        val barrier = beginSupersedingTurn(previousJob, previousTurnId) { turnId ->
            assertEquals(previousTurnId, turnId)
            events += "cancel-requested"
        }
        barrier.awaitDurableFinalization { turnId ->
            assertEquals(previousTurnId, turnId)
            events += "durable-cancelled"
        }
        events += "append-user"

        assertTrue(previousJob.isCancelled)
        assertEquals(
            listOf("cancel-requested", "previous-finished", "durable-cancelled", "append-user"),
            events,
        )
    }
}
