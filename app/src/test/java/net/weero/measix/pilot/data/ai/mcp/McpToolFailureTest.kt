package net.weero.measix.pilot.data.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpToolFailureTest {
    @Test
    fun `local failure projections contain only stable decision fields`() {
        val expected = mapOf(
            McpToolFailureKind.TOOL_UNAVAILABLE to Triple("unavailable", "tool_unavailable", null),
            McpToolFailureKind.SERVER_UNAVAILABLE to Triple("unavailable", "server_unavailable", "Try again later."),
            McpToolFailureKind.AUTHORIZATION_REQUIRED to Triple(
                "unavailable",
                "authorization_required",
                "User authorization is required.",
            ),
            McpToolFailureKind.PROTOCOL_INCOMPATIBLE to Triple("failed", "protocol_incompatible", null),
            McpToolFailureKind.OUTCOME_UNKNOWN to Triple(
                "unknown",
                "outcome_unknown",
                "The request may have completed.",
            ),
        )

        expected.forEach { (kind, values) ->
            val projected = McpToolFailureProjector.project(kind)
            val json = Json.parseToJsonElement(
                (projected.output.single() as UIMessagePart.Text).text
            ).jsonObject
            val expectedKeys = if (values.third != null) {
                setOf("status", "reason", "message")
            } else {
                setOf("status", "reason")
            }
            assertEquals(expectedKeys, json.keys)
            assertEquals(values.first, json.getValue("status").jsonPrimitive.content)
            assertEquals(values.second, json.getValue("reason").jsonPrimitive.content)
            assertEquals(values.third, json["message"]?.jsonPrimitive?.content)
            assertFalse(json.containsKey("detail"))
        }
    }

    @Test
    fun `remote error preserves remote content and structured result without local diagnostics`() {
        val remote = UIMessagePart.Text("remote detail")
        val structured = buildJsonObject { put("code", JsonPrimitive("REMOTE_FAILURE")) }

        val projected = McpToolFailureProjector.project(
            kind = McpToolFailureKind.REMOTE_ERROR,
            remoteContent = listOf(remote),
            structuredContent = structured,
        )
        val envelope = Json.parseToJsonElement(
            (projected.output.first() as UIMessagePart.Text).text
        ).jsonObject

        assertEquals(setOf("status", "reason", "structured_content"), envelope.keys)
        assertEquals("failed", envelope.getValue("status").jsonPrimitive.content)
        assertEquals("remote_error", envelope.getValue("reason").jsonPrimitive.content)
        assertEquals(structured, envelope.getValue("structured_content") as JsonObject)
        assertEquals(remote, projected.output.last())
    }

    @Test
    fun `empty remote error includes one actionable fallback message`() {
        val projected = McpToolFailureProjector.project(McpToolFailureKind.REMOTE_ERROR)
        val json = Json.parseToJsonElement(
            (projected.output.single() as UIMessagePart.Text).text
        ).jsonObject

        assertEquals(setOf("status", "reason", "message"), json.keys)
        assertEquals("The MCP server reported an error.", json.getValue("message").jsonPrimitive.content)
    }
}
