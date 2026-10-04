package net.weero.measix.pilot.data.ai.attachments

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.async
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

    @Test fun `inspection allows LAN but still rejects loopback and metadata`() = runTest {
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { listOf(InetAddress.getByName("192.168.1.10")) },
            transport = { _, _ -> RemoteHttpResponse(200, emptyMap(), TINY_PNG) },
        )
        assertTrue(fetcher.fetch("http://192.168.1.10/image.png", true) is RemoteMediaFetchResult.Success)
        assertTrue(fetcher.fetch("http://127.0.0.1/image.png", true) is RemoteMediaFetchResult.Failure)
        assertTrue(fetcher.fetch("http://169.254.169.254/image.png", true) is RemoteMediaFetchResult.Failure)
    }

    @Test fun `Basic credentials survive same origin redirect but not cross origin`() = runTest {
        val authorizations = mutableListOf<String?>()
        val fetcher = SafeRemoteMediaFetcher(
            dnsLookup = { listOf(InetAddress.getByName("1.2.3.4")) },
            transport = { url, _ ->
                authorizations += SafeRemoteMediaFetcher.basicAuthorization(url)
                when (authorizations.size) {
                    1 -> RemoteHttpResponse(302, mapOf("Location" to listOf("https://a.example/next")), ByteArray(0))
                    2 -> RemoteHttpResponse(302, mapOf("Location" to listOf("https://injected:secret@b.example/image.png")), ByteArray(0))
                    else -> RemoteHttpResponse(200, emptyMap(), TINY_PNG)
                }
            },
        )
        assertTrue(fetcher.fetch("https://a%2Bb:p%40ss@a.example/image.png") is RemoteMediaFetchResult.Success)
        assertEquals(listOf(okhttp3.Credentials.basic("a+b", "p@ss"), okhttp3.Credentials.basic("a+b", "p@ss"), null), authorizations)
    }

    @Test fun `HTTP failures explain authentication without copying secret URLs`() = runTest {
        val fetcher = SafeRemoteMediaFetcher(dnsLookup = { listOf(InetAddress.getByName("1.2.3.4")) },
            transport = { _, _ -> RemoteHttpResponse(401, emptyMap(), ByteArray(0)) })
        val result = fetcher.fetch("https://user:secret@a.example/image.png") as RemoteMediaFetchResult.Failure
        assertEquals("HTTP 401: missing or rejected authentication.", result.detail)
    }

    @Test fun `real transport pins DNS sends Basic and rejects oversized body`() = kotlinx.coroutines.runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val request = kotlinx.coroutines.CompletableDeferred<String>()
            val worker = kotlin.concurrent.thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    request.complete(lines.joinToString("\n"))
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4000000000\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            val result = SafeRemoteMediaFetcher.defaultTransport(URL("http://user:pass@assets.invalid:${server.localPort}/image"),
                listOf(InetAddress.getByName("127.0.0.1")), 1000, 1000, 1024)
            assertEquals(413, result.code)
            val sent = request.await()
            assertTrue(sent.contains("Host: assets.invalid:"))
            assertTrue(sent.contains("Authorization: ${okhttp3.Credentials.basic("user", "pass")}"))
            worker.join(2000)
        }
    }

    @Test fun `cancellation closes a slow response before read timeout`() = kotlinx.coroutines.runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            kotlin.concurrent.thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nx".toByteArray())
                    socket.getOutputStream().flush()
                    started.complete(Unit)
                    try { while (socket.getInputStream().read() != -1) { } } finally { closed.complete(Unit) }
                }
            }
            val call = async {
                SafeRemoteMediaFetcher.defaultTransport(URL("http://assets.invalid:${server.localPort}/slow"),
                    listOf(InetAddress.getByName("127.0.0.1")), 1000, 15000, 1024)
            }
            started.await()
            kotlinx.coroutines.withTimeout(2000) { call.cancel(); call.join(); closed.await() }
            assertTrue(call.isCancelled)
        }
    }
}
