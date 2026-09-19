package me.rerere.common.http

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ForwardingSink
import okio.Socket

/** Uses the public HTTP upgrade socket with OkHttp's original WebSocket framing and lifecycle. */
class SingleAttemptWebSocketFactory(
    client: OkHttpClient,
    private val token: String,
    private val maxRequestBytes: Long,
) : WebSocket.Factory {
    private val handshakeClient = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 101) response else {
                val detail = response.use {
                    val source = it.body.source()
                    source.request(128L * 1024)
                    source.readUtf8(minOf(source.buffer.size, 128L * 1024)).replace(token, "[redacted]")
                }
                throw RoutedHttpException(response.code, detail.ifBlank { response.message })
            }
        }.build()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val connection = Connection(request)
        val framingClient = OkHttpClient.Builder().addInterceptor { chain ->
            val call = handshakeClient.newCall(chain.request())
            connection.handshake.set(call)
            if (connection.cancelled.get()) call.cancel()
            val response = call.execute()
            val original = response.socket
            if (original == null) response else response.newBuilder().socket(object : Socket by original {
                override val sink = object : ForwardingSink(original.sink) {
                    private var written = 0L
                    override fun write(source: Buffer, byteCount: Long) {
                        if (byteCount > maxRequestBytes - written) {
                            val failure = IOException("platform_websocket_request_limit_exceeded")
                            connection.failure.compareAndSet(null, failure)
                            original.cancel()
                            throw failure
                        }
                        super.write(source, byteCount)
                        written += byteCount
                    }
                }
            }).build()
        }.build()
        connection.bind(framingClient.newWebSocket(request, object : WebSocketListener() {
            private fun owner(socket: WebSocket): WebSocket = connection.also { it.bind(socket) }
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(owner(webSocket), response)
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(owner(webSocket), text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(owner(webSocket), bytes)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosing(owner(webSocket), code, reason)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(owner(webSocket), code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val failure = connection.failure.get() ?: t
                if (failure !== t) failure.addSuppressed(t)
                listener.onFailure(owner(webSocket), failure, response)
            }
        }))
        return connection
    }

    private class Connection(private val request: Request) : WebSocket {
        val handshake = AtomicReference<Call?>()
        val cancelled = AtomicBoolean()
        val failure = AtomicReference<IOException?>()
        private val delegate = AtomicReference<WebSocket?>()
        fun bind(socket: WebSocket) {
            delegate.compareAndSet(null, socket)
            if (cancelled.get()) socket.cancel()
        }
        override fun request() = request
        override fun queueSize() = requireNotNull(delegate.get()).queueSize()
        override fun send(text: String) = requireNotNull(delegate.get()).send(text)
        override fun send(bytes: ByteString) = requireNotNull(delegate.get()).send(bytes)
        override fun close(code: Int, reason: String?) = requireNotNull(delegate.get()).close(code, reason)
        override fun cancel() {
            cancelled.set(true)
            handshake.get()?.cancel()
            delegate.get()?.cancel()
        }
    }
}
