package me.rerere.ai.provider.images

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.common.http.isPrivate
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SafeRoutedImageDownloaderTest {
    private val publicAddress = InetAddress.getByName("93.184.216.34")

    @Test fun `download request carries no authority and validates image signature`() = runBlocking {
        val requests = mutableListOf<Request>()
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        val downloader = downloader { request, addresses, _ ->
            requests += request
            assertEquals(listOf(publicAddress), addresses)
            RoutedImageHttpResponse(200, contentType = "image/png; charset=binary", body = png)
        }

        val result = downloader.downloadAsBase64("https://cdn.example/image.png")

        assertEquals("image/png", result.mimeType)
        assertTrue(requests.single().isPrivate)
        assertEquals("image/*", requests.single().header("Accept"))
        assertNull(requests.single().header("Authorization"))
        assertNull(requests.single().header("Cookie"))
    }

    @Test fun `only HTTPS public targets are allowed and every redirect is revalidated`() = runBlocking {
        val contacted = mutableListOf<String>()
        val downloader = SafeRoutedImageDownloader(
            client = OkHttpClient(),
            dnsLookup = { host ->
                if (host == "internal.example") listOf(InetAddress.getByName("10.0.0.1"))
                else listOf(publicAddress)
            },
            transport = RoutedImageHttpTransport { request, _, _ ->
                contacted += request.url.host
                RoutedImageHttpResponse(
                    code = 302,
                    location = "https://internal.example/private.png",
                )
            },
        )

        assertFailure("routed_image_unsafe_url") {
            downloader.downloadAsBase64("http://cdn.example/image.png")
        }
        assertFailure("routed_image_non_public_target") {
            downloader.downloadAsBase64("https://cdn.example/image.png")
        }
        assertEquals(listOf("cdn.example"), contacted)
    }

    @Test fun `payload limit and MIME signature agreement are enforced`() = runBlocking {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        assertFailure("routed_image_payload_limit_exceeded") {
            downloader(maxBytes = 8) { _, _, _ ->
                RoutedImageHttpResponse(200, contentType = "image/png", body = png)
            }.downloadAsBase64("https://cdn.example/image.png")
        }
        assertFailure("routed_image_mime_signature_mismatch") {
            downloader { _, _, _ ->
                RoutedImageHttpResponse(200, contentType = "image/jpeg", body = png)
            }.downloadAsBase64("https://cdn.example/image.png")
        }
        assertFailure("routed_image_invalid_mime") {
            downloader { _, _, _ ->
                RoutedImageHttpResponse(200, contentType = "text/html", body = png)
            }.downloadAsBase64("https://cdn.example/image.png")
        }
    }

    @Test fun `transport cancellation propagates unchanged`() = runBlocking {
        val expected = CancellationException("fixture cancellation")
        val downloader = downloader { _, _, _ -> throw expected }
        try {
            downloader.downloadAsBase64("https://cdn.example/image.png")
            fail("cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test fun `network client removes ambient interceptors cookies authentication redirects and proxy`() {
        val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }
        val passthrough = Interceptor { chain -> chain.proceed(chain.request()) }
        val base = OkHttpClient.Builder()
            .addInterceptor(passthrough)
            .addNetworkInterceptor(passthrough)
            .cookieJar(cookieJar)
            .authenticator { _, response -> response.request }
            .proxyAuthenticator { _, response -> response.request }
            .build()

        val restricted = buildRoutedImageClient(base, "cdn.example", listOf(publicAddress))

        assertTrue(restricted.interceptors.isEmpty())
        assertTrue(restricted.networkInterceptors.isEmpty())
        assertSame(CookieJar.NO_COOKIES, restricted.cookieJar)
        assertSame(Authenticator.NONE, restricted.authenticator)
        assertSame(Authenticator.NONE, restricted.proxyAuthenticator)
        assertFalse(restricted.followRedirects)
        assertFalse(restricted.followSslRedirects)
        assertFalse(restricted.retryOnConnectionFailure)
        assertEquals(java.net.Proxy.NO_PROXY, restricted.proxy)
    }

    @Test fun `real transport uses pinned DNS and does not follow redirects`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            exchange.responseHeaders.add("Location", "http://unexpected.example/leak")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        val client = OkHttpClient()
        try {
            val request = Request.Builder()
                .url("http://cdn.example:${server.address.port}/image")
                .build()
            val response = OkHttpRoutedImageHttpTransport(client).execute(
                request,
                listOf(InetAddress.getByName("127.0.0.1")),
                1_024,
            )

            assertEquals(302, response.code)
            assertEquals("http://unexpected.example/leak", response.location)
            assertEquals(1, requests)
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `real transport aborts a chunked body beyond the limit`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(ByteArray(64) { 1 })
            } catch (_: IOException) {
                // The client is expected to close the stream as soon as the cap is crossed.
            } finally {
                exchange.close()
            }
        }
        server.start()
        val client = OkHttpClient()
        try {
            val request = Request.Builder().url("http://cdn.example:${server.address.port}/image").build()
            assertFailure("routed_image_payload_limit_exceeded") {
                OkHttpRoutedImageHttpTransport(client).execute(
                    request,
                    listOf(InetAddress.getByName("127.0.0.1")),
                    8,
                )
            }
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `cancelling a real transport read cancels its call`() = runBlocking {
        val release = CountDownLatch(1)
        val consuming = CompletableDeferred<Call>()
        val cancelled = CompletableDeferred<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 1_024)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                release.await(30, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.MINUTES)
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    consuming.complete(call)
                }
            })
            .build()
        val request = Request.Builder().url("http://cdn.example:${server.address.port}/image").build()
        val job = launch(Dispatchers.IO) {
            try {
                OkHttpRoutedImageHttpTransport(client).execute(
                    request,
                    listOf(InetAddress.getByName("127.0.0.1")),
                    2_048,
                )
            } catch (error: CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }
        try {
            val call = withTimeout(5_000) { consuming.await() }
            job.cancel()
            withTimeout(5_000) {
                job.join()
                cancelled.await()
            }
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            job.cancel()
            job.join()
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun downloader(
        maxBytes: Long = MAX_ROUTED_IMAGE_BYTES,
        transport: RoutedImageHttpTransport,
    ) = SafeRoutedImageDownloader(
        client = OkHttpClient(),
        maxBytes = maxBytes,
        dnsLookup = { listOf(publicAddress) },
        transport = transport,
    )

    private suspend fun assertFailure(expected: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (error: IOException) {
            assertEquals(expected, error.message)
        }
    }
}
