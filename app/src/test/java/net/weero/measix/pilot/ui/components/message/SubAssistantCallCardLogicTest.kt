package net.weero.measix.pilot.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAssistantCallCardLogicTest {
    @Test
    fun `tool fallback removes control characters and formats separators`() {
        assertEquals("mcp server search", sanitizeToolNameForDisplay("mcp__server\u0000_search"))
    }

    @Test
    fun `tool fallback clips by code point without splitting emoji`() {
        val result = sanitizeToolNameForDisplay("😀".repeat(80))

        assertEquals(65, result.codePointCount(0, result.length))
        assertTrue(result.endsWith("…"))
    }
}
