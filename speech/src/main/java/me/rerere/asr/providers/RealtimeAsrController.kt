package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.asr.AsrCleanup
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.PcmAudioCapture
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** One local realtime connection/recorder owner; provider settings retain their distinct wire profiles. */
class RealtimeAsrController(
    private val context: Context,
    private val sockets: WebSocket.Factory,
    private val provider: ASRProviderSetting,
    private val admitRecording: suspend (() -> Unit) -> Unit,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()
    private var socket: WebSocket? = null
    private var capture: PcmAudioCapture? = null
    private var finishing: Job? = null
    private var peerEnded: CompletableDeferred<Unit>? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val completed = mutableListOf<String>()
    private val partial = linkedMapOf<String, String>()

    @MainThread
    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording || !requireNotNull(scope.coroutineContext[Job]).isActive) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = ASRState(status = ASRStatus.Error, isAvailable = true, errorMessage = "Microphone permission is required")
            return
        }
        abandon()
        this.onTranscriptChange = onTranscriptChange
        completed.clear()
        partial.clear()
        _state.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)
        val endpoint = when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> provider.websocketEndpoint()
            is ASRProviderSetting.DashScope -> provider.websocketEndpoint()
        }
        val apiKey = when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> provider.apiKey
            is ASRProviderSetting.DashScope -> provider.apiKey
        }
        val terminal = CompletableDeferred<Unit>()
        try {
            val created = sockets.newWebSocket(Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        scope.launch(Dispatchers.Main) {
                            if (socket !== webSocket || state.value.status != ASRStatus.Connecting) {
                                webSocket.cancel()
                                return@launch
                            }
                            val update = when (provider) {
                                is ASRProviderSetting.OpenAIRealtime -> provider.sessionUpdateEvent()
                                is ASRProviderSetting.DashScope -> provider.sessionUpdateEvent()
                            }
                            if (!webSocket.send(update.toString())) { fail("ASR session initialization failed"); return@launch }
                            try {
                                admitRecording {
                                    check(socket === webSocket && state.value.status == ASRStatus.Connecting) { "asr_connection_replaced" }
                                    startCapture(webSocket)
                                    _state.update { it.copy(status = ASRStatus.Listening) }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { fail("Microphone recording failed") }
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch(Dispatchers.Main) { if (socket === webSocket) receive(webSocket, text) }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        try { response?.close() } finally { terminal.complete(Unit) }
                        scope.launch(Dispatchers.Main) { if (socket === webSocket) fail("ASR connection failed") }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        scope.launch(Dispatchers.Main) {
                            if (socket === webSocket) finishSocket(webSocket, remote = true)
                        }
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        terminal.complete(Unit)
                        scope.launch(Dispatchers.Main) {
                            if (socket === webSocket) {
                                abandon()
                                _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
                            }
                        }
                    }
                })
            socket = created
            // Each connection keeps its own terminal acknowledgment, including retired connections.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { terminal.await() }
                finally {
                    if (!terminal.isCompleted) {
                        created.cancel()
                        withContext(NonCancellable) { terminal.await() }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail("ASR connection failed") }
    }

    @MainThread
    override fun stop() {
        socket?.let { finishSocket(it, remote = false) }
    }

    /** Cancels each connection and awaits protocol terminal callbacks and every owned recorder's finally. */
    @MainThread
    override fun dispose(): AsrCleanup {
        abandon()
        onTranscriptChange = null
        _state.value = ASRState()
        scope.cancel()
        return AsrCleanup(requireNotNull(scope.coroutineContext[Job]))
    }

    private fun startCapture(original: WebSocket) {
        val sampleRate = when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> provider.sampleRate
            is ASRProviderSetting.DashScope -> provider.sampleRate
        }
        capture = PcmAudioCapture.start(scope, sampleRate, onFrame = { buffer, read ->
            val amplitude = calculateRmsAmplitude(buffer, read)
            scope.launch { if (socket === original) _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) } }
            if (original.queueSize() < 100_000L) {
                val event = JSONObject().put("type", "input_audio_buffer.append")
                    .put("audio", Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
                if (provider is ASRProviderSetting.DashScope) event.put("event_id", "evt_${System.currentTimeMillis()}")
                if (!original.send(event.toString())) scope.launch { if (socket === original) fail("ASR audio transmission failed") }
            }
        }, onFailure = { scope.launch { if (socket === original) fail("Microphone recording failed") } })
    }

    private fun receive(original: WebSocket, text: String) {
        val event = try { JSONObject(text) } catch (_: Exception) { fail("Invalid ASR response"); return }
        val itemId = event.optString("item_id", "default")
        when (event.optString("type")) {
            "conversation.item.input_audio_transcription.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) { partial[itemId] = (partial[itemId] ?: "") + delta; publishTranscript() }
            }
            "conversation.item.input_audio_transcription.text" -> if (provider is ASRProviderSetting.DashScope) {
                val preview = event.optString("text") + event.optString("stash")
                if (preview.isNotEmpty()) { partial[itemId] = preview; publishTranscript() }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                partial.remove(itemId)
                event.optString("transcript").trim().takeIf { it.isNotEmpty() }?.let(completed::add)
                publishTranscript()
            }
            "conversation.item.input_audio_transcription.failed" -> {
                partial.remove(itemId)
                publishTranscript()
                _state.update { it.copy(errorMessage = "ASR transcription failed") }
            }
            "error" -> fail("ASR service error")
            "session.finished" -> if (provider is ASRProviderSetting.DashScope) finishSocket(original, remote = true)
        }
    }

    /** User stop and all server endings share one reader join and one close handshake. */
    private fun finishSocket(original: WebSocket, remote: Boolean) {
        if (socket !== original) return
        if (finishing != null) {
            if (remote) peerEnded?.complete(Unit)
            return
        }
        val wasListening = state.value.status == ASRStatus.Listening
        val ended = CompletableDeferred<Unit>().also { peerEnded = it }
        if (remote) ended.complete(Unit)
        val reader = stopCapture()
        _state.update { it.copy(status = ASRStatus.Stopping) }
        finishing = scope.launch(start = CoroutineStart.LAZY) {
            reader?.join()
            if (socket !== original) return@launch
            if (!wasListening && !remote) {
                abandon()
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }
            if (!ended.isCompleted) {
                if (provider is ASRProviderSetting.DashScope && !original.send(sessionFinishEvent().toString())) {
                    fail("ASR session finish failed")
                    return@launch
                }
                withTimeoutOrNull(if (provider is ASRProviderSetting.DashScope) 10_000L else 500L) { ended.await() }
            }
            // false means closing/closed in the WebSocket API; its terminal callback remains authoritative.
            if (socket === original) original.close(1000, "stop")
        }
        finishing?.start()
    }

    private fun publishTranscript() {
        val value = (completed + partial.values).filter { it.isNotBlank() }.joinToString(" ")
        _state.update { it.copy(transcript = value, errorMessage = null) }
        onTranscriptChange?.invoke(value)
    }

    private fun fail(message: String) {
        abandon()
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun stopCapture(): Job? = capture?.let { original -> capture = null; original.stop(); original.job }

    private fun abandon() {
        finishing?.cancel()
        finishing = null
        peerEnded = null
        val original = socket
        socket = null
        stopCapture()
        original?.cancel()
    }
}

private fun ASRProviderSetting.OpenAIRealtime.websocketEndpoint(): String {
    val endpoint = websocketUrl.trim()
    if (endpoint.contains("intent=transcription")) return endpoint
    if (endpoint.contains("model=")) return endpoint
    val separator = if (endpoint.contains("?")) "&" else "?"
    return "${endpoint.trimEnd('/')}${separator}intent=transcription"
}

private fun ASRProviderSetting.OpenAIRealtime.sessionUpdateEvent(): JSONObject {
    val transcription = JSONObject()
        .put("model", model)
    if (language.isNotBlank()) transcription.put("language", language)
    if (prompt.isNotBlank()) transcription.put("prompt", prompt)

    return JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("type", "transcription")
                .put(
                    "audio",
                    JSONObject()
                        .put(
                            "input",
                            JSONObject()
                                .put(
                                    "format",
                                    JSONObject()
                                        .put("type", "audio/pcm")
                                        .put("rate", sampleRate)
                                )
                                .put("transcription", transcription)
                                .put(
                                    "noise_reduction",
                                    JSONObject()
                                        .put("type", "near_field")
                                )
                                .put(
                                    "turn_detection",
                                    JSONObject()
                                        .put("type", "server_vad")
                                        .put("threshold", vadThreshold)
                                        .put("prefix_padding_ms", prefixPaddingMs)
                                        .put("silence_duration_ms", silenceDurationMs)
                                )
                        )
                )
        )
}

private fun sessionFinishEvent(): JSONObject {
    return JSONObject()
        .put("event_id", "evt_session_finish_${System.currentTimeMillis()}")
        .put("type", "session.finish")
}

private fun ASRProviderSetting.DashScope.websocketEndpoint(): String {
    val endpoint = websocketUrl
        .trim()
        .trimEnd('/')
        .replace("/api-ws/v1/inference", "/api-ws/v1/realtime")
    val separator = if (endpoint.contains("?")) "&" else "?"
    return if (endpoint.contains("model=")) endpoint
    else "${endpoint}${separator}model=${model}"
}

private fun ASRProviderSetting.DashScope.sessionUpdateEvent(): JSONObject {
    val transcription = JSONObject()
    if (language.isNotBlank()) transcription.put("language", language)

    val session = JSONObject()
        .put("input_audio_format", "pcm")
        .put("sample_rate", sampleRate)
        .put("input_audio_transcription", transcription)
        .put(
            "turn_detection",
            JSONObject()
                .put("type", "server_vad")
                .put("threshold", vadThreshold)
                .put("silence_duration_ms", silenceDurationMs)
        )

    return JSONObject()
        .put("event_id", "evt_session_update")
        .put("type", "session.update")
        .put("session", session)
}
