package net.weero.measix.pilot.data.ai.attachments

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRemoteMediaFetcherTest {
    @Test
    fun `loopback literal is rejected before download`() = runTest {
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { error("dns should not run") },
            transport = { _, _ -> error("transport should not run") },
        )
        val result = fetcher.fetch("http://127.0.0.1/secret.png")
        assertEquals(
            AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL,
            (result as RemoteMediaFetchResult.Failure).reason,
        )
    }

    @Test
    fun `dns that resolves to private address is rejected`() = runTest {
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { listOf(InetAddress.getByName("10.0.0.5")) },
            transport = { _, _ -> error("transport should not run") },
        )
        val result = fetcher.fetch("https://evil.example/a.png")
        assertEquals(
            AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL,
            (result as RemoteMediaFetchResult.Failure).reason,
        )
    }

    @Test
    fun `redirect onto loopback is rejected`() = runTest {
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { host ->
                if (host == "cdn.example") listOf(InetAddress.getByName("1.2.3.4"))
                else error("unexpected host $host")
            },
            transport = { url, _ ->
                assertEquals("cdn.example", url.host)
                RemoteHttpResponse(
                    code = 302,
                    headers = mapOf("Location" to listOf("http://127.0.0.1/steal")),
                    body = ByteArray(0),
                )
            },
        )
        val result = fetcher.fetch("https://cdn.example/a.png")
        assertEquals(
            AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL,
            (result as RemoteMediaFetchResult.Failure).reason,
        )
    }

    @Test
    fun `file redirect is rejected`() {
        val resolved = SafeRemoteMediaFetcher.resolveRedirect(
            URL("https://cdn.example/a.png"),
            "file:///etc/passwd",
        )
        assertEquals(null, resolved)
    }

    @Test
    fun `successful png download returns bytes`() = runTest {
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { listOf(InetAddress.getByName("1.2.3.4")) },
            transport = { _, _ ->
                RemoteHttpResponse(
                    code = 200,
                    headers = mapOf("Content-Type" to listOf("image/png")),
                    body = TINY_PNG,
                )
            },
        )
        val result = fetcher.fetch("https://cdn.example/a.png")
        assertTrue(result is RemoteMediaFetchResult.Success)
        assertEquals("image/png", (result as RemoteMediaFetchResult.Success).mimeType)
    }

    @Test
    fun `cancellation from dns is propagated instead of becoming a fetch failure`() = runTest {
        val cancelled = CancellationException("cancelled")
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { throw cancelled },
            transport = { _, _ -> error("transport should not run") },
        )

        try {
            fetcher.fetch("https://cdn.example/a.png")
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancelled.message, actual.message)
        }
    }

    @Test
    fun `cancellation from transport is propagated`() = runTest {
        val cancelled = CancellationException("cancelled")
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { listOf(InetAddress.getByName("1.2.3.4")) },
            transport = { _, _ -> throw cancelled },
        )

        try {
            fetcher.fetch("https://cdn.example/a.png")
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancelled.message, actual.message)
        }
    }

    @Test
    fun `transport is given the already-checked addresses`() = runTest {
        val checked = InetAddress.getByName("1.2.3.4")
        var seen: List<InetAddress>? = null
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { listOf(checked) },
            transport = { _, addresses ->
                seen = addresses
                RemoteHttpResponse(
                    code = 200,
                    headers = mapOf("Content-Type" to listOf("image/png")),
                    body = TINY_PNG,
                )
            },
        )
        fetcher.fetch("https://cdn.example/a.png")
        assertEquals(listOf(checked), seen)
    }

    /**
     * 真实 transport 路径（Android OkHttp / OpenJDK）都是先建 TCP socket、再走
     * createSocket(Socket, host, port, autoClose) 包 SSL。这里直接测试该 overload：
     * 远端地址不等于 pin 必须拒绝，等于 pin 才交给 delegate。
     */
    @Test
    fun `pinning ssl factory rejects already-connected socket to wrong address`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pin = InetAddress.getByName("1.2.3.4")
        val delegate = RecordingSslSocketFactory()
        val factory = PinningSslSocketFactory(delegate, pin, 1_000)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
                try {
                    factory.createSocket(socket, "example.com", server.localPort, true)
                    throw AssertionError("expected pinning rejection")
                } catch (expected: IOException) {
                    assertTrue(expected.message!!.contains("pinned connection refused"))
                }
            }
            assertEquals(0, delegate.invocations)
        } finally {
            server.close()
        }
    }

    @Test
    fun `pinning ssl factory passes through socket already connected to pin`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pin = InetAddress.getByName("127.0.0.1")
        val delegate = RecordingSslSocketFactory()
        val factory = PinningSslSocketFactory(delegate, pin, 1_000)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
                val wrapped = factory.createSocket(socket, "example.com", server.localPort, false)
                assertEquals(1, delegate.invocations)
                assertSame(socket, wrapped)
            }
        } finally {
            server.close()
        }
    }

    private class RecordingSslSocketFactory : SSLSocketFactory() {
        var invocations = 0
        private fun unsupported(): Nothing = error("only the layered overload is expected")

        override fun getDefaultCipherSuites(): Array<String> = emptyArray()
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            invocations++
            return s
        }

        override fun createSocket(host: String, port: Int): Socket = unsupported()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
    }
}
