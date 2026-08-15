package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSearchModeMutationTest {
    @Test
    fun `search mode updates tools on the latest model instead of a stale snapshot`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val staleModel = Model(id = modelId, modelId = "model", displayName = "Stale")
        val latestModel = staleModel.copy(
            displayName = "Latest",
            tools = setOf(BuiltInTools.UrlContext),
        )
        val settings = Settings(
            assistants = listOf(Assistant(id = assistantId, enableWebSearch = false)),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(latestModel))),
        )

        val updated = applySearchMode(
            settings = settings,
            assistantId = assistantId,
            modelId = modelId,
            enableWebSearch = false,
            enableBuiltIn = true,
        )

        val model = updated.providers.single().models.single()
        assertEquals("Latest", model.displayName)
        assertTrue(model.tools.contains(BuiltInTools.Search))
        assertTrue(model.tools.contains(BuiltInTools.UrlContext))
        assertFalse(updated.assistants.single().enableWebSearch)
    }

    @Test
    fun `disabling built-in search leaves other model fields intact`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val latestModel = Model(
            id = modelId,
            modelId = "model",
            displayName = "Latest",
            tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext),
        )
        val settings = Settings(
            assistants = listOf(Assistant(id = assistantId, enableWebSearch = true)),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(latestModel))),
        )

        val updated = applySearchMode(
            settings = settings,
            assistantId = assistantId,
            modelId = modelId,
            enableWebSearch = true,
            enableBuiltIn = false,
        )

        val model = updated.providers.single().models.single()
        assertEquals("Latest", model.displayName)
        assertFalse(model.tools.contains(BuiltInTools.Search))
        assertTrue(model.tools.contains(BuiltInTools.UrlContext))
        assertTrue(updated.assistants.single().enableWebSearch)
    }
}
