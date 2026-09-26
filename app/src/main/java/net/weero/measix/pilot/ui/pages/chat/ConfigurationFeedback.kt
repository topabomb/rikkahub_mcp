package net.weero.measix.pilot.ui.pages.chat

import net.weero.measix.pilot.data.configuration.AssistantPreferenceChange

/** Only request configuration uses the next-send message; visual preferences apply immediately. */
internal fun AssistantPreferenceChange.affectsNextSend(): Boolean = when (this) {
    is AssistantPreferenceChange.Avatar, is AssistantPreferenceChange.Background,
    is AssistantPreferenceChange.Tags, is AssistantPreferenceChange.QuickMessage -> false
    is AssistantPreferenceChange.EditUsage -> with(baseline) {
        chatModelId != edited.chatModelId || systemPrompt != edited.systemPrompt ||
            temperature != edited.temperature || topP != edited.topP ||
            contextMessageLimit != edited.contextMessageLimit || streamOutput != edited.streamOutput ||
            enableMemory != edited.enableMemory || useGlobalMemory != edited.useGlobalMemory ||
            enableRecentChatsReference != edited.enableRecentChatsReference ||
            messageTemplate != edited.messageTemplate ||
            regexes.filterNot { it.visualOnly } != edited.regexes.filterNot { it.visualOnly } ||
            reasoningLevel != edited.reasoningLevel || maxTokens != edited.maxTokens ||
            customHeaders != edited.customHeaders || customBodies != edited.customBodies ||
            mcpServers != edited.mcpServers || localTools != edited.localTools ||
            enableWebSearch != edited.enableWebSearch || builtInSearch != edited.builtInSearch ||
            workspaceId != edited.workspaceId || modeInjectionIds != edited.modeInjectionIds ||
            enabledSkills != edited.enabledSkills || enableTimeReminder != edited.enableTimeReminder ||
            allowConversationSystemPrompt != edited.allowConversationSystemPrompt ||
            allowConversationPromptInjection != edited.allowConversationPromptInjection
    }
    AssistantPreferenceChange.ResetUsage, is AssistantPreferenceChange.Model,
    AssistantPreferenceChange.InheritModel, is AssistantPreferenceChange.Reasoning,
    is AssistantPreferenceChange.Search, is AssistantPreferenceChange.Mcp,
    is AssistantPreferenceChange.SubAssistant, is AssistantPreferenceChange.PromptInjection,
    is AssistantPreferenceChange.Skill, is AssistantPreferenceChange.Workspace,
    is AssistantPreferenceChange.LocalTool -> true
}
