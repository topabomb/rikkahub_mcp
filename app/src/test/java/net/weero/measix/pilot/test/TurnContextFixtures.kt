package net.weero.measix.pilot.test

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.tools.freezeToolSet
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.ModelExecutionSnapshot
import net.weero.measix.pilot.service.turn.TurnContext
import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot
import net.weero.measix.pilot.service.turn.freezeTurnSystem
import net.weero.measix.pilot.service.turn.isSystemPosition

internal fun testTurnContext(
    settings: Settings,
    model: Model,
    assistant: Assistant,
    tools: List<Tool> = emptyList(),
    mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    promptInputs: net.weero.measix.pilot.service.turn.TurnPromptSnapshot = testPromptInputs(),
    realmAccess: net.weero.measix.pilot.data.enterprise.RealmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
): TurnContext {
    val frozen = freezeToolSet(tools)
    val configuration = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(
        net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty().withPersonalSettings(
            settings.copy(assistants = settings.assistants.filterNot { it.id == assistant.id } + assistant)),
        net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal,
        net.weero.measix.pilot.data.enterprise.EnterpriseState.Loading,
    )

    return TurnContext(
        realmAccess = realmAccess,
        assistant = resolveTurnAssistantSnapshot(assistant),
        model = ModelExecutionSnapshot(
            model = model,
            requests = net.weero.measix.pilot.service.runtime.ModelExecutionLease { accept ->
                accept(net.weero.measix.pilot.service.runtime.ModelRequestTarget.Remote(model.findProvider(settings.providers) ?: error("Provider not found in test Settings")))
            },
            userRevision = "test",
            enterpriseVersion = null,
            imageGeneration = null,
        ),
        mediaCapabilities = mediaCapabilities,
        promptInputs = promptInputs.copy(
            promptInjections = promptInputs.promptInjections.filterNot { it.position.isSystemPosition() },
            workspaceReminder = null,
        ),
        toolDefinitions = frozen.definitions,
        toolBindingsByName = frozen.bindingsByName,
        system = freezeTurnSystem(resolveTurnAssistantSnapshot(assistant), promptInputs, frozen.definitions),
        disclosure = net.weero.measix.pilot.service.turn.TurnDisclosureSource.capture(
            configuration, assistant,
            net.weero.measix.pilot.data.model.DisclosureNamespace(null, assistant.id),
            readConfiguration = { configuration }, readMemory = { emptyList() },
        ),
    )
}
