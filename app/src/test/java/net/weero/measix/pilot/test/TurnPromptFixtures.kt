package net.weero.measix.pilot.test

import net.weero.measix.pilot.service.runtime.FrozenTurnPromptInputs
import net.weero.measix.pilot.service.runtime.ResolvedPromptInjection
import java.time.ZoneId
import java.util.Locale

fun testPromptInputs(
    messageTemplate: String? = "{{ message }}",
    promptInjections: List<ResolvedPromptInjection> = emptyList(),
    workspaceReminder: String? = null,
    enableTimeReminder: Boolean = false,
    placeholderValues: Map<String, String> = emptyMap(),
): FrozenTurnPromptInputs = FrozenTurnPromptInputs(
    messageTemplate = messageTemplate,
    promptInjections = promptInjections,
    workspaceReminder = workspaceReminder,
    localeTag = Locale.US.toLanguageTag(),
    zoneId = ZoneId.of("UTC").id,
    conversationSystemPrompt = null,
    modeInjectionIds = emptySet(),
    enableTimeReminder = enableTimeReminder,
    placeholderValues = placeholderValues,
)
