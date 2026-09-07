package net.weero.measix.pilot.service.turn

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ModelResponseCheckpoint
import net.weero.measix.pilot.service.runtime.StepHandle
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationChange
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.ConversationWrite
import net.weero.measix.pilot.service.runtime.DeleteMessage
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Structural write-amplification facts for checkpoint commits: one changed Assistant node
 * produces exactly one node upsert and invalidates exactly one historical node identity.
 * Timing and memory measurements belong to controlled benchmarks, not to this gate.
 */
class TurnPersistenceDeltaTest {
    @Test
    fun `assistant checkpoint upserts only the changed node and keeps history identities`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val historical = (0 until 49).map { index -> MessageNode.of(UIMessage.user("history-$index")) }
        val active = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("before")),
        )
        val old = net.weero.measix.pilot.data.model.Conversation.ofId(conversationId)
            .copy(messageNodes = historical + MessageNode.of(active))
            .toSnapshot()
        val updated = active.copy(parts = listOf(UIMessagePart.Text("after")))
        val handle = TurnHandle(conversationId, 1L, Uuid.random(), assistantId)
        val command = ModelResponseCheckpoint(
            turn = handle,
            step = StepHandle(Uuid.random()),
            assistantMessage = updated,
            turnStatus = TurnExecutionStatus.RUNNING,
        )
        val committed = ConversationTransition.apply(old, command)
        val mutation = (
            (ConversationTransition.plan(old, command, old.header.updateAt) as ConversationChange.Durable)
                .write as ConversationWrite.Mutate
            ).mutation

        assertEquals(1, mutation.upsertedNodes.size)
        // The delta carries the new-tree position of the owning Assistant, never index 0.
        assertEquals(listOf(49), mutation.upsertedNodeIndices)
        assertEquals(1, old.nodes.indices.count { old.nodes[it] !== committed.nodes[it] })
    }

    @Test
    fun `terminal commit replaces only the owning assistant and keeps history identities`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val historical = (0 until 49).map { index -> MessageNode.of(UIMessage.user("history-$index")) }
        val active = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
        )
        val old = net.weero.measix.pilot.data.model.Conversation.ofId(conversationId)
            .copy(messageNodes = historical + MessageNode.of(active))
            .toSnapshot()
        val terminal = active.copy(parts = listOf(UIMessagePart.Text("answer final")))
        val handle = TurnHandle(conversationId, 1L, Uuid.random(), assistantId)
        val command = FinalizeTurn(
            handle = handle,
            assistantMessage = terminal,
            terminalStatus = TurnExecutionStatus.COMPLETED,
            terminalReason = null,
        )
        val committed = ConversationTransition.apply(old, command)
        val mutation = (
            (ConversationTransition.plan(old, command, old.header.updateAt) as ConversationChange.Durable)
                .write as ConversationWrite.Mutate
            ).mutation

        // The terminal commit carries only the owning Assistant: it never rewrites the full tree.
        assertEquals(1, mutation.upsertedNodes.size)
        assertEquals(assistantId, mutation.upsertedNodes.single().currentMessage.id)
        assertEquals(listOf(49), mutation.upsertedNodeIndices)
        assertEquals(1, old.nodes.indices.count { old.nodes[it] !== committed.nodes[it] })
    }

    @Test
    fun `snapshot without changes keeps every node identity`() {
        val conversationId = Uuid.random()
        val nodes = (0 until 10).map { MessageNode.of(UIMessage.user("history-$it")) }
        val snapshot: ConversationAggregateSnapshot =
            net.weero.measix.pilot.data.model.Conversation.ofId(conversationId)
                .copy(messageNodes = nodes)
                .toSnapshot()
        assertEquals(0, snapshot.nodes.indices.count { snapshot.nodes[it] !== nodes[it] })
    }

    @Test
    fun `removing a middle node moves survivors without deleting their projections`() {
        val conversationId = Uuid.random()
        val first = MessageNode.of(UIMessage.user("first"))
        val removed = MessageNode.of(UIMessage.assistant("remove"))
        val moved = MessageNode.of(UIMessage.user("moved"))
        val old = net.weero.measix.pilot.data.model.Conversation.ofId(conversationId)
            .copy(messageNodes = listOf(first, removed, moved))
            .toSnapshot()
        val command = DeleteMessage(removed.currentMessage.id)
        val mutation = (
            (ConversationTransition.plan(old, command, old.header.updateAt) as ConversationChange.Durable)
                .write as ConversationWrite.Mutate
            ).mutation

        assertEquals(listOf(removed.id), mutation.deletedNodeIds)
        assertEquals(listOf(moved.id), mutation.upsertedNodes.map(MessageNode::id))
        assertEquals(listOf(1), mutation.upsertedNodeIndices)
    }
}
