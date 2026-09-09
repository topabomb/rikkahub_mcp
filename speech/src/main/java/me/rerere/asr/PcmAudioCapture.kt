package me.rerere.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns one microphone and its blocking read loop; stopped captures never release a newer recorder. */
internal class PcmAudioCapture private constructor(private val recorder: AudioRecord, val job: Job) {
    @MainThread
    fun stop() {
        job.cancel()
        try { recorder.stop() } catch (_: IllegalStateException) { /* Already stopped or released by its reader. */ }
    }

    companion object {
        @MainThread
        @SuppressLint("MissingPermission")
        fun start(
            scope: CoroutineScope,
            sampleRate: Int,
            onFrame: (ByteArray, Int) -> Unit,
            onFailure: (Exception) -> Unit,
        ): PcmAudioCapture {
            scope.coroutineContext.ensureActive()
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "asr_audio_format_unavailable" }
            val bufferSize = minimum.coerceAtLeast(sampleRate / 10 * 2).coerceAtLeast(4096)
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "asr_microphone_unavailable" }
                recorder.startRecording()
            } catch (error: Exception) {
                recorder.release()
                throw error
            }
            // ATOMIC guarantees teardown even when cancellation wins before the IO dispatcher starts.
            val worker = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                try {
                    val buffer = ByteArray(bufferSize)
                    while (isActive) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        currentCoroutineContext().ensureActive()
                        check(read >= 0) { "asr_audio_read_failed" }
                        if (read > 0) onFrame(buffer, read)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isActive) onFailure(error)
                } finally {
                    try { recorder.stop() } catch (_: IllegalStateException) { }
                    recorder.release()
                }
            }
            return PcmAudioCapture(recorder, worker)
        }
    }
}
