package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.utils.StrictJsonValue

import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class McpCatalogWireTest {
    @Test fun `capture uses original ID first response and releases cancelled requests`() = runTest {
        val wire = McpCatalogWire()
        val request = JSONRPCRequest(id = "page", method = "tools/list")
        val page = McpToolPageCapture.read {
            wire.bind(request)
            wire.decode(response("unrelated", "wrong"))
            wire.decode(response("page", "first"))
            wire.decode(response("page", "duplicate"))
        }
        assertEquals("first", page.tools.single().name)
        val entered = CompletableDeferred<Unit>()
        val abandoned = async {
            McpToolPageCapture.read {
                wire.bind(request)
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        abandoned.cancelAndJoin()
        wire.decode(response("page", "late"))
        val subsequent = McpToolPageCapture.read {
            wire.bind(request)
            wire.decode(response("page", "new"))
        }
        assertEquals("new", subsequent.tools.single().name)
        try {
            McpToolPageCapture.read { wire.bind(request) }
            fail("Missing raw response must not fall back to typed SDK tools")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `strict frame rejects duplicate decoded keys nested duplicates and invalid scalars`() {
        listOf(
            """{"id":"a","\u0069d":"b"}""",
            """{"tools":[{"name":"x","inputSchema":{"type":"object","type":"array"}}]}""",
            """{"value":01}""", """{"value":NaN}""", """{"value":truth}""",
            """{"value":1,}""", """{"value":[1,]}""", "{} {}",
        ).forEach { raw ->
            try { StrictJsonValue.parse(raw, MAX_MCP_FRAME_BYTES); fail("Accepted invalid JSON: $raw") }
            catch (_: IllegalStateException) { }
            catch (_: kotlinx.serialization.SerializationException) { }
        }
        assertEquals(Json.parseToJsonElement("""{"a":[1,-0,1.2e3,true,null,"中\\文"]}"""),
            StrictJsonValue.parse("""{"a":[1,-0,1.2e3,true,null,"中\\文"]}""", MAX_MCP_FRAME_BYTES))
        val overBytes = "\"" + "中".repeat(MAX_MCP_FRAME_BYTES / 3) + "\""
        try { StrictJsonValue.parse(overBytes, MAX_MCP_FRAME_BYTES); fail("Must enforce UTF-8 bytes") }
        catch (_: IllegalStateException) { }
    }

    @Test fun `released personal tool encoding and digest remain byte identical`() {
        val encoded = """[{"name":"old","description":null,"inputSchema":{"type":"object"}}]"""
        val tools = JsonInstant.decodeFromString<List<McpCatalogTool>>(encoded)
        assertEquals(encoded, JsonInstant.encodeToString(tools))
        assertEquals(sha256(encoded), sha256(JsonInstant.encodeToString(tools)))
        assertNull(tools.single().description)
    }

    private fun response(id: String, name: String) =
        """{"jsonrpc":"2.0","id":"$id","result":{"tools":[{"name":"$name","inputSchema":{"type":"object"}}]}}"""
}
