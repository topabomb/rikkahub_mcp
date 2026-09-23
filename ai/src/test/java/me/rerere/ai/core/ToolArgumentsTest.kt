package me.rerere.ai.core

import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentsTest {
    private fun tool(validator: (kotlinx.serialization.json.JsonElement) -> JsonObject? = { null }) = Tool(
        name = "test",
        description = "test",
        interactionRequirement = { error("Parsing must not request interaction") },
        validateArguments = validator,
        execute = { error("Parsing must not execute") },
    )

    @Test
    fun `empty provider argument buffer means an empty object`() {
        listOf("", "  \n", "{}").forEach { input ->
            assertEquals(JsonObject(emptyMap()), tool().parseArguments(input, Json))
        }
    }

    @Test
    fun `malformed and non object inputs reject before domain validation`() {
        val tool = tool { error("Invalid envelope must not reach the tool") }
        listOf("{", "{\"path\":", "[]", "null", "true", "42", "\"text\"").forEach { input ->
            val failure = assertThrows(ToolArgumentsException::class.java) { tool.parseArguments(input, Json) }
            assertTrue((failure.output.single() as UIMessagePart.Text).text.contains("invalid_arguments"))
        }
    }

    @Test
    fun `domain validation becomes the common bounded failure envelope`() {
        val details = Json.parseToJsonElement("{\"error\":\"invalid_arguments\",\"field\":\"path\"}").jsonObject
        val failure = assertThrows(ToolArgumentsException::class.java) {
            tool { details }.parseArguments("{}", Json)
        }
        val result = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(setOf("status", "reason", "detail"), result.keys)
        assertEquals("failed", result.getValue("status").let { (it as JsonPrimitive).content })
        assertEquals("invalid_arguments", result.getValue("reason").let { (it as JsonPrimitive).content })
        assertEquals("path", result.getValue("detail").let { (it as JsonPrimitive).content })
    }

    @Test
    fun `domain status rejection receives an explicit detail`() {
        val details = Json.parseToJsonElement("{\"status\":\"failed\",\"reason\":\"invalid_arguments\"}").jsonObject
        val failure = assertThrows(ToolArgumentsException::class.java) { tool { details }.parseArguments("{}", Json) }
        val result = Json.parseToJsonElement((failure.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(setOf("status", "reason", "detail"), result.keys)
        assertEquals(JsonPrimitive("failed"), result["status"])
        assertEquals(JsonPrimitive("invalid_arguments"), result["reason"])
        assertEquals(JsonPrimitive("Invalid tool arguments."), result["detail"])
    }

    @Test
    fun `validator cancellation is never converted to input failure`() {
        val cancellation = CancellationException("cancelled")
        val failure = assertThrows(CancellationException::class.java) {
            tool { throw cancellation }.parseArguments("{}", Json)
        }
        assertSame(cancellation, failure)
    }
}
