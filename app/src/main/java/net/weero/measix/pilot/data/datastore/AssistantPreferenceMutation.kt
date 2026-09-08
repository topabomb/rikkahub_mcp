package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
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
    when (change) {
        is AssistantPreferenceChange.Model, AssistantPreferenceChange.InheritModel -> {
            require(assistantId is ConfigurationReference.User) { "enterprise_assistant_model_is_fixed" }
            (change as? AssistantPreferenceChange.Model)?.reference?.let { reference ->
                val selected = resolved.choice(ResourceSelectionSlot.CHAT_MODEL, reference)
                if (!selected.isAvailable) throw SettingsLockedException("assistant/$assistantId/chatModelId", selected.unavailableReason!!.name)
            }
        }
        is AssistantPreferenceChange.Mcp -> {
            val fixed = (assistantId as? ConfigurationReference.Enterprise)?.let { reference ->
                resolved.enterpriseConfiguration?.assistants?.singleOrNull { it.id == reference.id }?.mcpServerIds
                    ?.map { ConfigurationReference.Enterprise(reference.authority, it) }
            }.orEmpty()
            if (change.reference in fixed) {
                require(change.enabled) { "enterprise_assistant_mcp_binding_is_fixed" }
                return this
            }
            if (change.enabled) requireSelectable(ConfigurationCategory.MCP, change.reference)
        }
        is AssistantPreferenceChange.QuickMessage -> if (change.enabled) requireSelectable(ConfigurationCategory.QUICK_MESSAGE, change.reference)
        is AssistantPreferenceChange.PromptInjection -> if (change.enabled) requireSelectable(ConfigurationCategory.PROMPT_INJECTION, change.reference)
        else -> Unit
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
            val proposed = (before ?: AssistantUsagePreferences(assistantId)).withPreference(change, usageBase)
                .takeUnless { it == AssistantUsagePreferences(assistantId) }
            val updated = copy(preferences = if (proposed == null) preferences.resetAssistantUsage(scope, assistantId)
                else preferences.withAssistantUsage(scope, proposed))
            requireAssistantUsageWriteAllowed(before, proposed, assistantId, resolved, ConfigurationResolver.resolve(updated, scope, state))
            updated
        }
    }
}
