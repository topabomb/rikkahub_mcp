package net.weero.measix.pilot.ui.components.ui

import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsControllerPolicyTest {
    @Test
    fun `toolbar pauses both active playback and synthesis`() {
        assertTrue(PlaybackStatus.Playing.shouldPauseFromToolbar())
        assertTrue(PlaybackStatus.Buffering.shouldPauseFromToolbar())
        assertFalse(PlaybackStatus.Paused.shouldPauseFromToolbar())
        assertFalse(PlaybackStatus.Ended.shouldPauseFromToolbar())
        assertFalse(PlaybackStatus.Idle.shouldPauseFromToolbar())
    }

    @Test
    fun `paused toolbar preserves audio and chunk progress`() {
        val state = PlaybackState(
            status = PlaybackStatus.Paused,
            positionMs = 2_500L,
            durationMs = 10_000L,
            currentChunkIndex = 2,
            totalChunks = 4,
        )

        assertEquals(0.25f, state.toolbarAudioProgress(), 0.0001f)
        assertEquals(0.5f, state.toolbarChunkProgress(), 0.0001f)
    }

    @Test
    fun `toolbar progress is finite before media metadata arrives`() {
        val buffering = PlaybackState(
            status = PlaybackStatus.Buffering,
            currentChunkIndex = 1,
            totalChunks = 2,
        )

        assertEquals(0f, buffering.toolbarAudioProgress(), 0f)
        assertEquals(0f, buffering.toolbarChunkProgress(), 0f)
    }
}
