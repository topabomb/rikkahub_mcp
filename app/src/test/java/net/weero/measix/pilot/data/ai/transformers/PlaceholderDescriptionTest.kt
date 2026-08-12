package net.weero.measix.pilot.data.ai.transformers

import android.content.Context
import io.mockk.mockk
import me.rerere.ai.provider.Model
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderDescriptionTest {
    @Test
    fun `description placeholder resolves assistant description`() {
        val ctx = PlaceholderCtx(
            context = mockk(relaxed = true),
            settingsStore = mockk(relaxed = true),
            model = Model(modelId = "test"),
            assistant = Assistant(description = "Android / Kotlin specialist"),
        )
        val resolver = DefaultPlaceholderProvider.placeholders.getValue("description").resolver
        assertEquals("Android / Kotlin specialist", resolver(ctx))
    }

    @Test
    fun `empty description resolves to empty string`() {
        val ctx = PlaceholderCtx(
            context = mockk(relaxed = true),
            settingsStore = mockk(relaxed = true),
            model = Model(modelId = "test"),
            assistant = Assistant(description = ""),
        )
        val resolver = DefaultPlaceholderProvider.placeholders.getValue("description").resolver
        assertEquals("", resolver(ctx))
        assertTrue(DefaultPlaceholderProvider.placeholders.containsKey("description"))
    }
}
