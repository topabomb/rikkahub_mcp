package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TTSAutoPlayTest {
    @Test
    fun `auto play ignores completion events from another conversation`() {
        val conversation = conversationWith(UIMessage.assistant("current"))

        assertFalse(shouldAutoPlayTts(Uuid.random(), conversation.toSnapshot()))
    }

    @Test
    fun `auto play waits while tool approval keeps master turn open`() {
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "ask_user",
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                )
            ),
        )
        val conversation = conversationWith(pending)

        assertFalse(shouldAutoPlayTts(conversation.id, conversation.toSnapshot()))
    }

    @Test
    fun `auto play accepts completed assistant response in current conversation`() {
        val conversation = conversationWith(UIMessage.assistant("done"))

        assertTrue(shouldAutoPlayTts(conversation.id, conversation.toSnapshot()))
    }

    @Test
    fun `auto play skips when text_to_speech already executed`() {
        val spoken = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("The answer is 42."),
                UIMessagePart.Tool(
                    toolCallId = "tts-1",
                    toolName = "text_to_speech",
                    input = """{"text":"The answer is 42."}""",
                    output = listOf(UIMessagePart.Text("""{"success":true}""")),
                ),
            ),
        )
        val conversation = conversationWith(spoken)

        assertFalse(shouldAutoPlayTts(conversation.id, conversation.toSnapshot()))
    }

    @Test
    fun `auto play appends to same turn only when sequential setting is enabled`() {
        assertFalse(autoPlayReplacesWithinTurn("turn-1", sequentialEnabled = true))
        assertTrue(autoPlayReplacesWithinTurn("turn-1", sequentialEnabled = false))
        assertTrue(autoPlayReplacesWithinTurn(null, sequentialEnabled = true))
    }

    private fun conversationWith(message: UIMessage): Conversation {
        val id = Uuid.random()
        return Conversation.ofId(id).copy(messageNodes = listOf(message.toMessageNode()))
    }
}
