package net.weero.measix.pilot.data.ai.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class McpStreamableHttpRecoveryIntegrationTest {
    @Test fun `two request recoveries preserve notification subscription and independent responses`() = runBlocking {
        exerciseConcurrentRecovery(cancelFirst = false)
    }

    @Test fun `cancelling one recovery awaits its IO without cancelling another recovery or notifications`() = runBlocking {
        exerciseConcurrentRecovery(cancelFirst = true)
    }

    private suspend fun exerciseConcurrentRecovery(cancelFirst: Boolean) = kotlinx.coroutines.coroutineScope {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val notificationGets = AtomicInteger()
        val notificationEntered = CompletableDeferred<Unit>()
        val notificationSeen = CompletableDeferred<Unit>()
        val recovered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val release = listOf(CountDownLatch(1), CountDownLatch(1))
        val sendNotification = CountDownLatch(1)
        val releaseNotification = CountDownLatch(1)
        val cancelled = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        listOf("notifications", "request-1", "request-2").forEach { cancelled[it] = CompletableDeferred() }
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try {
                val body = exchange.requestBody.readBytes().decodeToString()
                if (exchange.requestMethod == "POST") {
                    if (body.contains("notifications/initialized")) exchange.sendResponseHeaders(202, -1)
                    else {
                        val id = if (body.contains("\"id\":1")) 1 else 2
                        exchange.sse("id: request-$id\nretry: 1\ndata: \n\n")
                    }
                } else {
                    val token = exchange.requestHeaders.getFirst("Last-Event-ID")
                    if (token == null) {
                        notificationGets.incrementAndGet()
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.flush()
                        notificationEntered.complete(Unit)
                        check(sendNotification.await(10, TimeUnit.SECONDS))
                        exchange.responseBody.write(("data: " +
                            """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""" + "\n\n").toByteArray())
                        exchange.responseBody.flush()
                        check(releaseNotification.await(10, TimeUnit.SECONDS))
                    } else {
                        val index = token.removePrefix("request-").toInt() - 1
                        entered[index].complete(Unit)
                        check(release[index].await(10, TimeUnit.SECONDS))
                        exchange.sse("data: " + """{"jsonrpc":"2.0","id":${index + 1},"result":{}}""" + "\n\n")
                    }
                }
            } catch (_: IOException) { }
            finally { exchange.close() }
        }
        server.start()
        val engine = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.request().method == "GET" && call.isCanceled()) {
                    cancelled[call.request().header("Last-Event-ID") ?: "notifications"]?.complete(Unit)
                }
            }
        }).build()
        val http = HttpClient(OkHttp) { engine { preconfigured = engine } }
        val transport = McpStreamableHttpTransport(http, "http://127.0.0.1:${server.address.port}/mcp")
        transport.onMessage { message ->
            if (message is JSONRPCNotification) notificationSeen.complete(Unit)
            if (message is JSONRPCResponse) {
                val index = message.id.toString().filter(Char::isDigit).toInt() - 1
                recovered[index].complete(Unit)
            }
        }
        try {
            withTimeout(8_000) {
                transport.start()
                transport.send(message("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), null)
                notificationEntered.await()
                val requests = (1..2).map { id -> async {
                    transport.send(message("""{"jsonrpc":"2.0","id":$id,"method":"ping"}"""), null)
                } }
                entered.forEach { it.await() }
                if (cancelFirst) {
                    requests[0].cancelAndJoin()
                    cancelled.getValue("request-1").await()
                    assertFalse(cancelled.getValue("request-2").isCompleted)
                    assertFalse(cancelled.getValue("notifications").isCompleted)
                }
                release.forEach { it.countDown() }
                if (!cancelFirst) { requests[0].await(); recovered[0].await() }
                requests[1].await()
                recovered[1].await()
                sendNotification.countDown()
                notificationSeen.await()
                assertEquals(1, notificationGets.get())
                transport.close()
                cancelled.getValue("notifications").await()
            }
        } finally {
            release.forEach { it.countDown() }
            sendNotification.countDown()
            releaseNotification.countDown()
            transport.close()
            http.close()
            engine.dispatcher.executorService.shutdownNow()
            engine.connectionPool.evictAll()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun `exhausted notification stream restarts explicitly without another initialized POST`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val gets = AtomicInteger()
        val posts = AtomicInteger()
        val unavailable = CompletableDeferred<McpNotificationStreamState.Unavailable>()
        val listening = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try {
                exchange.requestBody.readBytes()
                if (exchange.requestMethod == "POST") {
                    posts.incrementAndGet()
                    exchange.sendResponseHeaders(202, -1)
                } else if (gets.incrementAndGet() == 1) exchange.sendResponseHeaders(503, -1)
                else {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(("data: " +
                        """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""" + "\n\n").toByteArray())
                    exchange.responseBody.flush()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            } finally { exchange.close() }
        }
        server.start()
        val http = HttpClient(OkHttp)
        val transport = McpStreamableHttpTransport(http, "http://127.0.0.1:${server.address.port}/mcp",
            reconnectionOptions = ReconnectionOptions(initialReconnectionDelay = 1.milliseconds, maxRetries = 1))
        transport.onNotificationState {
            if (it is McpNotificationStreamState.Unavailable) unavailable.complete(it)
            if (it is McpNotificationStreamState.Listening) listening.complete(Unit)
        }
        transport.onMessage { observed.complete(Unit) }
        try {
            withTimeout(5_000) {
                transport.start()
                transport.send(message("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), null)
                assertTrue(unavailable.await().retryable)
                // Resume may race the terminal callback; await the old collector's completion internally.
                transport.resumeNotificationStream()
                listening.await()
                observed.await()
                transport.resumeNotificationStream()
                assertEquals(2, gets.get())
                assertEquals(1, posts.get())
            }
        } finally {
            transport.close()
            release.countDown()
            http.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun message(json: String): JSONRPCMessage = McpJson.decodeFromString(json)

    private fun HttpExchange.sse(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "text/event-stream")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.write(bytes)
    }
}
