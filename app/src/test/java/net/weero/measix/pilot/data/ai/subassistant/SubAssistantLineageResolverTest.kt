package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantLineageResolverTest {

    private val json = JsonInstant
    private val targetId = Uuid.random()
    private val masterConvId = Uuid.random()

    private fun toolPartWithMeta(
        toolCallId: String = "call_${Uuid.random()}",
        meta: SubAssistantCallMetadata,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = "assistant_call",
        input = "{}",
        metadata = buildJsonObject {
            put("sub_assistant_call", json.encodeToJsonElement(
                SubAssistantCallMetadata.serializer(), meta
            ))
        },
    )

    private fun assistantCallMessage(
        toolCallId: String = "call_${Uuid.random()}",
        meta: SubAssistantCallMetadata,
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(toolPartWithMeta(toolCallId, meta)),
    )

    private fun userMessage(text: String = "task"): UIMessage =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    // ---- findPreviousCallMetadata ----

    @Test
    fun `no previous call returns null`() {
        val messages = listOf(
            userMessage("task"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("call_1", "assistant_call", "{}")
            )),
        )
        val result = findPreviousCallMetadata(messages, messages.last().id, 0, targetId, json)
        assertEquals(null, result)
    }

    @Test
    fun `finds previous call for same target`() {
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = Uuid.random().toString(),
            childTaskNodeId = Uuid.random().toString(),
        )
        val messages = listOf(
            userMessage("task1"),
            assistantCallMessage("call_1", meta),
            userMessage("task2"),
            assistantCallMessage("call_2", SubAssistantCallMetadata(
                runId = "run-2",
                targetAssistantId = targetId.toString(),
                targetNameSnapshot = "Helper",
                state = SubAssistantCallState.RUNNING,
            )),
        )
        val result = findPreviousCallMetadata(messages, messages.last().id, 0, targetId, json)
        assertNotNull(result)
        assertEquals("run-1", result!!.runId)
    }

    @Test
    fun `skips different target`() {
        val otherTarget = Uuid.random()
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = otherTarget.toString(),
            targetNameSnapshot = "Other",
            state = SubAssistantCallState.COMPLETED,
        )
        val messages = listOf(
            userMessage("task"),
            assistantCallMessage("call_1", meta),
            userMessage("task2"),
            assistantCallMessage("call_2", SubAssistantCallMetadata(
                runId = "run-2",
                targetAssistantId = targetId.toString(),
                targetNameSnapshot = "Helper",
                state = SubAssistantCallState.RUNNING,
            )),
        )
        val result = findPreviousCallMetadata(messages, messages.last().id, 0, targetId, json)
        assertEquals(null, result)
    }

    @Test
    fun `skips current tool call`() {
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.RUNNING,
        )
        val messages = listOf(
            userMessage("task"),
            assistantCallMessage("call_1", meta),
        )
        val result = findPreviousCallMetadata(messages, messages.last().id, 0, targetId, json)
        assertEquals(null, result)
    }

    @Test
    fun `same message duplicate provider ids use ordinal and nearest previous call`() {
        val older = SubAssistantCallMetadata(
            runId = "run-older",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
        )
        val nearest = older.copy(runId = "run-nearest")
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                toolPartWithMeta(toolCallId = "duplicate", meta = older),
                UIMessagePart.Text("between"),
                toolPartWithMeta(toolCallId = "duplicate", meta = nearest),
                UIMessagePart.Tool(
                    toolCallId = "duplicate",
                    toolName = "assistant_call",
                    input = "{}",
                ),
            ),
        )

        val result = findPreviousCallMetadata(
            masterMessages = listOf(message),
            currentMessageId = message.id,
            currentToolOrdinal = 2,
            targetAssistantId = targetId,
            json = json,
        )

        assertEquals("run-nearest", result?.runId)
    }

    // ---- resolveLineage ----

    @Test
    fun `null previous creates new child`() {
        val decision = resolveLineage(null, null, masterConvId, targetId)
        assertEquals(LineageDecision.CreateNew, decision)
    }

    @Test
    fun `missing child conversation ID creates new due to error`() {
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = null,
            childTaskNodeId = Uuid.random().toString(),
        )
        val decision = resolveLineage(meta, null, masterConvId, targetId)
        assertEquals(LineageDecision.CreateNewDueToError, decision)
    }

    @Test
    fun `child conversation null creates new due to error`() {
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = Uuid.random().toString(),
            childTaskNodeId = Uuid.random().toString(),
        )
        val decision = resolveLineage(meta, null, masterConvId, targetId)
        assertEquals(LineageDecision.CreateNewDueToError, decision)
    }

    @Test
    fun `child without parent creates new due to error`() {
        val childConvId = Uuid.random()
        val taskNodeId = Uuid.random()
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = childConvId.toString(),
            childTaskNodeId = taskNodeId.toString(),
        )
        val child = Conversation(
            id = childConvId,
            assistantId = targetId,
            messageNodes = listOf(
                UIMessage(id = taskNodeId, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("task")))
                    .toMessageNode()
            ),
            parentConversationId = null, // Not a child!
        )
        val decision = resolveLineage(meta, child, masterConvId, targetId)
        assertEquals(LineageDecision.CreateNewDueToError, decision)
    }

    @Test
    fun `child from another master or target is never reused`() {
        val childId = Uuid.random()
        val task = userMessage()
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = childId.toString(),
            childTaskNodeId = task.id.toString(),
        )
        val wrongMaster = Conversation(
            id = childId,
            assistantId = targetId,
            messageNodes = listOf(task.toMessageNode()),
            parentConversationId = Uuid.random(),
        )
        val wrongTarget = wrongMaster.copy(
            assistantId = Uuid.random(),
            parentConversationId = masterConvId,
        )

        assertEquals(
            LineageDecision.CreateNewDueToError,
            resolveLineage(meta, wrongMaster, masterConvId, targetId),
        )
        assertEquals(
            LineageDecision.CreateNewDueToError,
            resolveLineage(meta, wrongTarget, masterConvId, targetId),
        )
    }

    @Test
    fun `tail reuse when task node is last user message`() {
        val childConvId = Uuid.random()
        val taskMsg = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("task")))
        val taskNode = taskMsg.toMessageNode()
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = childConvId.toString(),
            childTaskNodeId = taskMsg.id.toString(),
        )
        val child = Conversation(
            id = childConvId,
            assistantId = targetId,
            messageNodes = listOf(
                taskNode,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer")))
                    .toMessageNode(),
            ),
            parentConversationId = masterConvId,
        )
        val decision = resolveLineage(meta, child, masterConvId, targetId)
        assertTrue(decision is LineageDecision.ReuseChild)
        assertEquals(childConvId, (decision as LineageDecision.ReuseChild).childConversationId)
        assertEquals("run-1", (decision as LineageDecision.ReuseChild).previousRunId)
    }

    @Test
    fun `clone when child has subsequent user tasks`() {
        val childConvId = Uuid.random()
        val taskMsg = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("task1")))
        val taskNode = taskMsg.toMessageNode()
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = childConvId.toString(),
            childTaskNodeId = taskMsg.id.toString(),
        )
        val child = Conversation(
            id = childConvId,
            assistantId = targetId,
            messageNodes = listOf(
                taskNode,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer1")))
                    .toMessageNode(),
                // Subsequent user task
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("task2")))
                    .toMessageNode(),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer2")))
                    .toMessageNode(),
            ),
            parentConversationId = masterConvId,
        )
        val decision = resolveLineage(meta, child, masterConvId, targetId)
        assertTrue(decision is LineageDecision.CloneChild)
        assertEquals(childConvId, (decision as LineageDecision.CloneChild).sourceChildConversationId)
        assertEquals("run-1", (decision as LineageDecision.CloneChild).sourceRunId)
        assertEquals(taskMsg.id, (decision as LineageDecision.CloneChild).throughTaskMessageId)

        val prefix = cloneLineagePrefix(child, taskMsg.id)!!
        assertEquals(2, prefix.size)
        assertEquals("answer1", (prefix.last().currentMessage.parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `selected user variant after previous run requires clone`() {
        val task = userMessage("task1")
        val laterAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("old")),
        )
        val laterUser = userMessage("task2")
        val child = childForLineage(
            task = task,
            laterNode = laterAssistant.toMessageNode().copy(
                messages = listOf(laterAssistant, laterUser),
                selectIndex = 1,
            ),
        )

        assertTrue(
            resolveLineage(lineageMetadata(task, child.id), child, masterConvId, targetId) is
                LineageDecision.CloneChild
        )
    }

    @Test
    fun `unselected user variant after previous run does not require clone`() {
        val task = userMessage("task1")
        val laterUser = userMessage("unselected")
        val laterAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("selected")),
        )
        val child = childForLineage(
            task = task,
            laterNode = laterUser.toMessageNode().copy(
                messages = listOf(laterUser, laterAssistant),
                selectIndex = 1,
            ),
        )

        assertTrue(
            resolveLineage(lineageMetadata(task, child.id), child, masterConvId, targetId) is
                LineageDecision.ReuseChild
        )
    }

    @Test
    fun `unselected task variant is not accepted as lineage endpoint`() {
        val childConvId = Uuid.random()
        val selectedTask = userMessage("selected")
        val unselectedTask = userMessage("unselected")
        val branchedNode = selectedTask.toMessageNode().copy(
            messages = listOf(selectedTask, unselectedTask),
            selectIndex = 0,
        )
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = childConvId.toString(),
            childTaskNodeId = unselectedTask.id.toString(),
        )
        val child = Conversation(
            id = childConvId,
            assistantId = targetId,
            messageNodes = listOf(branchedNode),
            parentConversationId = masterConvId,
        )

        assertEquals(
            LineageDecision.CreateNewDueToError,
            resolveLineage(meta, child, masterConvId, targetId),
        )
        assertEquals(null, cloneLineagePrefix(child, unselectedTask.id))
    }

    private fun childForLineage(task: UIMessage, laterNode: net.weero.measix.pilot.data.model.MessageNode) =
        Conversation(
            id = Uuid.random(),
            assistantId = targetId,
            messageNodes = listOf(
                task.toMessageNode(),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("answer")),
                ).toMessageNode(),
                laterNode,
            ),
            parentConversationId = masterConvId,
        )

    private fun lineageMetadata(task: UIMessage, childId: Uuid) = SubAssistantCallMetadata(
        runId = "run-selected-variant",
        targetAssistantId = targetId.toString(),
        targetNameSnapshot = "Helper",
        state = SubAssistantCallState.COMPLETED,
        childConversationId = childId.toString(),
        childTaskNodeId = task.id.toString(),
    )

    @Test
    fun `task node not found in child creates new due to error`() {
        val childConvId = Uuid.random()
        val meta = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId.toString(),
            targetNameSnapshot = "Helper",
            state = SubAssistantCallState.COMPLETED,
            childConversationId = childConvId.toString(),
            childTaskNodeId = Uuid.random().toString(),
        )
        val child = Conversation(
            id = childConvId,
            assistantId = targetId,
            messageNodes = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("different task")))
                    .toMessageNode(),
            ),
            parentConversationId = masterConvId,
        )
        val decision = resolveLineage(meta, child, masterConvId, targetId)
        assertEquals(LineageDecision.CreateNewDueToError, decision)
    }
}
