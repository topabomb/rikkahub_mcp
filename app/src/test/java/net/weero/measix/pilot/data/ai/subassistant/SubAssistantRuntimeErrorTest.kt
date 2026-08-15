package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAssistantRuntimeErrorTest {

    @Test
    fun `formatter includes exception type and message`() {
        val detail = formatRuntimeErrorDetail(IllegalStateException("Child conversation not found"))
        assertTrue(detail.startsWith("IllegalStateException: Child conversation not found"))
    }

    @Test
    fun `formatter omits cause chain and uses wrapped HttpException as the headline`() {
        val wrapped = RuntimeException("outer", HttpException("Failed to get response: 429"))
        val nested = RuntimeException("outer", IllegalStateException("inner"))
        assertEquals(
            "HttpException: Failed to get response: 429",
            formatRuntimeErrorDetail(wrapped),
        )
        assertEquals("RuntimeException: outer", formatRuntimeErrorDetail(nested))
        assertFalse(formatRuntimeErrorDetail(nested).contains("Caused by:"))
        assertFalse(formatRuntimeErrorDetail(nested).contains("inner"))
    }

    @Test
    fun `classifies content policy as content_blocked`() {
        val error = RuntimeException(
            "kn4: Content violates usage guidelines. Failed check: SAFETY_CHECK_TYPE_CSAM",
        )
        assertEquals(SUB_ASSISTANT_REASON_CONTENT_BLOCKED, classifySubAssistantFailure(error))
        assertEquals(
            CONTENT_BLOCKED_MODEL_DETAIL,
            modelVisibleFailureDetail(SUB_ASSISTANT_REASON_CONTENT_BLOCKED, error),
        )
        assertFalse(modelVisibleFailureDetail(SUB_ASSISTANT_REASON_CONTENT_BLOCKED, error).contains("CSAM"))
        val gemini = RuntimeException("Prompt feedback: SAFETY")
        assertEquals(SUB_ASSISTANT_REASON_CONTENT_BLOCKED, classifySubAssistantFailure(gemini))
        assertEquals(
            "The model refused this request because it violates the usage policy. Rephrase and try again.",
            resolveSubAssistantErrorBody(
                reason = classifySubAssistantFailure(gemini),
                detail = gemini.message,
                localizedContentBlocked = "The model refused this request because it violates the usage policy. Rephrase and try again.",
            ),
        )
    }

    @Test
    fun `classifies OpenAI content_filter as content_blocked`() {
        val error = HttpException("Response incomplete: content_filter")
        assertEquals(SUB_ASSISTANT_REASON_CONTENT_BLOCKED, classifySubAssistantFailure(error))
        assertEquals(
            CONTENT_BLOCKED_MODEL_DETAIL,
            modelVisibleFailureDetail(classifySubAssistantFailure(error), error),
        )
        assertFalse(
            modelVisibleFailureDetail(classifySubAssistantFailure(error), error).contains("content_filter"),
        )
    }

    @Test
    fun `classifies HttpException as provider_error`() {
        val error = RuntimeException("wrap", HttpException("Failed to get response: 429"))
        assertEquals(SUB_ASSISTANT_REASON_PROVIDER_ERROR, classifySubAssistantFailure(error))
        assertEquals(
            SUB_ASSISTANT_REASON_PROVIDER_ERROR,
            classifySubAssistantFailure(Exception("Unknown error: 403")),
        )
        assertEquals(
            SUB_ASSISTANT_REASON_PROVIDER_ERROR,
            classifySubAssistantFailure(HttpException("Resource has been exhausted")),
        )
    }

    @Test
    fun `fallback text prefers localized reason and completed content`() {
        val blocked = stringResourceStub()
        assertEquals(
            "Provider error\nFailed to get response: 429",
            fallbackSubAssistantOutputText(
                fields = SubAssistantToolResultFields(
                    status = "failed",
                    reason = "provider_error",
                    detail = "HttpException: Failed to get response: 429",
                ),
                localizedReason = "Provider error",
                localizedContentBlocked = blocked,
                rawOutput = """{"status":"failed"}""",
            ),
        )
        assertEquals(
            "Draft the summary.",
            fallbackSubAssistantOutputText(
                fields = SubAssistantToolResultFields(
                    status = "completed",
                    content = "Draft the summary.",
                ),
                localizedReason = null,
                localizedContentBlocked = blocked,
                rawOutput = """{"status":"completed","content":"Draft the summary."}""",
            ),
        )
        assertEquals(
            "step_limit_reached",
            fallbackSubAssistantOutputText(
                fields = SubAssistantToolResultFields(
                    status = "failed",
                    reason = "step_limit_reached",
                ),
                localizedReason = null,
                localizedContentBlocked = blocked,
                rawOutput = """{"status":"failed","reason":"step_limit_reached"}""",
            ),
        )
    }

    private fun stringResourceStub() =
        "The model refused this request because it violates the usage policy. Rephrase and try again."

    @Test
    fun `classifies unknown exceptions as runtime_error`() {
        assertEquals(
            SUB_ASSISTANT_REASON_RUNTIME_ERROR,
            classifySubAssistantFailure(IllegalStateException("Child conversation not found")),
        )
        assertEquals(
            SUB_ASSISTANT_REASON_RUNTIME_ERROR,
            classifySubAssistantFailure(IllegalStateException("There are 429 items in the queue")),
        )
    }

    @Test
    fun `user summary strips type prefix and omits policy details`() {
        assertEquals(
            "Failed to get response: 429",
            userFacingRuntimeErrorSummary("HttpException: Failed to get response: 429"),
        )
        assertNull(
            userFacingRuntimeErrorSummary(
                "kn4: Content violates usage guidelines. Failed check: SAFETY_CHECK_TYPE_CSAM",
            ),
        )
        assertEquals(
            "The model refused this request because it violates the usage policy. Rephrase and try again.",
            resolveSubAssistantErrorBody(
                reason = SUB_ASSISTANT_REASON_CONTENT_BLOCKED,
                detail = "SAFETY_CHECK_TYPE_CSAM",
                localizedContentBlocked = "The model refused this request because it violates the usage policy. Rephrase and try again.",
            ),
        )
    }

    @Test
    fun `parses detail from tool output json`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "assistant_call",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"failed","reason":"runtime_error","detail":"HttpException: boom"}""",
                ),
            ),
        )
        assertEquals("HttpException: boom", parseRuntimeErrorDetailFromToolOutput(tool, Json))
    }

    @Test
    fun `formatter clips to max chars`() {
        val huge = "x".repeat(20_000)
        val detail = formatRuntimeErrorDetail(RuntimeException(huge), maxChars = 256)
        assertTrue(detail.length <= 256)
        assertTrue(detail.endsWith("…"))
    }

    @Test
    fun `clip keeps result within the limit`() {
        val text = (1..200).joinToString("\n") { "line-$it-" + "y".repeat(80) }
        val clipped = clipRuntimeErrorDetail(text, maxChars = 512)
        assertTrue(clipped.length <= 512)
        assertTrue(clipped.endsWith("…"))
        assertFalse(clipped.contains("\n"))
    }

    @Test
    fun `formatter collapses multiline provider bodies to one line`() {
        val body = """
            {
              "error": {
                "message": "quota exceeded"
              }
            }
        """.trimIndent()
        val detail = formatRuntimeErrorDetail(HttpException("Failed to get response: 429 $body"))
        assertFalse(detail.contains("\n"))
        assertFalse(detail.contains("\r"))
        assertTrue(detail.startsWith("HttpException: Failed to get response: 429"))
    }

    @Test
    fun `policy errors still hide check types after collapsing whitespace`() {
        val error = HttpException("Content violates usage guidelines.\nFailed check: SAFETY_CHECK_TYPE_CSAM")
        assertEquals(SUB_ASSISTANT_REASON_CONTENT_BLOCKED, classifySubAssistantFailure(error))
        assertEquals(
            CONTENT_BLOCKED_MODEL_DETAIL,
            modelVisibleFailureDetail(classifySubAssistantFailure(error), error),
        )
        assertFalse(modelVisibleFailureDetail(classifySubAssistantFailure(error), error).contains("CSAM"))
    }
}
