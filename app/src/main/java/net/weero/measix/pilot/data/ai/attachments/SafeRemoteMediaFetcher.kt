package net.weero.measix.pilot.data.ai.attachments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import java.net.HttpURLConnection
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

data class RemoteHttpResponse(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

fun interface RemoteHttpTransport {
    fun execute(url: URL, resolvedAddresses: List<InetAddress>): RemoteHttpResponse
}

sealed class RemoteMediaFetchResult {
    data class Success(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String,
    ) : RemoteMediaFetchResult()

    data class Failure(val reason: String) : RemoteMediaFetchResult()
}

/**
 * Model-controlled HTTP(S) download with SSRF limits. Do not reuse
 * [net.weero.measix.pilot.data.files.ArtifactStore.createFromBytes] for this path.
 */
class SafeRemoteMediaFetcher(
    private val maxBytes: Int = GeneratedMediaStore.MAX_IMAGE_BYTES,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxRedirects: Int = 5,
    private val dnsLookup: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val transport: RemoteHttpTransport = RemoteHttpTransport { url, addresses ->
        defaultTransport(url, addresses, connectTimeoutMs, readTimeoutMs, maxBytes)
    },
) {
    suspend fun fetch(rawUrl: String): RemoteMediaFetchResult {
        return try {
            fetchInternal(rawUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
        }
    }

    private suspend fun fetchInternal(rawUrl: String): RemoteMediaFetchResult {
        var current = parseHttpUrl(rawUrl)
            ?: return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)

        repeat(maxRedirects + 1) { hop ->
            val hostCheck = inspectHost(current.host)
            if (hostCheck != null) return hostCheck

            val addresses = try {
                runInterruptible(Dispatchers.IO) { dnsLookup(current.host) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
            }
            if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
            }

            val response = try {
                runInterruptible(Dispatchers.IO) { transport.execute(current, addresses) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
            }

            if (response.code in 300..399) {
                if (hop >= maxRedirects) {
                    return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
                }
                val location = headerValue(response.headers, "Location")
                    ?: return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
                current = resolveRedirect(current, location)
                    ?: return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
                return@repeat
            }

            if (response.code !in 200..299) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
            }
            if (response.body.isEmpty()) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
            }
            if (response.body.size > maxBytes) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
            }

            val contentType = headerValue(response.headers, "Content-Type")
            if (ImageMime.isUnsupportedNonImage(response.body, contentType)) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
            }
            if (!ImageMime.isAcceptedImage(response.body)) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
            }

            val mime = requireNotNull(ImageMime.sniff(response.body)) {
                "validated image MIME is unavailable"
            }
            val fileName = guessFileName(current, headerValue(response.headers, "Content-Disposition"), mime)
            return RemoteMediaFetchResult.Success(
                bytes = response.body,
                mimeType = mime,
                fileName = fileName,
            )
        }
        return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
    }

    internal fun inspectHost(host: String?): RemoteMediaFetchResult.Failure? {
        if (host.isNullOrBlank()) {
            return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
        }
        val normalized = normalizeHost(host)
        if (normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized == "metadata.google.internal" ||
            normalized.endsWith(".internal") ||
            normalized == "0.0.0.0"
        ) {
            return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
        }
        val literal = parseLiteralAddress(host)
        if (literal != null && isBlockedAddress(literal)) {
            return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
        }
        return null
    }

    internal fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isBlockedIpv4(address.address)
            is Inet6Address -> isBlockedIpv6(address)
            else -> true
        }
    }

    companion object {
        internal fun parseHttpUrl(raw: String): URL? {
            val trimmed = raw.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            return runCatching { uri.toURL() }.getOrNull()
        }

        internal fun resolveRedirect(current: URL, location: String): URL? {
            val trimmed = location.trim()
            if (trimmed.isEmpty()) return null
            val lower = trimmed.lowercase(Locale.US)
            if (lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("javascript:")) {
                return null
            }
            val resolved = runCatching { URI(current.toString()).resolve(trimmed).toURL() }.getOrNull()
                ?: return null
            val scheme = resolved.protocol.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return null
            if (resolved.host.isNullOrBlank()) return null
            return resolved
        }

        private fun normalizeHost(host: String): String {
            val ascii = runCatching { IDN.toASCII(host) }.getOrDefault(host)
            return ascii.trim('.').lowercase(Locale.US)
        }

        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        private fun parseLiteralAddress(host: String): InetAddress? {
            val cleaned = host.removePrefix("[").removeSuffix("]")
            if (!IPV4_LITERAL.matches(cleaned) && ':' !in cleaned) return null
            return runCatching { InetAddress.getByName(cleaned) }.getOrNull()
        }

        private fun isBlockedIpv4(bytes: ByteArray): Boolean {
            if (bytes.size != 4) return true
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0) return true
            if (first == 100 && second in 64..127) return true
            if (first == 169 && second == 254) return true
            if (first == 192 && second == 0 && (bytes[2].toInt() and 0xFF) == 0) return true
            if (first == 198 && second in 18..19) return true
            return false
        }

        private fun isBlockedIpv6(address: Inet6Address): Boolean {
            val bytes = address.address
            if (bytes.size != 16) return true
            if (address.isIPv4CompatibleAddress || isIpv4Mapped(bytes)) {
                val v4 = bytes.copyOfRange(12, 16)
                if (isBlockedEmbeddedIpv4(v4)) return true
            }
            // NAT64 64:ff9b::/96
            if (bytes[0] == 0x00.toByte() && bytes[1] == 0x64.toByte() &&
                bytes[2] == 0xFF.toByte() && bytes[3] == 0x9B.toByte()
            ) {
                if (isBlockedEmbeddedIpv4(bytes.copyOfRange(12, 16))) return true
            }
            // 6to4 2002::/16
            if (bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()) {
                if (isBlockedEmbeddedIpv4(bytes.copyOfRange(2, 6))) return true
            }
            // Unique local fc00::/7
            if (bytes[0].toInt() and 0xFE == 0xFC) return true
            return false
        }

        private fun isBlockedEmbeddedIpv4(v4: ByteArray): Boolean {
            val mapped = runCatching { InetAddress.getByAddress(v4) }.getOrNull() ?: return true
            return mapped.isLoopbackAddress ||
                mapped.isSiteLocalAddress ||
                mapped.isLinkLocalAddress ||
                mapped.isAnyLocalAddress ||
                mapped.isMulticastAddress ||
                isBlockedIpv4(v4)
        }

        private fun isIpv4Mapped(bytes: ByteArray): Boolean {
            if (bytes.size != 16) return false
            for (i in 0 until 10) if (bytes[i] != 0.toByte()) return false
            return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        }

        private fun headerValue(headers: Map<String, List<String>>, name: String): String? {
            val values = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            return values?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun guessFileName(url: URL, contentDisposition: String?, mime: String): String {
            val fromHeader = contentDisposition
                ?.substringAfter("filename=", "")
                ?.trim()
                ?.trim('"')
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() && !it.contains("..") }
            val fromPath = url.path.substringAfterLast('/').takeIf { it.isNotBlank() && '.' in it }
            val ext = when (mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                else -> "img"
            }
            return fromHeader ?: fromPath ?: "remote.$ext"
        }

        private fun defaultTransport(
            url: URL,
            resolvedAddresses: List<InetAddress>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            maxBytes: Int,
        ): RemoteHttpResponse {
            val pin = resolvedAddresses.firstOrNull()
                ?: return RemoteHttpResponse(code = 499, headers = emptyMap(), body = ByteArray(0))
            val connection = openPinnedConnection(url, pin, connectTimeoutMs)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            try {
                connection.connect()
                val code = connection.responseCode
                val headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key }
                    .mapValues { it.value.orEmpty() }
                val contentLength = connection.contentLengthLong
                if (contentLength > maxBytes) {
                    return RemoteHttpResponse(code = 413, headers = headers, body = ByteArray(0))
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val out = java.io.ByteArrayOutputStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (out.size() + read > maxBytes) {
                            return RemoteHttpResponse(code = 413, headers = headers, body = ByteArray(0))
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                return RemoteHttpResponse(code = code, headers = headers, body = body)
            } finally {
                connection.disconnect()
            }
        }

        private fun openPinnedConnection(
            url: URL,
            pin: InetAddress,
            connectTimeoutMs: Int,
        ): HttpURLConnection {
            val originalHost = url.host
            val hostHeader = if (url.port != -1) "$originalHost:${url.port}" else originalHost
            return if (url.protocol.equals("https", ignoreCase = true)) {
                val connection = url.openConnection() as HttpsURLConnection
                val delegate = connection.sslSocketFactory ?: HttpsURLConnection.getDefaultSSLSocketFactory()
                connection.sslSocketFactory = PinningSslSocketFactory(delegate, pin, connectTimeoutMs)
                connection.hostnameVerifier = HostnameVerifier { hostname, session ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(originalHost, session) ||
                        HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                }
                connection.setRequestProperty("Host", hostHeader)
                connection
            } else {
                val ipHost = if (pin is Inet6Address) "[${pin.hostAddress}]" else pin.hostAddress
                val pinned = URL(url.protocol, ipHost, url.port, url.file)
                val connection = pinned.openConnection() as HttpURLConnection
                connection.setRequestProperty("Host", hostHeader)
                connection
            }
        }
    }
}

internal class PinningSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val pin: InetAddress,
    private val connectTimeoutMs: Int,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    /**
     * Android (com.android.okhttp) 与 OpenJDK 的 HttpsURLConnection 都先自建 TCP socket、
     * 再通过本 overload 包装 SSL；平台自己解析 DNS，因此这里必须校验已连接地址等于 pin，
     * 否则 DNS rebinding 会绕过整个 pinning。
     */
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val remote = (s.remoteSocketAddress as? InetSocketAddress)?.address
        if (remote == null || remote != pin) {
            runCatching { s.close() }
            throw java.io.IOException("pinned connection refused: unexpected remote address $remote")
        }
        return delegate.createSocket(s, host, port, autoClose)
    }

    override fun createSocket(host: String, port: Int): Socket = connectPinned(host, port)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = connectPinned(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket = connectPinned(host.hostName, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = connectPinned(address.hostName, port)

    private fun connectPinned(sniHost: String, port: Int): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(pin, port), connectTimeoutMs)
        return delegate.createSocket(raw, sniHost, port, true)
    }
}
