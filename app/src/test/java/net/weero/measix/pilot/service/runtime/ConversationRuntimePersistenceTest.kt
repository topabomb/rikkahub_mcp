package net.weero.measix.pilot.service.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationRuntimePersistenceTest {
    @Test
    fun `durable checkpoint is published only after persistence succeeds`() = runTest {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(Job())
        val runtime = runtime(conversationId, scope)
        val persistedCommands = mutableListOf<ConversationCommand>()
        val persist: suspend (
            ConversationSnapshot,
            ConversationSnapshot,
            ConversationCommand,
        ) -> ConversationSnapshot = { _, new, command ->
            persistedCommands += command
            new
        }
        val assistantMessageId = Uuid.random()
        val handle = runtime.startTurn(Uuid.random(), assistantMessageId, false, persist)
        val message = UIMessage.assistant("checkpoint").copy(id = assistantMessageId)

        runtime.submit(
            CommitCheckpoint(
                handle = handle,
                messages = listOf(message),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
            ),
            persist,
        )

        assertEquals(listOf(StartTurn::class, CommitCheckpoint::class), persistedCommands.map { it::class })
        assertEquals("checkpoint", runtime.snapshot.value.currentMessages().last().toText())
        scope.cancel()
    }

    @Test
    fun `persistence failure is visible and leaves the published snapshot unchanged`() = runTest {
        val conversationId = Uuid.random()
        val scope = CoroutineScope(Job())
        val runtime = runtime(conversationId, scope)
        val before = runtime.snapshot.value

        try {
            runtime.submit(AppendUserMessage(UIMessage.user("must not publish"))) { _, _, _ ->
                error("disk unavailable")
            }
            fail("persistence failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("disk unavailable", expected.message)
        }

        assertEquals(before, runtime.snapshot.value)
        scope.cancel()
    }

    @Test
    fun `finalization mutation contains only the active assistant node`() = runTest {
        val conversationId = Uuid.random()
        val historicalUser = MessageNode.of(UIMessage.user("question"))
        val historicalAssistant = MessageNode.of(UIMessage.assistant("old answer"))
        val currentUser = MessageNode.of(UIMessage.user("follow-up"))
        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(
            id = conversationId,
            initial = Conversation.ofId(conversationId).copy(
                messageNodes = listOf(historicalUser, historicalAssistant, currentUser),
            ).toSnapshot(),
            scope = scope,
            onIdle = {},
        )
        var previous = runtime.snapshot.value
        val mutations = mutableListOf<ConversationMutation>()
        val persist: suspend (
            ConversationSnapshot,
            ConversationSnapshot,
            ConversationCommand,
        ) -> ConversationSnapshot = { old, new, _ ->
            mutations += ConversationMutationBuilder.build(old, new)
            previous = new
            new
        }
        val assistantMessageId = Uuid.random()
        val handle = runtime.startTurn(Uuid.random(), assistantMessageId, false, persist)
        val completedMessages = runtime.snapshot.value.currentMessages().dropLast(1) +
            UIMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("new answer")),
            )
        runtime.submit(
            FinalizeTurn(
                handle = handle,
                messages = completedMessages,
                terminalStatus = TurnExecutionStatus.COMPLETED,
                terminalReason = null,
                closeInterruptedTools = false,
            ),
            persist,
        )

        val finalMutation = mutations.last()
        assertEquals(1, finalMutation.upsertedNodes.size)
        assertEquals(assistantMessageId, finalMutation.upsertedNodes.single().messages.single().id)
        assertEquals(listOf(3), finalMutation.upsertedNodeIndices)
        assertEquals(previous, runtime.snapshot.value)
        scope.cancel()
    }

    @Test
    fun `removing a middle node moves survivors without deleting their projections`() {
        val conversationId = Uuid.random()
        val first = MessageNode.of(UIMessage.user("first"))
        val removed = MessageNode.of(UIMessage.assistant("remove"))
        val moved = MessageNode.of(UIMessage.user("moved"))
        val old = Conversation.ofId(conversationId).copy(
            messageNodes = listOf(first, removed, moved),
        ).toSnapshot()
        val command = DeleteMessage(removed.currentMessage.id)
        val updated = ConversationReducer.reduce(old, command)

        val mutation = ConversationMutationBuilder.build(old, updated, command)

        assertEquals(listOf(removed.id), mutation.deletedNodeIds)
        assertEquals(listOf(moved.id), mutation.upsertedNodes.map(MessageNode::id))
        assertEquals(listOf(1), mutation.upsertedNodeIndices)
    }

    private fun runtime(id: Uuid, scope: CoroutineScope) = ConversationRuntime(
        id = id,
        initial = Conversation.ofId(id).toSnapshot(),
        scope = scope,
        onIdle = {},
    )
}
