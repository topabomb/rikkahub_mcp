package net.weero.measix.pilot.service

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.ResolveToolInteraction
import net.weero.measix.pilot.service.runtime.ToolUserDecision
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MasterTurnCoordinatorTest {
    private fun approvalSnapshot(
        approvalState: ToolApprovalState,
        metadata: JsonObject? = null,
    ): Pair<Uuid, ConversationAggregateSnapshot> {
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
                    metadata = metadata,
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

        // UI 合并只是 projection 层的每次新覆盖；durable backfill 规划只读 aggregate nodes。
        val presentation = snapshot.toPresentationSnapshot()
        assertTrue(presentation.nodes.last() !== snapshot.nodes.last())
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
                entry = MasterTurnEntry.CONTINUE_USER_INTERACTION,
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
            ToolUserDecision.Approve,
            ToolUserDecision.Deny("not allowed"),
        ).forEach { decision ->
            val (turnId, initial) = approvalSnapshot(ToolApprovalState.Pending)
            val owner = requireNotNull(initial.activeTurn)
            val locator = ToolCallLocator(owner.assistantMessageId, 0)
            var current = initial
            val submitted = mutableListOf<ResolveToolInteraction>()
            var continuation: Pair<Uuid, MasterTurnEntry>? = null

            applyToolUserDecision(
                locator = locator,
                decision = decision,
                awaitPreviousGeneration = {},
                currentSnapshot = { current },
                submit = { command ->
                    submitted += command
                    current = ConversationTransition.apply(current, command)
                },
                onMoreApprovalsPending = { error("single approval must continue its turn") },
                continueTurn = { active, entry -> continuation = active.turnId to entry },
            )

            assertEquals(
                listOf(
                    ResolveToolInteraction(
                        messageId = locator.messageId,
                        toolOrdinal = locator.toolOrdinal,
                        decision = decision,
                        handle = TurnHandle(
                            conversationId = current.conversationId,
                            epoch = owner.epoch,
                            turnId = owner.turnId,
                            assistantMessageId = owner.assistantMessageId,
                        ),
                    )
                ),
                submitted,
            )
            assertEquals(turnId to MasterTurnEntry.CONTINUE_USER_INTERACTION, continuation)
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
    fun `remaining pending on the decided assistant waits instead of continuing`() = runTest {
        val turnId = Uuid.random()
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("c1", "generate_image", "{}", approvalState = ToolApprovalState.Pending),
                UIMessagePart.Tool("c2", "generate_image", "{}", approvalState = ToolApprovalState.Pending),
            ),
        )
        var current = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID)
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
        var morePending = false
        var continued = false

        applyToolUserDecision(
            locator = ToolCallLocator(message.id, 0),
            decision = ToolUserDecision.Approve,
            awaitPreviousGeneration = {},
            currentSnapshot = { current },
            submit = { command -> current = ConversationTransition.apply(current, command) },
            onMoreApprovalsPending = { morePending = true },
            continueTurn = { _, _ -> continued = true },
        )

        assertTrue(morePending)
        assertFalse(continued)
    }

    @Test
    fun `malformed runtime metadata cannot reach the decision command`() = runTest {
        val metadata = buildJsonObject {
            put("tool_runtime", Json.parseToJsonElement("""{"version":2,"interaction":"approval"}"""))
        }
        val (_, snapshot) = approvalSnapshot(ToolApprovalState.Pending, metadata)
        val owner = requireNotNull(snapshot.activeTurn)
        var submitted = false

        val failure = runCatching {
            applyToolUserDecision(
                locator = ToolCallLocator(owner.assistantMessageId, 0),
                decision = ToolUserDecision.Approve,
                awaitPreviousGeneration = {},
                currentSnapshot = { snapshot },
                submit = { submitted = true },
                onMoreApprovalsPending = {},
                continueTurn = { _, _ -> },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(submitted)
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
}
