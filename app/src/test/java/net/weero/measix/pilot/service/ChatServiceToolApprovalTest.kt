package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildInitialSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.service.runtime.ConversationReducer
import net.weero.measix.pilot.service.runtime.UpdateToolApproval
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceToolApprovalTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `approval locator updates only selected ordinal when provider ids repeat`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                pendingTool("duplicate"),
                pendingTool("duplicate"),
            ),
        )
        val conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(message.toMessageNode()),
        )

        // D2：HITL 审批走 UpdateToolApproval 命令（reducer 唯一路径）
        val updated = ConversationReducer.reduce(
            conversation,
            UpdateToolApproval(message.id, 1, ToolApprovalState.Answered("answer")),
        )

        val tools = updated.currentMessages.last().getTools()
        assertEquals(ToolApprovalState.Pending, tools[0].approvalState)
        assertEquals(ToolApprovalState.Answered("answer"), tools[1].approvalState)
    }

    @Test
    fun `attention keys include pending tools and bridged ask_user`() {
        val pending = pendingTool("ask")
        val assistantCall = runningCallWithAskUser("ask-42")
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pending, assistantCall),
        )

        val keys = collectUserAttentionKeys(listOf(message), json)

        assertEquals(
            setOf("tool:${message.id}:0", "ask:ask-42"),
            keys,
        )
        assertEquals(
            setOf("tool:${message.id}:0", "ask:ask-42"),
            collectUserAttentionKeys(listOf(message), json),
        )
    }

    @Test
    fun `attention keys ignore leftover ask_user on a terminal call`() {
        val leftover = runningCallWithAskUser("ask-stale").let { tool ->
            val metadata = tool.getSubAssistantCallMetadata(json)!!.copy(
                state = SubAssistantCallState.COMPLETED,
            )
            tool.mergeSubAssistantCallMetadata(json, metadata)
        }
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(leftover))
        assertEquals(emptySet<String>(), collectUserAttentionKeys(listOf(message), json))
    }

    @Test
    fun `a new ask_user interaction id is a new attention key`() {
        val first = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(runningCallWithAskUser("ask-1")))
        val second = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(runningCallWithAskUser("ask-2")))
        assertEquals(setOf("ask:ask-1"), collectUserAttentionKeys(listOf(first), json))
        assertEquals(setOf("ask:ask-2"), collectUserAttentionKeys(listOf(second), json))
    }

    @Test
    fun `stale message locator cannot mutate current branch`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool("id")),
        )
        val conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(message.toMessageNode()),
        )

        // locator 指向不存在的消息 → reducer 返回原引用（无变更、不落库）
        val updated = ConversationReducer.reduce(
            conversation,
            UpdateToolApproval(Uuid.random(), 0, ToolApprovalState.Approved),
        )
        assertEquals(conversation, updated)
    }

    @Test
    fun `multiple pending decisions compose without losing an earlier answer`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(pendingTool("duplicate"), pendingTool("duplicate")),
        )
        val conversation = Conversation(
            assistantId = DEFAULT_ASSISTANT_ID,
            messageNodes = listOf(message.toMessageNode()),
        )

        val afterFirst = ConversationReducer.reduce(
            conversation,
            UpdateToolApproval(message.id, 0, ToolApprovalState.Answered("first")),
        )
        val afterSecond = ConversationReducer.reduce(
            afterFirst,
            UpdateToolApproval(message.id, 1, ToolApprovalState.Answered("second")),
        )

        assertEquals(
            listOf(ToolApprovalState.Answered("first"), ToolApprovalState.Answered("second")),
            afterSecond.currentMessages.last().getTools().map { it.approvalState },
        )
    }

    @Test
    fun `stopping master generation closes running sub-assistant card`() {
        val targetId = Uuid.random()
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = targetId,
            targetNameSnapshot = "Reviewer",
        ).copy(state = SubAssistantCallState.RUNNING)
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "assistant_call",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
        ).mergeSubAssistantCallMetadata(json, metadata)

        val stopped = finishInterruptedToolAfterGenerationStop(tool, json)
        val stoppedMetadata = stopped.getSubAssistantCallMetadata(json)!!

        assertEquals(SubAssistantCallState.STOPPED, stoppedMetadata.state)
        assertEquals("user_cancelled", stoppedMetadata.reason)
        assertEquals(null, stoppedMetadata.phase)
        assertEquals(null, stoppedMetadata.userInteraction)
        assertEquals(1, stopped.output.size)
        assertEquals(true, (stopped.output.single() as UIMessagePart.Text).text.contains("user_cancelled"))
        assertEquals(false, (stopped.output.single() as UIMessagePart.Text).text.contains("tool_calls"))
    }

    @Test
    fun `stopping master generation includes tts_stats but not bulky extras by default`() {
        val stopped = finishInterruptedToolAfterGenerationStop(
            runningAssistantCall(input = "{}"),
            json,
            childMessagesWithTts(),
        )
        val text = (stopped.output.single() as UIMessagePart.Text).text
        assertEquals(true, text.contains("user_cancelled"))
        assertEquals(true, text.contains("\"tts_stats\""))
        assertEquals(false, text.contains("\"tool_calls\""))
        assertEquals(false, text.contains("Spoken answer."))
    }

    @Test
    fun `stopping master generation returns extras when requested`() {
        val stopped = finishInterruptedToolAfterGenerationStop(
            runningAssistantCall(input = """{"extras":["tts","tool_calls"]}"""),
            json,
            childMessagesWithTts(),
        )
        val text = (stopped.output.single() as UIMessagePart.Text).text
        assertEquals(true, text.contains("user_cancelled"))
        assertEquals(true, text.contains("\"tts_stats\""))
        assertEquals(true, text.contains("\"tool_calls\""))
        assertEquals(true, text.contains("search_web"))
        assertEquals(true, text.contains("Spoken answer."))
    }

    @Test
    fun `stopping master generation does not project artifacts even when extras request them`() {
        val stopped = finishInterruptedToolAfterGenerationStop(
            runningAssistantCall(input = """{"extras":["artifacts","tts","tool_calls"]}"""),
            json,
            childMessagesWithTts(),
        )
        assertEquals(1, stopped.output.size)
        val text = (stopped.output.single() as UIMessagePart.Text).text
        assertEquals(true, text.contains("user_cancelled"))
        assertEquals(false, text.contains("\"artifacts\""))
        assertEquals(false, text.contains("artifact_delivery"))
        assertEquals(true, stopped.output.none { it is UIMessagePart.Image })
    }

    private fun runningCallWithAskUser(interactionId: String): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-ask",
            targetAssistantId = Uuid.random(),
            targetNameSnapshot = "Reviewer",
        ).copy(
            state = SubAssistantCallState.RUNNING,
            userInteraction = net.weero.measix.pilot.data.ai.subassistant.SubAssistantUserInteraction(
                interactionId = interactionId,
                messageId = Uuid.random().toString(),
                toolOrdinal = 0,
                toolName = "ask_user",
                input = "{}",
            ),
        )
        return UIMessagePart.Tool(
            toolCallId = "call-ask",
            toolName = "assistant_call",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private fun runningAssistantCall(input: String): UIMessagePart.Tool {
        val metadata = buildInitialSubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = Uuid.random(),
            targetNameSnapshot = "Reviewer",
        ).copy(
            state = SubAssistantCallState.RUNNING,
            childConversationId = Uuid.random().toString(),
            childTaskNodeId = childTaskId.toString(),
        )
        return UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "assistant_call",
            input = input,
            approvalState = ToolApprovalState.Auto,
        ).mergeSubAssistantCallMetadata(json, metadata)
    }

    private val childTaskId = Uuid.random()

    private fun childMessagesWithTts() = listOf(
        UIMessage(id = childTaskId, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("do it"))),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "s1",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("ok")),
                ),
                UIMessagePart.Tool(
                    toolCallId = "t1",
                    toolName = "text_to_speech",
                    input = """{"text":"Spoken answer."}""",
                    output = listOf(UIMessagePart.Text("""{"success":true}""")),
                ),
            ),
        ),
    )

    private fun pendingTool(id: String) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "ask_user",
        input = "{}",
        approvalState = ToolApprovalState.Pending,
    )
}
