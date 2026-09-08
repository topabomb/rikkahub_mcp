package net.weero.measix.pilot.ui.components.ai

import net.weero.measix.pilot.data.configuration.AssistantSearchMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPickerLogicTest {
    @Test
    fun `built-in search takes display priority over local search`() {
        assertEquals(
            AssistantSearchMode.BUILT_IN,
            resolveDisplayedSearchMode(enableSearch = true, hasBuiltInSearchEnabled = true),
        )
        assertEquals(
            AssistantSearchMode.LOCAL,
            resolveDisplayedSearchMode(enableSearch = true, hasBuiltInSearchEnabled = false),
        )
        assertEquals(
            AssistantSearchMode.OFF,
            resolveDisplayedSearchMode(enableSearch = false, hasBuiltInSearchEnabled = false),
        )
    }

}
