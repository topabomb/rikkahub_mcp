package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.asr.AsrCleanup
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.PcmAudioCapture
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude

/** Owns temporary PCM/WAV capture through HTTP transcription; application callbacks own admission and transport. */
class HttpAsrController(
    private val context: Context,
    private val admitRecording: suspend (() -> Unit) -> Unit,
    private val transcribe: suspend (File) -> String,
    private val admitTranscript: suspend (() -> Unit) -> Unit,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state = _state.asStateFlow()
    private class Recording {
        val stopped = CompletableDeferred<Unit>()
        var capture: PcmAudioCapture? = null
        var audio: File? = null
    }
    private var recording: Recording? = null
    private var disposed = false

    @MainThread
    override fun start(onTranscriptChange: (String) -> Unit) {
        if (recording != null || !requireNotNull(scope.coroutineContext[Job]).isActive) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = ASRState(status = ASRStatus.Error, isAvailable = true, errorMessage = "Microphone permission is required")
            return
        }
        val original = Recording().also { recording = it }
        _state.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)
        scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var audio: File? = null
            try {
                currentCoroutineContext().ensureActive()
                audio = File.createTempFile("asr-", ".wav", context.cacheDir).also { original.audio = it }
                RandomAccessFile(audio, "rw").use { output ->
                    output.write(ByteArray(44))
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            admitRecording {
                                check(recording === original) { "asr_recording_replaced" }
                                if (original.stopped.isCompleted) throw CancellationException("asr_stopped_before_recording")
                                original.capture = PcmAudioCapture.start(scope, SAMPLE_RATE, onFrame = { bytes, count ->
                                    output.write(bytes, 0, count)
                                    val amplitude = calculateRmsAmplitude(bytes, count)
                                    scope.launch {
                                        if (recording === original && !disposed) _state.update {
                                            it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude))
                                        }
                                    }
                                }, onFailure = { original.stopped.completeExceptionally(it) })
                                _state.update { it.copy(status = ASRStatus.Listening) }
                            }
                        }
                        original.stopped.await()
                    } finally {
                        // Keep the output open until this exact recorder has stopped writing.
                        withContext(NonCancellable) {
                            val capture = withContext(Dispatchers.Main.immediate) {
                                original.capture?.also { it.stop() }
                            }
                            capture?.job?.join()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    writePcmWaveHeader(output, SAMPLE_RATE)
                }
                val text = transcribe(audio)
                withContext(Dispatchers.Main.immediate) {
                    admitTranscript {
                        check(recording === original) { "asr_recording_replaced" }
                        onTranscriptChange(text)
                        _state.update { it.copy(status = ASRStatus.Idle, transcript = text, errorMessage = null) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (recording === original && !disposed) _state.update {
                        it.copy(status = ASRStatus.Error, errorMessage = "ASR transcription failed")
                    }
                }
            } finally {
                val cleaned = audio?.let { it.delete() || !it.exists() } ?: true
                if (cleaned) original.audio = null
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (recording === original && !disposed) {
                        if (cleaned) {
                            recording = null
                            if (_state.value.isRecording) _state.update { it.copy(status = ASRStatus.Idle) }
                        } else _state.update { it.copy(status = ASRStatus.Error, errorMessage = "ASR recording cleanup failed") }
                    }
                }
            }
        }
    }

    @MainThread
    override fun stop() {
        recording?.let {
            if (it.stopped.complete(Unit)) {
                it.capture?.stop()
                _state.update { value -> value.copy(status = ASRStatus.Stopping) }
            }
        }
    }

    @MainThread
    override fun dispose(): AsrCleanup {
        val original = recording
        disposed = true
        original?.capture?.stop()
        _state.value = ASRState()
        scope.cancel()
        return AsrCleanup(requireNotNull(scope.coroutineContext[Job])) {
            withContext(Dispatchers.IO) {
                original?.audio?.let { file ->
                    check(file.delete() || !file.exists()) { "asr_temporary_audio_cleanup_failed" }
                    original.audio = null
                }
            }
            withContext(Dispatchers.Main.immediate) { if (recording === original && !disposed) recording = null }
        }
    }

    private companion object { const val SAMPLE_RATE = 24_000 }
}

/** The device capture format is internal; managed ASR configuration has no sample-rate field. */
internal fun writePcmWaveHeader(output: RandomAccessFile, sampleRate: Int) {
    val size = output.length() - 44
    check(size > 0 && size % 2 == 0L && size <= 0xffff_ffffL - 36) { "asr_recording_size_invalid" }
    fun little32(value: Long) { repeat(4) { output.write((value ushr (it * 8)).toInt() and 255) } }
    fun little16(value: Int) { repeat(2) { output.write((value ushr (it * 8)) and 255) } }
    output.seek(0)
    output.writeBytes("RIFF"); little32(size + 36); output.writeBytes("WAVEfmt ")
    little32(16); little16(1); little16(1); little32(sampleRate.toLong())
    little32(sampleRate.toLong() * 2); little16(2); little16(16)
    output.writeBytes("data"); little32(size)
}
