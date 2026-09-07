package net.weero.measix.pilot.service.turn
import net.weero.measix.pilot.service.runtime.ProviderTransportLease
import net.weero.measix.pilot.service.runtime.freezeProviderWireShape

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.tools.freezeToolSet
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import java.time.ZoneId
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * androidTest 共享的最小 canonical candidate 与 [TurnContext] 构造（与
 * `app/src/test` 的 ModelContextTestSupport / TurnContextFixtures 同源语义；
 * androidTest 源集无法访问 JVM test 源集，故同包重建）。
 */
internal fun disclosureCandidate(
    memoryContents: List<String> = emptyList(),
    seed: Long = 1L,
): String {
    val assistant = Assistant(
        id = Uuid.parse("dddd0000-0000-0000-0000-00000000%04x".format(seed)),
        name = "Disclosure Test Assistant",
        localTools = emptyList(),
    )
    return ConversationDisclosureSnapshotService.render(
        ConversationDisclosureSnapshotService.Candidate(
            assistant = assistant,
            allAssistants = listOf(assistant),
            memories = memoryContents.mapIndexed { index, content -> AssistantMemory(index + 1, content) },
        ),
    )
}

internal fun androidTestTurnContext(
    settings: Settings,
    model: Model,
    assistant: Assistant,
    tools: List<Tool> = emptyList(),
    mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
): TurnContext {
    val frozen = freezeToolSet(tools)
    val promptInputs = TurnPromptSnapshot(
        messageTemplate = "{{ message }}",
        promptInjections = emptyList(),
        workspaceReminder = null,
        localeTag = Locale.US.toLanguageTag(),
        zoneId = ZoneId.of("UTC").id,
        conversationSystemPrompt = null,
        modeInjectionIds = emptySet(),
        enableTimeReminder = false,
        placeholderValues = emptyMap(),
    )
    return TurnContext(
        assistant = resolveTurnAssistantSnapshot(assistant),
        model = TurnModelSnapshot(
            model = model,
            providerShape = freezeProviderWireShape(
                model.findProvider(settings.providers) ?: error("Provider not found in test Settings"),
                model,
            ),
            transportLease = ProviderTransportLease {
                model.findProvider(settings.providers) ?: error("Provider not found in test Settings")
            },
        ),
        mediaCapabilities = mediaCapabilities,
        promptInputs = promptInputs,
        toolDefinitions = frozen.definitions,
        toolBindingsByName = frozen.bindingsByName,
    )
}
