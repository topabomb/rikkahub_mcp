package net.weero.measix.pilot.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.supportsImageGeneration
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationKey
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.datastore.ResourceSelections
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.datastore.DEFAULT_AUTO_MODEL_ID

internal enum class DefaultModelBehavior { UNCONFIGURED, FOLLOW_CHAT, FOLLOW_FAST, DISABLED }

internal data class ModelFavoriteUiModel(
    val reference: ConfigurationReference,
    val choice: ModelChoiceUiModel?,
    val group: ModelGroupUiModel?,
    val unavailableReason: ConfigurationUnavailableReason?,
)

internal sealed interface ModelCatalogReadState {
    data object Loading : ModelCatalogReadState
    data class Available(val catalog: ModelCatalogUiModel) : ModelCatalogReadState
    data class Unavailable(val detail: String) : ModelCatalogReadState
}

internal data class ModelChoiceUiModel(
    val model: Model,
    val unavailableReason: ConfigurationUnavailableReason?,
    val roleReasons: Map<ResourceSelectionSlot, ConfigurationUnavailableReason?> = emptyMap(),
) {
    val canSelect: Boolean get() = unavailableReason == null
}

internal data class ModelGroupUiModel(
    val id: String,
    val name: String,
    val models: List<ModelChoiceUiModel>,
    val userProviderId: ConfigurationReference.User?,
)

/** A rendered directory contains public definitions, never enterprise connection inputs. */
internal data class ModelCatalogUiModel(
    val groups: List<ModelGroupUiModel>,
    val selection: RealmSelection? = null,
    val selections: ResourceSelections = ResourceSelections(),
    val storedSelections: ResourceSelections = selections,
    val roleSelections: Map<ResourceSelectionSlot, ConfigurationSelection> = emptyMap(),
    val unresolvedModels: Map<ConfigurationReference, ConfigurationUnavailableReason> = emptyMap(),
) {
    fun find(id: ConfigurationReference?): ModelChoiceUiModel? = groups.asSequence()
        .flatMap { it.models.asSequence() }.firstOrNull { it.model.id == id }

    fun filterModels(predicate: (Model) -> Boolean): ModelCatalogUiModel = copy(
        groups = groups.map { it.copy(models = it.models.filter { choice -> predicate(choice.model) }) }
            .filter { it.models.isNotEmpty() },
    )

    fun forSlot(slot: ResourceSelectionSlot): ModelCatalogUiModel = copy(groups = groups.map { group ->
        group.copy(models = group.models.map { it.copy(unavailableReason = it.roleReasons.getValue(slot)) })
    })

    fun defaultBehavior(slot: ResourceSelectionSlot): DefaultModelBehavior? {
        val reference = slot.reference(selections)
        if (reference != null && !(selection?.access == RealmAccess.Personal && reference == DEFAULT_AUTO_MODEL_ID)) return null
        if (selection?.access != RealmAccess.Personal) return DefaultModelBehavior.UNCONFIGURED
        return when (slot) {
            ResourceSelectionSlot.FAST_MODEL, ResourceSelectionSlot.COMPRESS_MODEL -> DefaultModelBehavior.FOLLOW_CHAT
            ResourceSelectionSlot.TITLE_MODEL, ResourceSelectionSlot.SUGGESTION_MODEL -> DefaultModelBehavior.FOLLOW_FAST
            ResourceSelectionSlot.ATTACHMENT_INSPECTION_MODEL -> DefaultModelBehavior.DISABLED
            else -> DefaultModelBehavior.UNCONFIGURED
        }
    }

    fun favorites(references: List<ConfigurationReference>, type: ModelType): List<ModelFavoriteUiModel> =
        references.mapNotNull { reference ->
            val group = groups.firstOrNull { it.models.any { model -> model.model.id == reference } }
            val choice = group?.models?.firstOrNull { it.model.id == reference }
            if (choice != null && choice.model.type != type) null
            else ModelFavoriteUiModel(reference, choice, group, choice?.unavailableReason
                ?: if (choice == null) unresolvedModels[reference] ?: ConfigurationUnavailableReason.REFERENCE_MISSING else null)
        }
}

internal fun ResolvedConfiguration.modelCatalog(selection: RealmSelection): ModelCatalogUiModel {
    check(selection.access.scope == scope) { "model_catalog_scope_mismatch" }
    val modelSlots = ResourceSelectionSlot.entries.filter { it.modelRole != null }
    val groups = models.values.groupBy { it.userProviderId }.map { (providerId, definitions) ->
        ModelGroupUiModel(
            id = providerId?.toString() ?: "enterprise",
            name = providerId?.let { catalog[ConfigurationKey(ConfigurationCategory.PROVIDER, it)]?.name }
                ?: enterpriseIdentity?.enterpriseName.orEmpty(),
            models = definitions.map { definition ->
                ModelChoiceUiModel(
                    model = definition.model,
                    unavailableReason = if (definition.model.type == ModelType.IMAGE)
                        choice(ResourceSelectionSlot.IMAGE_MODEL, definition.model.id).unavailableReason
                    else access(ConfigurationCategory.MODEL, definition.model.id).unavailableReason,
                    roleReasons = modelSlots.associateWith { choice(it, definition.model.id).unavailableReason },
                )
            },
            userProviderId = providerId,
        )
    }
    return ModelCatalogUiModel(groups, selection, selections, storedSelections,
        modelSlots.associateWith { this.selection(it) },
        catalog.values.filter { it.key.category == ConfigurationCategory.MODEL && it.key.reference !in models }
            .associate { it.key.reference to requireNotNull(it.access.unavailableReason) })
}

/** Editing a shared user definition cannot introduce a reference to an enterprise definition. */
internal fun userDefinitionModelCatalog(providers: List<ProviderSetting>): ModelCatalogUiModel {
    val ambiguous = providers.flatMap { it.models }.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    return ModelCatalogUiModel(providers.map { provider ->
        ModelGroupUiModel(
            id = provider.id.toString(), name = provider.name,
            models = provider.models.distinctBy { it.id }.map { model ->
                ModelChoiceUiModel(model, when {
                    model.id in ambiguous -> ConfigurationUnavailableReason.REFERENCE_AMBIGUOUS
                    !provider.enabled -> ConfigurationUnavailableReason.RESOURCE_DISABLED
                    model.type == ModelType.IMAGE && !supportsImageGeneration(model.providerOverwrite ?: provider) ->
                        ConfigurationUnavailableReason.RESOURCE_CAPABILITY_MISMATCH
                    else -> null
                })
            },
            userProviderId = provider.id as ConfigurationReference.User,
        )
    })
}
