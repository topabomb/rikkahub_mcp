package me.rerere.common.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.*
import org.junit.Test

class RequestPrivacyTest {
    @Test fun `private redirects never reach a second origin and personal requests retain redirects and logging`() {
        val received = AtomicInteger()
        val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        destination.createContext("/target") { exchange ->
            received.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        destination.start()
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${destination.address.port}/target")
            exchange.sendResponseHeaders(exchange.requestURI.path.drop(1).toInt(), -1)
            exchange.close()
        }
        source.start()
        val logs = mutableListOf<String>()
        val logger = HttpLoggingInterceptor { logs += it }.apply { level = HttpLoggingInterceptor.Level.HEADERS }
        val client = OkHttpClient.Builder().addInterceptor(logger.excludingPrivateRequests())
            .addNetworkInterceptor(PrivateRequestBoundaryInterceptor()).build()
        try {
            for (code in listOf(301, 302, 303, 307, 308)) {
                val request = Request.Builder().url("http://127.0.0.1:${source.address.port}/$code")
                    .header("x-goog-api-key", "private-credential").header("X-Private-Tenant", "private-tenant")
                    .tag(PrivateRequest::class.java, PrivateRequest).post("private-body".toRequestBody()).build()
                try { client.newCall(request).execute().use { }; fail("private redirect accepted") }
                catch (error: java.io.IOException) { assertEquals("private_request_cross_origin_redirect", error.message) }
                assertEquals(0, received.get())
                assertTrue(logs.isEmpty())
            }
            val personal = Request.Builder().url("http://127.0.0.1:${source.address.port}/302").build()
            client.newCall(personal).execute().use { assertEquals(204, it.code) }
            assertEquals(1, received.get())
            assertTrue(logs.isNotEmpty())
            assertEquals(HttpLoggingInterceptor.Level.HEADERS, logger.level)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            source.stop(0)
            destination.stop(0)
        }
    }
}
