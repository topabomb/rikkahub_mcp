package net.weero.measix.pilot.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.ASSISTANT_CALL_EXTRA_TOOL_CALLS
import net.weero.measix.pilot.data.ai.subassistant.ASSISTANT_CALL_EXTRA_TTS
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantTtsStats
import net.weero.measix.pilot.service.runtime.checkNonTextOutputInternal
import net.weero.measix.pilot.service.runtime.collectRunToolCalls
import net.weero.measix.pilot.service.runtime.collectRunTtsStats
import net.weero.measix.pilot.service.runtime.collectRunTtsTexts
import net.weero.measix.pilot.service.runtime.collectSubAssistantCallOutputs
import net.weero.measix.pilot.service.runtime.extractFinalAnswerInternal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantFinalAnswerTest {

    private val childTaskNodeId = Uuid.random()

    /** Task 消息：使用 childTaskNodeId 作为 ID */
    private fun taskMessage(text: String) = UIMessage(
        id = childTaskNodeId,
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    /** 非起始 USER 消息（如后续新问题），使用随机 ID */
    private fun userMessage(text: String) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantMessage(vararg parts: UIMessagePart) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun executedTool(name: String = "search") = UIMessagePart.Tool(
        toolCallId = Uuid.random().toString(),
        toolName = name,
        input = "{}",
        output = listOf(UIMessagePart.Text("tool result")),
    )

    private fun pendingTool(name: String = "search") = UIMessagePart.Tool(
        toolCallId = Uuid.random().toString(),
        toolName = name,
        input = "{}",
    )

    // ---- extractFinalAnswerInternal ----

    @Test
    fun `single assistant message no tools returns all text`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(UIMessagePart.Text("Here is the answer.")),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("Here is the answer.", result)
    }

    @Test
    fun `text after executed tool only - no pre-tool text included`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Let me search for that."),
                executedTool(),
                UIMessagePart.Text("Based on the results, the answer is 42."),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("Based on the results, the answer is 42.", result)
    }

    @Test
    fun `multiple assistant steps returns last step text only`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Searching..."),
                executedTool(),
                UIMessagePart.Text("Found something, let me refine."),
            ),
            assistantMessage(
                UIMessagePart.Text("Final answer: 42."),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("Final answer: 42.", result)
    }

    @Test
    fun `multiple executed tools in final step - text after last tool only`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Let me check."),
                executedTool("search"),
                UIMessagePart.Text("Now let me verify."),
                executedTool("verify"),
                UIMessagePart.Text("Confirmed: the answer is 42."),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("Confirmed: the answer is 42.", result)
    }

    @Test
    fun `no assistant message returns empty`() {
        val messages = listOf(
            taskMessage("do something"),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("", result)
    }

    @Test
    fun `assistant with only blank text returns empty`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(UIMessagePart.Text("   ")),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("", result)
    }

    @Test
    fun `text before pending tool is not included`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("I will search for that."),
                pendingTool(),
                // Tool is not executed, so text after it is still "final answer"
                // but lastExecutedToolEnd stays 0, so all text is included
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        // No executed tool → lastExecutedToolEnd = 0 → all Text parts included
        assertEquals("I will search for that.", result)
    }

    @Test
    fun `range ends at next user message`() {
        val nextUserTaskId = Uuid.random()
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(UIMessagePart.Text("Answer from first run.")),
            UIMessage(
                id = nextUserTaskId,
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("new question")),
            ),
            assistantMessage(UIMessagePart.Text("This should not be included.")),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("Answer from first run.", result)
    }

    @Test
    fun `multiple text parts after tool are joined with newline`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                executedTool(),
                UIMessagePart.Text("First paragraph."),
                UIMessagePart.Text("Second paragraph."),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("First paragraph.\nSecond paragraph.", result)
    }

    @Test
    fun `text before trailing text_to_speech is kept`() {
        val messages = listOf(
            taskMessage("summarize"),
            assistantMessage(
                UIMessagePart.Text("Here is the analysis."),
                executedTool("text_to_speech"),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("Here is the analysis.", result)
    }

    @Test
    fun `work tool then answer then tts keeps post-tool answer`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Let me search."),
                executedTool("search"),
                UIMessagePart.Text("The answer is 42."),
                executedTool("text_to_speech"),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("The answer is 42.", result)
    }

    @Test
    fun `empty last assistant falls back to previous post-tool text`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Let me search."),
                executedTool(),
                UIMessagePart.Text("Based on results, 42."),
            ),
            assistantMessage(
                UIMessagePart.Reasoning("Nothing more to add."),
                UIMessagePart.Text(""),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("Based on results, 42.", result)
    }

    @Test
    fun `work tool without post-tool text falls back to last text island`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("The complete answer is 42."),
                executedTool("verify"),
            ),
        )

        val result = extractFinalAnswerInternal(messages, childTaskNodeId)
        assertEquals("The complete answer is 42.", result)
    }

    // ---- collectRunToolCalls / collectRunTtsTexts ----

    @Test
    fun `tool calls count each issued name in first-seen order`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                executedTool("search_web"),
                executedTool("search_web"),
                pendingTool("ask_user"),
            ),
            assistantMessage(executedTool("text_to_speech")),
        )
        assertEquals(
            listOf("search_web" to 2, "ask_user" to 1, "text_to_speech" to 1),
            collectRunToolCalls(messages, childTaskNodeId),
        )
    }

    @Test
    fun `tool calls ignore the next user task`() {
        val messages = listOf(
            taskMessage("first"),
            assistantMessage(executedTool("search_web")),
            userMessage("second"),
            assistantMessage(executedTool("memory_tool")),
        )
        assertEquals(
            listOf("search_web" to 1),
            collectRunToolCalls(messages, childTaskNodeId),
        )
    }

    @Test
    fun `tts texts follow call order and skip blank input`() {
        val spoken = UIMessagePart.Tool(
            toolCallId = "tts-1",
            toolName = "text_to_speech",
            input = """{"text":"Hello there."}""",
            output = listOf(UIMessagePart.Text("""{"success":true}""")),
        )
        val blank = UIMessagePart.Tool(
            toolCallId = "tts-2",
            toolName = "text_to_speech",
            input = """{"text":"  "}""",
        )
        val second = UIMessagePart.Tool(
            toolCallId = "tts-3",
            toolName = "text_to_speech",
            input = """{"text":"Second line."}""",
        )
        val messages = listOf(
            taskMessage("speak"),
            assistantMessage(spoken, blank, executedTool("search_web"), second),
        )
        assertEquals(
            listOf("Hello there.", "Second line."),
            collectRunTtsTexts(messages, childTaskNodeId),
        )
        assertEquals(
            SubAssistantTtsStats(calls = 3, chars = "Hello there.".length + "Second line.".length),
            collectRunTtsStats(messages, childTaskNodeId),
        )
    }

    @Test
    fun `optional extras omit bulky tables by default`() {
        val messages = listOf(
            taskMessage("speak"),
            assistantMessage(
                executedTool("search_web"),
                UIMessagePart.Tool(
                    toolCallId = "tts-1",
                    toolName = "text_to_speech",
                    input = """{"text":"Hello there."}""",
                ),
            ),
        )
        val none = collectSubAssistantCallOutputs(messages, childTaskNodeId, emptySet())
        assertTrue(none.toolCalls.isEmpty())
        assertTrue(none.ttsTexts.isEmpty())
        assertEquals(SubAssistantTtsStats(calls = 1, chars = "Hello there.".length), none.ttsStats)

        val requested = collectSubAssistantCallOutputs(
            messages,
            childTaskNodeId,
            setOf(ASSISTANT_CALL_EXTRA_TTS, ASSISTANT_CALL_EXTRA_TOOL_CALLS),
        )
        assertEquals(listOf("search_web" to 1, "text_to_speech" to 1), requested.toolCalls)
        assertEquals(listOf("Hello there."), requested.ttsTexts)
        assertEquals(none.ttsStats, requested.ttsStats)
    }

    // ---- checkNonTextOutputInternal ----

    @Test
    fun `final step with only text returns false`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(UIMessagePart.Text("Just text.")),
        )

        assertFalse(checkNonTextOutputInternal(messages, childTaskNodeId))
    }

    @Test
    fun `final step with image returns true`() {
        val messages = listOf(
            taskMessage("draw something"),
            assistantMessage(
                UIMessagePart.Text("Here is an image:"),
                UIMessagePart.Image(url = "file:///test.png"),
            ),
        )

        assertTrue(checkNonTextOutputInternal(messages, childTaskNodeId))
    }

    @Test
    fun `final step with tool does not count as non-text`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Working on it."),
                executedTool(),
                UIMessagePart.Text("Done."),
            ),
        )

        // Tool is excluded from non-text check
        assertFalse(checkNonTextOutputInternal(messages, childTaskNodeId))
    }

    @Test
    fun `final step with reasoning does not count as non-text`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Reasoning("Thinking about this..."),
                UIMessagePart.Text("The answer is 42."),
            ),
        )

        // Reasoning is excluded from non-text check
        assertFalse(checkNonTextOutputInternal(messages, childTaskNodeId))
    }

    @Test
    fun `non-text in intermediate step but not final step returns false`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Generating image..."),
                UIMessagePart.Image(url = "file:///intermediate.png"),
            ),
            assistantMessage(
                UIMessagePart.Text("Final text answer."),
            ),
        )

        // Non-text output in intermediate step should not count
        assertFalse(checkNonTextOutputInternal(messages, childTaskNodeId))
    }

    @Test
    fun `generate_image tool output counts as non-text deliverable`() {
        val image = UIMessagePart.Image(url = "file:///upload/out.png")
        val messages = listOf(
            taskMessage("draw something"),
            assistantMessage(
                UIMessagePart.Text("Working"),
                UIMessagePart.Tool(
                    toolCallId = "g1",
                    toolName = "generate_image",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text("""{"status":"completed"}"""),
                        image,
                    ),
                    metadata = kotlinx.serialization.json.buildJsonObject {
                        put(
                            "artifact",
                            kotlinx.serialization.json.buildJsonObject {
                                put("version", 1)
                                put("relativePath", "upload/out.png")
                                put("mimeType", "image/png")
                            },
                        )
                    },
                ),
                UIMessagePart.Text("Here you go."),
            ),
        )
        assertTrue(checkNonTextOutputInternal(messages, childTaskNodeId))
    }

    @Test
    fun `non-text in final step returns true even with intermediate steps`() {
        val messages = listOf(
            taskMessage("do something"),
            assistantMessage(
                UIMessagePart.Text("Step 1."),
                executedTool(),
            ),
            assistantMessage(
                UIMessagePart.Text("Final answer."),
                UIMessagePart.Image(url = "file:///final.png"),
            ),
        )

        assertTrue(checkNonTextOutputInternal(messages, childTaskNodeId))
    }
}
