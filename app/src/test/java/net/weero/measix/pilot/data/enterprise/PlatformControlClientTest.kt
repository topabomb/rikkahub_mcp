package net.weero.measix.pilot.data.enterprise

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PlatformControlClientTest {
    private val client = PlatformControlClient(OkHttpClient())
    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        .first { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject.getValue("value").toString()

    @Test
    fun `HTTP discovery preserves origin and rejects cross origin or traversing API bases`() = runBlocking {
        val server = server { reply(200, fixture("discovery")) }
        try {
            val origin = origin(server)
            val connection = client.discover(origin)
            assertEquals(origin, connection.origin)
            assertEquals(origin + "/api/client/v1/bootstrap", connection.control("/bootstrap"))
            assertFalse(connection.authority.isLocal)
            listOf("//other.test/api", "https://other.test/api", "/api/../private", "/api/%2e%2e/private", "/api?token=x").forEach { base ->
                assertThrows(IllegalArgumentException::class.java) { connection.copy(discovery = connection.discovery.copy(clientApiBase = base)) }
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `redirect is not followed and original error detail is retained`() = runBlocking {
        val calls = AtomicInteger()
        val server = server {
            calls.incrementAndGet()
            responseHeaders.set("Location", "/redirected")
            reply(302, "origin moved")
        }
        try {
            try { client.discover(origin(server)); fail("redirect accepted") }
            catch (error: PlatformHttpException) {
                assertEquals(302, error.status)
                assertTrue(error.message.orEmpty().contains("origin moved"))
            }
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `snapshot checks quoted ETag deployment generation and conditional cache`() = runBlocking {
        val raw = fixture("v4-full")
        val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(raw)
        val requests = mutableListOf<Pair<String?, String?>>()
        var badEtag = false
        val server = server {
            requests += requestHeaders.getFirst("Authorization") to requestHeaders.getFirst("If-None-Match")
            if (requestHeaders.getFirst("If-None-Match") != null) reply(304, "")
            else {
                responseHeaders.set("ETag", if (badEtag) "wrong" else "\"${snapshot.snapshotHash}\"")
                reply(200, raw)
            }
        }
        try {
            val connection = PlatformConnection(origin(server), PlatformWireCodec.decode(fixture("discovery")))
            assertEquals(snapshot, (client.snapshot(connection, "token", snapshot.managedGeneration, null) as PlatformSnapshotResponse.Downloaded).snapshot)
            assertEquals(PlatformSnapshotResponse.NotModified, client.snapshot(connection, "token", snapshot.managedGeneration, snapshot.snapshotHash))
            assertEquals("Bearer token", requests.first().first)
            assertEquals("\"${snapshot.snapshotHash}\"", requests.last().second)
            badEtag = true
            try { client.snapshot(connection, "token", snapshot.managedGeneration, null); fail("bad ETag accepted") }
            catch (error: IllegalArgumentException) { assertEquals("platform_snapshot_etag_mismatch", error.message) }
        } finally { server.stop(0) }
    }

    @Test
    fun `refresh uses body credential and caller durable idempotency key without retry`() = runBlocking {
        val key = "idem_12345678-1234-4234-8234-123456789012"
        var received: Triple<String, String?, String>? = null
        val server = server {
            received = Triple(requestURI.path, requestHeaders.getFirst("Idempotency-Key"), requestBody.bufferedReader().readText())
            reply(200, fixture("refresh-response"))
        }
        try {
            val connection = PlatformConnection(origin(server), PlatformWireCodec.decode(fixture("discovery")))
            client.refresh(connection, "original-refresh", key)
            assertEquals("/api/client/v1/sessions/refresh", received?.first)
            assertEquals(key, received?.second)
            assertEquals("original-refresh", Json.parseToJsonElement(requireNotNull(received).third).jsonObject.getValue("refreshToken").jsonPrimitive.content)
        } finally { server.stop(0) }
    }

    @Test
    fun `side effecting control requests are not replayed after retryable HTTP responses`() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val server = server {
            calls.computeIfAbsent(requestURI.path) { AtomicInteger() }.incrementAndGet()
            responseHeaders.set("Retry-After", "0")
            reply(503, "temporarily unavailable")
        }
        try {
            val connection = PlatformConnection(origin(server), PlatformWireCodec.decode(fixture("discovery")))
            val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(fixture("v4-full"))
            val requests: List<Pair<String, suspend () -> Unit>> = listOf(
                "/api/client/v1/enrollments/exchange" to {
                    client.enroll(connection, PlatformEnrollmentExchangeRequest("one-use-code",
                        "ins_12345678-1234-4234-8234-123456789012", "Android", "test",
                        PlatformEnrollmentExchangeRequestPlatform.ANDROID))
                },
                "/api/client/v1/sessions/refresh" to {
                    client.refresh(connection, "refresh", "idem_12345678-1234-4234-8234-123456789012")
                },
                "/api/client/v1/managed/applied" to {
                    client.reportApplied(connection, "access", PlatformManagedAppliedReport(snapshot.managedGeneration, snapshot.snapshotHash))
                },
                "/api/client/v1/sessions/logout" to { client.logout(connection, "refresh") },
            )
            for ((path, send) in requests) {
                try { send(); fail("HTTP 503 accepted for $path") }
                catch (error: PlatformHttpException) { assertEquals(503, error.status) }
                assertEquals("Unexpected replay for $path", 1, calls[path]?.get())
            }
        } finally { server.stop(0) }
    }

    private fun server(handler: HttpExchange.() -> Unit): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { it.handler() } }
        start()
    }
    private fun origin(server: HttpServer) = "http://127.0.0.1:${server.address.port}"
    private fun HttpExchange.reply(status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(status, if (status == 304 || status == 204) -1 else bytes.size.toLong())
        if (status != 304 && status != 204) responseBody.write(bytes)
    }

    @Test
    fun `applied report sends only durable generation and hash under the current bearer`() = runBlocking {
        var captured: Pair<String, JsonObject>? = null
        val server = server {
            captured = requestMethod to Json.parseToJsonElement(requestBody.bufferedReader().readText()).jsonObject
            assertEquals("Bearer current-session", requestHeaders.getFirst("Authorization"))
            assertEquals("/api/client/v1/managed/applied", requestURI.path)
            reply(204, "")
        }
        try {
            val connection = PlatformConnection(origin(server), PlatformWireCodec.decode(fixture("discovery")))
            val snapshot = PlatformWireCodec.decode<PlatformManagedSnapshot>(fixture("v4-full"))
            client.reportApplied(connection, "current-session", PlatformManagedAppliedReport(snapshot.managedGeneration, snapshot.snapshotHash))
            assertEquals("PUT", captured?.first)
            assertEquals(setOf("managedGeneration", "snapshotHash"), captured?.second?.keys)
            assertEquals(snapshot.snapshotHash, captured?.second?.get("snapshotHash")?.jsonPrimitive?.content)
        } finally { server.stop(0) }
    }
}
