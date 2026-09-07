package net.weero.measix.pilot.ui.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeAutoScrollerTest {
    @Test
    fun `only keyboard expansion produces a forward scroll delta`() {
        listOf(
            Triple(0, 240, 240),
            Triple(180, 200, 20),
            Triple(240, 240, 0),
            Triple(240, 180, 0),
            Triple(180, 0, 0),
        ).forEach { (previous, current, expected) ->
            assertEquals("IME $previous -> $current", expected, imeScrollDelta(previous, current))
        }
    }
}
