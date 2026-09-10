package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar

internal sealed interface AssistantManagementChange {
    data class Create(val assistant: Assistant) : AssistantManagementChange
    data class Update(val id: ConfigurationReference, val name: String?, val description: String?, val instructions: String?) : AssistantManagementChange
    data class Delete(val id: ConfigurationReference) : AssistantManagementChange
}

internal data class AssistantManagementResult(val assistant: Assistant, val deletion: PendingAssistantDeletion? = null)
internal data class AssistantManagementMutation(val document: UserSettingsDocument, val result: AssistantManagementResult)

/** One typed mutation owns the shared definition, per-principal grants and durable cleanup receipt. */
internal fun UserSettingsDocument.manageAssistant(
    scope: ConfigurationScope,
    state: EnterpriseState,
    callerId: ConfigurationReference?,
    change: AssistantManagementChange,
): AssistantManagementMutation {
    val definitions = configuration.assistants.withBuiltInAssistantDefinitions()
    val resolved = ConfigurationResolver.resolve(this, scope, state)
    val caller = callerId?.let {
        require(resolved.access(ConfigurationCategory.ASSISTANT, it).canSelect) { "tool_not_permitted" }
        requireNotNull(resolved.assistants[it]) { "tool_not_permitted" }.also { assistant ->
            require(LocalToolOption.AssistantManagement in assistant.localTools) { "tool_not_permitted" }
        }
    }
    if (scope is ConfigurationScope.Enterprise) {
        require(caller != null) { "assistant_management_caller_required" }
        require(resolved.enterpriseConfiguration?.policy?.allowLocalAssistants == true) { "user_assistants_not_allowed" }
    }
    fun target(id: ConfigurationReference): Assistant {
        require(id is ConfigurationReference.User) { "enterprise_assistant_read_only" }
        val target = definitions.singleOrNull { it.id == id }
            ?: throw NoSuchElementException("assistant_not_found")
        if (caller != null) {
            require(resolved.access(ConfigurationCategory.ASSISTANT, id).canSelect &&
                resolved.assistants[id]?.let { SubAssistantAccessPolicy.canAccess(caller, it) } == true) { "target_not_allowed" }
        }
        return target
    }
    return when (change) {
        is AssistantManagementChange.Create -> {
            val assistant = change.assistant
            require(assistant.id is ConfigurationReference.User && definitions.none { it.id == assistant.id }) { "invalid_assistant_identity" }
            var next = copy(configuration = configuration.copy(assistants = definitions + assistant))
            if (caller != null) when (scope) {
                ConfigurationScope.Personal -> {
                    val definition = definitions.singleOrNull { it.id == caller.id } ?: caller
                    val updated = definition.copy(allowedSubAssistantIds = definition.allowedSubAssistantIds + assistant.id)
                    next = next.copy(configuration = next.configuration.copy(assistants =
                        if (next.configuration.assistants.any { it.id == caller.id }) next.configuration.assistants.map { if (it.id == caller.id) updated else it }
                        else next.configuration.assistants + updated))
                }
                is ConfigurationScope.Enterprise -> {
                    val before = preferences.assistantUsage(scope, caller.id)
                    val usage = (before ?: AssistantUsagePreferences(caller.id)).let {
                        it.copy(additionalSubAssistantIds = it.additionalSubAssistantIds + assistant.id)
                    }
                    next = next.copy(preferences = next.preferences.withAssistantUsage(scope, usage))
                    requireAssistantUsageWriteAllowed(before, usage, caller.id, resolved, ConfigurationResolver.resolve(next, scope, state))
                }
            }
            AssistantManagementMutation(next, AssistantManagementResult(assistant))
        }
        is AssistantManagementChange.Update -> {
            val before = target(change.id)
            val updated = before.copy(name = change.name ?: before.name, description = change.description ?: before.description,
                systemPrompt = change.instructions ?: before.systemPrompt)
            AssistantManagementMutation(copy(configuration = configuration.copy(assistants = definitions.map {
                if (it.id == updated.id) updated else it
            })), AssistantManagementResult(updated))
        }
        is AssistantManagementChange.Delete -> {
            require(change.id != callerId) { "target_is_caller" }
            val removed = target(change.id)
            require(definitions.size > 1) { "last_assistant" }
            val remainingDefinitions = definitions.filterNot { it.id == removed.id }
                .map { it.copy(allowedSubAssistantIds = it.allowedSubAssistantIds - removed.id) }
            val remaining = remainingDefinitions.firstOrNull { !it.allowAsSubAssistant } ?: remainingDefinitions.first()
            val updatedPreferences = preferences.copy(scopes = preferences.scopes.map { scoped ->
                scoped.copy(
                    selections = if (scoped.scope == ConfigurationScope.Personal && scoped.selections.assistantId == removed.id)
                        scoped.selections.copy(assistantId = remaining.id) else scoped.selections,
                    assistantUsage = scoped.assistantUsage.filterNot { it.assistantId == removed.id }.map {
                        it.copy(additionalSubAssistantIds = it.additionalSubAssistantIds - removed.id)
                    },
                )
            })
            val deletion = PendingAssistantDeletion(removed.id, (removed.avatar as? Avatar.Image)?.url, removed.background)
            AssistantManagementMutation(copy(configuration = configuration.copy(assistants = remainingDefinitions), preferences = updatedPreferences,
                internalState = internalState.copy(pendingAssistantDeletions =
                    (internalState.pendingAssistantDeletions + deletion).distinctBy { it.assistantId })),
                AssistantManagementResult(removed, deletion))
        }
    }
}
