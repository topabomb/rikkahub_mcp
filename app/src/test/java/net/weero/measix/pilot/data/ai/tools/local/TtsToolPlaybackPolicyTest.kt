package net.weero.measix.pilot.data.ai.tools.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsToolPlaybackPolicyTest {
    @Test
    fun `sequential setting appends within master turn`() {
        assertFalse(ttsToolReplacesWithinTurn(sequentialEnabled = true))
    }

    @Test
    fun `disabled sequential setting replaces within master turn`() {
        assertTrue(ttsToolReplacesWithinTurn(sequentialEnabled = false))
    }
}
