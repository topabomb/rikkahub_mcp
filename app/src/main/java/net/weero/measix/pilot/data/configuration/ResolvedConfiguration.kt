package net.weero.measix.pilot.data.configuration

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ChatTransportCapabilities
import me.rerere.ai.provider.chatTransportCapabilities
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.supportsImageGeneration
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.datastore.DEFAULT_AUTO_MODEL_ID
import net.weero.measix.pilot.data.datastore.DEFAULT_SYSTEM_TTS_ID
import net.weero.measix.pilot.data.datastore.ResourceSelections
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.datastore.withBuiltInDefinitions
import net.weero.measix.pilot.data.enterprise.EnterpriseConfiguration
import net.weero.measix.pilot.data.enterprise.EnterpriseIdentity
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.withAssistantSearch

internal data class ConfigurationKey(val category: ConfigurationCategory, val reference: ConfigurationReference)

internal data class ConfigurationCatalogItem(
    val key: ConfigurationKey,
    val name: String,
    val access: ConfigurationAccess,
    val gatewayEnablement: ResolvedGatewayEnablement? = null,
)

/** One enablement decision applies to the gateway's complete discover/invoke pair. */
internal data class ResolvedGatewayEnablement(val enabled: Boolean, val canChange: Boolean)

internal data class ResolvedModelConfiguration(
    val model: Model,
    val userProviderId: ConfigurationReference.User?,
    val imageGenerationSupported: Boolean,
    val transportCapabilities: ChatTransportCapabilities,
)

/** An explicit invalid reference remains visible; it never turns into a different selection. */
internal data class ConfigurationSelection(
    val reference: ConfigurationReference?,
    val unavailableReason: ConfigurationUnavailableReason?,
) {
    val isAvailable: Boolean get() = reference != null && unavailableReason == null
}

internal enum class ModelSelectionRole(val type: ModelType) {
    CHAT(ModelType.CHAT), FAST(ModelType.CHAT), TITLE(ModelType.CHAT), IMAGE(ModelType.IMAGE),
    ATTACHMENT_INSPECTION(ModelType.CHAT), SUGGESTION(ModelType.CHAT), COMPRESS(ModelType.CHAT),
}

/** Derived catalog and selections for one principal. Enterprise credentials stay with their execution owner. */
internal data class ResolvedConfiguration(
    val scope: ConfigurationScope,
    val enterpriseIdentity: EnterpriseIdentity?,
    val enterpriseConfiguration: EnterpriseConfiguration?,
    val catalog: Map<ConfigurationKey, ConfigurationCatalogItem>,
    val models: Map<ConfigurationReference, ResolvedModelConfiguration>,
    val assistants: Map<ConfigurationReference, Assistant>,
    val selections: ResourceSelections,
    val storedSelections: ResourceSelections,
) {
    fun access(category: ConfigurationCategory, reference: ConfigurationReference): ConfigurationAccess =
        catalog[ConfigurationKey(category, reference)]?.access ?: ConfigurationAccess(
            canEditDefinition = reference is ConfigurationReference.User,
            unavailableReason = ConfigurationUnavailableReason.REFERENCE_MISSING,
        )

    fun selection(category: ConfigurationCategory, reference: ConfigurationReference?): ConfigurationSelection =
        ConfigurationSelection(reference, reference?.let { access(category, it).unavailableReason })

    fun selection(slot: ResourceSelectionSlot): ConfigurationSelection =
        slot.modelRole?.let(::modelSelection) ?: selection(slot.category, slot.reference(selections))

    fun choice(slot: ResourceSelectionSlot, reference: ConfigurationReference): ConfigurationSelection =
        slot.modelRole?.let { selectModel(reference, it) } ?: selection(slot.category, reference)

    fun modelSelection(role: ModelSelectionRole): ConfigurationSelection = selectModel(
        when (role) {
            ModelSelectionRole.CHAT -> selections.chatModelId
            ModelSelectionRole.FAST -> selections.fastModelId
            ModelSelectionRole.TITLE -> selections.titleModelId
            ModelSelectionRole.IMAGE -> selections.imageGenerationModelId
            ModelSelectionRole.ATTACHMENT_INSPECTION -> selections.attachmentInspectionModelId
            ModelSelectionRole.SUGGESTION -> selections.suggestionModelId
            ModelSelectionRole.COMPRESS -> selections.compressModelId
        },
        role,
    )

    fun assistantModel(assistantId: ConfigurationReference): ConfigurationSelection {
        val assistant = assistants[assistantId] ?: return ConfigurationSelection(assistantId, ConfigurationUnavailableReason.REFERENCE_MISSING)
        return assistantModel(assistant)
    }

    fun assistantModel(assistant: Assistant): ConfigurationSelection =
        selectModel(assistant.chatModelId ?: selections.chatModelId, ModelType.CHAT)

    fun availableChatModel(assistant: Assistant): Model? = assistantModel(assistant)
        .takeIf { it.isAvailable }?.reference?.let { models[it]?.model?.withAssistantSearch(assistant) }

    private fun selectModel(reference: ConfigurationReference?, role: ModelSelectionRole): ConfigurationSelection {
        val selected = selectModel(reference, role.type)
        if (!selected.isAvailable) return selected
        if (role == ModelSelectionRole.IMAGE && !models.getValue(requireNotNull(reference)).imageGenerationSupported) {
            return selected.copy(unavailableReason = ConfigurationUnavailableReason.RESOURCE_CAPABILITY_MISMATCH)
        }
        return if (role == ModelSelectionRole.ATTACHMENT_INSPECTION &&
            Modality.IMAGE !in models.getValue(requireNotNull(reference)).model.inputModalities) {
            selected.copy(unavailableReason = ConfigurationUnavailableReason.RESOURCE_CAPABILITY_MISMATCH)
        } else selected
    }

    private fun selectModel(reference: ConfigurationReference?, type: ModelType): ConfigurationSelection {
        val selected = selection(ConfigurationCategory.MODEL, reference)
        if (!selected.isAvailable) return selected
        return if (models[reference]?.model?.type == type) selected else selected.copy(
            unavailableReason = ConfigurationUnavailableReason.RESOURCE_CAPABILITY_MISMATCH,
        )
    }
}

/** Pure resolver: user definitions are shared, while policy and usage choices follow the requested scope. */
internal object ConfigurationResolver {
    fun resolve(document: UserSettingsDocument, scope: ConfigurationScope, enterpriseState: EnterpriseState): ResolvedConfiguration {
        val available = enterpriseState as? EnterpriseState.Available
        val identity = available?.manifest?.session?.identity?.takeIf { scope == it.scope }
        val enterprise = available?.configuration?.takeIf { identity != null && available.manifest.applied != null }
        val user = document.personalSettings().withBuiltInDefinitions()
        val policy = enterprise?.policy
        val catalog = linkedMapOf<ConfigurationKey, ConfigurationCatalogItem>()
        val models = linkedMapOf<ConfigurationReference, ResolvedModelConfiguration>()
        val assistants = linkedMapOf<ConfigurationReference, Assistant>()

        fun add(category: ConfigurationCategory, id: ConfigurationReference, name: String, enabled: Boolean = true) {
            val key = ConfigurationKey(category, id)
            require(key !in catalog) { "duplicate_configuration_identity" }
            catalog[key] = ConfigurationCatalogItem(key, name, configurationAccess(scope, id, category, enabled, policy))
        }

        user.providers.forEach { provider ->
            add(ConfigurationCategory.PROVIDER, provider.id, provider.name, provider.enabled)
        }
        user.providers.flatMap { provider -> provider.models.map { provider to it } }
            .groupBy { (_, model) -> model.id }.forEach { (id, definitions) ->
                val only = definitions.singleOrNull()
                if (only != null) {
                    val (provider, model) = only
                    add(ConfigurationCategory.MODEL, id, model.displayName, provider.enabled)
                    models[id] = ResolvedModelConfiguration(model, provider.id as ConfigurationReference.User,
                        supportsImageGeneration(model.providerOverwrite ?: provider), chatTransportCapabilities(model.providerOverwrite ?: provider))
                } else {
                    // Imported providers can retain model IDs. No owner may be chosen arbitrarily.
                    val key = ConfigurationKey(ConfigurationCategory.MODEL, id)
                    catalog[key] = ConfigurationCatalogItem(key, definitions.map { it.second.displayName }.distinct().joinToString(" / "),
                        ConfigurationAccess(canEditDefinition = true, unavailableReason = ConfigurationUnavailableReason.REFERENCE_AMBIGUOUS))
                }
            }
        user.ttsProviders.forEach { add(ConfigurationCategory.TTS, it.id, it.name) }
        user.asrProviders.forEach { add(ConfigurationCategory.ASR, it.id, it.name) }
        user.mcpServers.forEach { add(ConfigurationCategory.MCP, it.id, it.commonOptions.name, it.commonOptions.enable) }
        user.searchServices.forEach { add(ConfigurationCategory.SEARCH, it.id, it.displayName) }
        user.modeInjections.forEach { add(ConfigurationCategory.PROMPT_INJECTION, it.id, it.name, it.enabled) }
        user.quickMessages.forEach { add(ConfigurationCategory.QUICK_MESSAGE, it.id, it.title) }
        user.assistants.forEach { definition ->
            add(ConfigurationCategory.ASSISTANT, definition.id, definition.name)
            assistants[definition.id] = resolveUserAssistantUsage(definition, document.preferences.assistantUsage(scope, definition.id))
        }

        if (enterprise != null && identity != null) {
            enterprise.models.forEach { definition ->
                val id = identity.reference(definition.id)
                add(ConfigurationCategory.MODEL, id, definition.name, definition.enabled)
                models[id] = ResolvedModelConfiguration(
                    Model(id = id, modelId = definition.modelId, displayName = definition.name, type = definition.type,
                        inputModalities = definition.inputModalities, outputModalities = definition.outputModalities, abilities = definition.abilities),
                    null,
                    true,
                    available.modelCapabilities[definition.id] ?: ChatTransportCapabilities.BASIC,
                )
            }
            enterprise.tts.forEach { add(ConfigurationCategory.TTS, identity.reference(it.id), it.name, it.enabled) }
            enterprise.asr.forEach { add(ConfigurationCategory.ASR, identity.reference(it.id), it.name, it.enabled) }
            enterprise.mcpServers.forEach { add(ConfigurationCategory.MCP, identity.reference(it.id), it.name, it.enabled) }
            enterprise.gateways.forEach { definition ->
                val reference = identity.reference(definition.id)
                val preference = document.preferences.gateway(identity.scope, reference)
                val enabled = effectiveGatewayEnabled(definition.enablement, preference, reference)
                add(ConfigurationCategory.GATEWAY, reference, definition.name, enabled)
                val key = ConfigurationKey(ConfigurationCategory.GATEWAY, reference)
                val item = catalog.getValue(key)
                catalog[key] = item.copy(
                    access = item.access.copy(requiredEnabled = enabled && definition.enablement == GatewayEnablementPolicy.REQUIRED),
                    gatewayEnablement = ResolvedGatewayEnablement(enabled,
                        definition.enablement == GatewayEnablementPolicy.USER_CONTROLLABLE_DEFAULT_ON),
                )
            }
            enterprise.memorySeeds.forEach { add(ConfigurationCategory.MEMORY_SEED, identity.reference(it.id), it.id) }
            enterprise.starters.forEach { add(ConfigurationCategory.STARTER, identity.reference(it.id), it.title) }
            enterprise.assistants.forEach { definition ->
                val id = identity.reference(definition.id)
                add(ConfigurationCategory.ASSISTANT, id, definition.name, definition.enabled)
                assistants[id] = resolveEnterpriseAssistantUsage(identity, definition, document.preferences.assistantUsage(scope, id))
            }
        }
        val selected = document.preferences.forScope(scope)
        val effectiveSelections = if (scope is ConfigurationScope.Personal) selected.copy(
            chatModelId = selected.chatModelId ?: DEFAULT_AUTO_MODEL_ID,
            fastModelId = selected.fastModelId ?: DEFAULT_AUTO_MODEL_ID,
            imageGenerationModelId = selected.imageGenerationModelId ?: DEFAULT_AUTO_MODEL_ID,
            compressModelId = selected.compressModelId ?: DEFAULT_AUTO_MODEL_ID,
            assistantId = selected.assistantId ?: DEFAULT_ASSISTANT_ID,
            selectedTTSProviderId = selected.selectedTTSProviderId ?: DEFAULT_SYSTEM_TTS_ID,
        ) else {
            val defaults = enterprise?.defaults
            fun enterpriseReference(id: String?) = id?.let { requireNotNull(identity).reference(it) }
            selected.copy(
                chatModelId = selected.chatModelId ?: enterpriseReference(defaults?.chatModelId),
                fastModelId = selected.fastModelId ?: enterpriseReference(defaults?.fastModelId),
                titleModelId = selected.titleModelId ?: enterpriseReference(defaults?.titleModelId),
                imageGenerationModelId = selected.imageGenerationModelId ?: enterpriseReference(defaults?.imageGenerationModelId),
                attachmentInspectionModelId = selected.attachmentInspectionModelId ?: enterpriseReference(defaults?.attachmentInspectionModelId),
                suggestionModelId = selected.suggestionModelId ?: enterpriseReference(defaults?.suggestionModelId),
                compressModelId = selected.compressModelId ?: enterpriseReference(defaults?.compressModelId),
                assistantId = selected.assistantId ?: enterpriseReference(defaults?.assistantId),
                selectedTTSProviderId = selected.selectedTTSProviderId ?: enterpriseReference(defaults?.ttsId),
                selectedASRProviderId = selected.selectedASRProviderId ?: enterpriseReference(defaults?.asrId),
            )
        }
        return ResolvedConfiguration(scope, identity, enterprise, catalog, models, assistants, effectiveSelections, selected)
    }
}
