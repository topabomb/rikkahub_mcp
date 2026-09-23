package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JavascriptToolTest {
    @Test
    fun `missing or non-string code is rejected before QuickJS starts`() {
        val tool = buildJavascriptTool()
        for (input in listOf("{}", "{\"code\":null}", "{\"code\":5}")) {
            val error = tool.validateArguments(Json.parseToJsonElement(input))!!
            assertEquals("invalid_arguments", error.getValue("reason").jsonPrimitive.content)
            assertEquals("code must be a string.", error.getValue("detail").jsonPrimitive.content)
        }
    }

    @Test
    fun `local range tools reject malformed fields before permission checks`() {
        val invalidBegin = Json.parseToJsonElement("{\"begin\":{}}")
        val invalidRange = Json.parseToJsonElement("{\"range\":\"yesterday\"}")
        val invalidTop = Json.parseToJsonElement("{\"top\":\"many\"}")
        assertEquals("invalid_arguments", validateLocalTimeRangeArguments(
            invalidBegin, setOf("today", "week"), "top",
        )!!.getValue("reason").jsonPrimitive.content)
        assertEquals("invalid_arguments", validateLocalTimeRangeArguments(
            invalidRange, setOf("today", "week"), "top",
        )!!.getValue("reason").jsonPrimitive.content)
        assertEquals("invalid_arguments", validateLocalTimeRangeArguments(
            invalidTop, setOf("today", "week"), "top",
        )!!.getValue("reason").jsonPrimitive.content)
    }

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
