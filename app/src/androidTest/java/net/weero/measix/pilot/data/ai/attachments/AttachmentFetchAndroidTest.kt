package net.weero.measix.pilot.data.ai.attachments

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AttachmentFetchAndroidTest {
    @Test fun basicHeaderAndChunkedLimitUseActualAndroidTransport() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val request = CompletableDeferred<String>()
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    request.complete(generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.joinToString("\n"))
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n800\r\n".toByteArray())
                        write(ByteArray(2048)); write("\r\n0\r\n\r\n".toByteArray()); flush()
                    }
                }
            }
            val result = withTimeout(5000) {
                SafeRemoteMediaFetcher.defaultTransport(URL("http://u%2B:p%40ss@attachment.test:${server.localPort}/image"),
                    listOf(InetAddress.getByName("127.0.0.1")), 1000, 2000, 1024)
            }
            assertEquals(413, result.code)
            assertTrue(request.await().contains("Authorization: ${okhttp3.Credentials.basic("u+", "p@ss")}"))
            assertTrue(request.await().contains("Host: attachment.test:"))
        }
    }

    @Test fun stopCancelsAndroidSocketWhileResponseBodyIsBlocked() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val started = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nx".toByteArray()); flush()
                    }
                    started.complete(Unit)
                    try { while (socket.getInputStream().read() != -1) { } }
                    catch (_: java.io.IOException) { }
                    finally { closed.complete(Unit) }
                }
            }
            val call = async {
                SafeRemoteMediaFetcher.defaultTransport(URL("http://attachment.test:${server.localPort}/slow"),
                    listOf(InetAddress.getByName("127.0.0.1")), 1000, 15000, 1024)
            }
            withTimeout(5000) { started.await() }
            withTimeout(2000) { call.cancelAndJoin(); closed.await() }
            assertTrue(call.isCancelled)
        }
    }
}
