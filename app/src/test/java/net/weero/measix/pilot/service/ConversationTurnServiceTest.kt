package net.weero.measix.pilot.service

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.afterResolve
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.ResolveToolInteraction
import net.weero.measix.pilot.service.runtime.ToolInteractionDecision
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTurnServiceTest {
    private fun approvalSnapshot(
        interactionState: ToolInteractionState,
        metadata: JsonObject? = null,
    ): Pair<Uuid, ConversationRuntimeSnapshot> {
        val turnId = Uuid.random()
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
                    toolName = "generate_image",
                    input = "{}",
                    interactionState = interactionState,
                    metadata = metadata,
                ),
            ),
        )
        val durable = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID)
            .copy(messageNodes = listOf(message.toMessageNode()))
            .toSnapshot()
        val snapshot = ConversationRuntimeSnapshot(
            durable = durable,
            stream = TurnStreamProjection(
                epoch = 1L,
                turnId = turnId,
                assistantMessageId = message.id,
                assistantMessage = message,
            ),
        )
        return turnId to snapshot
    }

    @Test
    fun `fresh approval overlay never creates a false attachment backfill`() {
        val (_, snapshot) = approvalSnapshot(ToolInteractionState.AwaitingApproval)

        // UI 合并只是 projection 层的每次新覆盖；durable backfill 规划只读 aggregate nodes。
        val presentation = snapshot.toPresentationSnapshot()
        assertTrue(presentation.nodes.last() !== snapshot.durable.nodes.last())
        assertTrue(planDurableAttachmentRefBackfills(snapshot.durable).isEmpty())
    }

    @Test
    fun `approved and denied decisions continue without structural preflight`() {
        listOf(
            ToolInteractionState.Approved,
            ToolInteractionState.Denied("not allowed"),
        ).forEach { decision ->
            val (turnId, snapshot) = approvalSnapshot(decision)

            val policy = turnLaunchPolicy(
                entry = TurnEntry.CONTINUE_USER_INTERACTION,
                activeTurn = snapshot.stream,
                turnId = turnId,
                messageRange = null,
            )

            assertFalse(policy.runStructuralPreflight)
            assertTrue(policy.reuseTtsQueue)
            assertTrue(planDurableAttachmentRefBackfills(snapshot.durable).isEmpty())
        }
    }

    @Test
    fun `approve and deny orchestration commit one typed decision and retain the active owner`() = runTest {
        listOf(
            ToolInteractionDecision.Approve,
            ToolInteractionDecision.Deny("not allowed"),
        ).forEach { decision ->
            val (turnId, initial) = approvalSnapshot(ToolInteractionState.AwaitingApproval)
            val owner = requireNotNull(initial.stream)
            val locator = requireNotNull(owner.assistantMessage).getTools().single().let { ToolCallLocator(owner.assistantMessageId, it.stepId, it.localCallId) }
            var current = initial
            val submitted = mutableListOf<ResolveToolInteraction>()
            var continuation: Pair<Uuid, TurnEntry>? = null

            applyToolInteractionDecision(
                locator = locator,
                decision = decision,
                awaitPreviousGeneration = {},
                currentSnapshot = { current },
                submit = { command ->
                    submitted += command
                    current = current.copy(
                        durable = ConversationTransition.apply(current.durable, command),
                        stream = current.stream?.afterResolve(command),
                    )
                },
                onMoreApprovalsPending = { error("single approval must continue its turn") },
                continueTurn = { active, entry -> continuation = active.turnId to entry },
            )

            assertEquals(
                listOf(
                    ResolveToolInteraction(
                        messageId = locator.assistantMessageId,
                        stepId = locator.stepId,
                        localCallId = locator.localCallId,
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
            assertEquals(turnId to TurnEntry.CONTINUE_USER_INTERACTION, continuation)
            assertFalse(
                turnLaunchPolicy(
                    entry = requireNotNull(continuation).second,
                    activeTurn = current.stream,
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
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "c1", toolName = "generate_image", input = "{}", interactionState = ToolInteractionState.AwaitingApproval),
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "c2", toolName = "generate_image", input = "{}", interactionState = ToolInteractionState.AwaitingApproval),
            ),
        )
        var current = ConversationRuntimeSnapshot(
            durable = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID)
                .copy(messageNodes = listOf(message.toMessageNode()))
                .toSnapshot(),
            stream = TurnStreamProjection(
                epoch = 1L,
                turnId = turnId,
                assistantMessageId = message.id,
                assistantMessage = message,
            ),
        )
        var morePending = false
        var continued = false

        applyToolInteractionDecision(
            locator = message.getTools()[0].let { ToolCallLocator(message.id, it.stepId, it.localCallId) },
            decision = ToolInteractionDecision.Approve,
            awaitPreviousGeneration = {},
            currentSnapshot = { current },
            submit = { command ->
                current = current.copy(
                    durable = ConversationTransition.apply(current.durable, command),
                    stream = current.stream?.afterResolve(command),
                )
            },
            onMoreApprovalsPending = { morePending = true },
            continueTurn = { _, _ -> continued = true },
        )

        assertTrue(morePending)
        assertFalse(continued)
    }

    @Test
    fun `invalid open-tool nodes are pruned while terminal and resumable nodes remain`() {
        val openTool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
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
            parts = listOf(openTool.copy(interactionState = ToolInteractionState.Approved)),
        ).toMessageNode()
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool.copy(interactionState = ToolInteractionState.AwaitingApproval)),
        ).toMessageNode()
        val illegal = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(openTool)).toMessageNode()

        val retained = retainValidMessageNodes(listOf(cancelled, resumable, pending, illegal))

        assertEquals(listOf(cancelled.id, resumable.id, pending.id), retained.map { it.id })
    }
}
