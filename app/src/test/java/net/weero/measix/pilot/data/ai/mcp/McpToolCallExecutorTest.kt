package net.weero.measix.pilot.data.ai.mcp

import io.mockk.coEvery
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class McpToolCallExecutorTest {
    @Test fun `managed transport failure preserves outcome but excludes private diagnostics from the public exception`() = runTest {
        val client = mockk<Client>()
        val transportError = IOException("https://private.example/mcp?token=private-credential")
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } throws transportError
        val executor = McpToolCallExecutor(mockk())
        for (managed in listOf(false, true)) {
            val outcome = executor.execute(ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1, managed),
                "query", JsonObject(emptyMap()), {}) as McpInvocationOutcome.Failed
            assertEquals(McpInvocationFailureKind.CONNECTION, outcome.kind)
            if (managed) {
                assertNull(outcome.failure.cause)
                assertFalse(outcome.failure.stackTraceToString().contains("private-credential"))
            } else assertSame(transportError, outcome.failure.cause)
        }
    }

    @Test fun `managed barrier remains typed for the original interaction owner`() = runTest {
        val client = mockk<Client>()
        val barrier = McpManagedSnapshotRequired(2, "req_test")
        coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } throws barrier
        try {
            McpToolCallExecutor(mockk()).execute(ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1, true),
                "query", JsonObject(emptyMap()), {})
            org.junit.Assert.fail("barrier became a tool result")
        } catch (actual: McpManagedSnapshotRequired) { assertSame(barrier, actual) }
    }
}
