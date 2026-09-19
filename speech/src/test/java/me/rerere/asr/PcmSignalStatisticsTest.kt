package me.rerere.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSignalStatisticsTest {
    @Test fun `silent PCM is rejected before transcription`() {
        val statistics = PcmSignalStatistics()
        statistics.append(ByteArray(4_800), 4_800)

        assertEquals(2_400L, statistics.sampleCount)
        assertEquals(0L, statistics.nonZeroSampleCount)
        assertEquals(0.0, statistics.rms, 0.0)
        assertEquals(0.0, statistics.peak, 0.0)
        assertThrows(NoSpeechDetectedException::class.java) { statistics.requireEffectiveSpeech() }
    }

    @Test fun `sparse or too quiet PCM is rejected`() {
        val sparse = PcmSignalStatistics()
        sparse.append(pcm(2_400) { if (it == 0) Short.MAX_VALUE.toInt() else 0 }, 4_800)
        assertFalse(sparse.rms >= 0.001 && sparse.peak >= 0.003 && sparse.nonZeroSampleCount >= 480)
        assertThrows(NoSpeechDetectedException::class.java) { sparse.requireEffectiveSpeech() }
    }

    @Test fun `sustained audible PCM is accepted`() {
        val statistics = PcmSignalStatistics()
        statistics.append(pcm(960) { 2_000 }, 1_920)

        assertTrue(statistics.rms > 0.05)
        assertTrue(statistics.peak > 0.05)
        statistics.requireEffectiveSpeech()
    }

    private fun pcm(samples: Int, value: (Int) -> Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        repeat(samples) { index ->
            val sample = value(index)
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }
}
