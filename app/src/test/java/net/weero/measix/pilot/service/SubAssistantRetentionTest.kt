package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantRetentionTest {
    private val json = JsonInstant
    private val masterId = Uuid.random()
    private val targetId = Uuid.random()
    private val childId = Uuid.random()
    private val task1 = user("task 1")
    private val task2 = user("task 2")
    private val child = Conversation(
        id = childId,
        assistantId = targetId,
        parentConversationId = masterId,
        messageNodes = listOf(
            task1.toMessageNode(),
            assistant("answer 1").toMessageNode(),
            task2.toMessageNode(),
            assistant("answer 2").toMessageNode(),
        ),
    )

    @Test
    fun `removing last referenced run truncates unreferenced child tail`() {
        val master = master(call("run-1", task1.id))

        val plan = planSubAssistantRetention(master.id, master.messageNodes, mapOf(child.id to child.toSnapshot()), json)

        assertTrue(plan.deletedChildIds.isEmpty())
        assertEquals(2, plan.truncatedChildren.single().nodes.size)
    }

    @Test
    fun `later retained run keeps its real intermediate history`() {
        val master = master(call("run-2", task2.id, previousRunId = "run-1"))

        val plan = planSubAssistantRetention(master.id, master.messageNodes, mapOf(child.id to child.toSnapshot()), json)

        assertTrue(plan.truncatedChildren.isEmpty())
    }

    @Test
    fun `child with no remaining valid references is deleted`() {
        val master = Conversation(id = masterId, assistantId = Uuid.random(), messageNodes = emptyList())

        val plan = planSubAssistantRetention(master.id, master.messageNodes, mapOf(child.id to child.toSnapshot()), json)

        assertTrue(plan.truncatedChildren.isEmpty())
        assertEquals(listOf(child.id), plan.deletedChildIds)
    }

    private fun call(
        runId: String,
        taskId: Uuid,
        previousRunId: String? = null,
    ): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId,
            targetId,
            "Target",
            previousRunId,
        ).copy(
            childConversationId = childId.toString(),
            childTaskNodeId = taskId.toString(),
            state = SubAssistantCallState.COMPLETED,
        )
        return UIMessagePart.Tool(
            toolCallId = runId,
            toolName = "assistant_call",
            input = "{}",
            output = listOf(UIMessagePart.Text("done")),
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun master(vararg calls: UIMessagePart.Tool) = Conversation(
        id = masterId,
        assistantId = Uuid.random(),
        messageNodes = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = calls.toList()).toMessageNode()
        ),
    )

    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistant(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
