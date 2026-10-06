package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ToolClientDiagnosticTest {
    @Test fun `provider message rejects client diagnostics even inside nested tool results`() {
        val tool = UIMessagePart.Tool(Uuid.random(), Uuid.random(), "call", "tool", "{}",
            clientDiagnostic = ToolClientDiagnostic("client-only detail"))
        assertThrows(IllegalArgumentException::class.java) {
            me.rerere.ai.core.ModelRequestMessage(me.rerere.ai.core.MessageRole.ASSISTANT, listOf(tool))
        }
        val outer = tool.copy(clientDiagnostic = null, output = listOf(tool))
        assertThrows(IllegalArgumentException::class.java) {
            me.rerere.ai.core.ModelRequestMessage(me.rerere.ai.core.MessageRole.ASSISTANT, listOf(outer))
        }
    }

    @Test fun `new diagnostic survives transcript serialization while old tools default to no diagnostic`() {
        val diagnostic = ToolClientDiagnostic("IOException: " + "x".repeat(600) + " tail\nCaused by: cause 111")
        val tool = UIMessagePart.Tool(Uuid.random(), Uuid.random(), "call", "tool", "{}",
            resultStatus = ToolResultStatus.FAILED, output = listOf(UIMessagePart.Text("bounded result")), clientDiagnostic = diagnostic)
        val encoded = Json.encodeToString(UIMessagePart.serializer(), tool)
        assertEquals(tool, Json.decodeFromString(UIMessagePart.serializer(), encoded))
        val old = Json.encodeToString(UIMessagePart.serializer(), tool.copy(clientDiagnostic = null))
        assertFalse(old.contains("clientDiagnostic"))
        assertNull((Json.decodeFromString(UIMessagePart.serializer(), old) as UIMessagePart.Tool).clientDiagnostic)
        assertEquals(diagnostic, tool.merge(tool.copy(input = "", toolName = "", output = emptyList(), clientDiagnostic = null)).clientDiagnostic)
    }
}
