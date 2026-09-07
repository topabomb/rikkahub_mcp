package net.weero.measix.pilot.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.ASSISTANT_CALL_EXTRA_TOOL_CALLS
import net.weero.measix.pilot.data.ai.subassistant.ASSISTANT_CALL_EXTRA_TTS
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantTtsStats
import net.weero.measix.pilot.data.ai.subassistant.collectSubAssistantCallOutputs
import net.weero.measix.pilot.data.ai.subassistant.extractDeliverableArtifacts
import net.weero.measix.pilot.data.ai.subassistant.extractFinalAnswerInternal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

private fun checkNonTextOutputInternal(messages: List<UIMessage>, childTaskNodeId: Uuid): Boolean =
    extractDeliverableArtifacts(messages, childTaskNodeId).hasNonTextOutput

private fun collectRunToolCalls(messages: List<UIMessage>, childTaskNodeId: Uuid): List<Pair<String, Int>> =
    collectSubAssistantCallOutputs(messages, childTaskNodeId, setOf(ASSISTANT_CALL_EXTRA_TOOL_CALLS)).toolCalls

private fun collectRunTtsTexts(messages: List<UIMessage>, childTaskNodeId: Uuid): List<String> =
    collectSubAssistantCallOutputs(messages, childTaskNodeId, setOf(ASSISTANT_CALL_EXTRA_TTS)).ttsTexts

private fun collectRunTtsStats(messages: List<UIMessage>, childTaskNodeId: Uuid): SubAssistantTtsStats? =
    collectSubAssistantCallOutputs(messages, childTaskNodeId, emptySet()).ttsStats

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
        parts = if (parts.firstOrNull() is UIMessagePart.Step) parts.toList() else listOf(step()) + parts,

    )

    private fun step(ordinal: Int = 0, outcome: me.rerere.ai.ui.StepOutcome? = me.rerere.ai.ui.StepOutcome.Final) =
        UIMessagePart.Step(Uuid.random(), ordinal, kotlin.time.Instant.fromEpochMilliseconds(1), outcome = outcome)

    private fun executedTool(name: String = "search") = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = Uuid.random().toString(),
        toolName = name,
        input = "{}",
        output = listOf(UIMessagePart.Text("tool result")),
        resultStatus = me.rerere.ai.ui.ToolResultStatus.COMPLETED,
    )

    private fun pendingTool(name: String = "search") = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = Uuid.random().toString(),
        toolName = name,
        input = "{}",
    )

    @Test
    fun `final step owns the answer and excludes earlier reasoning and tool sampling text`() {
        val first = step(outcome = me.rerere.ai.ui.StepOutcome.Continue)
        val final = step(1)
        val messages = listOf(taskMessage("task"), assistantMessage(
            first, UIMessagePart.Text("Searching"), executedTool().copy(stepId = first.stepId),
            final, UIMessagePart.Reasoning("private"), UIMessagePart.Text("Answer 42"), UIMessagePart.Text("Details"),
        ))
        assertEquals("Answer 42\nDetails", extractFinalAnswerInternal(messages, childTaskNodeId))
    }

    @Test
    fun `unfinished and interrupted steps cannot fabricate final answers`() {
        for (outcome in listOf(null, me.rerere.ai.ui.StepOutcome.Continue, me.rerere.ai.ui.StepOutcome.Interrupted)) {
            val messages = listOf(taskMessage("task"), assistantMessage(step(outcome = outcome), UIMessagePart.Text("Working")))
            assertEquals("", extractFinalAnswerInternal(messages, childTaskNodeId))
        }
    }

    @Test
    fun `empty final step does not reuse an earlier sampling answer`() {
        val messages = listOf(taskMessage("task"), assistantMessage(
            step(outcome = me.rerere.ai.ui.StepOutcome.Continue), UIMessagePart.Text("Draft"),
            step(1), UIMessagePart.Reasoning("no visible output"), UIMessagePart.Text(" "),
        ))
        assertEquals("", extractFinalAnswerInternal(messages, childTaskNodeId))
    }

    @Test
    fun `range ends before the next user task`() {
        val messages = listOf(taskMessage("first"), assistantMessage(UIMessagePart.Text("First answer")),
            userMessage("second"), assistantMessage(UIMessagePart.Text("Second answer")))
        assertEquals("First answer", extractFinalAnswerInternal(messages, childTaskNodeId))
        assertEquals("", extractFinalAnswerInternal(emptyList(), childTaskNodeId))
    }

    @Test
    fun `last assistant final step is authoritative`() {
        val messages = listOf(taskMessage("task"), assistantMessage(UIMessagePart.Text("Old answer")),
            assistantMessage(UIMessagePart.Text("Final answer")))
        assertEquals("Final answer", extractFinalAnswerInternal(messages, childTaskNodeId))
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
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tts-1",
            toolName = "text_to_speech",
            input = """{"text":"Hello there."}""",
            output = listOf(UIMessagePart.Text("""{"success":true}""")),
        )
        val blank = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tts-2",
            toolName = "text_to_speech",
            input = """{"text":"  "}""",
        )
        val second = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tts-3",
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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "tts-1",
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
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "g1",
                    toolName = "generate_image",
                    resultStatus = me.rerere.ai.ui.ToolResultStatus.COMPLETED,
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
