package net.weero.measix.pilot.test

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.tools.freezeToolSet
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.turn.TurnModelSnapshot
import net.weero.measix.pilot.service.turn.TurnContext
import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot

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
    return TurnContext(
        realmAccess = realmAccess,
        assistant = resolveTurnAssistantSnapshot(assistant),
        model = TurnModelSnapshot(
            model = model,
            executionLease = net.weero.measix.pilot.service.runtime.ModelExecutionLease { accept ->
                accept(net.weero.measix.pilot.service.runtime.ModelRequestTarget.Remote(model.findProvider(settings.providers) ?: error("Provider not found in test Settings")))
            },
            userRevision = "test",
            enterpriseVersion = null,
        ),
        mediaCapabilities = mediaCapabilities,
        promptInputs = promptInputs,
        toolDefinitions = frozen.definitions,
        toolBindingsByName = frozen.bindingsByName,
    )
}
