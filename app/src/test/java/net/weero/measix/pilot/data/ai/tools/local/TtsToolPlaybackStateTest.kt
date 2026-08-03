package net.weero.measix.pilot.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsToolPlaybackStateTest {
    @Test
    fun `sequential playback flushes first call and appends later calls`() {
        val state = TtsToolPlaybackState()

        assertTrue(state.prepare("first", sequentialEnabled = true).flush)
        assertFalse(state.prepare("second", sequentialEnabled = true).flush)
        assertFalse(state.prepare("third", sequentialEnabled = true).flush)
    }

    @Test
    fun `disabled sequential playback always flushes`() {
        val state = TtsToolPlaybackState()

        assertTrue(state.prepare("first", sequentialEnabled = false).flush)
        assertTrue(state.prepare("second", sequentialEnabled = false).flush)
    }

    @Test
    fun `playback state is isolated between generation runs`() {
        val firstGeneration = TtsToolPlaybackState()
        val secondGeneration = TtsToolPlaybackState()

        assertTrue(firstGeneration.prepare("first", sequentialEnabled = true).flush)
        assertFalse(firstGeneration.prepare("second", sequentialEnabled = true).flush)
        assertTrue(secondGeneration.prepare("first", sequentialEnabled = true).flush)
    }

    @Test
    fun `blank text is rejected before it can consume playback state`() {
        val state = TtsToolPlaybackState()

        assertThrows(IllegalStateException::class.java) {
            state.prepare("   ", sequentialEnabled = true)
        }
        val request = state.prepare("hello", sequentialEnabled = true)
        assertTrue(request.flush)
        assertEquals("hello", request.text)
    }
}
