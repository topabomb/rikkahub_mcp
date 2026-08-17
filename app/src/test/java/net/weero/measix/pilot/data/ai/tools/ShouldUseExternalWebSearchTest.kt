package net.weero.measix.pilot.data.ai.tools

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldUseExternalWebSearchTest {
    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        assertFalse(shouldUseExternalWebSearch(Assistant(enableWebSearch = false), Model()))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        assertTrue(shouldUseExternalWebSearch(Assistant(enableWebSearch = true), Model()))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val model = Model(tools = setOf(BuiltInTools.Search))
        assertFalse(shouldUseExternalWebSearch(Assistant(enableWebSearch = true), model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val model = Model(tools = setOf(BuiltInTools.Search))
        assertFalse(shouldUseExternalWebSearch(Assistant(enableWebSearch = false), model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val model = Model(tools = setOf(BuiltInTools.UrlContext))
        assertTrue(shouldUseExternalWebSearch(Assistant(enableWebSearch = true), model))
    }

    @Test
    fun `missing model still allows external search when assistant enabled it`() {
        assertTrue(shouldUseExternalWebSearch(Assistant(enableWebSearch = true), null))
    }
}
