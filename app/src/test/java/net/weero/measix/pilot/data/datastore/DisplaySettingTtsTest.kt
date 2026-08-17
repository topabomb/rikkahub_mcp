package net.weero.measix.pilot.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingTtsTest {
    @Test
    fun `sequential TTS tool playback defaults to enabled`() {
        assertTrue(DisplaySetting().ttsToolSequentialPlayback)
    }

    @Test
    fun `older display settings enable sequential playback when field is absent`() {
        val decoded = Json.decodeFromString<DisplaySetting>("{}")

        assertTrue(decoded.ttsToolSequentialPlayback)
    }

    @Test
    fun `update checks stay enabled when pause timestamp is absent`() {
        val decoded = Json.decodeFromString<DisplaySetting>("{}")

        assertTrue(decoded.showUpdates)
        assertEquals(0L, decoded.updateCheckDisabledUntilEpochMillis)
        assertTrue(decoded.areUpdateChecksEnabled(nowEpochMillis = 1_000L))
    }

    @Test
    fun `legacy showUpdates false remains disabled without a pause timestamp`() {
        val setting = DisplaySetting(showUpdates = false)

        assertFalse(setting.areUpdateChecksEnabled(nowEpochMillis = 10_000L))
    }

    @Test
    fun `update checks stay paused until the timestamp expires`() {
        val setting = DisplaySetting(updateCheckDisabledUntilEpochMillis = 5_000L)

        assertFalse(setting.areUpdateChecksEnabled(nowEpochMillis = 4_999L))
        assertTrue(setting.areUpdateChecksEnabled(nowEpochMillis = 5_000L))
    }
}
