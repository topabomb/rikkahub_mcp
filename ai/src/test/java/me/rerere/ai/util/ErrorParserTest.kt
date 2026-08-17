package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserTest {
    @Test
    fun `structured json uses the nested message`() {
        val error = formatProviderHttpError(
            400,
            """{"error":{"message":"Prompt was blocked\nby safety"}}""",
        )
        assertEquals("Failed to get response: 400 Prompt was blocked by safety", error.message)
        assertFalse(error.message.orEmpty().contains("\n"))
    }

    @Test
    fun `html and unparsable bodies stay as a stable status line`() {
        assertEquals(
            "Failed to get response: 502",
            formatProviderHttpError(502, "<html><body>Bad Gateway</body></html>").message,
        )
        assertEquals(
            "Failed to get response: 500",
            formatProviderHttpError(500, "not-json {{{").message,
        )
        assertNull(parseStructuredHttpError("<html>oops</html>"))
    }

    @Test
    fun `long json messages are still extracted without the raw body`() {
        val huge = "x".repeat(20_000)
        val error = formatProviderHttpError(429, """{"error":{"message":"$huge"}}""")
        assertTrue(error.message.orEmpty().startsWith("Failed to get response: 429 "))
        assertFalse(error.message.orEmpty().contains("{"))
        assertTrue(error.message.orEmpty().length <= 3_000)
    }

    @Test
    fun `envelope keeps code type and status`() {
        val error = formatProviderHttpError(
            400,
            """{"error":{"message":"Your request was rejected as a result of our safety system.","type":"image_generation_user_error","code":"moderation_blocked"}}""",
        )
        assertEquals(400, error.statusCode)
        assertEquals("moderation_blocked", error.errorCode)
        assertEquals("image_generation_user_error", error.errorType)
        assertTrue(error.message.orEmpty().contains("moderation_blocked"))
        assertTrue(error.message.orEmpty().contains("safety system"))
    }

    @Test
    fun `xAI style top level message and code are extracted`() {
        val error = formatProviderHttpError(
            401,
            """{"code":"unauthorized","message":"No authorization header or an invalid authorization token was provided."}""",
        )
        assertEquals("unauthorized", error.errorCode)
        assertTrue(error.message.orEmpty().contains("authorization"))
    }
}
