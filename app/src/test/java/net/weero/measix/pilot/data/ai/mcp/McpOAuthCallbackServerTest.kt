package net.weero.measix.pilot.data.ai.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * loopback OAuth 回调接收器的契约：只绑定 loopback、固定 exact path、精确 state、
 * 单次 code/error、有界 HTTP 输入、no-store 响应与超时/取消释放。
 */
class McpOAuthCallbackServerTest {

    private fun startedServer(state: String = "expected-state") =
        McpOAuthCallbackServer().also { it.start(expectedState = state) }

    private fun get(uri: String): HttpURLConnection {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.useCaches = false
        return connection
    }

    /** 从 redirect URI 解出 authority（host[:port]），host 可能是 IPv6 字面量 [::1]。 */
    private fun authorityOf(uri: String): String = uri.substringAfter("http://").substringBefore("/")

    private fun portOf(uri: String): Int {
        val authority = authorityOf(uri)
        return if (authority.startsWith("[")) {
            authority.substringAfter("]:").toInt()
        } else {
            authority.substringAfter(":").toInt()
        }
    }

    @Test
    fun `start binds a loopback ephemeral port with a fixed path`() {
        val server = startedServer()
        try {
            val uri = server.redirectUri
            assertTrue(uri.startsWith("http://"))
            assertTrue(uri.endsWith(McpOAuthCallbackServer.CALLBACK_PATH))
            val authority = authorityOf(uri)
            assertTrue(
                "redirect host must be a loopback literal: $authority",
                authority.startsWith("127.0.0.1:") || authority.startsWith("[::1]:"),
            )
            assertTrue("ephemeral port must be > 0", portOf(uri) > 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun `wrong path is rejected and produces no callback`() {
        val server = startedServer()
        val authority = authorityOf(server.redirectUri)
        try {
            val wrongPath = "http://$authority/callback/not-the-path?state=expected-state&code=wrong"
            assertEquals(404, get(wrongPath).responseCode)
            val callback = runBlocking { server.awaitCallback(200.milliseconds) }
            assertNull(callback)
        } finally {
            server.close()
        }
    }

    @Test
    fun `accepts one code callback and returns no-store response`() {
        val server = startedServer("the-state")
        val redirect = server.redirectUri
        try {
            var responseCode = -1
            var cacheControl: String? = null
            val callback = runBlocking {
                val deferred = async { server.awaitCallback(5.seconds) }
                val connection = get("$redirect?state=the-state&code=auth-code-1")
                responseCode = connection.responseCode
                cacheControl = connection.getHeaderField("Cache-Control")
                deferred.await()
            }
            assertNotNull(callback)
            assertEquals("the-state", callback?.state)
            assertEquals("auth-code-1", callback?.code)
            assertNull(callback?.error)
            assertEquals(200, responseCode)
            assertEquals("no-store", cacheControl)
        } finally {
            server.close()
        }
    }

    @Test
    fun `accepts an error callback without code`() {
        val server = startedServer("s1")
        val redirect = server.redirectUri
        try {
            val callback = runBlocking {
                val deferred = async { server.awaitCallback(5.seconds) }
                val connection = get("$redirect?state=s1&error=access_denied")
                connection.responseCode
                connection.disconnect()
                deferred.await()
            }
            assertNotNull(callback)
            assertEquals("access_denied", callback?.error)
            assertNull(callback?.code)
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects missing state and oversized query`() {
        val server = startedServer()
        val redirect = server.redirectUri
        try {
            val callback = runBlocking {
                val deferred = async { server.awaitCallback(200.milliseconds) }
                get("$redirect?code=orphan-code").responseCode
                deferred.await()
            }
            assertNull(callback)
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects oversized query`() {
        val server = startedServer()
        val redirect = server.redirectUri
        try {
            val oversized = "s=" + "x".repeat(McpOAuthCallbackServer.MAX_QUERY_LENGTH + 10)
            val callback = runBlocking {
                val deferred = async { server.awaitCallback(200.milliseconds) }
                get("$redirect?$oversized").responseCode
                deferred.await()
            }
            assertNull(callback)
        } finally {
            server.close()
        }
    }

    @Test
    fun `invalid path and unknown state do not consume the valid callback`() {
        val server = startedServer("valid-state")
        val redirect = server.redirectUri
        try {
            val callback = runBlocking {
                val deferred = async { server.awaitCallback(5.seconds) }
                assertEquals(404, get("${redirect}suffix?state=valid-state&code=wrong-path").responseCode)
                assertEquals(400, get("$redirect?state=unknown&code=wrong-state").responseCode)
                assertEquals(200, get("$redirect?state=valid-state&code=valid-code").responseCode)
                deferred.await()
            }
            assertEquals("valid-code", callback?.code)
        } finally {
            server.close()
        }
    }

    @Test
    fun `malformed percent encoding does not consume the valid callback`() {
        val server = startedServer("valid-state")
        val redirect = server.redirectUri
        try {
            val callback = runBlocking {
                val deferred = async { server.awaitCallback(5.seconds) }
                assertEquals(400, get("$redirect?state=valid-state&code=%").responseCode)
                assertEquals(200, get("$redirect?state=valid-state&code=valid-code").responseCode)
                deferred.await()
            }
            assertEquals("valid-code", callback?.code)
        } finally {
            server.close()
        }
    }

    @Test
    fun `oversized request line is bounded and listener still accepts a valid callback`() {
        val server = startedServer("valid-state")
        val redirect = URL(server.redirectUri)
        try {
            val callback = runBlocking {
                val deferred = async { server.awaitCallback(5.seconds) }
                val host = redirect.host.removePrefix("[").removeSuffix("]")
                Socket(InetAddress.getByName(host), redirect.port).use { socket ->
                    val oversized = "GET /" + "x".repeat(McpOAuthCallbackServer.MAX_REQUEST_LINE_LENGTH + 1) +
                        " HTTP/1.1\r\n\r\n"
                    socket.getOutputStream().write(oversized.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
                assertEquals(
                    200,
                    get("${server.redirectUri}?state=valid-state&code=after-oversized").responseCode,
                )
                deferred.await()
            }
            assertEquals("after-oversized", callback?.code)
        } finally {
            server.close()
        }
    }

    @Test
    fun `timeout returns null promptly`() {
        val server = startedServer()
        val started = System.currentTimeMillis()
        val callback = runBlocking { server.awaitCallback(300.milliseconds) }
        val elapsed = System.currentTimeMillis() - started
        assertNull(callback)
        assertTrue("timeout must fire promptly, took $elapsed ms", elapsed < 5_000)
        server.close()
    }

    @Test
    fun `cancellation releases the accept thread and close is idempotent`() {
        val server = startedServer()
        runBlocking {
            val job = launch { server.awaitCallback(60.seconds) }
            delay(50)
            job.cancelAndJoin()
        }
        server.close()
        server.close()
    }
}
