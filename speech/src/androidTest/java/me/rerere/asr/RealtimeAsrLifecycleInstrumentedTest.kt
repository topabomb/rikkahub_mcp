package me.rerere.asr

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.asr.providers.RealtimeAsrController
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeAsrLifecycleInstrumentedTest {
    private fun providers() = listOf(
        ASRProviderSetting.OpenAIRealtime(apiKey = "fixture", websocketUrl = "wss://asr.invalid/realtime"),
        ASRProviderSetting.DashScope(apiKey = "fixture", websocketUrl = "wss://asr.invalid/realtime"),
    )

    private fun microphoneContext(): android.content.Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return instrumentation.targetContext.also {
            instrumentation.uiAutomation.grantRuntimePermission(it.packageName, Manifest.permission.RECORD_AUDIO)
        }
    }

    @Test fun disposalCancelsTheSocketBeforeLateHandshakeAndTranscriptCallbacks() = runBlocking {
        val context = microphoneContext()
        for (provider in providers()) {
            val factory = Sockets()
            val transcripts = CopyOnWriteArrayList<String>()
            val controller = withContext(Dispatchers.Main) {
                RealtimeAsrController(context, factory, provider).also { it.start(transcripts::add) }
            }
            val socket = factory.values.single()
            socket.completeCancellation = false
            val cleanup = withContext(Dispatchers.Main) { controller.stop(); controller.dispose() }
            val disposed = async(start = CoroutineStart.UNDISPATCHED) { cleanup.join() }
            assertFalse("Disposal must await the original socket's terminal callback", disposed.isCompleted)
            socket.finishCancellation()
            withTimeout(10_000) { disposed.await() }
            assertTrue(socket.cancellations.get() > 0)
            socket.open()
            socket.transcript("late private transcript")
            withContext(Dispatchers.Main) { }
            assertTrue(transcripts.isEmpty())
            assertEquals(ASRState(), controller.state.value)
            assertTrue(cleanup.isCompleted)
        }
    }

    @Test fun originalSocketAndRecorderCannotPublishIntoTheirReplacement() = runBlocking {
        val context = microphoneContext()
        for (provider in providers()) {
            val factory = Sockets()
            val delivered = CompletableDeferred<String>()
            val controller = withContext(Dispatchers.Main) { RealtimeAsrController(context, factory, provider) }
            try {
                withContext(Dispatchers.Main) { controller.start { error("Original callback must not receive text") } }
                val old = factory.values.single()
                old.open()
                withTimeout(10_000) { old.firstAudio.await() }
                withContext(Dispatchers.Main) { controller.stop() }
                old.listener.onClosed(old, 1000, "test stop")
                withTimeout(10_000) { controller.state.first { it.status == ASRStatus.Idle } }
                withContext(Dispatchers.Main) { controller.start { delivered.complete(it) } }
                val current = factory.values.last()
                old.transcript("stale")
                old.listener.onFailure(old, IllegalStateException("old connection"), null)
                current.open()
                withTimeout(10_000) { current.firstAudio.await() }
                current.transcript("current transcript")
                assertEquals("current transcript", withTimeout(10_000) { delivered.await() })
                assertEquals(ASRStatus.Listening, controller.state.value.status)
            } finally {
                val cleanup = withContext(Dispatchers.Main) { controller.dispose() }
                withTimeout(10_000) { cleanup.join() }
                assertTrue(cleanup.children.none())
                assertTrue(factory.values.all { it.cancellations.get() > 0 })
            }
        }
    }

    @Test fun serverTerminationStopsRecordingBeforeClosingTheSocket() = runBlocking {
        val context = microphoneContext()
        for (provider in providers()) {
            val endings = if (provider is ASRProviderSetting.DashScope) listOf("peer", "session", "local") else listOf("peer", "local")
            for (ending in endings) {
                val factory = Sockets()
                val controller = withContext(Dispatchers.Main) {
                    RealtimeAsrController(context, factory, provider).also { it.start { } }
                }
                val socket = factory.values.single()
                try {
                    socket.open()
                    withTimeout(10_000) { socket.firstAudio.await() }
                    when (ending) {
                        "peer" -> socket.listener.onClosing(socket, 1000, "server stopped")
                        "session" -> {
                            socket.listener.onMessage(socket, """{"type":"session.finished"}""")
                            socket.listener.onClosing(socket, 1000, "server stopped")
                        }
                        "local" -> {
                            withContext(Dispatchers.Main) { controller.stop() }
                            if (provider is ASRProviderSetting.DashScope) socket.listener.onMessage(socket, """{"type":"session.finished"}""")
                        }
                    }
                    withTimeout(10_000) { socket.closeRequested.await() }
                    socket.listener.onClosing(socket, 1000, "close acknowledged")
                    withContext(Dispatchers.Main) { }
                    assertEquals(1, socket.closeCalls.get())
                    assertEquals(ASRStatus.Stopping, controller.state.value.status)
                    val framesAtClose = socket.audioFrames.get()
                    socket.listener.onClosed(socket, 1000, "closed")
                    withTimeout(10_000) { controller.state.first { it.status == ASRStatus.Idle } }
                    assertEquals(framesAtClose, socket.audioFrames.get())
                } finally {
                    val cleanup = withContext(Dispatchers.Main) { controller.dispose() }
                    withTimeout(10_000) { cleanup.join() }
                }
            }
        }
    }

    private class Sockets : WebSocket.Factory {
        val values = CopyOnWriteArrayList<Socket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also(values::add)
    }

    private class Socket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val cancellations = AtomicInteger()
        val firstAudio = CompletableDeferred<Unit>()
        val closeRequested = CompletableDeferred<Unit>()
        val closeCalls = AtomicInteger()
        val audioFrames = AtomicInteger()
        var completeCancellation = true
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (text.contains("input_audio_buffer.append")) { audioFrames.incrementAndGet(); firstAudio.complete(Unit) }
            return cancellations.get() == 0
        }
        override fun send(bytes: ByteString) = cancellations.get() == 0
        override fun close(code: Int, reason: String?): Boolean {
            val first = closeCalls.incrementAndGet() == 1
            closeRequested.complete(Unit)
            return first
        }
        override fun cancel() {
            cancellations.incrementAndGet()
            if (completeCancellation) finishCancellation()
        }
        fun finishCancellation() { listener.onFailure(this, java.io.IOException("fixture cancelled"), null) }
        fun open() { listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()) }
        fun transcript(value: String) { listener.onMessage(this,
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"fixture","transcript":"$value"}""") }
    }
}
