package me.rerere.asr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

private const val MAX_AMPLITUDES = 32

fun calculateRmsAmplitude(buffer: ByteArray, readBytes: Int): Float {
    val shorts = ByteBuffer.wrap(buffer, 0, readBytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .asShortBuffer()
    var sum = 0.0
    val count = shorts.remaining()
    if (count == 0) return 0f
    for (i in 0 until count) {
        val sample = shorts[i].toDouble()
        sum += sample * sample
    }
    val rms = sqrt(sum / count)
    val linear = (rms / Short.MAX_VALUE).toFloat()
    if (linear < 1e-6f) return 0f
    val db = 20f * log10(linear)
    // -60dB ~ 0dB -> 0f ~ 1f
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

fun List<Float>.appendAmplitude(amplitude: Float): List<Float> {
    val list = if (size >= MAX_AMPLITUDES) drop(1) else this
    return list + amplitude
}

/** Cumulative PCM evidence for deciding whether a stopped file recording is worth uploading. */
internal class PcmSignalStatistics {
    private var squares = 0.0
    private var peakSample = 0
    var sampleCount = 0L
        private set
    var nonZeroSampleCount = 0L
        private set

    val rms: Double
        get() = if (sampleCount == 0L) 0.0 else sqrt(squares / sampleCount) / MAX_PCM_SAMPLE
    val peak: Double
        get() = peakSample / MAX_PCM_SAMPLE

    fun append(buffer: ByteArray, readBytes: Int) {
        require(readBytes in 0..buffer.size && readBytes % 2 == 0) { "asr_pcm_frame_invalid" }
        for (offset in 0 until readBytes step 2) {
            val sample = (buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xff)
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() + 1 else abs(sample)
            sampleCount++
            if (magnitude != 0) nonZeroSampleCount++
            if (magnitude > peakSample) peakSample = magnitude
            squares += magnitude.toDouble() * magnitude
        }
    }

    fun requireEffectiveSpeech() {
        if (sampleCount < MIN_SIGNAL_SAMPLES || nonZeroSampleCount < MIN_SIGNAL_SAMPLES ||
            rms < MIN_RMS || peak < MIN_PEAK) {
            throw NoSpeechDetectedException(sampleCount, nonZeroSampleCount, rms, peak)
        }
    }

    private companion object {
        const val MAX_PCM_SAMPLE = 32_768.0
        const val MIN_SIGNAL_SAMPLES = 480L
        const val MIN_RMS = 0.001
        const val MIN_PEAK = 0.003
    }
}

/** Expected recording outcome: the device provided no PCM signal suitable for transcription. */
class NoSpeechDetectedException internal constructor(
    sampleCount: Long,
    nonZeroSampleCount: Long,
    rms: Double,
    peak: Double,
) : IllegalStateException("asr_no_speech_detected samples=$sampleCount nonZero=$nonZeroSampleCount rms=$rms peak=$peak")
