package net.weero.measix.pilot.ui.pages.setting

import net.weero.measix.pilot.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySettingMutationTest {
    @Test
    fun `page delta applies edited fields without overwriting concurrent fields`() {
        val baseline = DisplaySetting()
        val edited = baseline.copy(
            showModelName = false,
            fontSizeRatio = 1.25f,
        )
        val current = baseline.copy(
            enableBlurEffect = true,
            pasteLongTextThreshold = 2048,
        )

        val merged = mergeDisplaySettingDelta(baseline, edited, current)

        assertEquals(false, merged.showModelName)
        assertEquals(1.25f, merged.fontSizeRatio)
        assertEquals(true, merged.enableBlurEffect)
        assertEquals(2048, merged.pasteLongTextThreshold)
    }

    @Test
    fun `unchanged page snapshot preserves the latest display settings`() {
        val baseline = DisplaySetting()
        val current = baseline.copy(
            showLineNumbers = true,
            volumeKeyScrollRatio = 2.0f,
        )

        assertEquals(current, mergeDisplaySettingDelta(baseline, baseline, current))
    }
}
