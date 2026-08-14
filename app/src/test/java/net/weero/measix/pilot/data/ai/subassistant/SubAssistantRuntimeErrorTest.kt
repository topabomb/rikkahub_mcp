package net.weero.measix.pilot.data.ai.subassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAssistantRuntimeErrorTest {

    @Test
    fun `formatter includes exception type and message`() {
        val detail = formatRuntimeErrorDetail(IllegalStateException("Child conversation not found"))
        assertTrue(detail.startsWith("IllegalStateException: Child conversation not found"))
    }

    @Test
    fun `formatter includes cause chain`() {
        val error = RuntimeException("outer", IllegalStateException("inner"))
        val detail = formatRuntimeErrorDetail(error)
        assertTrue(detail.contains("RuntimeException: outer"))
        assertTrue(detail.contains("Caused by: IllegalStateException: inner"))
    }

    @Test
    fun `formatter keeps app frames and skips coroutine frames`() {
        val error = RuntimeException("boom")
        error.stackTrace = arrayOf(
            StackTraceElement("kotlinx.coroutines.DispatchedTask", "run", "DispatchedTask.kt", 1),
            StackTraceElement(
                "net.weero.measix.pilot.service.SubAssistantCoordinator",
                "executeCall",
                "SubAssistantCoordinator.kt",
                2,
            ),
            StackTraceElement("okhttp3.internal.http.CallServerInterceptor", "intercept", "CallServerInterceptor.kt", 3),
        )
        val detail = formatRuntimeErrorDetail(error)
        assertTrue(detail.contains("SubAssistantCoordinator.executeCall"))
        assertFalse(detail.contains("DispatchedTask"))
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
    }
}
