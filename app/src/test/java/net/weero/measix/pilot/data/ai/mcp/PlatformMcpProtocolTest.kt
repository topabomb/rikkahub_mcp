package net.weero.measix.pilot.data.ai.mcp

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PlatformMcpProtocolTest {
    @Test fun `platform SDK discovery and call keep route and catalog while refreshing bearer per HTTP request`() = runBlocking {
        val received = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val resource = "mcp_${Uuid.random()}"
        val path = "/runtime/v1/resources/$resource/mcp/v1"
        server.createContext("/") { exchange -> exchange.use {
            received += Triple(it.requestURI.toString(), it.requestHeaders.getFirst("Authorization"),
                it.requestHeaders.getFirst("X-Measix-Interaction-Id"))
            if (it.requestMethod == "GET") it.sendResponseHeaders(405, -1) else {
                val request = Json.parseToJsonElement(it.requestBody.bufferedReader().readText()).jsonObject
                val result = when (request["method"]!!.jsonPrimitive.content) {
                    "initialize" -> """{"protocolVersion":"2025-11-25","serverInfo":{"name":"platform","version":"1"},"capabilities":{"tools":{}}}"""
                    "notifications/initialized" -> null
                    "tools/list" -> """{"tools":[{"name":"read_status","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}]}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"ready"}]}"""
                    else -> error("unexpected method")
                }
                if (result == null) it.sendResponseHeaders(202, -1) else {
                    val body = """{"jsonrpc":"2.0","id":${request["id"]},"result":$result}""".toByteArray()
                    it.responseHeaders.set("Content-Type", "application/json")
                    it.sendResponseHeaders(200, body.size.toLong()); it.responseBody.write(body)
                }
            }
        } }
        server.start()
        val connection = PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformDiscovery(
            PlatformDiscoveryProduct.MEASIX_AGENT_PLATFORM, "1", "dep_${Uuid.random()}", "test",
            "/api/client/v1", "/runtime/v1", listOf(4)))
        val identity = exampleEnterprisePackage().identity.copy(authority = connection.authority)
        val source = EnterpriseExecution.Platform(connection, "rel_${Uuid.random()}", "sha256:" + "a".repeat(64), mapOf(resource to "/mcp/v1"))
        val token = AtomicReference("first-token")
        val interaction = "int_${Uuid.random()}"
        val definition = McpConnectionDefinition.ManagedPlatform(RealmAccess.Enterprise(identity.scope, "ses_${Uuid.random()}"),
            identity.reference(resource), "test", source, PlatformMcpDefinitionAuthOwnership.NONE,
            EnterpriseAppliedVersion(Uuid.random().toString(), 42, "config", "execution"), interaction) { token.get() }
        val fingerprint = definition.connectionFingerprint()
        val digest = definition.mcpDefinitionDigest()
        val http = HttpClient(OkHttp)
        val factory = McpProtocolClientFactory(createHttpClient = { error("personal transport") },
            createManagedHttpClient = { http }, createLocalHttpClient = { error("local transport") })
        val client = factory.createClient(definition)
        try {
            withTimeout(10_000) {
                client.connect(factory.createTransport(definition))
                val candidate = McpCatalogDiscovery.fetchCandidate(definition.catalogKey, digest, client, definition.managed)
                assertEquals("read_status", candidate.tools.single().name)
                token.set("second-token")
                val result = client.callTool(io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                    params = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(name = "read_status")))
                assertEquals(1, result.content.size)
                assertEquals(fingerprint, definition.connectionFingerprint())
                assertEquals(digest, definition.mcpDefinitionDigest())
                assertEquals("Bearer second-token", received.last().second)
                assertTrue(received.any { it.second == "Bearer first-token" })
                assertTrue(received.all { it.first == path && it.third == interaction })
            }
        } finally { client.close(); http.close(); server.stop(0) }
    }
}
