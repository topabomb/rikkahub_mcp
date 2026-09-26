package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.enterprise.EnterpriseStarter
import net.weero.measix.pilot.data.enterprise.EnterpriseCandidate
import net.weero.measix.pilot.data.enterprise.EnterpriseExecution
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationCodec
import net.weero.measix.pilot.data.model.ConversationOpening
import java.security.MessageDigest
import kotlin.uuid.Uuid

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
    val openingAvailable: Boolean,
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
                openingAvailable = starter.openingSnapshot != null,
            )
        },
    )
}

internal data class StarterDraftRequest(val request: ConversationOpenRequest.NewDraft, val text: String)

internal data class ConversationOpeningSummary(val reference: ConfigurationReference.Enterprise, val title: String, val selectionToken: Uuid?)
internal data class StarterContextUiModel(val title: String, val content: String)
internal enum class StarterOpeningIssue { NOT_SUPPLIED, UPDATED, UNAVAILABLE, ASSISTANT_CHANGED }
internal class StarterOpeningException(val issue: StarterOpeningIssue) : IllegalStateException("enterprise_starter_${issue.name.lowercase()}")
internal data class StarterOpeningDetailUiModel(
    val title: String,
    val prompt: String,
    val systemPrompt: String?,
    val contexts: List<StarterContextUiModel>,
    val isDraft: Boolean,
    val systemApplicable: Boolean,
    val issue: StarterOpeningIssue? = null,
    val canRefresh: Boolean = false,
)

internal fun EnterpriseStarter.definitionHash(): String = MessageDigest.getInstance("SHA-256")
    .digest(EnterpriseConfigurationCodec.json.encodeToString(EnterpriseStarter.serializer(), this).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun EnterpriseCandidate.opening(starter: EnterpriseStarter): ConversationOpening {
    if (starter.openingSnapshot == null) throw StarterOpeningException(StarterOpeningIssue.NOT_SUPPLIED)
    val source = execution as EnterpriseExecution.Platform
    return ConversationOpening(assistant = identity.reference(starter.assistantId), releaseId = source.releaseId,
        generation = configuration.generation, snapshotHash = source.snapshotHash, definition = starter)
}

internal fun ConversationOpening.navigationReference() = StarterOpeningReference(
    assistant.copy(id = definition.id), definition.definitionHash(), releaseId, generation, snapshotHash,
)

internal fun StarterOpeningReference.restore(candidate: EnterpriseCandidate, starter: EnterpriseStarter): ConversationOpening {
    if (reference.authority != candidate.identity.authority || reference.id != starter.id) throw StarterOpeningException(StarterOpeningIssue.UNAVAILABLE)
    if (definitionHash != starter.definitionHash()) throw StarterOpeningException(StarterOpeningIssue.UPDATED)
    return candidate.opening(starter).copy(releaseId = releaseId, generation = generation, snapshotHash = snapshotHash)
}

/** Called at the first AppendUserMessage boundary; publication changes alone do not invalidate a selection. */
internal fun requireCurrentStarterOpening(opening: ConversationOpening?, assistantId: ConfigurationReference, configuration: ResolvedConfiguration) {
    if (opening == null) return
    if (opening.assistant != assistantId) throw StarterOpeningException(StarterOpeningIssue.ASSISTANT_CHANGED)
    val current = configuration.availableStarters(opening.assistant).singleOrNull { it.id == opening.definition.id }
        ?: throw StarterOpeningException(StarterOpeningIssue.UNAVAILABLE)
    if (current.openingSnapshot == null) throw StarterOpeningException(StarterOpeningIssue.NOT_SUPPLIED)
    if (current != opening.definition) throw StarterOpeningException(StarterOpeningIssue.UPDATED)
}

internal fun EnterpriseStarter.details(isDraft: Boolean, applicable: Boolean = true, issue: StarterOpeningIssue? = null, canRefresh: Boolean = false) =
    StarterOpeningDetailUiModel(title, prompt, openingSnapshot?.systemPrompt,
        openingSnapshot?.initialContexts.orEmpty().map { StarterContextUiModel(it.title, it.content) },
        isDraft, applicable, issue ?: if (openingSnapshot == null) StarterOpeningIssue.NOT_SUPPLIED else null, canRefresh)
