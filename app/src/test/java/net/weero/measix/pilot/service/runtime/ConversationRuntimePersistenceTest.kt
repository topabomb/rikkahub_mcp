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
        val assistantMessageId = Uuid.random()
        applyCommand(runtime, StartTurn(Uuid.random(), assistantMessageId, false), persistedCommands)
        val handle = runtime.snapshot.value.activeTurn.let { active ->
            requireNotNull(active)
            TurnHandle(runtime.id, active.epoch, active.turnId, active.assistantMessageId)
        }
        val message = UIMessage.assistant("checkpoint").copy(id = assistantMessageId)

        applyCommand(
            runtime,
            CommitCheckpoint(
                handle = handle,
                kind = net.weero.measix.pilot.data.ai.CheckpointKind.STEP_COMPLETED,
                messages = listOf(message),
                turnStatus = TurnExecutionStatus.RUNNING,
                turnReason = null,
                toolExecution = null,
            ),
            persistedCommands,
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
        val command = AppendUserMessage(UIMessage.user("must not publish"))

        try {
            val change = ConversationTransition.plan(before, command, before.header.updateAt)
            error("disk unavailable")
            runtime.publishCommitted(before, command, (change as ConversationChange.Durable).snapshot)
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
        val mutations = mutableListOf<ConversationMutation>()
        val assistantMessageId = Uuid.random()
        val start = StartTurn(Uuid.random(), assistantMessageId, false)
        applyCommand(runtime, start, mutations = mutations)
        val handle = runtime.snapshot.value.activeTurn.let { active ->
            requireNotNull(active)
            TurnHandle(runtime.id, active.epoch, active.turnId, active.assistantMessageId)
        }
        val completedMessages = runtime.snapshot.value.currentMessages().dropLast(1) +
            UIMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("new answer")),
            )
        applyCommand(
            runtime,
            FinalizeTurn(
                handle = handle,
                messages = completedMessages,
                terminalStatus = TurnExecutionStatus.COMPLETED,
                terminalReason = null,
                closeInterruptedTools = false,
            ),
            mutations = mutations,
        )

        val finalMutation = mutations.last()
        assertEquals(1, finalMutation.upsertedNodes.size)
        assertEquals(assistantMessageId, finalMutation.upsertedNodes.single().messages.single().id)
        assertEquals(listOf(3), finalMutation.upsertedNodeIndices)
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
        val mutate = (
            ConversationTransition.plan(old, command, old.header.updateAt) as ConversationChange.Durable
            ).write as ConversationWrite.Mutate

        assertEquals(listOf(removed.id), mutate.mutation.deletedNodeIds)
        assertEquals(listOf(moved.id), mutate.mutation.upsertedNodes.map(MessageNode::id))
        assertEquals(listOf(1), mutate.mutation.upsertedNodeIndices)
    }

    private fun applyCommand(
        runtime: ConversationRuntime,
        command: ConversationCommand,
        persistedCommands: MutableList<ConversationCommand>? = null,
        mutations: MutableList<ConversationMutation>? = null,
    ): ConversationSnapshot {
        val old = runtime.snapshot.value
        val planned = if (command is StartTurn) {
            command.copy(epoch = runtime.nextTurnEpoch())
        } else {
            command
        }
        if (planned is StartTurn && runtime.currentGenerationTurnId() != planned.turnId) {
            runtime.installActiveRequest(planned.turnId, Job())
        }
        validateConversationCommandOwner(runtime.id, old, planned, runtime.currentGenerationTurnId())
        val change = ConversationTransition.plan(old, planned, old.header.updateAt) as ConversationChange.Durable
        persistedCommands?.add(planned)
        (change.write as? ConversationWrite.Mutate)?.let { mutations?.add(it.mutation) }
        runtime.publishCommitted(old, planned, change.snapshot)
        return change.snapshot
    }

    private fun runtime(id: Uuid, scope: CoroutineScope) = ConversationRuntime(
        id = id,
        initial = Conversation.ofId(id).toSnapshot(),
        scope = scope,
        onIdle = {},
    )
}
