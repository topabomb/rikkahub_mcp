package net.weero.measix.pilot.ui.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeAutoScrollerTest {
    @Test
    fun `ime expansion scrolls by positive delta and reset is observed`() {
        assertEquals(240, imeScrollDelta(previousImeBottom = 0, currentImeBottom = 240))
        assertEquals(0, imeScrollDelta(previousImeBottom = 240, currentImeBottom = 0))
        assertEquals(240, imeScrollDelta(previousImeBottom = 0, currentImeBottom = 240))
    }

    @Test
    fun `ime dismissal and shrinking animation never reverse scroll`() {
        assertEquals(0, imeScrollDelta(previousImeBottom = 240, currentImeBottom = 180))
        assertEquals(0, imeScrollDelta(previousImeBottom = 180, currentImeBottom = 0))
        assertEquals(20, imeScrollDelta(previousImeBottom = 180, currentImeBottom = 200))
    }
}
