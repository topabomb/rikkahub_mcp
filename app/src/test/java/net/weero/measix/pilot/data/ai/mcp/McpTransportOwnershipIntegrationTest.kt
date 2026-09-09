package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.enterprise.ManagedSnapshotRequired

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import kotlin.time.Duration.Companion.hours
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class McpTransportOwnershipIntegrationTest {
    @Test fun `managed GET barrier stays typed for notification and primed request recovery without replay`() = runBlocking {
        val problem = requireNotNull(javaClass.getResourceAsStream("/contracts/runtime/managed-snapshot-required.json")).use { it.readBytes() }
        for (primed in listOf(false, true)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val posts = AtomicInteger()
            val gets = AtomicInteger()
            val failure = CompletableDeferred<Throwable>()
            server.createContext("/mcp") { exchange ->
                try {
                    exchange.requestBody.readBytes()
                    if (exchange.requestMethod == "GET") {
                        gets.incrementAndGet()
                        exchange.responseHeaders.add("Content-Type", if (primed) "application/json" else "application/problem+json")
                        exchange.sendResponseHeaders(428, problem.size.toLong())
                        exchange.responseBody.write(problem)
                    } else {
                        posts.incrementAndGet()
                        if (primed) {
                            val body = "id: original-cursor\nretry: 1\ndata: \n\n".toByteArray()
                            exchange.responseHeaders.add("Content-Type", "text/event-stream")
                            exchange.sendResponseHeaders(200, body.size.toLong())
                            exchange.responseBody.write(body)
                        } else exchange.sendResponseHeaders(202, -1)
                    }
                } finally { exchange.close() }
            }
            server.start()
            val http = HttpClient(OkHttp)
            val transport = McpStreamableHttpTransport(http, "http://127.0.0.1:${server.address.port}/mcp", managed = true)
            transport.onError { failure.complete(it) }
            try {
                withTimeout(5_000) {
                    transport.start()
                    transport.send(McpJson.decodeFromString<JSONRPCMessage>(if (primed)
                        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"invoke_tool","arguments":{}}}"""
                        else """{"jsonrpc":"2.0","method":"notifications/initialized"}"""), null)
                    assertTrue(failure.await() is ManagedSnapshotRequired)
                    transport.close()
                    assertEquals(1, gets.get())
                    assertEquals(1, posts.get())
                }
            } finally { transport.close(); http.close(); server.stop(0) }
        }
    }

    @Test fun `legacy SSE EOF before endpoint fails initialization explicitly`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        val http = HttpClient(OkHttp)
        val transport = McpSseTransport(http, "http://127.0.0.1:${server.address.port}/mcp")
        try {
            withTimeout(5_000) {
                val error = try { transport.start(); null } catch (error: Exception) { error }
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("before endpoint"))
            }
        } finally {
            transport.close()
            http.close()
            server.stop(0)
        }
    }

    @Test fun `invalid SSE JSON reports protocol failure instead of silently reconnecting`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val failure = CompletableDeferred<Throwable>()
        server.createContext("/mcp") { exchange ->
            try {
                if (exchange.requestMethod == "GET") {
                    requests.incrementAndGet()
                    val frame = """data: {"jsonrpc":"2.0","method":"first","method":"second"}""" + "\n\n"
                    val bytes = frame.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } else exchange.sendResponseHeaders(202, -1)
            } finally { exchange.close() }
        }
        server.start()
        val http = HttpClient(OkHttp)
        val transport = McpStreamableHttpTransport(http, "http://127.0.0.1:${server.address.port}/mcp")
        transport.onError { failure.complete(it) }
        try {
            withTimeout(5_000) {
                transport.start()
                transport.send(McpJson.decodeFromString<JSONRPCMessage>(
                    """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
                ), null)
                failure.await()
                transport.close()
                assertEquals(1, requests.get())
            }
        } finally {
            transport.close()
            http.close()
            server.stop(0)
        }
    }

    @Test fun `SSE read failure retains resume cursor and retry delay without exhausting connection attempts`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val observed = CompletableDeferred<Unit>()
        val consumed = java.util.concurrent.LinkedBlockingQueue<CountDownLatch>()
        val headers = java.util.Collections.synchronizedList(mutableListOf<String?>())
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try {
                if (exchange.requestMethod == "GET") {
                    val count = synchronized(headers) {
                        headers += exchange.requestHeaders.getFirst("Last-Event-ID")
                        headers.size
                    }
                    if (count >= 3) observed.complete(Unit)
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    // Deliberately truncate a declared body after a complete event, forcing read IOException.
                    exchange.sendResponseHeaders(200, 100_000)
                    val eventConsumed = CountDownLatch(1)
                    consumed.add(eventConsumed)
                    exchange.responseBody.write(("id: cursor-$count\nretry: 5\ndata: " +
                        """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""" + "\n\n").toByteArray())
                    exchange.responseBody.flush()
                    check(eventConsumed.await(5, TimeUnit.SECONDS))
                } else exchange.sendResponseHeaders(202, -1)
            } catch (_: IOException) { }
            finally { exchange.close() }
        }
        server.start()
        val http = HttpClient(OkHttp)
        val transport = McpStreamableHttpTransport(http, "http://127.0.0.1:${server.address.port}/mcp",
            reconnectionOptions = ReconnectionOptions(initialReconnectionDelay = 1.hours, maxRetries = 2))
        transport.onMessage { consumed.poll()?.countDown() }
        try {
            withTimeout(5_000) {
                transport.start()
                transport.send(McpJson.decodeFromString<JSONRPCMessage>(
                    """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
                ), null)
                observed.await()
                transport.close()
                assertEquals(listOf(null, "cursor-1", "cursor-2"), headers.take(3))
            }
        } finally {
            transport.close()
            http.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun `both transports cancel pending SSE headers without closing the shared HTTP client`() = runBlocking {
        for (legacy in listOf(false, true)) {
            val requested = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val responded = CompletableDeferred<Unit>()
            val releaseHeaders = CountDownLatch(1)
            val frames = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val executor = Executors.newCachedThreadPool()
            server.executor = executor
            server.createContext("/mcp") { exchange ->
                try {
                    if (exchange.requestMethod == "GET") {
                        requested.complete(Unit)
                        check(releaseHeaders.await(10, TimeUnit.SECONDS))
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write("event: endpoint\ndata: /mcp\n\n".toByteArray())
                        exchange.responseBody.flush()
                    } else exchange.sendResponseHeaders(202, -1)
                } catch (_: IOException) {
                    // The client has already closed the socket while these response headers waited.
                } finally {
                    exchange.close()
                    if (exchange.requestMethod == "GET") responded.complete(Unit)
                }
            }
            server.createContext("/probe") { exchange ->
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
                exchange.close()
            }
            server.start()
            val engine = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    if (call.request().method == "GET" && call.request().url.encodedPath == "/mcp" && call.isCanceled()) {
                        cancelled.complete(Unit)
                    }
                }
            }).build()
            val http = HttpClient(OkHttp) { engine { preconfigured = engine } }
            val url = "http://127.0.0.1:${server.address.port}"
            val transport: McpClientTransport = if (legacy) McpSseTransport(http, "$url/mcp")
                else McpStreamableHttpTransport(http, "$url/mcp")
            transport.onMessage { frames.incrementAndGet() }
            try {
                withTimeout(5_000) {
                    val start = async {
                        transport.start()
                        if (!legacy) transport.send(McpJson.decodeFromString<JSONRPCMessage>(
                            """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
                        ), null)
                    }
                    requested.await()
                    transport.close()
                    cancelled.await()
                    start.join()
                    releaseHeaders.countDown()
                    responded.await()
                    assertEquals(0, frames.get())
                    assertEquals("ok", http.get("$url/probe").bodyAsText())
                    transport.close()
                }
            } finally {
                releaseHeaders.countDown()
                transport.close()
                http.close()
                engine.dispatcher.executorService.shutdownNow()
                engine.connectionPool.evictAll()
                server.stop(0)
                executor.shutdownNow()
            }
        }
    }
}
