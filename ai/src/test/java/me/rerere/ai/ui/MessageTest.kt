package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTest {

    // ==================== limitContext Tests ====================

    @Test
    fun `limitContext should preserve the original list when disabled or within threshold`() {
        val messages = createAlternatingMessages(10)

        assertEquals(messages, messages.limitContext(0))
        assertEquals(messages, messages.limitContext(-1))
        assertEquals(messages, messages.limitContext(10))
        assertEquals(emptyList<UIMessage>(), emptyList<UIMessage>().limitContext(10))
    }

    @Test
    fun `limitContext should keep a stable user-turn boundary within one step`() {
        val messages = createAlternatingMessages(30)

        val startsWithinStep = (11..14).map { size ->
            messages.subList(0, size).limitContext(10).first()
        }

        assertEquals(1, startsWithinStep.distinct().size)
        assertEquals(MessageRole.USER, startsWithinStep.first().role)
        assertEquals(messages[4], startsWithinStep.first())
        assertEquals(messages[10], messages.subList(0, 15).limitContext(10).first())
    }

    @Test
    fun `limitContext should align an assistant start to its preceding user turn`() {
        val messages = listOf(
            UIMessage.user("Old question"),
            UIMessage.assistant("Old answer"),
            UIMessage.user("Question with tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "test_tool",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("result"))
                    )
                )
            ),
            UIMessage.assistant("Final answer"),
            UIMessage.user("Newest question")
        )

        // limit=4 produces a stepped start at index 4 (an assistant message);
        // the implementation must move it back to the user at index 2.
        val result = messages.limitContext(4)

        assertEquals(messages.subList(2, messages.size), result)
        assertEquals(MessageRole.USER, result.first().role)
    }

    @Test
    fun `findUserTurnStart should preserve complete turns`() {
        val messages = listOf(
            UIMessage.user("First question"),
            UIMessage.assistant("First answer"),
            UIMessage.user("Second question"),
            UIMessage.assistant("Second answer")
        )

        assertEquals(2, messages.findUserTurnStart(3))
        assertEquals(2, messages.findUserTurnStart(2))
        assertEquals(0, emptyList<UIMessage>().findUserTurnStart(3))
    }

    @Test
    fun `limitContext should remain a suffix and retain at least half the threshold`() {
        val messages = createAlternatingMessages(120)

        for (size in 11..120) {
            val source = messages.subList(0, size)
            val result = source.limitContext(10)

            assertTrue("size=$size retained ${result.size}", result.size >= 5)
            assertEquals(MessageRole.USER, result.first().role)
            assertEquals(source.takeLast(result.size), result)
        }
    }

    // ==================== isValidToUpload Tests ====================

    @Test
    fun `isValidToUpload should be true for non-empty reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking"),
                UIMessagePart.Text("")
            )
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be false for blank reasoning with empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "   "),
                UIMessagePart.Text("")
            )
        )

        assertFalse(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should be true for non-empty text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("ok"))
        )

        assertTrue(message.isValidToUpload())
    }

    @Test
    fun `isValidToUpload should keep tool-only message valid`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = """{"q":"hello"}"""
                )
            )
        )

        assertTrue(message.isValidToUpload())
    }

    private fun createAlternatingMessages(count: Int): List<UIMessage> = List(count) { index ->
        if (index % 2 == 0) {
            UIMessage.user("Question $index")
        } else {
            UIMessage.assistant("Answer $index")
        }
    }
}
