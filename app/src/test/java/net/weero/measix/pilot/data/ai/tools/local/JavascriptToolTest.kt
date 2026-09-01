package net.weero.measix.pilot.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JavascriptToolTest {
    @Test
    fun `output preserves console entries as physical lines`() {
        val output = formatJavascriptOutput(
            logs = listOf("[LOG] first", "[WARN] second"),
            result = "42",
        )

        assertEquals(
            listOf("[console]", "[LOG] first", "[WARN] second", "[result]", "42"),
            output.lines(),
        )
        assertFalse("\\n" in output)
    }

    @Test
    fun `output without console starts with result section`() {
        assertEquals("[result]\nnull", formatJavascriptOutput(emptyList(), "null"))
    }
}
