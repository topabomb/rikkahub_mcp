package net.weero.measix.pilot.ui.pages.assistant.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextMessageLimitInputTest {
    @Test
    fun `only disabled or configured range is accepted`() {
        assertEquals(0, parseContextMessageLimitInput("0"))
        assertEquals(40, parseContextMessageLimitInput("40"))
        assertEquals(512, parseContextMessageLimitInput("512"))
        listOf("", "-1", "+40", " 40", "39", "513", "1.0", "abc").forEach {
            assertNull(it, parseContextMessageLimitInput(it))
        }
    }

    @Test
    fun `overflow never becomes a valid value`() {
        assertNull(parseContextMessageLimitInput("999999999999999999999999"))
    }

    @Test
    fun `done and focus loss submit a valid edit only once`() {
        val editing = ContextMessageLimitEditState.initial(40)
            .focusChanged(true).state
            .edit("80")

        val done = editing.done()
        assertEquals(80, done.submission)
        val focusLoss = done.state.focusChanged(false)
        assertNull(focusLoss.submission)
    }

    @Test
    fun `toggle while focused suppresses stale buffer on following focus loss`() {
        val editing = ContextMessageLimitEditState.initial(40)
            .focusChanged(true).state
            .edit("120")

        val disabled = editing.toggle(false)
        assertEquals(0, disabled.submission)
        val focusLoss = disabled.state.focusChanged(false)
        assertNull(focusLoss.submission)
        assertEquals("0", focusLoss.state.input)
    }

    @Test
    fun `external value does not replace an active edit buffer`() {
        val editing = ContextMessageLimitEditState.initial(40)
            .focusChanged(true).state
            .edit("120")

        val observed = editing.observe(80)
        assertEquals("120", observed.input)
        assertEquals(80, observed.lastSubmitted)
    }
}
