package me.rerere.ai.provider.images

import java.io.IOException
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.detectImageMimeTypeBySignature
import me.rerere.common.http.PrivateRequest
import me.rerere.common.http.readResponse
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val MAX_ROUTED_IMAGE_BYTES = 20L * 1024 * 1024
internal const val MAX_ROUTED_IMAGE_REDIRECTS = 5

internal data class RoutedImageHttpResponse(
    val code: Int,
    val location: String? = null,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
)

internal fun interface RoutedImageHttpTransport {
    suspend fun execute(
        request: Request,
        resolvedAddresses: List<InetAddress>,
        maxBytes: Long,
    ): RoutedImageHttpResponse
}

/**
 * Downloads an enterprise relay's model-controlled image URL without carrying relay authority.
 * Every redirect is an independent HTTPS/public-address decision and DNS is pinned for the call.
 */
internal class SafeRoutedImageDownloader(
    client: OkHttpClient,
    private val maxBytes: Long = MAX_ROUTED_IMAGE_BYTES,
    private val maxRedirects: Int = MAX_ROUTED_IMAGE_REDIRECTS,
    private val dnsLookup: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val transport: RoutedImageHttpTransport = OkHttpRoutedImageHttpTransport(client),
) {
    init {
        require(maxBytes > 0) { "routed_image_invalid_size_limit" }
        require(maxRedirects >= 0) { "routed_image_invalid_redirect_limit" }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun downloadAsBase64(rawUrl: String): ImageGenerationItem {
        var current = requireSafeHttpsUrl(rawUrl)

        repeat(maxRedirects + 1) { hop ->
            val addresses = runInterruptible(Dispatchers.IO) { dnsLookup(current.host) }
            if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
                throw IOException("routed_image_non_public_target")
            }

            val request = Request.Builder()
                .url(current)
                .tag(PrivateRequest::class.java, PrivateRequest)
                .header("Accept", "image/*")
                .get()
                .build()
            val response = transport.execute(request, addresses, maxBytes)

            if (response.code in REDIRECT_CODES) {
                if (hop >= maxRedirects) throw IOException("routed_image_redirect_limit_exceeded")
                val location = response.location?.trim().orEmpty()
                if (location.isEmpty()) throw IOException("routed_image_redirect_without_location")
                current = requireSafeHttpsUrl(
                    current.resolve(location)?.toString()
                        ?: throw IOException("routed_image_invalid_redirect"),
                )
                return@repeat
            }
            if (response.code !in 200..299) {
                throw IOException("routed_image_download_http_${response.code}")
            }
            if (response.body.isEmpty()) throw IOException("routed_image_empty_payload")
            if (response.body.size.toLong() > maxBytes) {
                throw IOException("routed_image_payload_limit_exceeded")
            }

            val declaredMime = normalizeImageMime(response.contentType)
                ?: throw IOException("routed_image_invalid_mime")
            val detectedMime = detectImageMimeTypeBySignature(response.body)
                ?: throw IOException("routed_image_invalid_signature")
            if (declaredMime != normalizeImageMime(detectedMime)) {
                throw IOException("routed_image_mime_signature_mismatch")
            }
            return ImageGenerationItem(
                data = Base64.encode(response.body),
                mimeType = detectedMime,
            )
        }
        throw IOException("routed_image_redirect_limit_exceeded")
    }

    private fun requireSafeHttpsUrl(rawUrl: String): HttpUrl {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IOException("routed_image_invalid_url")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || isBlockedHostName(url.host)
        ) {
            throw IOException("routed_image_unsafe_url")
        }
        return url
    }

    private fun isBlockedHostName(host: String): Boolean {
        val normalized = runCatching { IDN.toASCII(host) }.getOrDefault(host)
            .trim('.').lowercase(Locale.US)
        return normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized == "metadata.google.internal" ||
            normalized.endsWith(".internal") ||
            normalized == "0.0.0.0"
    }

    internal fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isBlockedIpv4(address.address)
            is Inet6Address -> isBlockedIpv6(address)
            else -> true
        }
    }

    private fun isBlockedIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return true
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        val third = bytes[2].toInt() and 0xFF
        return first == 0 ||
            first == 100 && second in 64..127 ||
            first == 169 && second == 254 ||
            first == 192 && second == 0 && third == 0 ||
            first == 192 && second == 0 && third == 2 ||
            first == 192 && second == 88 && third == 99 ||
            first == 198 && second in 18..19 ||
            first == 198 && second == 51 && third == 100 ||
            first == 203 && second == 0 && third == 113 ||
            first >= 240
    }

    private fun isBlockedIpv6(address: Inet6Address): Boolean {
        val bytes = address.address
        if (bytes.size != 16) return true
        if (address.isIPv4CompatibleAddress || isIpv4Mapped(bytes)) {
            return isBlockedEmbeddedIpv4(bytes.copyOfRange(12, 16))
        }
        if (matchesPrefix(bytes, byteArrayOf(0x00, 0x64, 0xFF.toByte(), 0x9B.toByte()), 32)) {
            return isBlockedEmbeddedIpv4(bytes.copyOfRange(12, 16))
        }
        if (matchesPrefix(bytes, byteArrayOf(0x20, 0x02), 16)) {
            return isBlockedEmbeddedIpv4(bytes.copyOfRange(2, 6))
        }
        return bytes[0].toInt() and 0xFE == 0xFC ||
            matchesPrefix(bytes, byteArrayOf(0x20, 0x01, 0x0D, 0xB8.toByte()), 32) ||
            matchesPrefix(bytes, byteArrayOf(0x20, 0x01, 0x00, 0x00), 32)
    }

    private fun isBlockedEmbeddedIpv4(bytes: ByteArray): Boolean {
        val address = runCatching { InetAddress.getByAddress(bytes) }.getOrNull() ?: return true
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress || isBlockedIpv4(bytes)
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()

    private fun matchesPrefix(address: ByteArray, prefix: ByteArray, bits: Int): Boolean {
        val fullBytes = bits / 8
        if (address.size < fullBytes || prefix.size < fullBytes) return false
        return (0 until fullBytes).all { address[it] == prefix[it] }
    }

    private fun normalizeImageMime(raw: String?): String? {
        val mime = raw?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            ?.takeIf { it.startsWith("image/") } ?: return null
        return when (mime) {
            "image/jpg" -> "image/jpeg"
            "image/x-png" -> "image/png"
            "image/heif" -> "image/heic"
            else -> mime
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

private class OkHttpRoutedImageHttpTransport(
    private val baseClient: OkHttpClient,
) : RoutedImageHttpTransport {
    override suspend fun execute(
        request: Request,
        resolvedAddresses: List<InetAddress>,
        maxBytes: Long,
    ): RoutedImageHttpResponse {
        val client = buildRoutedImageClient(baseClient, request.url.host, resolvedAddresses)
        return client.newCall(request).readResponse { response ->
            val redirect = response.code in setOf(300, 301, 302, 303, 307, 308)
            RoutedImageHttpResponse(
                code = response.code,
                location = response.header("Location"),
                contentType = response.header("Content-Type"),
                body = if (response.isSuccessful && !redirect) {
                    response.readBoundedBody(maxBytes)
                } else {
                    ByteArray(0)
                },
            )
        }
    }
}

internal fun buildRoutedImageClient(
    baseClient: OkHttpClient,
    expectedHost: String,
    resolvedAddresses: List<InetAddress>,
): OkHttpClient = baseClient.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
    authenticator(Authenticator.NONE)
    proxyAuthenticator(Authenticator.NONE)
    cookieJar(CookieJar.NO_COOKIES)
    proxy(Proxy.NO_PROXY)
    connectionPool(ConnectionPool())
    dns { host ->
        if (!host.equals(expectedHost, ignoreCase = true)) {
            throw UnknownHostException("routed_image_unexpected_dns_host")
        }
        resolvedAddresses
    }
}.build()

private fun okhttp3.Response.readBoundedBody(maxBytes: Long): ByteArray {
    val length = body.contentLength()
    if (length > maxBytes) throw IOException("routed_image_payload_limit_exceeded")
    val source = body.source()
    val output = Buffer()
    var total = 0L
    while (true) {
        val read = source.read(output, minOf(8_192L, maxBytes - total + 1L))
        if (read == -1L) break
        total += read
        if (total > maxBytes) throw IOException("routed_image_payload_limit_exceeded")
    }
    return output.readByteArray()
}
