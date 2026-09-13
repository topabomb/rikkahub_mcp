package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.reference

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

internal data class EnterpriseStarterCatalogUiModel(
    val enterpriseName: String,
    val starters: List<EnterpriseStarterUiModel>,
)

internal sealed interface EnterpriseStarterReadState {
    data class Available(val value: EnterpriseStarterCatalogUiModel) : EnterpriseStarterReadState
    data object SourceChanged : EnterpriseStarterReadState
    data class Failed(val detail: String) : EnterpriseStarterReadState
}

internal fun ResolvedConfiguration.enterpriseStarterCatalog(
    selection: RealmSelection,
): EnterpriseStarterCatalogUiModel? {
    val identity = enterpriseIdentity ?: return null
    val definitions = enterpriseConfiguration ?: return null
    return EnterpriseStarterCatalogUiModel(
        enterpriseName = identity.enterpriseName,
        starters = availableStarters().map { starter ->
            EnterpriseStarterUiModel(
                target = EnterpriseStarterTarget(
                    selection = selection,
                    generation = definitions.generation,
                    reference = identity.reference(starter.id),
                ),
                assistantName = assistants.getValue(identity.reference(starter.assistantId)).name,
                title = starter.title,
                prompt = starter.prompt,
                description = starter.description,
            )
        },
    )
}

internal data class StarterDraftRequest(val request: ConversationOpenRequest.NewDraft, val text: String)
