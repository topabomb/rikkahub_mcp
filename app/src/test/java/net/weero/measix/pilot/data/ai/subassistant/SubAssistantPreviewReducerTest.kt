package net.weero.measix.pilot.data.ai.subassistant

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantPreviewReducerTest {

    private val taskNodeId = Uuid.random()

    private fun userMessage(text: String, id: Uuid = Uuid.random()) =
        UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantMessage(text: String, id: Uuid = Uuid.random()) =
        UIMessage(id = id, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun assistantMessageWithReasoning(text: String, reasoning: String) =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning),
                UIMessagePart.Text(text),
            )
        )

    private fun assistantMessageWithTool(text: String, toolName: String) =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(text),
                UIMessagePart.Tool(
                    toolCallId = "call_${Uuid.random()}",
                    toolName = toolName,
                    input = "{}",
                ),
            )
        )

    // ---- extractTargetTextPartsInRange ----

    @Test
    fun `short text extracted correctly`() {
        val messages = listOf(
            userMessage("Do something", taskNodeId),
            assistantMessage("Done it."),
        )
        val texts = extractTargetTextPartsInRange(messages, taskNodeId)
        assertEquals(1, texts.size)
        assertEquals("Done it.", texts[0])
    }

    @Test
    fun `reasoning parts excluded`() {
        val messages = listOf(
            userMessage("Do something", taskNodeId),
            assistantMessageWithReasoning("Final answer", "thinking..."),
        )
        val texts = extractTargetTextPartsInRange(messages, taskNodeId)
        assertEquals(1, texts.size)
        assertEquals("Final answer", texts[0])
    }

    @Test
    fun `tool parts excluded from text extraction`() {
        val messages = listOf(
            userMessage("Do something", taskNodeId),
            assistantMessageWithTool("Working on it", "workspace_read_file"),
        )
        val texts = extractTargetTextPartsInRange(messages, taskNodeId)
        assertEquals(1, texts.size)
        assertEquals("Working on it", texts[0])
    }

    @Test
    fun `range ends before next user message`() {
        val messages = listOf(
            userMessage("First task", taskNodeId),
            assistantMessage("First answer"),
            userMessage("Second task"),
            assistantMessage("Second answer"),
        )
        val texts = extractTargetTextPartsInRange(messages, taskNodeId)
        assertEquals(1, texts.size)
        assertEquals("First answer", texts[0])
    }

    @Test
    fun `task node not found returns empty`() {
        val messages = listOf(
            userMessage("Do something", Uuid.random()),
            assistantMessage("Done"),
        )
        val texts = extractTargetTextPartsInRange(messages, taskNodeId)
        assertTrue(texts.isEmpty())
    }

    @Test
    fun `multiple assistant messages extracted in reverse order`() {
        val messages = listOf(
            userMessage("Do something", taskNodeId),
            assistantMessage("Part 1"),
            assistantMessage("Part 2"),
            assistantMessage("Part 3"),
        )
        val texts = extractTargetTextPartsInRange(messages, taskNodeId)
        assertEquals(3, texts.size)
        // Reverse order: newest first
        assertEquals("Part 3", texts[0])
        assertEquals("Part 2", texts[1])
        assertEquals("Part 1", texts[2])
    }

    // ---- reducePreviewTexts ----

    @Test
    fun `empty texts produce empty preview`() {
        assertEquals("", reducePreviewTexts(emptyList()))
    }

    @Test
    fun `short text preserved`() {
        val result = reducePreviewTexts(listOf("Hello world"))
        assertEquals("Hello world", result)
    }

    @Test
    fun `multiple texts joined with newline`() {
        val result = reducePreviewTexts(listOf("Part 1", "Part 2"))
        // extractTargetTextPartsInRange 返回逆序列表（最新在前）
        // reducePreviewTexts 从后向前拼接，所以顺序会是 Part 2\nPart 1
        assertEquals("Part 2\nPart 1", result)
    }

    @Test
    fun `CRLF normalized to LF`() {
        val result = reducePreviewTexts(listOf("Line1\r\nLine2\rLine3"))
        assertFalse(result.contains("\r"))
        assertTrue(result.contains("Line1\nLine2\nLine3"))
    }

    @Test
    fun `NUL and control characters removed`() {
        val result = reducePreviewTexts(listOf("Hello\u0000World\u0001\u0002"))
        assertEquals("HelloWorld", result)
    }

    @Test
    fun `excessive blank lines collapsed`() {
        val result = reducePreviewTexts(listOf("Line1\n\n\n\n\nLine2"))
        assertEquals("Line1\n\nLine2", result)
    }

    @Test
    fun `surrogate pairs not split`() {
        // Emoji is a surrogate pair (U+1F600)
        val emoji = "😀".repeat(10)
        val result = reducePreviewTexts(listOf(emoji))
        assertEquals(emoji, result)
    }

    @Test
    fun `long text truncated with ellipsis`() {
        val longText = "A".repeat(3001)  // 超过 MAX_BUFFER_CODEPOINTS (2000)
        val result = reducePreviewTexts(listOf(longText))
        assertTrue(result.startsWith("…\n"))
        assertTrue(result.length < longText.length)
    }

    @Test
    fun `terminal preview short text preserved`() {
        val result = computeTerminalPreview("Short answer.")
        assertEquals("Short answer.", result)
    }

    @Test
    fun `terminal preview limits to max lines`() {
        val multiLine = "Line1\nLine2\nLine3\nLine4\nLine5"
        val result = computeTerminalPreview(multiLine, maxLines = 3)
        assertTrue(result.startsWith("…\n"))
        assertTrue(result.contains("Line3"))
        assertTrue(result.contains("Line4"))
        assertTrue(result.contains("Line5"))
        assertFalse(result.contains("Line1"))
        assertFalse(result.contains("Line2"))
    }

    @Test
    fun `terminal preview empty text returns empty`() {
        assertEquals("", computeTerminalPreview(""))
        assertEquals("", computeTerminalPreview("  \n  \n  "))
    }

    // ---- computeSubAssistantPreview ----

    @Test
    fun `compute preview from messages`() {
        val messages = listOf(
            userMessage("Do something", taskNodeId),
            assistantMessage("Here is my answer."),
        )
        val preview = computeSubAssistantPreview(messages, taskNodeId)
        assertEquals("Here is my answer.", preview)
    }

    @Test
    fun `compute preview empty when no assistant messages`() {
        val messages = listOf(
            userMessage("Do something", taskNodeId),
        )
        val preview = computeSubAssistantPreview(messages, taskNodeId)
        assertEquals("", preview)
    }
}
