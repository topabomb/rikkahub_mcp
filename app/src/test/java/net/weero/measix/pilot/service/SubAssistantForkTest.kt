package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantForkTest {
    private val json = JsonInstant
    private val sourceMasterId = Uuid.random()
    private val targetId = Uuid.random()
    private val childId = Uuid.random()
    private val task1 = user("task 1")
    private val task2 = user("task 2")
    private val child = Conversation(
        id = childId,
        assistantId = targetId,
        parentConversationId = sourceMasterId,
        messageNodes = listOf(
            task1.toMessageNode(),
            assistant("answer 1").toMessageNode(),
            task2.toMessageNode(),
            assistant("answer 2").toMessageNode(),
        ),
    )

    @Test
    fun `fork of earlier run clones only its child prefix and remaps ids`() {
        val run1 = call("run-1", task1.id)
        val source = master(listOf(run1))
        val copiedNodes = source.messageNodes.map { it.copy(id = Uuid.random()) }
        val newMasterId = Uuid.random()

        val result = forkSubAssistantTree(
            source,
            copiedNodes,
            newMasterId,
            mapOf(child.id to child),
            json,
        )

        assertEquals(1, result.children.size)
        val forkedChild = result.children.single()
        assertEquals(newMasterId, forkedChild.parentConversationId)
        assertEquals(2, forkedChild.messageNodes.size)
        assertEquals("answer 1", text(forkedChild.messageNodes.last().currentMessage))
        assertTrue(forkedChild.messageNodes.none { text(it.currentMessage) == "task 2" })

        val metadata = result.masterNodes.single().currentMessage.getTools().single()
            .getSubAssistantCallMetadata(json)!!
        assertNotEquals("run-1", metadata.runId)
        assertEquals(forkedChild.id.toString(), metadata.childConversationId)
        assertNotEquals(task1.id.toString(), metadata.childTaskNodeId)
        assertNull(metadata.previousRunId)
    }

    @Test
    fun `fork remaps previous run only when predecessor is retained`() {
        val run1 = call("run-1", task1.id)
        val run2 = call("run-2", task2.id, previousRunId = "run-1")
        val source = master(listOf(run1, run2))

        val result = forkSubAssistantTree(
            source,
            source.messageNodes.map { it.copy(id = Uuid.random()) },
            Uuid.random(),
            mapOf(child.id to child),
            json,
        )

        val metadata = result.masterNodes.single().currentMessage.getTools()
            .map { it.getSubAssistantCallMetadata(json)!! }
        assertEquals(metadata[0].runId, metadata[1].previousRunId)
        assertEquals(metadata[0].childConversationId, metadata[1].childConversationId)
        assertEquals(4, result.children.single().messageNodes.size)
    }

    private fun call(
        runId: String,
        taskId: Uuid,
        previousRunId: String? = null,
    ): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = runId,
            targetAssistantId = targetId,
            targetNameSnapshot = "Target",
            previousRunId = previousRunId,
        ).copy(
            childConversationId = childId.toString(),
            childTaskNodeId = taskId.toString(),
            state = SubAssistantCallState.COMPLETED,
        )
        return UIMessagePart.Tool(runId, "assistant_call", "{}", output = listOf(UIMessagePart.Text("done")))
            .mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun master(tools: List<UIMessagePart.Tool>) = Conversation(
        id = sourceMasterId,
        assistantId = Uuid.random(),
        messageNodes = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = tools).toMessageNode()
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

    private fun text(message: UIMessage): String =
        (message.parts.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.text).orEmpty()
}
