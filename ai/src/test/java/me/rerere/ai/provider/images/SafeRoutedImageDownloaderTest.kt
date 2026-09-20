package me.rerere.ai.provider.images

import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.common.http.isPrivate
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
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
