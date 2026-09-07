package net.weero.measix.pilot.service.subassistant

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.LineageDecision
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.cloneLineagePrefix
import net.weero.measix.pilot.data.ai.subassistant.findPreviousCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.resolveLineage
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 子助手 lineage 测试族：previous-call 解析与 [resolveLineage]/[cloneLineagePrefix] 决策、
 * fork 时的 Child 前缀克隆与 id 重映射、以及树变更后 [planSubAssistantRetention] 的保留/截断计划。
 */
class SubAssistantLineageTest {

    private val json = JsonInstant
    private val targetId = Uuid.random()

    // ── findPreviousCallMetadata ──

    private val masterConvId = Uuid.random()

    private fun toolPartWithMeta(
        providerCallId: String = "call_${Uuid.random()}",
        meta: SubAssistantCallMetadata,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = providerCallId,
        toolName = "assistant_call",
        input = "{}",
        metadata = buildJsonObject {
            put("sub_assistant_call", json.encodeToJsonElement(
                SubAssistantCallMetadata.serializer(), meta
            ))
        },
    )

    private fun assistantCallMessage(
        providerCallId: String = "call_${Uuid.random()}",
        meta: SubAssistantCallMetadata,
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(toolPartWithMeta(providerCallId, meta)),
    )

    private fun userMessage(text: String = "task"): UIMessage =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `no previous call returns null`() {
        val messages = listOf(
            userMessage("task"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call_1", toolName = "assistant_call", input = "{}")
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
                toolPartWithMeta("duplicate", older),
                UIMessagePart.Text("between"),
                toolPartWithMeta("duplicate", nearest),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "duplicate",
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

    // ── resolveLineage ──

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

        val prefix = cloneLineagePrefix(child.messageNodes, taskMsg.id)!!
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
        assertEquals(null, cloneLineagePrefix(child.messageNodes, unselectedTask.id))
    }

    private fun childForLineage(task: UIMessage, laterNode: MessageNode) =
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

    // ── forkSubAssistantTree ──

    private val sourceMasterId = Uuid.random()
    private val forkChildId = Uuid.random()
    private val forkTask1 = user("task 1")
    private val forkTask2 = user("task 2")
    private val answer1 = assistant("answer 1")
    private val answer2 = assistant("answer 2")
    private val forkChildConversation = Conversation(
        id = forkChildId,
        assistantId = targetId,
        parentConversationId = sourceMasterId,
        messageNodes = listOf(
            forkTask1.toMessageNode(),
            answer1.toMessageNode(),
            forkTask2.toMessageNode(),
            answer2.toMessageNode(),
        ),
    )
    private val forkChild = forkChildConversation.toSnapshot(
        modelContextEntries = listOf(
            ConversationModelContextEntry(
                ownerNodeId = forkChildConversation.messageNodes[1].id,
                ownerMessageId = answer1.id,
                anchorNodeId = forkChildConversation.messageNodes[0].id,
                anchorMessageId = forkTask1.id,
                content = "snapshot-1",
            ),
        ),
    )

    @Test
    fun `fork of earlier run clones only its child prefix and remaps ids`() {
        val run1 = call("run-1", forkTask1.id, forkChildId)
        val source = masterOfTools(listOf(run1))
        val copiedNodes = source.messageNodes.map { it.copy(id = Uuid.random()) }
        val newMasterId = Uuid.random()

        val result = forkSubAssistantTree(
            source.id,
            copiedNodes,
            newMasterId,
            mapOf(forkChild.conversationId to forkChild),
            json,
        )

        assertEquals(1, result.children.size)
        val forkedChild = result.children.single()
        assertEquals(newMasterId, forkedChild.header.parentConversationId)
        assertEquals(2, forkedChild.nodes.size)
        assertEquals("answer 1", text(forkedChild.nodes.last().currentMessage))
        assertTrue(forkedChild.nodes.none { text(it.currentMessage) == "task 2" })
        val context = forkedChild.modelContextEntries.single()
        assertEquals(forkedChild.nodes[0].id, context.anchorNodeId)
        assertEquals(forkedChild.nodes[0].currentMessage.id, context.anchorMessageId)
        assertEquals(forkedChild.nodes[1].id, context.ownerNodeId)
        assertEquals(forkedChild.nodes[1].currentMessage.id, context.ownerMessageId)

        val metadata = result.masterNodes.single().currentMessage.getTools().single()
            .getSubAssistantCallMetadata(json)!!
        assertNotEquals("run-1", metadata.runId)
        assertEquals(forkedChild.conversationId.toString(), metadata.childConversationId)
        assertNotEquals(forkTask1.id.toString(), metadata.childTaskNodeId)
        assertNull(metadata.previousRunId)
    }

    @Test
    fun `fork remaps previous run only when predecessor is retained`() {
        val run1 = call("run-1", forkTask1.id, forkChildId)
        val run2 = call("run-2", forkTask2.id, forkChildId, previousRunId = "run-1")
        val source = masterOfTools(listOf(run1, run2))

        val result = forkSubAssistantTree(
            source.id,
            source.messageNodes.map { it.copy(id = Uuid.random()) },
            Uuid.random(),
            mapOf(forkChild.conversationId to forkChild),
            json,
        )

        val metadata = result.masterNodes.single().currentMessage.getTools()
            .map { it.getSubAssistantCallMetadata(json)!! }
        assertEquals(metadata[0].runId, metadata[1].previousRunId)
        assertEquals(metadata[0].childConversationId, metadata[1].childConversationId)
        assertEquals(4, result.children.single().nodes.size)
    }

    // ── planSubAssistantRetention ──

    private val retentionMasterId = Uuid.random()
    private val retentionChildId = Uuid.random()
    private val retentionTask1 = user("task 1")
    private val retentionTask2 = user("task 2")
    private val retentionChild = Conversation(
        id = retentionChildId,
        assistantId = targetId,
        parentConversationId = retentionMasterId,
        messageNodes = listOf(
            retentionTask1.toMessageNode(),
            assistant("answer 1").toMessageNode(),
            retentionTask2.toMessageNode(),
            assistant("answer 2").toMessageNode(),
        ),
    )

    @Test
    fun `removing last referenced run truncates unreferenced child tail`() {
        val master = retentionMaster(call("run-1", retentionTask1.id, retentionChildId))

        val plan = planSubAssistantRetention(master.id, master.messageNodes, mapOf(retentionChild.id to retentionChild.toSnapshot()), json)

        assertTrue(plan.deletedChildIds.isEmpty())
        assertEquals(2, plan.truncatedChildren.single().nodes.size)
    }

    @Test
    fun `later retained run keeps its real intermediate history`() {
        val master = retentionMaster(call("run-2", retentionTask2.id, retentionChildId, previousRunId = "run-1"))

        val plan = planSubAssistantRetention(master.id, master.messageNodes, mapOf(retentionChild.id to retentionChild.toSnapshot()), json)

        assertTrue(plan.truncatedChildren.isEmpty())
    }

    @Test
    fun `child with no remaining valid references is deleted`() {
        val master = Conversation(id = retentionMasterId, assistantId = Uuid.random(), messageNodes = emptyList())

        val plan = planSubAssistantRetention(master.id, master.messageNodes, mapOf(retentionChild.id to retentionChild.toSnapshot()), json)

        assertTrue(plan.truncatedChildren.isEmpty())
        assertEquals(listOf(retentionChild.id), plan.deletedChildIds)
    }

    private fun retentionMaster(vararg calls: UIMessagePart.Tool) = Conversation(
        id = retentionMasterId,
        assistantId = Uuid.random(),
        messageNodes = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = calls.toList()).toMessageNode()
        ),
    )

    // ── shared fixtures ──

    private fun call(
        runId: String,
        taskId: Uuid,
        childId: Uuid,
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
        return UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = runId,
            toolName = "assistant_call",
            input = "{}",
            output = listOf(UIMessagePart.Text("done")),
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun masterOfTools(tools: List<UIMessagePart.Tool>) = Conversation(
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
