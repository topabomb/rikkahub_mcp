package net.weero.measix.pilot.ui.components.ai

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AssistantPickerTest {
    @Test
    fun `picker hides unavailable candidates but keeps the current invalid assistant`() {
        val current = Assistant(id = ConfigurationReference.random(), name = "Current")
        val blocked = Assistant(id = ConfigurationReference.random(), name = "Blocked")
        val available = Assistant(id = ConfigurationReference.random(), name = "Available")
        val reasons = mapOf(
            current.id to ConfigurationUnavailableReason.RESOURCE_DISABLED,
            blocked.id to ConfigurationUnavailableReason.RESOURCE_DISABLED,
            available.id to null,
        )

        assertEquals(
            listOf(current.id, available.id),
            assistantsForPicker(listOf(current, blocked, available), reasons, current.id).map { it.id },
        )
    }

    @Test
    fun `quick restore only exposes the assistant default action when it is not selected`() {
        val assistantDefault = ModelSelectionAction(label = "assistant", selected = false) {}
        val spaceDefault = ModelSelectionAction(label = "space", selected = true) {}
        assertSame(
            assistantDefault,
            AssistantModelSelectionUi(listOf(assistantDefault, spaceDefault), null, null)
                .quickRestoreAssistantDefault(),
        )

        assertNull(
            AssistantModelSelectionUi(
                actions = listOf(assistantDefault.copy(selected = true), spaceDefault.copy(selected = false)),
                selectedModelId = null,
                modeLabel = null,
            ).quickRestoreAssistantDefault(),
        )
    }

    @Test
    fun `missing current assistant keeps an explicit unavailable reason`() {
        val missing = ConfigurationReference.random()
        assertEquals(
            ConfigurationUnavailableReason.REFERENCE_MISSING,
            currentAssistantUnavailableReason(emptyMap(), emptyMap(), missing),
        )
        assertEquals(
            ConfigurationUnavailableReason.RESOURCE_DISABLED,
            currentAssistantUnavailableReason(
                assistants = emptyMap(),
                unavailableReasons = mapOf(missing to ConfigurationUnavailableReason.RESOURCE_DISABLED),
                currentAssistantId = missing,
            ),
        )
    }
}
