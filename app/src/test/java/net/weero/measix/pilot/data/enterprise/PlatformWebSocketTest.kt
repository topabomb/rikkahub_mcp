package net.weero.measix.pilot.data.enterprise

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import me.rerere.common.http.SingleAttemptWebSocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformWebSocketTest {
    @Test fun `public upgrade socket preserves masked text and closing handshake`() {
        server({ socket ->
            upgrade(socket)
            assertEquals(1 to "hello", frame(socket))
            socket.getOutputStream().apply { write(byteArrayOf(0x81.toByte(), 2, 111, 107)); flush() }
            val close = frame(socket)
            assertEquals(8, close.first)
            val payload = close.second.toByteArray(Charsets.ISO_8859_1)
            socket.getOutputStream().apply { write(byteArrayOf(0x88.toByte(), payload.size.toByte())); write(payload); flush() }
        }) { request, finished ->
            val closed = CompletableFuture<Unit>()
            val received = CompletableFuture<String>()
            val socket = factory(1024).newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { assertTrue(webSocket.send("hello")) }
                override fun onMessage(webSocket: WebSocket, text: String) { received.complete(text); webSocket.close(1000, "done") }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.completeExceptionally(t) }
            })
            try {
                assertEquals("ok", received.get(5, TimeUnit.SECONDS))
                closed.get(5, TimeUnit.SECONDS)
                finished.get(5, TimeUnit.SECONDS)
            } finally { socket.cancel() }
        }
    }

    @Test fun `actual byte limit rejects text frames and automatic pong frames before writing`() {
        listOf(false, true).forEach { ping ->
            server({ socket ->
                upgrade(socket)
                if (ping) socket.getOutputStream().apply { write(byteArrayOf(0x89.toByte(), 2, 111, 107)); flush() }
                assertEquals("Over-budget masked frame reached server", -1, socket.getInputStream().read())
            }) { request, finished ->
                val failure = CompletableFuture<Throwable>()
                val socket = factory(7).newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { if (!ping) webSocket.send("ok") }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure.complete(t) }
                })
                try {
                    assertEquals("platform_websocket_request_limit_exceeded", failure.get(5, TimeUnit.SECONDS).message)
                    finished.get(5, TimeUnit.SECONDS)
                } finally { socket.cancel() }
            }
        }
    }

    @Test fun `cancel closes an in-flight HTTP upgrade without waiting for handshake timeout`() {
        val accepted = CompletableFuture<Unit>()
        server({ socket ->
            headers(socket)
            accepted.complete(Unit)
            assertEquals(-1, socket.getInputStream().read())
        }) { request, finished ->
            val failed = CompletableFuture<Unit>()
            val socket = factory(1024).newWebSocket(request, object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed.complete(Unit) }
            })
            try {
                accepted.get(5, TimeUnit.SECONDS)
                socket.cancel()
                failed.get(5, TimeUnit.SECONDS)
                finished.get(5, TimeUnit.SECONDS)
            } finally { socket.cancel() }
        }
    }

    private fun factory(limit: Long) = SingleAttemptWebSocketFactory(OkHttpClient(), "fixture", limit)

    private fun server(serve: (Socket) -> Unit, test: (Request, CompletableFuture<Void>) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val finished = CompletableFuture.runAsync { server.accept().use { it.soTimeout = 5000; serve(it) } }
            try { test(Request.Builder().url("http://127.0.0.1:${server.localPort}/realtime").build(), finished) }
            finally { server.close() }
        }
    }

    private fun headers(socket: Socket): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (!bytes.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
            val next = socket.getInputStream().read()
            check(next >= 0)
            bytes.write(next)
            check(bytes.size() <= 16384)
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun upgrade(socket: Socket) {
        val key = headers(socket).lineSequence().single { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
        val accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        socket.getOutputStream().apply {
            write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            flush()
        }
    }

    private fun frame(socket: Socket): Pair<Int, String> {
        val input = java.io.DataInputStream(socket.getInputStream())
        val opcode = input.readUnsignedByte() and 15
        val header = input.readUnsignedByte()
        assertTrue("Client frame was not masked", header and 128 != 0)
        val length = header and 127
        check(length < 126)
        val mask = ByteArray(4).also(input::readFully)
        val payload = ByteArray(length).also(input::readFully)
        payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return opcode to payload.toString(Charsets.ISO_8859_1)
    }
}
