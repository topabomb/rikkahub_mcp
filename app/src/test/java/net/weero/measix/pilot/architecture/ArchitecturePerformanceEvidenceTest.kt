package net.weero.measix.pilot.architecture

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.CommitCheckpoint
import net.weero.measix.pilot.service.runtime.ConversationMutationBuilder
import net.weero.measix.pilot.service.runtime.ConversationReducer
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/** Reproducible before/after measurements for checkpoint delta construction. */
class ArchitecturePerformanceEvidenceTest {
    @Test
    fun `fixed conversation datasets keep healthy recovery on the execution index`() {
        listOf(50, 500, 5_000).forEach(::measureHealthyRecoveryDataset)
    }

    @Test
    fun `fixed long conversation datasets keep one row and one identity invalidation`() {
        listOf(50, 500, 5_000).forEach(::measureDataset)
    }

    private fun measureDataset(nodeCount: Int) {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val historical = (0 until nodeCount - 1).map { index ->
            MessageNode.of(UIMessage.user("history-$index"))
        }
        val active = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("before")),
        )
        val old = Conversation.ofId(conversationId).copy(
            messageNodes = historical + MessageNode.of(active),
        ).toSnapshot()
        val updated = active.copy(parts = listOf(UIMessagePart.Text("after")))
        val handle = TurnHandle(conversationId, 1L, Uuid.random(), assistantId)
        val command = CommitCheckpoint(
            handle = handle,
            kind = net.weero.measix.pilot.data.ai.CheckpointKind.STEP_COMPLETED,
            messages = old.currentMessages().dropLast(1) + updated,
            turnStatus = TurnExecutionStatus.RUNNING,
            turnReason = null,
            toolExecution = null,
        )
        val currentNew = ConversationReducer.reduce(old, command)
        repeat(30) {
            buildLegacyCheckpointNodes(old, updated)
            ConversationMutationBuilder.build(old, currentNew, command)
        }
        val baselineNanos = samples(200) { buildLegacyCheckpointNodes(old, updated) }
        val currentNanos = samples(200) { ConversationMutationBuilder.build(old, currentNew, command) }
        // The pre-seal path handed every copied node to persistence. Reusing the current
        // equality-based builder here would measure today's implementation twice and erase the
        // legacy write amplification the evidence is intended to reproduce.
        val baselineNodes = buildLegacyCheckpointNodes(old, updated)
        val currentMutation = ConversationMutationBuilder.build(old, currentNew, command)
        val baselineBytes = JsonInstant.encodeToString(baselineNodes).toByteArray().size
        val currentBytes = JsonInstant.encodeToString(currentMutation.upsertedNodes).toByteArray().size
        val baselineInvalidations = old.nodes.indices.count { old.nodes[it] !== baselineNodes[it] }
        val currentInvalidations = old.nodes.indices.count { old.nodes[it] !== currentNew.nodes[it] }

        assertEquals(nodeCount, baselineNodes.size)
        assertEquals(1, currentMutation.upsertedNodes.size)
        assertEquals(nodeCount, baselineInvalidations)
        assertEquals(1, currentInvalidations)
        println(
            "ARCH_PERF|nodes=$nodeCount|baselineRows=${baselineNodes.size}" +
                "|currentRows=${currentMutation.upsertedNodes.size}|baselineBytes=$baselineBytes" +
                "|currentBytes=$currentBytes|baselineP50us=${percentile(baselineNanos, 50) / 1_000}" +
                "|baselineP95us=${percentile(baselineNanos, 95) / 1_000}" +
                "|currentP50us=${percentile(currentNanos, 50) / 1_000}" +
                "|currentP95us=${percentile(currentNanos, 95) / 1_000}" +
                "|baselineIdentityInvalidations=$baselineInvalidations" +
                "|currentIdentityInvalidations=$currentInvalidations"
        )
    }

    private fun buildLegacyCheckpointNodes(
        old: ConversationSnapshot,
        updated: UIMessage,
    ): List<MessageNode> = old.nodes.mapIndexed { index, node ->
        if (index == old.nodes.lastIndex) {
            node.copy(messages = listOf(updated), selectIndex = 0)
        } else {
            node.copy(messages = node.messages.toList())
        }
    }

    private fun measureHealthyRecoveryDataset(conversationCount: Int) {
        val conversations = List(conversationCount) { index ->
            Conversation.ofId(Uuid.random()).copy(
                title = "conversation-$index",
                messageNodes = listOf(
                    MessageNode.of(UIMessage.user("question-$index")),
                    MessageNode.of(UIMessage.assistant("answer-$index")),
                ),
            )
        }
        val baselinePayload = JsonInstant.encodeToString(conversations)
        val currentPayload = "[]"
        repeat(5) {
            JsonInstant.decodeFromString<List<Conversation>>(baselinePayload)
            JsonInstant.decodeFromString<List<String>>(currentPayload)
        }
        val baselineNanos = samples(30) {
            JsonInstant.decodeFromString<List<Conversation>>(baselinePayload)
        }
        val currentNanos = samples(30) {
            JsonInstant.decodeFromString<List<String>>(currentPayload)
        }

        println(
            "ARCH_RECOVERY|conversations=$conversationCount|baselineRows=$conversationCount|currentRows=0" +
                "|baselineBytes=${baselinePayload.toByteArray().size}|currentBytes=0" +
                "|baselineP50us=${percentile(baselineNanos, 50) / 1_000}" +
                "|baselineP95us=${percentile(baselineNanos, 95) / 1_000}" +
                "|currentP50us=${percentile(currentNanos, 50) / 1_000}" +
                "|currentP95us=${percentile(currentNanos, 95) / 1_000}"
        )
    }

    private inline fun samples(count: Int, block: () -> Unit): LongArray =
        LongArray(count) {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }

    private fun percentile(values: LongArray, percentile: Int): Long {
        val sorted = values.sortedArray()
        val index = ((sorted.lastIndex * percentile) / 100.0).toInt()
        return sorted[index]
    }
}
