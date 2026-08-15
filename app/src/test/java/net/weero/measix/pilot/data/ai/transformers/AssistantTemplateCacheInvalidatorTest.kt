package net.weero.measix.pilot.data.ai.transformers

import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantTemplateCacheInvalidatorTest {
    @Test
    fun `template fingerprint ignores unrelated settings changes`() {
        val assistant = Assistant(id = Uuid.random(), messageTemplate = "{{ message }}")
        val before = Settings(assistants = listOf(assistant))
        val after = before.copy(themeId = "another-theme", launchCount = 42)

        assertEquals(before.assistantTemplateFingerprint(), after.assistantTemplateFingerprint())
    }

    @Test
    fun `template fingerprint changes when a template changes`() {
        val assistant = Assistant(id = Uuid.random(), messageTemplate = "{{ message }}")
        val before = Settings(assistants = listOf(assistant))
        val after = before.copy(
            assistants = listOf(assistant.copy(messageTemplate = "prefix {{ message }}")),
        )

        assertNotEquals(before.assistantTemplateFingerprint(), after.assistantTemplateFingerprint())
    }

    @Test
    fun `compiled template cache key includes content as well as assistant id`() {
        val assistantId = Uuid.random().toString()

        val before = AssistantTemplateCacheKey(assistantId, "{{ message }}")
        val after = AssistantTemplateCacheKey(assistantId, "prefix {{ message }}")

        assertNotEquals(before, after)
    }
}
