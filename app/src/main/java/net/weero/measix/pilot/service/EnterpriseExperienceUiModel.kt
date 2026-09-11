package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.model.Assistant

internal data class EnterpriseStarterTarget(
    val selection: RealmSelection,
    val generation: Long,
    val reference: ConfigurationReference.Enterprise,
)

internal data class EnterpriseStarterUiModel(
    val target: EnterpriseStarterTarget,
    val assistantName: String,
    val title: String,
    val prompt: String,
    val description: String?,
)

internal data class EnterpriseAssistantUiModel(
    val assistant: Assistant,
    val modelName: String,
    val mcpNames: List<String>,
    val subAssistantNames: List<String>,
    val memorySeeds: List<AssistantMemorySeedUiModel>,
    val unavailableReason: ConfigurationUnavailableReason?,
)

/** Public definitions and current usage only. Runtime bindings and credentials are never projected. */
internal data class EnterpriseExperienceUiModel(
    val selection: RealmSelection,
    val enterpriseName: String,
    val generation: Long,
    val assistants: List<EnterpriseAssistantUiModel>,
    val starters: List<EnterpriseStarterUiModel>,
)

internal sealed interface EnterpriseExperienceReadState {
    data class Available(val value: EnterpriseExperienceUiModel) : EnterpriseExperienceReadState
    data object Unavailable : EnterpriseExperienceReadState
}

internal fun ResolvedConfiguration.enterpriseExperience(selection: RealmSelection): EnterpriseExperienceUiModel? {
    val identity = enterpriseIdentity ?: return null
    val definitions = enterpriseConfiguration ?: return null
    val seeds = definitions.memorySeeds.associateBy { it.id }
    return EnterpriseExperienceUiModel(selection, identity.enterpriseName, definitions.generation,
        definitions.assistants.mapNotNull { definition ->
            val reference = identity.reference(definition.id)
            val assistant = assistants[reference] ?: return@mapNotNull null
            EnterpriseAssistantUiModel(assistant,
                definitions.models.single { it.id == definition.modelId }.name,
                definition.mcpServerIds.map { id -> definitions.mcpServers.single { it.id == id }.name },
                definition.allowedSubAssistantIds.map { id -> definitions.assistants.single { it.id == id }.name },
                definition.memorySeedIds.map { id -> AssistantMemorySeedUiModel(id, seeds.getValue(id).content) },
                access(ConfigurationCategory.ASSISTANT, reference).unavailableReason)
        },
        availableStarters().map { starter ->
            EnterpriseStarterUiModel(EnterpriseStarterTarget(selection, definitions.generation, identity.reference(starter.id)),
                assistants.getValue(identity.reference(starter.assistantId)).name, starter.title, starter.prompt, starter.description)
        })
}

internal data class StarterDraftRequest(val request: ConversationOpenRequest.NewDraft, val text: String)
