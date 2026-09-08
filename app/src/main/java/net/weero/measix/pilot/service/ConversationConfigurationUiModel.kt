package net.weero.measix.pilot.service

import me.rerere.ai.provider.ChatTransportCapabilities
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationCatalogItem
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.configuration.ModelSelectionRole
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.model.Assistant

/** The assistant and its choices belong to the same rendered conversation and authorized configuration. */
internal data class ConversationConfigurationUiModel(
    val target: ConversationAssistantTarget,
    val assistant: Assistant?,
    val assistantUnavailableReason: ConfigurationUnavailableReason?,
    val modelSelection: ConfigurationSelection,
    val model: Model?,
    val modelCatalog: ModelCatalogUiModel,
    val transportCapabilities: ChatTransportCapabilities,
    val builtInSearchEnabled: Boolean,
    val imageGenerationAvailable: Boolean,
    val resources: List<ConfigurationCatalogItem>,
    val assistants: Map<ConfigurationReference, Assistant>,
    val searchSelection: ConfigurationSelection,
    val fixedMcpBindings: Set<ConfigurationReference>,
) {
    val canChangeModel: Boolean get() = assistant != null && target.assistantId is ConfigurationReference.User
    val canEditDefinition: Boolean get() = target.assistantId is ConfigurationReference.User
}

internal fun ResolvedConfiguration.conversationConfiguration(target: ConversationAssistantTarget): ConversationConfigurationUiModel {
    val reason = access(ConfigurationCategory.ASSISTANT, target.assistantId).unavailableReason
    val assistant = assistants[target.assistantId].takeIf { reason == null }
    val modelSelection = assistant?.let(::assistantModel) ?: ConfigurationSelection(null, reason)
    return ConversationConfigurationUiModel(
        target, assistant, reason, modelSelection, assistant?.let(::availableChatModel),
        modelCatalog(target.conversation.selection).forSlot(ResourceSelectionSlot.CHAT_MODEL),
        models[modelSelection.reference]?.transportCapabilities?.takeIf { modelSelection.isAvailable }
            ?: ChatTransportCapabilities.BASIC,
        assistant?.builtInSearch ?: (models[modelSelection.reference]?.model?.tools?.contains(BuiltInTools.Search) == true),
        modelSelection(ModelSelectionRole.IMAGE).isAvailable,
        catalog.values.toList(), assistants, selection(ResourceSelectionSlot.SEARCH),
        (target.assistantId as? ConfigurationReference.Enterprise)?.let { reference ->
            enterpriseConfiguration?.assistants?.singleOrNull { it.id == reference.id }?.mcpServerIds
                ?.map { ConfigurationReference.Enterprise(reference.authority, it) }?.toSet()
        }.orEmpty(),
    )
}
