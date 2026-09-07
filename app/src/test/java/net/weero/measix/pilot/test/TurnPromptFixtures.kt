package net.weero.measix.pilot.test

import net.weero.measix.pilot.service.turn.TurnPromptSnapshot
import net.weero.measix.pilot.service.turn.ResolvedPromptInjection
import java.time.ZoneId
import java.util.Locale

fun testPromptInputs(
    messageTemplate: String? = "{{ message }}",
    promptInjections: List<ResolvedPromptInjection> = emptyList(),
    workspaceReminder: String? = null,
    enableTimeReminder: Boolean = false,
    placeholderValues: Map<String, String> = emptyMap(),
): TurnPromptSnapshot = TurnPromptSnapshot(
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
