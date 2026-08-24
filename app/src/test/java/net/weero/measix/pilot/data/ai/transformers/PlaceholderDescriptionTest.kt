package net.weero.measix.pilot.data.ai.transformers

import android.content.Context
import me.rerere.ai.provider.Model
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderDescriptionTest {
    @Test
    fun `description placeholder resolves assistant description`() {
        val ctx = PlaceholderCtx(
            settings = Settings(),
            model = Model(modelId = "test"),
            assistant = Assistant(description = "Android / Kotlin specialist"),
        )
        val resolver = DefaultPlaceholderProvider.placeholders.getValue("description").resolver
        assertEquals("Android / Kotlin specialist", resolver(ctx))
    }

    @Test
    fun `empty description resolves to empty string`() {
        val ctx = PlaceholderCtx(
            settings = Settings(),
            model = Model(modelId = "test"),
            assistant = Assistant(description = ""),
        )
        val resolver = DefaultPlaceholderProvider.placeholders.getValue("description").resolver
        assertEquals("", resolver(ctx))
        assertTrue(DefaultPlaceholderProvider.placeholders.containsKey("description"))
    }
}
