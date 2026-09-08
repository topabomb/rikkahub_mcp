package net.weero.measix.pilot.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ModelType
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.datastore.ResourceSelections
import net.weero.measix.pilot.data.datastore.DEFAULT_AUTO_MODEL_ID
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogUiModelTest {
    @Test
    fun `personal default modes are distinct from broken explicit references`() {
        val catalog = ModelCatalogUiModel(emptyList(), RealmSelection(RealmAccess.Personal, 0),
            ResourceSelections(fastModelId = DEFAULT_AUTO_MODEL_ID, compressModelId = DEFAULT_AUTO_MODEL_ID))
        assertEquals(DefaultModelBehavior.FOLLOW_CHAT, catalog.defaultBehavior(ResourceSelectionSlot.FAST_MODEL))
        assertEquals(DefaultModelBehavior.FOLLOW_CHAT, catalog.defaultBehavior(ResourceSelectionSlot.COMPRESS_MODEL))
        assertEquals(DefaultModelBehavior.FOLLOW_FAST, catalog.defaultBehavior(ResourceSelectionSlot.TITLE_MODEL))
        assertEquals(DefaultModelBehavior.FOLLOW_FAST, catalog.defaultBehavior(ResourceSelectionSlot.SUGGESTION_MODEL))
        assertEquals(DefaultModelBehavior.DISABLED, catalog.defaultBehavior(ResourceSelectionSlot.ATTACHMENT_INSPECTION_MODEL))
        assertEquals(DefaultModelBehavior.UNCONFIGURED, catalog.defaultBehavior(ResourceSelectionSlot.CHAT_MODEL))
        assertEquals(DefaultModelBehavior.UNCONFIGURED, catalog.defaultBehavior(ResourceSelectionSlot.IMAGE_MODEL))
        assertNull(catalog.copy(selections = ResourceSelections(fastModelId = ConfigurationReference.random()))
            .defaultBehavior(ResourceSelectionSlot.FAST_MODEL))
    }

    @Test
    fun `deleted and ambiguous favorites retain their references and reason for removal`() {
        val missing = ConfigurationReference.random()
        val ambiguous = ConfigurationReference.random()
        val image = Model(type = ModelType.IMAGE)
        val catalog = userDefinitionModelCatalog(listOf(ProviderSetting.OpenAI(models = listOf(image))))
            .copy(unresolvedModels = mapOf(ambiguous to ConfigurationUnavailableReason.REFERENCE_AMBIGUOUS))
        val favorites = catalog.favorites(listOf(missing, image.id, ambiguous), ModelType.CHAT)
        assertEquals(listOf(missing, ambiguous), favorites.map { it.reference })
        assertEquals(listOf(ConfigurationUnavailableReason.REFERENCE_MISSING, ConfigurationUnavailableReason.REFERENCE_AMBIGUOUS),
            favorites.map { it.unavailableReason })
        assertTrue(favorites.all { it.choice == null && it.group == null })
    }
    @Test
    fun `unsaved user provider draft is visible but ambiguous model identities are not selectable`() {
        val model = Model(modelId = "draft", displayName = "Unsaved model")
        val first = ProviderSetting.OpenAI(name = "Unsaved provider", models = listOf(model))
        val draft = userDefinitionModelCatalog(listOf(first))
        assertNull(draft.selection)
        assertEquals("Unsaved provider", draft.groups.single().name)
        assertTrue(draft.find(model.id)!!.canSelect)
        val ambiguous = userDefinitionModelCatalog(listOf(first, ProviderSetting.Google(models = listOf(model))))
        assertTrue(ambiguous.groups.all { it.models.single().unavailableReason == ConfigurationUnavailableReason.REFERENCE_AMBIGUOUS })
        assertEquals(first.id, ambiguous.groups.first().userProviderId)
    }
}
