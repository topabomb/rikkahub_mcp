package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolErrorProtocolTest {
    @Test
    fun `clear reason omits detail instead of repeating the code`() {
        val envelope = ToolErrorProtocol.envelope("unavailable", "authorization_required")
        assertEquals(setOf("status", "reason"), envelope.keys)
    }

    @Test
    fun `unexpected error keeps deepest cause type and message`() {
        val error = IllegalStateException("outer", IllegalArgumentException("record 111 is missing"))
        assertEquals(
            "IllegalArgumentException: record 111 is missing (from IllegalStateException: outer)",
            ToolErrorProtocol.exceptionDetail(error),
        )
    }

    @Test
    fun `detail is redacted before one line Unicode truncation`() {
        val detail = ToolErrorProtocol.boundedDetail("Authorization: Bearer secret-value\n" + "😀".repeat(180))
        assertFalse(detail.contains("secret-value"))
        assertTrue(detail.contains("<redacted>"))
        assertEquals(128, detail.codePointCount(0, detail.length))
        assertTrue(detail.endsWith("…"))
        assertFalse(detail.contains('\n'))
        assertEquals("Bearer <redacted>", ToolErrorProtocol.redactSecrets("Bearer secret-value"))
    }

    @Test
    fun `quoted credential values with spaces are fully redacted`() {
        val detail = ToolErrorProtocol.boundedDetail(
            "IOException: password=\"hello world\" and secret='another private value' while loading record 111",
        )
        assertFalse(detail.contains("hello world"))
        assertFalse(detail.contains("another private value"))
        assertTrue(detail.contains("IOException"))
        assertTrue(detail.contains("record 111"))
        val unquoted = ToolErrorProtocol.boundedDetail("password=hello world; record 111")
        assertFalse(unquoted.contains("hello world"))
        assertTrue(unquoted.contains("record 111"))
        val token = ToolErrorProtocol.boundedDetail("token=abc while reading /workspace/data.csv")
        assertFalse(token.contains("abc"))
        assertTrue(token.contains("while reading /workspace/data.csv"))
    }
}
