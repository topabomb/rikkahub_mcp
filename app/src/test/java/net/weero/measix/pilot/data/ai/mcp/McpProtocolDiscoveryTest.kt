package net.weero.measix.pilot.data.ai.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

/** Actual SDK and production OkHttp engine against a loopback HTTP/SSE server. */
class McpProtocolDiscoveryTest {
    @Test fun `HTTP JSON preserves complete tool definitions through discovery and catalog encoding`() = verify(0)
    @Test fun `POST SSE preserves original IDs and multiline data without waiting for EOF`() = verify(1)
    @Test fun `GET SSE preserves the original response associated with SDK request ID`() = verify(2)
    @Test fun `personal SSE uses the same lossless decoder and SDK request lifecycle`() = verify(3)

    @Test fun `SSE multiline must not concatenate separated number tokens into a valid value`() = verify(1, true)

    @Test fun `Gateway HTTP discovery preserves its published surface and managed generation`() = verify(0, gateway = true)
    @Test fun `Gateway SSE discovery preserves its published surface and managed generation`() = verify(1, gateway = true)

    private fun verify(mode: Int, malformedMultiline: Boolean = false, gateway: Boolean = false): Unit = runBlocking {
        val packet = net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage()
        val exampleTools = net.weero.measix.pilot.data.enterprise.LocalEnterpriseMcpSurface.gatewayTools

        val first = if (gateway) exampleTools.first().definition else Json.parseToJsonElement("""{
            "name":"discover_tools","title":"Discovery","description":"Search enterprise tools",
            "inputSchema":{"type":"object","properties":{"queries":{"type":"array","items":{"type":"string"}}},"required":["queries"],"additionalProperties":false},
            "outputSchema":{"type":"object","properties":{"results":{"type":"array"}},"additionalProperties":false},
            "annotations":{"readOnlyHint":true,"vendorHint":"preserve"},
            "_meta":{"com.example/catalog":{"version":7}},"futureField":{"kept":true}
        }""") as JsonObject
        val second = if (gateway) exampleTools.last().definition else Json.parseToJsonElement("""{"name":"invoke_tool","inputSchema":{"type":"object","additionalProperties":false}}""") as JsonObject
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val shutdown = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val reopened = CountDownLatch(1)
        val getCount = java.util.concurrent.atomic.AtomicInteger()
        val stream = AtomicReference<OutputStream?>()
        var pageCount = 0
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try {
                if (exchange.requestMethod == "GET") {
                    if (mode < 2) {
                        exchange.sendResponseHeaders(405, -1)
                    } else {
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        stream.set(exchange.responseBody)
                        if (mode == 3) exchange.responseBody.write("event: endpoint\ndata: /mcp/messages\n\n".toByteArray())
                        exchange.responseBody.flush()
                        if (getCount.incrementAndGet() > 1) reopened.countDown()
                        opened.countDown()
                        check(shutdown.await(15, TimeUnit.SECONDS))
                    }
                } else {
                    val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    val method = body.getValue("method").jsonPrimitive.content
                    methods += method
                    if (method != "initialize" && mode != 3) assertEquals("2025-11-25", exchange.requestHeaders.getFirst("mcp-protocol-version"))
                    if (method == "notifications/initialized") {
                        exchange.sendResponseHeaders(202, -1)
                    } else {
                        val result = when (method) {
                            "initialize" -> """{"protocolVersion":"2025-11-25","serverInfo":{"name":"local","version":"1"},"capabilities":{"tools":{}}}"""
                            "tools/list" -> if (pageCount++ == 0) """{"tools":[$first],"nextCursor":"second"}""" else """{"tools":[$second]}"""
                            "tools/call" -> """{"content":[{"type":"text","text":"done"}]}"""
                            else -> error("Unexpected method $method")
                        }
                        val envelope = """{"jsonrpc":"2.0","id":${body.getValue("id")},"result":$result}"""
                        if (mode == 3 || mode == 2 && method != "initialize") {
                            check(opened.await(5, TimeUnit.SECONDS))
                            if (mode == 2 && pageCount >= 2) check(reopened.await(5, TimeUnit.SECONDS))
                            synchronized(stream) {
                                stream.get()!!.write("event: message\ndata: $envelope\n\n".toByteArray())
                                stream.get()!!.flush()
                                if (mode == 2 && method == "tools/list" && pageCount == 1) stream.get()!!.close()
                            }
                            exchange.sendResponseHeaders(202, -1)
                        } else if (mode == 1) {
                            exchange.responseHeaders.add("Content-Type", "text/event-stream")
                            exchange.sendResponseHeaders(200, 0)
                            // A wrong ID must not complete this request; the matching response follows.
                            if (method == "tools/list") exchange.responseBody.write(
                                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"unrelated\",\"result\":{\"tools\":[]}}\n\n".toByteArray()
                            )
                            val data = if (malformedMultiline && method == "tools/list") {
                                envelope.replace("\"result\":{", "\"result\":{\"_meta\":{\"split\":1\n2},")
                            } else envelope.replace(",\"result\":", ",\n\"result\":")
                            exchange.responseBody.write(("event: message\n" + data.lines().joinToString("\n") { "data: $it" } + "\n\n").toByteArray())
                            exchange.responseBody.flush()
                            // Deliberately leave the stream open after its terminal response.
                            check(shutdown.await(15, TimeUnit.SECONDS))
                        } else exchange.json(envelope)
                    }
                }
            } catch (error: Throwable) {
                if (shutdown.count > 0) failure.compareAndSet(null, error)
                try { exchange.sendResponseHeaders(500, -1) } catch (_: Exception) { }
            } finally { exchange.close() }
        }
        server.start()
        val http = HttpClient(OkHttp)
        val factory = McpProtocolClientFactory(createManagedHttpClient = { error("unexpected managed connection") }, createLocalHttpClient = { error("unexpected local connection") }, createHttpClient = { http })
        val url = "http://127.0.0.1:${server.address.port}/mcp"
        val config = if (mode == 3) McpServerConfig.SseTransportServer(url = url)
            else McpServerConfig.StreamableHTTPServer(url = url)
        val client = factory.createClient(McpConnectionDefinition.User(config))
        val key = if (gateway) McpCatalogKey(packet.identity.scope, packet.identity.reference(packet.configuration.gateways.single().id))
            else McpCatalogKey(net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal, config.id)
        val managed = if (gateway) McpManagedCatalog(packet.configuration.generation, packet.configuration.gateways.single().surface) else null
        try {
            withTimeout(10_000) {
                client.connect(factory.createTransport(McpConnectionDefinition.User(config)))
                if (malformedMultiline) {
                    try {
                        McpCatalogDiscovery.fetchCandidate(key, config.mcpDefinitionDigest(), client, managed)
                        fail("Separated number tokens cannot become one valid number")
                    } catch (error: io.modelcontextprotocol.kotlin.sdk.types.McpException) {
                        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message == "Invalid MCP object" })
                    }
                    assertEquals(1, pageCount)
                    return@withTimeout
                }
                val candidate = McpCatalogDiscovery.fetchCandidate(key, config.mcpDefinitionDigest(), client, managed)
                assertEquals(listOf(first, second), candidate.tools.map { it.definition })
                val snapshot = candidate.initialSnapshot()
                val roundTrip = JsonInstant.decodeFromString<McpCatalogSnapshot>(JsonInstant.encodeToString(snapshot))
                assertEquals(snapshot, roundTrip.validated())
                assertEquals(managed, roundTrip.managed)
                assertEquals(first, roundTrip.tools.first().definition)
                val result = client.callTool(io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                    params = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(name = "invoke_tool")
                ))
                assertEquals(1, result.content.size)
                assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call"), methods)
                if (mode == 2) assertEquals(2, getCount.get())
            }
            failure.get()?.let { throw it }
        } catch (error: Throwable) {
            throw failure.get() ?: error
        } finally {
            shutdown.countDown()
            client.close()
            http.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.json(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.write(bytes)
    }
}
