package me.rerere.tts.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOwnerTest {
    @Test
    fun `new claim prevents stale playback from releasing current owner`() {
        val owner = PlaybackOwner()
        val stale = owner.claim()
        val current = owner.claim()

        assertFalse(owner.owns(stale))
        assertFalse(owner.release(stale))
        assertTrue(owner.owns(current))
        assertTrue(owner.release(current))
    }

    @Test
    fun `invalidate revokes current playback ownership`() {
        val owner = PlaybackOwner()
        val token = owner.claim()

        owner.invalidate()

        assertFalse(owner.owns(token))
        assertFalse(owner.release(token))
    }
}
