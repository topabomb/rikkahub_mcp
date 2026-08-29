package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * MCP OAuth loopback 回调接收器（RFC 8252）。
 *
 * 它只拥有一次授权所需的 loopback socket 与 callback session：
 * - 只绑定 loopback（优先 127.0.0.1，回退 [::1]），绝不监听 0.0.0.0；
 * - 每次授权绑定 OS 分配的 ephemeral port（port 0），redirect path 固定为 [CALLBACK_PATH]；
 * - 只有 exact path、exact state 且恰好携带 code/error 的请求才能完成 session；
 * - 无效或畸形请求只结束当前连接，listener 继续等待合法回调；
 * - request line/header/query 均有硬上限，响应带 `Cache-Control: no-store`。
 *
 * 固定 path 是 client registration identity 的一部分；RFC 8252 只允许 loopback 端口变化，
 * 不允许每次授权随机改变 path。会话防伪由 coordinator 生成的高熵 state 独占。
 */
internal class McpOAuthCallbackServer {

    data class Callback(
        val state: String,
        val code: String?,
        val error: String?,
    )

    private var serverSocket: ServerSocket? = null
    private var redirectUriValue: String? = null
    private var expectedStateValue: String? = null
    private val pending = CompletableFuture<Callback?>()

    val redirectUri: String
        get() = checkNotNull(redirectUriValue) { "callback server not started" }

    /** 绑定 loopback 端口并派生 redirect_uri；失败抛错，不留下半开 socket。 */
    fun start(expectedState: String) {
        check(serverSocket == null) { "callback server already started" }
        require(expectedState.isNotBlank()) { "OAuth callback state must not be blank" }
        val socket = bindLoopback()
        serverSocket = socket
        expectedStateValue = expectedState
        redirectUriValue = buildRedirectUri(socket)
        acceptLoop(socket)
    }

    /** 使用实际成功绑定的 loopback 地址拼 URI；IPv6 字面量按 RFC 3986 加方括号。 */
    private fun buildRedirectUri(socket: ServerSocket): String {
        val host = socket.inetAddress.hostAddress ?: "127.0.0.1"
        val displayHost = if (host.contains(':')) "[$host]" else host
        return "http://$displayHost:${socket.localPort}$CALLBACK_PATH"
    }

    /** 等待唯一合法 callback。超时返回 null；调用方取消会关闭 socket 并传播。 */
    suspend fun awaitCallback(timeout: Duration): Callback? {
        if (serverSocket == null) return null
        return try {
            withTimeout(timeout.inWholeMilliseconds) { pending.awaitCancellable() }
        } catch (_: TimeoutCancellationException) {
            close()
            null
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }

    fun close() {
        serverSocket?.close()
        serverSocket = null
        pending.complete(null)
    }

    private fun acceptLoop(socket: ServerSocket) {
        Thread {
            try {
                while (!socket.isClosed && !pending.isDone) {
                    val client = try {
                        socket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    val callback = try {
                        processCallback(client)
                    } catch (_: SocketTimeoutException) {
                        client.close()
                        null
                    } catch (_: RequestLimitExceededException) {
                        writeAndClose(client, 431, "Request Header Fields Too Large")
                        null
                    } catch (_: Throwable) {
                        client.close()
                        null
                    }
                    if (callback != null && pending.complete(callback)) {
                        socket.close()
                        return@Thread
                    }
                }
                pending.complete(null)
            } catch (error: Throwable) {
                pending.completeExceptionally(error)
            }
        }.apply {
            isDaemon = true
            name = "mcp-oauth-callback"
            start()
        }
    }

    private fun bindLoopback(): ServerSocket {
        var lastFailure: Throwable? = null
        for (address in listOf("127.0.0.1", "::1")) {
            try {
                return ServerSocket().apply {
                    reuseAddress = false
                    bind(InetSocketAddress(InetAddress.getByName(address), 0))
                }
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        throw IllegalStateException("无法在 loopback 上启动 OAuth 回调服务", lastFailure)
    }

    /** 返回 null 表示当前连接无效，但不能消费整个授权 session。 */
    private fun processCallback(client: Socket): Callback? {
        if (!client.inetAddress.isLoopbackAddress) {
            client.close()
            return null
        }
        client.use { socket ->
            socket.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_LENGTH) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size != 3 || parts[0] != "GET" || !parts[2].startsWith("HTTP/1.")) {
                writeResponse(socket, 405, "Method Not Allowed")
                return null
            }
            if (!drainHeaders(input)) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }

            val requestTarget = parts[1]
            val path = requestTarget.substringBefore('?')
            if (path != CALLBACK_PATH) {
                writeResponse(socket, 404, "Not Found")
                return null
            }
            val query = requestTarget.substringAfter('?', "")
            if (query.length > MAX_QUERY_LENGTH) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            val params = parseQuery(query) ?: run {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            val state = params["state"].orEmpty()
            if (state != expectedStateValue) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            val code = params["code"]?.takeIf(String::isNotBlank)
            val error = params["error"]?.takeIf(String::isNotBlank)
            if ((code == null) == (error == null)) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            writeResponse(socket, 200, "OK")
            return Callback(state = state, code = code, error = error)
        }
    }

    private fun drainHeaders(input: InputStream): Boolean {
        var total = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_LINE_LENGTH) ?: return false
            total += line.length + 2
            if (total > MAX_HEADER_LENGTH) throw RequestLimitExceededException()
            if (line.isEmpty()) return true
        }
    }

    private fun readAsciiLine(input: InputStream, maxLength: Int): String? {
        val bytes = ByteArrayOutputStream(minOf(maxLength, 256))
        while (true) {
            val value = input.read()
            if (value < 0) return bytes.takeIf { it.size() > 0 }?.toString(Charsets.US_ASCII.name())
            if (value == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            if (bytes.size() >= maxLength) throw RequestLimitExceededException()
            bytes.write(value)
        }
    }

    private fun writeAndClose(socket: Socket, status: Int, reason: String) {
        try {
            writeResponse(socket, status, reason)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun writeResponse(socket: Socket, status: Int, reason: String) {
        runCatching {
            val body = if (status == 200) {
                "<html><body><h3>You can close this window now.</h3></body></html>"
            } else {
                reason
            }
            socket.getOutputStream().use { out ->
                out.write(
                    ("HTTP/1.1 $status $reason\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n" +
                        body).toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    /** 重复参数会产生歧义，整个当前请求拒绝。 */
    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            val index = pair.indexOf('=')
            if (index < 0) return null
            val key = decode(pair.substring(0, index)) ?: return null
            if (key in result) return null
            result[key] = decode(pair.substring(index + 1)) ?: return null
        }
        return result
    }

    private fun decode(value: String): String? = try {
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val CALLBACK_PATH = "/callback"
        const val MAX_QUERY_LENGTH = 4096
        const val MAX_REQUEST_LINE_LENGTH = 8192
        const val MAX_HEADER_LINE_LENGTH = 4096
        const val MAX_HEADER_LENGTH = 16 * 1024
        private const val CLIENT_READ_TIMEOUT_MS = 2_000
    }
}

private class RequestLimitExceededException : IllegalArgumentException()

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error != null) {
                continuation.resumeWithException(error)
            } else {
                continuation.resume(value)
            }
        }
        continuation.invokeOnCancellation { cancel(false) }
    }

/** 使用 Chrome Custom Tabs 打开授权 URL。 */
fun launchOAuthAuthorization(context: Context, authorizationUrl: String) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.launchUrl(context, authorizationUrl.toUri())
}
