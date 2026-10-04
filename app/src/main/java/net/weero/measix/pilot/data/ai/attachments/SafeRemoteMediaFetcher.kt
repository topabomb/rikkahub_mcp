package net.weero.measix.pilot.data.ai.attachments

import me.rerere.common.http.readResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.Locale

data class RemoteHttpResponse(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
)

fun interface RemoteHttpTransport {
    suspend fun execute(url: URL, resolvedAddresses: List<InetAddress>): RemoteHttpResponse
}

sealed class RemoteMediaFetchResult {
    data class Success(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String,
    ) : RemoteMediaFetchResult()

    data class Failure(val reason: String, val detail: String? = null) : RemoteMediaFetchResult()
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
    suspend fun fetch(rawUrl: String, allowLocalNetwork: Boolean = false): RemoteMediaFetchResult {
        return try {
            fetchInternal(rawUrl, allowLocalNetwork)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            networkFailure(error)
        }
    }

    private fun networkFailure(error: Exception): RemoteMediaFetchResult.Failure {
        android.util.Log.w("AttachmentFetch", net.weero.measix.pilot.utils.redactDiagnosticSecrets(error.stackTraceToString()))
        val detail = net.weero.measix.pilot.utils.redactDiagnosticSecrets("${error.javaClass.simpleName}: ${error.message.orEmpty()}").take(300)
        return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED, detail)
    }

    private suspend fun fetchInternal(rawUrl: String, allowLocalNetwork: Boolean): RemoteMediaFetchResult {
        var current = parseHttpUrl(rawUrl)
            ?: return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "Invalid HTTP(S) URL.")

        repeat(maxRedirects + 1) { hop ->
            val hostCheck = inspectHost(current.host, allowLocalNetwork)
            if (hostCheck != null) return hostCheck

            val addresses = try {
                runInterruptible(Dispatchers.IO) { dnsLookup(current.host) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return networkFailure(error)
            }
            if (addresses.isEmpty() || addresses.any { isBlockedAddress(it, allowLocalNetwork) }) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "URL or resolved address is not allowed.")
            }

            val response = try {
                transport.execute(current, addresses)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return networkFailure(error)
            }

            if (response.code in 300..399) {
                if (hop >= maxRedirects) {
                    return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "Too many redirects (limit $maxRedirects).")
                }
                val location = headerValue(response.headers, "Location")
                    ?: return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED, "HTTP ${response.code}: redirect has no Location.")
                val next = resolveRedirect(current, location)
                    ?: return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "Invalid redirect URL.")
                if (current.protocol == "https" && next.protocol != "https") {
                    return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "HTTPS redirect to HTTP refused.")
                }
                // Credentials belong to one origin. Redirects cannot introduce credentials.
                val sameOrigin = current.protocol == next.protocol && current.host.equals(next.host, true) &&
                    effectivePort(current) == effectivePort(next)
                current = withUserInfo(next, if (sameOrigin) current.userInfo else null)
                return@repeat
            }

            if (response.code !in 200..299) {
                return RemoteMediaFetchResult.Failure(
                    if (response.code == 413) AttachmentFailureReasons.ATTACHMENT_TOO_LARGE else AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED,
                    when (response.code) {
                        401 -> "HTTP 401: missing or rejected authentication."
                        403 -> "HTTP 403: access denied."
                        404 -> "HTTP 404: resource not found."
                        413 -> "Attachment exceeds the $maxBytes byte limit."
                        else -> "HTTP ${response.code}: attachment fetch failed."
                    },
                )
            }
            if (response.body.isEmpty()) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED, "HTTP ${response.code}: empty response body.")
            }
            if (response.body.size > maxBytes) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.ATTACHMENT_TOO_LARGE, "Attachment exceeds the $maxBytes byte limit.")
            }

            val contentType = headerValue(response.headers, "Content-Type")
            if (ImageMime.isUnsupportedNonImage(response.body, contentType)) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE, "The response is not a supported image; directories and HTML pages cannot be inspected.")
            }
            if (!ImageMime.isAcceptedImage(response.body)) {
                return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE, "The response is not a supported image; directories and HTML pages cannot be inspected.")
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
        return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "URL or resolved address is not allowed.")
    }

    internal fun inspectHost(host: String?, allowLocalNetwork: Boolean = false): RemoteMediaFetchResult.Failure? {
        if (host.isNullOrBlank()) {
            return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "URL or resolved address is not allowed.")
        }
        val normalized = normalizeHost(host)
        if (normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized == "metadata.google.internal" ||
            normalized.endsWith(".internal") ||
            normalized == "0.0.0.0"
        ) {
            return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "URL or resolved address is not allowed.")
        }
        val literal = parseLiteralAddress(host)
        if (literal != null && isBlockedAddress(literal, allowLocalNetwork)) {
            return RemoteMediaFetchResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL, "URL or resolved address is not allowed.")
        }
        return null
    }

    internal fun isBlockedAddress(address: InetAddress, allowLocalNetwork: Boolean = false): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            (!allowLocalNetwork && address.isSiteLocalAddress) ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isBlockedIpv4(address.address)
            is Inet6Address -> if (allowLocalNetwork && address.address[0].toInt() and 0xFE == 0xFC) false else isBlockedIpv6(address)
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

        internal fun basicAuthorization(url: URL): String? {
            val userInfo = url.userInfo ?: return null
            fun decode(value: String) = java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            return okhttp3.Credentials.basic(decode(userInfo.substringBefore(':')), decode(userInfo.substringAfter(':', "")))
        }

        private fun effectivePort(url: URL) = if (url.port >= 0) url.port else url.defaultPort

        private fun withUserInfo(url: URL, userInfo: String?): URL {
            val uri = url.toURI()
            val host = if (':' in url.host && !url.host.startsWith("[")) "[${url.host}]" else url.host
            val authority = (userInfo?.let { "$it@" } ?: "") + host + if (url.port >= 0) ":${url.port}" else ""
            return URL("${url.protocol}://$authority${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}")
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

        private val httpClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .proxy(java.net.Proxy.NO_PROXY)
            .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
            .build()

        internal suspend fun defaultTransport(
            url: URL,
            resolvedAddresses: List<InetAddress>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            maxBytes: Int,
        ): RemoteHttpResponse {
            require(resolvedAddresses.isNotEmpty())
            val cleanUrl = withUserInfo(url, null)
            val client = httpClient.newBuilder()
                .dns { host ->
                    require(host.equals(url.host.removePrefix("[").removeSuffix("]"), true))
                    resolvedAddresses
                }
                .connectTimeout(connectTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout((connectTimeoutMs + readTimeoutMs).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(cleanUrl).header("Accept", "image/*,*/*;q=0.8")
            basicAuthorization(url)?.let { request.header("Authorization", it) }
            return client.newCall(request.build()).readResponse { response ->
                val headers = response.headers.toMultimap()
                val body = response.body
                if (response.code !in 200..299) {
                    RemoteHttpResponse(response.code, headers, ByteArray(0))
                } else if (body.contentLength() > maxBytes) {
                    RemoteHttpResponse(413, headers, ByteArray(0))
                } else body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size().toLong() + read > maxBytes) return@readResponse RemoteHttpResponse(413, headers, ByteArray(0))
                        out.write(buffer, 0, read)
                    }
                    RemoteHttpResponse(response.code, headers, out.toByteArray())
                }
            }
        }
    }
}
