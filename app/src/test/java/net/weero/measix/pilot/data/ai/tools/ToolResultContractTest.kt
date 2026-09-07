package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolResultContractTest {
    @Test
    fun `built in failure helper emits one compact stable envelope and typed failure`() {
        val failure = try {
            failToolResult("no_permission", "Permission is required.")
        } catch (error: ToolExecutionFailure) {
            error
        }

        val payload = Json.parseToJsonElement(
            (failure.output.single() as UIMessagePart.Text).text,
        ).jsonObject
        assertEquals("failed", payload["status"]!!.jsonPrimitive.content)
        assertEquals("no_permission", payload["reason"]!!.jsonPrimitive.content)
        assertEquals("Permission is required.", payload["detail"]!!.jsonPrimitive.content)
        assertEquals(setOf("status", "reason", "detail"), payload.keys)
    }
}
