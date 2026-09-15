package net.weero.measix.pilot.ui.components.ai

import me.rerere.ai.provider.Model
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.datastore.DEFAULT_AUTO_MODEL_ID
import net.weero.measix.pilot.data.datastore.ResourceSelections
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.ModelCatalogUiModel
import net.weero.measix.pilot.service.ModelChoiceUiModel
import net.weero.measix.pilot.service.ModelGroupUiModel
import org.junit.Assert.assertEquals
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

    @Test
    fun `model default presentation distinguishes unset available blocked and missing references`() {
        val available = Model(modelId = "available", displayName = "Available")
        val blocked = Model(modelId = "blocked", displayName = "Blocked")
        val missing = ConfigurationReference.random()
        val catalog = ModelCatalogUiModel(
            groups = listOf(ModelGroupUiModel("models", "Models", listOf(
                ModelChoiceUiModel(available, null),
                ModelChoiceUiModel(blocked, ConfigurationUnavailableReason.RESOURCE_DISABLED),
            ), null)),
            unresolvedModels = mapOf(missing to ConfigurationUnavailableReason.REFERENCE_MISSING),
        )

        assertEquals(
            ModelSelectionReferencePresentation(null, ConfigurationUnavailableReason.REFERENCE_MISSING, false),
            modelSelectionReferencePresentation(null, catalog),
        )
        val unconfigured = catalog.copy(
            selection = RealmSelection(RealmAccess.Personal, 0),
            selections = ResourceSelections(chatModelId = DEFAULT_AUTO_MODEL_ID),
        )
        assertEquals(
            ModelSelectionReferencePresentation(null, ConfigurationUnavailableReason.REFERENCE_MISSING, false),
            modelSelectionReferencePresentation(DEFAULT_AUTO_MODEL_ID, unconfigured),
        )
        assertEquals(
            ModelSelectionReferencePresentation("Available", null, false),
            modelSelectionReferencePresentation(available.id, catalog),
        )
        assertEquals(
            ModelSelectionReferencePresentation("Blocked", ConfigurationUnavailableReason.RESOURCE_DISABLED, false),
            modelSelectionReferencePresentation(blocked.id, catalog),
        )
        assertEquals(
            ModelSelectionReferencePresentation(null, ConfigurationUnavailableReason.REFERENCE_MISSING, true),
            modelSelectionReferencePresentation(missing, catalog),
        )
    }

    @Test
    fun `default card keeps selected mode and otherwise offers the first available mode`() {
        fun action(label: String, selected: Boolean, unavailable: Boolean) = ModelSelectionAction(
            label = label,
            value = label,
            selected = selected,
            unavailableReason = if (unavailable) ConfigurationUnavailableReason.REFERENCE_MISSING else null,
            commit = {},
        )
        val unavailableSelected = action("selected", selected = true, unavailable = true)
        val available = action("available", selected = false, unavailable = false)
        val unavailable = action("unavailable", selected = false, unavailable = true)

        assertEquals(unavailableSelected, primaryDefaultAction(listOf(unavailableSelected, available)))
        assertEquals(available, primaryDefaultAction(listOf(unavailable, available)))
        assertEquals(unavailable, primaryDefaultAction(listOf(unavailable)))
    }
}
