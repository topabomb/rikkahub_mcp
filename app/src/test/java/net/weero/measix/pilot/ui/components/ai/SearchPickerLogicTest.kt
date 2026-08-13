package net.weero.measix.pilot.ui.components.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPickerLogicTest {
    @Test
    fun `gpt model on Chat Completions is not treated as built-in search`() {
        val model = Model(modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(models = listOf(model), useResponseApi = false)
        assertFalse(supportsProviderBuiltInSearch(model, listOf(provider)))
    }

    @Test
    fun `OpenAI Responses and Google providers expose built-in search`() {
        val responsesModel = Model(modelId = "gpt-5")
        val geminiModel = Model(modelId = "custom-proxy-name")
        val responses = ProviderSetting.OpenAI(models = listOf(responsesModel), useResponseApi = true)
        val google = ProviderSetting.Google(models = listOf(geminiModel))

        assertTrue(supportsProviderBuiltInSearch(responsesModel, listOf(responses, google)))
        assertTrue(supportsProviderBuiltInSearch(geminiModel, listOf(responses, google)))
    }

    @Test
    fun `unlinked or missing models do not expose built-in search`() {
        val model = Model(modelId = "gpt-4o")
        assertFalse(supportsProviderBuiltInSearch(null, emptyList()))
        assertFalse(supportsProviderBuiltInSearch(model, emptyList()))
        assertFalse(supportsProviderBuiltInSearch(model, listOf(ProviderSetting.OpenAI(useResponseApi = true))))
    }

    @Test
    fun `built-in search takes display priority over local search`() {
        assertEquals(
            SearchMode.BUILT_IN,
            resolveDisplayedSearchMode(enableSearch = true, hasBuiltInSearchEnabled = true),
        )
        assertEquals(
            SearchMode.LOCAL,
            resolveDisplayedSearchMode(enableSearch = true, hasBuiltInSearchEnabled = false),
        )
        assertEquals(
            SearchMode.OFF,
            resolveDisplayedSearchMode(enableSearch = false, hasBuiltInSearchEnabled = false),
        )
    }

    @Test
    fun `search modes are exclusive`() {
        assertTrue(searchModeEnablesLocal(SearchMode.LOCAL))
        assertFalse(searchModeEnablesBuiltIn(SearchMode.LOCAL))
        assertTrue(searchModeEnablesBuiltIn(SearchMode.BUILT_IN))
        assertFalse(searchModeEnablesLocal(SearchMode.BUILT_IN))
        assertFalse(searchModeEnablesLocal(SearchMode.OFF))
        assertFalse(searchModeEnablesBuiltIn(SearchMode.OFF))
    }
}
