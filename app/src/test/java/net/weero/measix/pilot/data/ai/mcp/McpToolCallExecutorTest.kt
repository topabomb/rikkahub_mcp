package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.enterprise.ManagedSnapshotRequired
import net.weero.measix.pilot.utils.userVisibleDiagnostic

import io.mockk.coEvery
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class McpToolCallExecutorTest {
    @Test fun `parent timeout propagates cancellation instead of an unknown tool result`() = runTest {
        val client = mockk<Client>()
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } coAnswers { awaitCancellation() }
        try {
            withTimeout(1) {
                McpToolCallExecutor(mockk()).execute(
                    ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1),
                    "query", JsonObject(emptyMap()), {},
                )
            }
            org.junit.Assert.fail("parent cancellation became a tool result")
        } catch (_: TimeoutCancellationException) {
            // The parent owns its timeout; the MCP tool must not project it as a remote outcome.
        }
    }

    @Test fun `explicit remote error is not reclassified by invalid image or metadata`() = runTest {
        val client = mockk<Client>()
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } returns
            CallToolResult(
                content = listOf(TextContent("remote rejected request"), ImageContent(data = "invalid", mimeType = "image/png")),
                isError = true,
                meta = buildJsonObject { put("com.measix/resolvedTool", "invalid") },
            )
        val outcome = McpToolCallExecutor(mockk()).execute(
            ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1),
            "query", JsonObject(emptyMap()), {},
        ) as McpInvocationOutcome.Failed
        assertEquals(McpInvocationFailureKind.REMOTE, outcome.kind)
        val envelope = Json.parseToJsonElement((outcome.failure.output.first() as UIMessagePart.Text).text).jsonObject
        assertEquals("remote_error", envelope.getValue("reason").jsonPrimitive.content)
        assertEquals("remote rejected request", (outcome.failure.output[1] as UIMessagePart.Text).text)
        assertEquals("[Remote MCP error image omitted]", (outcome.failure.output[2] as UIMessagePart.Text).text)
    }

    @Test fun `local failure after receiving MCP result is not a protocol or remote error`() = runTest {
        val client = mockk<Client>()
        val localError = IOException("local metadata write failed")
        val metadata = buildJsonObject {
            put("com.measix/resolvedTool", buildJsonObject {
                put("gatewayToolId", "id")
                put("name", "tool")
                put("status", "ok")
                put("requestId", "request")
            })
        }
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } returns
            CallToolResult(content = listOf(TextContent("result")), meta = metadata)
        val outcome = McpToolCallExecutor(mockk()).execute(
            ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1),
            "query", JsonObject(emptyMap()), {}, onResolvedTool = { throw localError },
        ) as McpInvocationOutcome.Failed
        assertEquals(McpInvocationFailureKind.RESULT_PROCESSING, outcome.kind)
        assertSame(localError, outcome.failure.cause)
        val envelope = Json.parseToJsonElement((outcome.failure.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("result_processing_failed", envelope.getValue("reason").jsonPrimitive.content)
        org.junit.Assert.assertTrue(envelope.getValue("detail").jsonPrimitive.content.contains("IOException"))
    }

    @Test fun `malformed MCP result metadata is protocol incompatible`() = runTest {
        val client = mockk<Client>()
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } returns
            CallToolResult(
                content = listOf(TextContent("result")),
                meta = buildJsonObject { put("com.measix/resolvedTool", "invalid") },
            )
        val outcome = McpToolCallExecutor(mockk()).execute(
            ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1),
            "query", JsonObject(emptyMap()), {},
        ) as McpInvocationOutcome.Failed
        assertEquals(McpInvocationFailureKind.PROTOCOL, outcome.kind)
        val envelope = Json.parseToJsonElement((outcome.failure.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("protocol_incompatible", envelope.getValue("reason").jsonPrimitive.content)
    }

    @Test fun `SDK timeout is unknown rather than an explicit remote error`() = runTest {
        val client = mockk<Client>()
        val timeout = McpException(code = -32_001, message = "Request timed out")
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } throws timeout
        val outcome = McpToolCallExecutor(mockk()).execute(
            ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1),
            "query", JsonObject(emptyMap()), {},
        ) as McpInvocationOutcome.Failed
        assertEquals(McpInvocationFailureKind.TIMEOUT, outcome.kind)
        assertSame(timeout, outcome.failure.cause)
        val envelope = Json.parseToJsonElement((outcome.failure.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("unknown", envelope.getValue("status").jsonPrimitive.content)
        assertEquals("outcome_unknown", envelope.getValue("reason").jsonPrimitive.content)
        org.junit.Assert.assertTrue(envelope.getValue("detail").jsonPrimitive.content.contains("McpException"))
    }

    @Test fun `managed transport failure retains original cause while public diagnostics redact credentials`() = runTest {
        val client = mockk<Client>()
        val transportError = IOException("https://private.example/mcp?token=private-credential")
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } throws transportError
        val executor = McpToolCallExecutor(mockk())
        val outcome = executor.execute(ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1),
            "query", JsonObject(emptyMap()), {}) as McpInvocationOutcome.Failed
        assertEquals(McpInvocationFailureKind.CONNECTION, outcome.kind)
        assertSame(transportError, outcome.failure.cause)
        val diagnostic = outcome.failure.userVisibleDiagnostic()
        assertFalse(diagnostic.contains("private-credential"))
        org.junit.Assert.assertTrue(diagnostic.contains("IOException"))
        org.junit.Assert.assertTrue(diagnostic.contains("private.example"))
    }

    @Test fun `managed barrier remains typed for the original interaction owner`() = runTest {
        val client = mockk<Client>()
        val barrier = ManagedSnapshotRequired(2, "req_test")
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } throws barrier
        try {
            McpToolCallExecutor(mockk()).execute(ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1),
                "query", JsonObject(emptyMap()), {})
            org.junit.Assert.fail("barrier became a tool result")
        } catch (actual: ManagedSnapshotRequired) { assertSame(barrier, actual) }
    }
}
