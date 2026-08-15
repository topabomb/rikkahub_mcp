package net.weero.measix.pilot.ui.pages.setting

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderSettingMutationTest {
    @Test
    fun `provider form save keeps latest models`() {
        val providerId = Uuid.random()
        val concurrentModel = Model(id = Uuid.random(), modelId = "concurrent", displayName = "Concurrent")
        val latest = ProviderSetting.OpenAI(
            id = providerId,
            name = "Latest name",
            apiKey = "old-key",
            models = listOf(concurrentModel),
        )
        val edited = latest.copy(
            name = "Edited name",
            apiKey = "new-key",
            models = emptyList(),
        )

        val saved = applyProviderEditorSave(latest, edited)

        assertEquals("Edited name", saved.name)
        assertEquals("new-key", (saved as ProviderSetting.OpenAI).apiKey)
        assertEquals(listOf(concurrentModel), saved.models)
    }

    @Test
    fun `model reorder resolves stable ids against the latest list`() {
        val first = Model(id = Uuid.random(), modelId = "a")
        val concurrent = Model(id = Uuid.random(), modelId = "b")
        val last = Model(id = Uuid.random(), modelId = "c")
        val provider = ProviderSetting.OpenAI(models = listOf(first, concurrent, last))

        val moved = moveProviderModelsById(provider, first.id, last.id)

        assertEquals(listOf(concurrent, last, first), moved.models)
    }

    @Test
    fun `model reorder leaves latest list untouched when an id disappeared`() {
        val provider = ProviderSetting.OpenAI(
            models = listOf(Model(id = Uuid.random(), modelId = "a")),
        )

        val result = moveProviderModelsById(provider, Uuid.random(), provider.models.first().id)

        assertSame(provider, result)
    }
}
