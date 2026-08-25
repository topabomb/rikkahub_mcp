package net.weero.measix.pilot.service

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.ActiveTurnState
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationReducer
import net.weero.measix.pilot.service.runtime.UpdateToolApproval
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MasterTurnCoordinatorTest {
    private fun approvalSnapshot(approvalState: ToolApprovalState): Pair<Uuid, ConversationSnapshot> {
        val turnId = Uuid.random()
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "generate_image",
                    input = "{}",
                    approvalState = approvalState,
                ),
            ),
        )
        val snapshot = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID)
            .copy(messageNodes = listOf(message.toMessageNode()))
            .toSnapshot()
            .copy(
                activeTurn = ActiveTurnState(
                    epoch = 1L,
                    turnId = turnId,
                    assistantMessageId = message.id,
                    messages = listOf(message),
                ),
            )
        return turnId to snapshot
    }

    @Test
    fun `fresh approval overlay never creates a false attachment backfill`() {
        val (_, snapshot) = approvalSnapshot(ToolApprovalState.Pending)

        // renderNodes is intentionally a fresh per-read overlay; its identity is never a write signal.
        assertTrue(snapshot.renderNodes !== snapshot.renderNodes)
        assertTrue(planDurableAttachmentRefBackfills(snapshot).isEmpty())
    }

    @Test
    fun `approved and denied decisions continue without structural preflight`() {
        listOf(
            ToolApprovalState.Approved,
            ToolApprovalState.Denied("not allowed"),
        ).forEach { decision ->
            val (turnId, snapshot) = approvalSnapshot(decision)

            val policy = masterTurnLaunchPolicy(
                entry = MasterTurnEntry.CONTINUE_APPROVAL,
                snapshot = snapshot,
                turnId = turnId,
                messageRange = null,
            )

            assertFalse(policy.runStructuralPreflight)
            assertTrue(policy.reuseTtsQueue)
            assertTrue(planDurableAttachmentRefBackfills(snapshot).isEmpty())
        }
    }

    @Test
    fun `approve and deny orchestration commit one typed decision and retain the active owner`() = runTest {
        listOf(
            ToolApprovalState.Approved,
            ToolApprovalState.Denied("not allowed"),
        ).forEach { decision ->
            val (turnId, initial) = approvalSnapshot(ToolApprovalState.Pending)
            val owner = requireNotNull(initial.activeTurn)
            val locator = ToolCallLocator(owner.assistantMessageId, 0)
            var current = initial
            val submitted = mutableListOf<UpdateToolApproval>()
            var continuation: Pair<Uuid, MasterTurnEntry>? = null

            applyToolApprovalDecision(
                locator = locator,
                approvalState = decision,
                awaitPreviousGeneration = {},
                currentSnapshot = { current },
                submit = { command ->
                    submitted += command
                    current = ConversationReducer.reduce(current, command)
                },
                onMoreApprovalsPending = { error("single approval must continue its turn") },
                continueTurn = { active, entry -> continuation = active.turnId to entry },
            )

            assertEquals(listOf(UpdateToolApproval(locator.messageId, locator.toolOrdinal, decision)), submitted)
            assertEquals(turnId to MasterTurnEntry.CONTINUE_APPROVAL, continuation)
            assertFalse(
                masterTurnLaunchPolicy(
                    entry = requireNotNull(continuation).second,
                    snapshot = current,
                    turnId = turnId,
                    messageRange = null,
                ).runStructuralPreflight,
            )
        }
    }

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
