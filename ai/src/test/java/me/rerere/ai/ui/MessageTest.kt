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

    @Test
    fun `current assistant step should normalize out of order tool content and reasoning deltas`() {
        var messages = listOf(UIMessage.user("Use a tool"))

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    input = "{}",
                )
            )
        )
        messages = messages.handleMessageChunk(assistantChunk(UIMessagePart.Text("Calling lookup")))
        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Reasoning(reasoning = "Need lookup"))
        )

        val parts = messages.last().parts
        assertEquals(3, parts.size)
        assertTrue(parts[0] is UIMessagePart.Reasoning)
        assertTrue(parts[1] is UIMessagePart.Text)
        assertTrue(parts[2] is UIMessagePart.Tool)
        assertEquals("Need lookup", (parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Calling lookup", (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `normalizing current assistant step should not move completed tool history`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "first",
            input = "{}",
            output = listOf(UIMessagePart.Text("first result")),
        )
        var messages = listOf(
            UIMessage.user("Use tools"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "First reasoning"),
                    UIMessagePart.Text("First content"),
                    completedTool,
                ),
            ),
        )

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "second",
                    input = "{}",
                )
            )
        )
        messages = messages.handleMessageChunk(assistantChunk(UIMessagePart.Text("Second content")))
        messages = messages.handleMessageChunk(
            assistantChunk(UIMessagePart.Reasoning(reasoning = "Second reasoning"))
        )

        val parts = messages.last().parts
        assertEquals(
            listOf(
                UIMessagePart.Reasoning::class,
                UIMessagePart.Text::class,
                UIMessagePart.Tool::class,
                UIMessagePart.Reasoning::class,
                UIMessagePart.Text::class,
                UIMessagePart.Tool::class,
            ),
            parts.map { it::class },
        )
        assertEquals("call-1", (parts[2] as UIMessagePart.Tool).toolCallId)
        assertEquals("call-2", (parts[5] as UIMessagePart.Tool).toolCallId)
    }

    @Test
    fun `blank tool delta should not merge into a completed tool step`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "first",
            input = "{}",
            output = listOf(UIMessagePart.Text("first result")),
        )
        var messages = listOf(
            UIMessage.user("Use another tool"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completedTool)),
        )

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "",
                    toolName = "second",
                    input = "{",
                )
            )
        )

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals(completedTool, tools[0])
        assertEquals("second", tools[1].toolName)
    }

    @Test
    fun `reused nonblank tool id should not mutate a completed tool step`() {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "reused-id",
            toolName = "first",
            input = "{}",
            output = listOf(UIMessagePart.Text("first result")),
        )
        var messages = listOf(
            UIMessage.user("Use another tool"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completedTool)),
        )

        messages = messages.handleMessageChunk(
            assistantChunk(
                UIMessagePart.Tool(
                    toolCallId = "reused-id",
                    toolName = "second",
                    input = "[]",
                )
            )
        )

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(2, tools.size)
        assertEquals(completedTool, tools[0])
        assertEquals("second", tools[1].toolName)
        assertEquals("[]", tools[1].input)
    }

    private fun assistantChunk(vararg parts: UIMessagePart) = MessageChunk(
        id = "chunk",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun createAlternatingMessages(count: Int): List<UIMessage> = List(count) { index ->
        if (index % 2 == 0) {
            UIMessage.user("Question $index")
        } else {
            UIMessage.assistant("Answer $index")
        }
    }
}
