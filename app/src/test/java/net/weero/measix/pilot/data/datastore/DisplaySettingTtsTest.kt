package net.weero.measix.pilot.data.datastore

import kotlinx.serialization.json.Json
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
}
