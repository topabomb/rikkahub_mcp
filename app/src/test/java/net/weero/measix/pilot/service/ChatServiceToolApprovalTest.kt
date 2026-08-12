package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        val updated = updateCurrentToolApproval(
            conversation = conversation,
            locator = ToolCallLocator(message.id, 1),
            approvalState = ToolApprovalState.Answered("answer"),
        )!!

        val tools = updated.currentMessages.last().getTools()
        assertEquals(ToolApprovalState.Pending, tools[0].approvalState)
        assertEquals(ToolApprovalState.Answered("answer"), tools[1].approvalState)
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

        assertNull(
            updateCurrentToolApproval(
                conversation = conversation,
                locator = ToolCallLocator(Uuid.random(), 0),
                approvalState = ToolApprovalState.Approved,
            )
        )
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

        val afterFirst = updateCurrentToolApproval(
            conversation,
            ToolCallLocator(message.id, 0),
            ToolApprovalState.Answered("first"),
        )!!
        val afterSecond = updateCurrentToolApproval(
            afterFirst,
            ToolCallLocator(message.id, 1),
            ToolApprovalState.Answered("second"),
        )!!

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
    }

    private fun pendingTool(id: String) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "ask_user",
        input = "{}",
        approvalState = ToolApprovalState.Pending,
    )
}
