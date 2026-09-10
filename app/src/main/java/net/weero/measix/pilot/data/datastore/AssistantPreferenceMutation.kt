package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.configuration.withPreference
import net.weero.measix.pilot.data.enterprise.EnterpriseState

internal fun UserSettingsDocument.changeAssistantPreference(
    scope: ConfigurationScope,
    state: EnterpriseState,
    assistantId: ConfigurationReference,
    change: AssistantPreferenceChange,
): UserSettingsDocument {
    val resolved = ConfigurationResolver.resolve(this, scope, state)
    fun requireSelectable(category: ConfigurationCategory, reference: ConfigurationReference) {
        val access = resolved.access(category, reference)
        if (!access.canSelect) throw SettingsLockedException("assistant/$assistantId", access.unavailableReason!!.name)
    }
    requireSelectable(ConfigurationCategory.ASSISTANT, assistantId)
    val current = resolved.assistants[assistantId] ?: error("conversation_assistant_unavailable")
    val fixedMcp = (assistantId as? ConfigurationReference.Enterprise)?.let { reference ->
        resolved.enterpriseConfiguration?.assistants?.singleOrNull { it.id == reference.id }?.mcpServerIds
            ?.mapTo(linkedSetOf()) { ConfigurationReference.Enterprise(reference.authority, it) }
    }.orEmpty()
    val definitionSubAssistants = resolved.inheritedSubAssistantIds[assistantId].orEmpty()
    when (change) {
        is AssistantPreferenceChange.EditUsage -> {
            require(change.baseline.id == assistantId && change.edited.id == assistantId) { "assistant_edit_target_changed" }
            val edited = change.edited
            require(edited.temperature == change.baseline.temperature || edited.temperature == null || edited.temperature.isFinite() && edited.temperature in 0f..2f) { "invalid_temperature" }
            require(edited.topP == change.baseline.topP || edited.topP == null || edited.topP.isFinite() && edited.topP in 0f..1f) { "invalid_top_p" }
            require(edited.maxTokens == change.baseline.maxTokens || edited.maxTokens == null || edited.maxTokens > 0) { "invalid_max_tokens" }
            require(edited.backgroundOpacity == change.baseline.backgroundOpacity || edited.backgroundOpacity.isFinite() && edited.backgroundOpacity in 0f..1f) { "invalid_background_opacity" }
            require((change.baseline.mcpServers - edited.mcpServers).none { it in fixedMcp }) { "enterprise_assistant_mcp_binding_is_fixed" }
            if (edited.chatModelId != change.baseline.chatModelId) {
                require(assistantId is ConfigurationReference.User) { "enterprise_assistant_model_is_fixed" }
                edited.chatModelId?.let { reference ->
                    val choice = resolved.choice(ResourceSelectionSlot.CHAT_MODEL, reference)
                    if (!choice.isAvailable) throw SettingsLockedException("assistant/$assistantId/chatModelId", choice.unavailableReason!!.name)
                }
            }
            if (edited.tags != change.baseline.tags) {
                require(edited.tags.all { selected -> configuration.assistantTags.any { it.id == selected } }) { "assistant_tag_missing" }
            }
            (edited.mcpServers - change.baseline.mcpServers).forEach { requireSelectable(ConfigurationCategory.MCP, it) }
            (edited.quickMessageIds - change.baseline.quickMessageIds).forEach { requireSelectable(ConfigurationCategory.QUICK_MESSAGE, it) }
            (edited.modeInjectionIds - change.baseline.modeInjectionIds).forEach { requireSelectable(ConfigurationCategory.PROMPT_INJECTION, it) }
        }
        AssistantPreferenceChange.ResetUsage -> {
            require(scope is ConfigurationScope.Enterprise) { "personal_assistant_definition_has_no_usage_override" }
            return copy(preferences = preferences.resetAssistantUsage(scope, assistantId))
        }
        is AssistantPreferenceChange.SubAssistant -> {
            require(change.reference != assistantId) { "assistant_cannot_delegate_to_itself" }
            if (scope is ConfigurationScope.Enterprise && change.reference in definitionSubAssistants) {
                require(change.enabled) { "assistant_sub_assistant_binding_is_inherited" }
                return this
            }
            if (change.enabled) {
                requireSelectable(ConfigurationCategory.ASSISTANT, change.reference)
                require(resolved.assistants[change.reference]?.allowAsSubAssistant == true) { "assistant_not_available_for_delegation" }
            }
        }
        is AssistantPreferenceChange.Model, AssistantPreferenceChange.InheritModel -> {
            require(assistantId is ConfigurationReference.User) { "enterprise_assistant_model_is_fixed" }
            (change as? AssistantPreferenceChange.Model)?.reference?.let { reference ->
                val selected = resolved.choice(ResourceSelectionSlot.CHAT_MODEL, reference)
                if (!selected.isAvailable) throw SettingsLockedException("assistant/$assistantId/chatModelId", selected.unavailableReason!!.name)
            }
        }
        is AssistantPreferenceChange.Mcp -> {
            if (change.reference in fixedMcp) {
                require(change.enabled) { "enterprise_assistant_mcp_binding_is_fixed" }
                return this
            }
            if (change.enabled) requireSelectable(ConfigurationCategory.MCP, change.reference)
        }
        is AssistantPreferenceChange.QuickMessage -> if (change.enabled) requireSelectable(ConfigurationCategory.QUICK_MESSAGE, change.reference)
        is AssistantPreferenceChange.PromptInjection -> if (change.enabled) requireSelectable(ConfigurationCategory.PROMPT_INJECTION, change.reference)
        else -> Unit
    }
    val knownTags = configuration.assistantTags.mapTo(hashSetOf()) { it.id }
    val tags = configuration.assistantTags + (change as? AssistantPreferenceChange.Tags)?.catalog.orEmpty().filter { it.id !in knownTags }.distinctBy { it.id }
    if (change is AssistantPreferenceChange.Tags) {
        require(change.selected.all { selected -> tags.any { it.id == selected } }) { "assistant_tag_missing" }
    }
    return when (scope) {
        ConfigurationScope.Personal -> {
            val edited = current.withPreference(change)
            val definitions = configuration.assistants
            copy(configuration = configuration.copy(assistants = if (definitions.any { it.id == assistantId })
                definitions.map { if (it.id == assistantId) edited else it } else definitions + edited))
        }
        is ConfigurationScope.Enterprise -> {
            val before = preferences.assistantUsage(scope, assistantId)
            val usageBase = if (assistantId is ConfigurationReference.Enterprise && change is AssistantPreferenceChange.Mcp) {
                val fixedIds = resolved.enterpriseConfiguration?.assistants?.singleOrNull { it.id == assistantId.id }?.mcpServerIds.orEmpty()
                current.copy(mcpServers = before?.mcpServers?.value ?: current.mcpServers.filterNot {
                    it is ConfigurationReference.Enterprise && it.authority == assistantId.authority && it.id in fixedIds
                }.toSet())
            } else current
            val editedUsage = (before ?: AssistantUsagePreferences(assistantId)).withPreference(change, usageBase)
            val mergedUsage = if (change is AssistantPreferenceChange.EditUsage && change.baseline.mcpServers != change.edited.mcpServers) {
                val originalExtras = before?.mcpServers?.value ?: (current.mcpServers - fixedMcp)
                editedUsage.copy(mcpServers = UsageValue((originalExtras - (change.baseline.mcpServers - change.edited.mcpServers)) +
                    ((change.edited.mcpServers - change.baseline.mcpServers) - fixedMcp)))
            } else editedUsage
            val proposed = mergedUsage.takeUnless { it == AssistantUsagePreferences(assistantId) }
            val updated = copy(preferences = if (proposed == null) preferences.resetAssistantUsage(scope, assistantId)
                else preferences.withAssistantUsage(scope, proposed))
            requireAssistantUsageWriteAllowed(before, proposed, assistantId, resolved, ConfigurationResolver.resolve(updated, scope, state))
            updated
        }
    }.let { result ->
        if (change is AssistantPreferenceChange.Tags) result.copy(configuration = result.configuration.copy(assistantTags = tags)) else result
    }
}
