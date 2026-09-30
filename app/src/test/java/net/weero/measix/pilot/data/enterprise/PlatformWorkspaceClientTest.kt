package net.weero.measix.pilot.data.enterprise

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PlatformWorkspaceClientTest {
    private val space = "spc_550e8400-e29b-41d4-a716-446655440000"
    private val client = PlatformWorkspaceClient(OkHttpClient())
    private fun connection(server: HttpServer): PlatformConnection {
        val cases = javaClass.getResourceAsStream("/contracts/platform/cases.json")!!.bufferedReader().use { it.readText() }
        val discovery = Json.parseToJsonElement(cases).jsonArray.first {
            it.jsonObject["name"]!!.jsonPrimitive.content == "discovery"
        }.jsonObject["value"].toString()
        return PlatformConnection("http://127.0.0.1:${server.address.port}", PlatformWireCodec.decode(discovery))
    }
    private fun server(handler: HttpExchange.() -> Unit) = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { it.handler() } }; start()
    }
    private fun HttpExchange.reply(status: Int, text: String) {
        val bytes = text.toByteArray(); sendResponseHeaders(status, bytes.size.toLong()); responseBody.write(bytes)
    }

    @Test fun `files only and unprovisioned fixtures strictly decode`() {
        for (name in listOf("projection-files-only.json", "projection-unprovisioned.json")) {
            val raw = javaClass.getResourceAsStream("/contracts/workspace/$name")!!.bufferedReader().use { it.readText() }
            val value = PlatformWireCodec.decode<PlatformWorkspaceProjection>(raw)
            assertFalse(value.mcpAvailable)
            val objectValue = Json.parseToJsonElement(raw).jsonObject
            for (invalid in listOf(JsonObject(objectValue - "serviceState"), JsonObject(objectValue + ("agentSpaceId" to JsonNull)),
                JsonObject(objectValue + ("future" to JsonPrimitive(true))), JsonObject(objectValue + ("state" to JsonPrimitive("FUTURE"))))) {
                assertThrows(Exception::class.java) { PlatformWireCodec.decode<PlatformWorkspaceProjection>(invalid.toString()) }
            }
        }
    }

    @Test fun `known missing endpoint is optional and a later refresh discovers upgraded Core`() = runBlocking {
        val current = javaClass.getResourceAsStream("/contracts/workspace/projection-files-only.json")!!.bufferedReader().use { it.readText() }
        val calls = AtomicInteger()
        val server = server {
            assertTrue(requestURI.path.endsWith("/workspace"))
            if (calls.incrementAndGet() == 1) {
                responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                reply(404, "404 page not found\n")
            } else reply(200, current)
        }
        try {
            try { client.state(connection(server), "token"); fail("old endpoint accepted") }
            catch (error: WorkspaceProtocolUnavailableException) { assertEquals("workspace_endpoint_not_supported", error.message) }
            assertTrue(client.state(connection(server), "token").filesAvailable)
            assertEquals(2, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun `legacy projection is recognized without enabling its unbound file protocol`() = runBlocking {
        val current = Json.parseToJsonElement(javaClass.getResourceAsStream("/contracts/workspace/projection-files-only.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
        val calls = AtomicInteger()
        val server = server {
            calls.incrementAndGet()
            assertTrue(requestURI.path.endsWith("/workspace"))
            reply(200, JsonObject(current - "serviceState").toString())
        }
        try {
            try { client.state(connection(server), "token"); fail("legacy capability accepted") }
            catch (error: WorkspaceProtocolUnavailableException) { assertEquals("workspace_requires_fixed_space_protocol", error.message) }
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun `real HTTP errors are not hidden as unsupported workspace`() = runBlocking {
        for ((status, type, body) in listOf(
            Triple(404, "application/problem+json", """{"type":"about:blank","title":"Missing workspace","status":404,"code":"workspace_not_found"}"""),
            Triple(404, "text/html", "<html>proxy route missing</html>"),
            Triple(404, "text/plain", "missing enterprise deployment"),
            Triple(401, "text/plain", "404 page not found\n"),
            Triple(503, "text/plain", "workspace unavailable"),
        )) {
            val server = server { responseHeaders.set("Content-Type", type); reply(status, body) }
            try {
                try { client.state(connection(server), "token"); fail("error accepted") }
                catch (error: PlatformHttpException) { assertEquals(status, error.status); assertTrue(error.message!!.isNotBlank()) }
            } finally { server.stop(0) }
        }
    }

    @Test fun `malformed projections remain contract failures even when service state is missing`() = runBlocking {
        val current = Json.parseToJsonElement(javaClass.getResourceAsStream("/contracts/workspace/projection-files-only.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
        val legacy = current - "serviceState"
        for (invalid in listOf(
            JsonObject(legacy - "bindingRevision"),
            JsonObject(legacy + ("state" to JsonPrimitive("FUTURE"))),
            JsonObject(legacy + ("agentSpaceId" to JsonNull)),
            JsonObject(legacy + ("agentSpaceId" to JsonPrimitive("not-a-space"))),
            JsonObject(legacy + ("schemaVersion" to JsonPrimitive(2))),
            JsonObject(legacy + ("future" to JsonPrimitive(true))),
            JsonObject(current + ("serviceState" to JsonNull)),
        )) {
            val server = server { reply(200, invalid.toString()) }
            try {
                try { client.state(connection(server), "token"); fail("invalid contract accepted") }
                catch (error: Exception) { assertFalse(error is WorkspaceProtocolUnavailableException) }
            } finally { server.stop(0) }
        }
    }

    @Test fun `paths encode once and content captures body and etag together`() = runBlocking {
        val server = server {
            assertEquals("agentSpaceId=$space&path=%E4%B8%AD%E6%96%87%20file.txt", requestURI.rawQuery)
            assertEquals("Bearer token", requestHeaders.getFirst("Authorization"))
            responseHeaders.set("ETag", "\"version\"")
            reply(200, "content")
        }
        try {
            val output = ByteArrayOutputStream()
            val metadata = client.download(connection(server), "token", space, "中文 file.txt", output)
            assertEquals("content", output.toString("UTF-8")); assertEquals("\"version\"", metadata.etag)
        } finally { server.stop(0) }
    }

    @Test fun `safe GET reconnects when a reused connection closes before response headers`() = runBlocking {
        val projection = javaClass.getResourceAsStream("/contracts/workspace/projection-files-only.json")!!.bufferedReader().use { it.readText() }
        val calls = AtomicInteger()
        val drop = AtomicBoolean()
        val server = server {
            assertEquals("GET", requestMethod)
            calls.incrementAndGet()
            if (drop.getAndSet(false)) close()
            else when {
                requestURI.path.endsWith("/files") -> reply(200, """{"entries":[]}""")
                requestURI.path.endsWith("/content") -> reply(200, "content")
                else -> reply(200, projection)
            }
        }
        try {
            val connection = connection(server)
            assertTrue(client.state(connection, "token").filesAvailable)
            drop.set(true)
            assertTrue(client.state(connection, "token").filesAvailable)
            drop.set(true)
            assertTrue(client.list(connection, "token", space, "").entries.isEmpty())
            drop.set(true)
            val output = ByteArrayOutputStream()
            client.download(connection, "token", space, "a.txt", output)
            assertEquals("content", output.toString("UTF-8"))
            assertEquals(7, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun `GET recovery does not replay an incomplete response body`() = runBlocking {
        val calls = AtomicInteger()
        val server = server {
            calls.incrementAndGet()
            sendResponseHeaders(200, 100)
            responseBody.write("partial".toByteArray())
        }
        try {
            val output = ByteArrayOutputStream()
            try { client.download(connection(server), "token", space, "a.txt", output); fail("incomplete download accepted") }
            catch (_: IOException) { }
            assertEquals(1, calls.get())
            assertEquals("partial", output.toString("UTF-8"))
        } finally { server.stop(0) }
    }

    @Test fun `writes are not replayed after sending to a connection that closes without confirmation`() = runBlocking {
        for (put in listOf(true, false)) {
            val calls = AtomicInteger()
            val server = server { calls.incrementAndGet(); requestBody.readBytes(); close() }
            try {
                try {
                    if (put) client.upload(connection(server), "token", space, "a.txt", null, 1, { byteArrayOf(1).inputStream() })
                    else client.mutate(connection(server), "token", space, PlatformWorkspaceFileMutation(PlatformWorkspaceFileMutationAction.MKCOL, "dir"))
                    fail("unconfirmed write accepted")
                } catch (_: IOException) { }
                assertEquals(1, calls.get())
            } finally { server.stop(0) }
        }
    }

    @Test fun `writes never follow redirects or 408 and 503 retries`() = runBlocking {
        for (status in listOf(301, 302, 307, 308, 401, 408, 503, 507)) {
            val count = AtomicInteger()
            val server = server {
                count.incrementAndGet(); requestBody.readBytes()
                responseHeaders.set("Location", "/redirected"); responseHeaders.set("Retry-After", "0")
                reply(status, """{"type":"about:blank","title":"Unknown","status":$status,"code":"workspace_result_unknown"}""")
            }
            try {
                for (put in listOf(true, false)) {
                    try {
                        if (put) client.upload(connection(server), "token", space, "a.txt", null, 1, { byteArrayOf(1).inputStream() })
                        else client.mutate(connection(server), "token", space, PlatformWorkspaceFileMutation(PlatformWorkspaceFileMutationAction.MKCOL, "dir"))
                        fail("error accepted")
                    } catch (error: PlatformHttpException) { assertEquals(status, error.status) }
                }
                assertEquals(2, count.get())
            } finally { server.stop(0) }
        }
    }

    @Test fun `unexpected partial cached and incomplete contents are rejected`() = runBlocking {
        for (status in listOf(206, 304, 200)) {
            val server = server {
                if (status == 200) { sendResponseHeaders(200, 100); responseBody.write(byteArrayOf(1)) }
                else sendResponseHeaders(status, -1)
            }
            try {
                try { client.download(connection(server), "token", space, "a.txt", ByteArrayOutputStream()); fail("incomplete accepted") }
                catch (_: java.io.IOException) { }
            } finally { server.stop(0) }
        }
    }

    @Test fun `empty or contradictory successful write response is not confirmed`() = runBlocking {
        for (body in listOf("", """{"outcome":"SUCCEEDED","failures":[],"truncated":true}""", """{"outcome":"PARTIAL","failures":[],"truncated":false}""")) {
            val server = server { requestBody.readBytes(); reply(200, body) }
            try {
                var failed = false
                try { client.upload(connection(server), "token", space, "a.txt", null, 1, { byteArrayOf(1).inputStream() }) }
                catch (_: Exception) { failed = true }
                assertTrue(failed)
            } finally { server.stop(0) }
        }
    }

    @Test fun `201 and empty 204 do not confirm a write or trigger another request`() = runBlocking {
        for (status in listOf(201, 204)) {
            val count = AtomicInteger()
            val server = server {
                count.incrementAndGet(); requestBody.readBytes()
                if (status == 204) sendResponseHeaders(status, -1)
                else reply(status, """{"outcome":"SUCCEEDED","failures":[],"truncated":false}""")
            }
            try {
                try {
                    client.upload(connection(server), "token", space, "a.txt", null, 1, { byteArrayOf(1).inputStream() })
                    fail("unexpected status confirmed the write")
                } catch (error: PlatformHttpException) { assertEquals(status, error.status) }
                assertEquals(1, count.get())
            } finally { server.stop(0) }
        }
    }

    @Test fun `cancelling upload closes a blocked local input before returning`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closed = AtomicBoolean()
        val input = object : InputStream() {
            override fun read(): Int {
                started.complete(Unit)
                check(released.await(10, TimeUnit.SECONDS)) { "input close did not release the blocked reader" }
                throw IOException("input closed")
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int) = read()
            override fun close() { closed.set(true); released.countDown() }
        }
        val server = server {
            try { requestBody.readBytes(); reply(200, """{"outcome":"SUCCEEDED","failures":[],"truncated":false}""") }
            catch (_: IOException) { /* Cancellation closes the request before its advertised body arrives. */ }
        }
        val upload = launch(Dispatchers.Default) { client.upload(connection(server), "token", space, "a.txt", null, 1, { input }) }
        try {
            withTimeout(5_000) { started.await() }
            upload.cancel()
            withTimeout(5_000) { upload.join() }
            assertTrue(upload.isCancelled)
            assertTrue(closed.get())
        } finally { input.close(); upload.cancelAndJoin(); server.stop(0) }
    }

    @Test fun `cancelling download closes a blocked local output before returning`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val closed = AtomicBoolean()
        val output = object : OutputStream() {
            override fun write(value: Int) {
                started.complete(Unit)
                check(released.await(10, TimeUnit.SECONDS)) { "output close did not release the blocked writer" }
                throw IOException("output closed")
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) = write(bytes[offset].toInt())
            override fun close() { closed.set(true); released.countDown() }
        }
        val server = server { reply(200, "content") }
        val download = launch(Dispatchers.Default) { client.download(connection(server), "token", space, "a.txt", output) }
        try {
            withTimeout(5_000) { started.await() }
            download.cancel()
            withTimeout(5_000) { download.join() }
            assertTrue(download.isCancelled)
            assertTrue(closed.get())
        } finally { output.close(); download.cancelAndJoin(); server.stop(0) }
    }

    @Test fun `path aliases and non Core strong etags are rejected`() {
        for (path in listOf("/a", "a//b", "a/../b", "a\\b", ".agent-space-x", "a/%2E", "a/%25", "a\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { WorkspaceFileRules.path(path) }
        }
        for (etag in listOf("W/\"a\"", "*", "\"a\",\"b\"", "\"has space\"", "\"<a>\"")) assertFalse(WorkspaceFileRules.strongEtag(etag))
        assertTrue(WorkspaceFileRules.strongEtag("\"a\""))
    }

    @Test fun `text round trip preserves BOM actual FEFF and newline bytes`() {
        for (newline in listOf("\n", "\r\n", "\r")) for (bom in listOf("", "\uFEFF")) {
            val original = (bom + "\uFEFFalpha${newline}beta${newline}").toByteArray(Charsets.UTF_8)
            val decoded = WorkspaceText.decode(original)
            assertArrayEquals(original, decoded.encode(decoded.text))
        }
        for (invalid in listOf(byteArrayOf(0xc3.toByte()), "a\r\nb\n".toByteArray(), byteArrayOf(0))) {
            assertThrows(Exception::class.java) { WorkspaceText.decode(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceText("", true, "\n").encode("a".repeat(WorkspaceFileRules.TEXT_LIMIT)) }
    }

    @Test fun `markdown image URLs decode once before logical paths enter HTTP`() = runBlocking {
        val paths = mutableListOf<String>()
        val server = server {
            val encoded = requestURI.rawQuery.split('&').single { it.startsWith("path=") }.substringAfter('=')
            paths += java.net.URLDecoder.decode(encoded, "UTF-8")
            reply(200, "image")
        }
        try {
            for ((reference, path) in listOf(
                "images/a%20b.png" to "docs/images/a b.png",
                "../images/%E4%B8%AD%E6%96%87.png" to "images/中文.png",
                "images/a+b.png" to "docs/images/a+b.png",
                "images/中文.png" to "docs/images/中文.png",
            )) {
                val resolved = WorkspaceFileRules.relativeImage("docs/readme.md", reference)
                assertEquals(path, resolved)
                client.download(connection(server), "token", space, resolved, ByteArrayOutputStream())
            }
            assertEquals(listOf("docs/images/a b.png", "images/中文.png", "docs/images/a+b.png", "docs/images/中文.png"), paths)
        } finally { server.stop(0) }
    }

    @Test fun `markdown image URLs reject malformed encodings and path aliases`() {
        for (reference in listOf(
            "/secret.png", "https://example.test/a.png", "//example.test/a.png", "../../secret.png", "a\\b.png",
            "a%2fsecret.png", "%2E%2e/secret.png", "%5csecret.png", "%252e%252e/secret.png", "%00.png",
            "a%.png", "a%2", "a%GG.png", "%C3", "%C0%AE", "%E0%80%AE", "%ED%A0%80", "%F4%90%80%80",
            "%0d.png", "%0a.png", "%3a.png", "%3f.png", "%23.png", "\uD800.png",
        )) {
            assertThrows(reference, IllegalArgumentException::class.java) { WorkspaceFileRules.relativeImage("docs/readme.md", reference) }
        }
    }
}
