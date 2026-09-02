package net.weero.measix.pilot.test

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.ai.tools.freezeToolSet
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.ResolvedModelRequest
import net.weero.measix.pilot.service.runtime.TurnRequestContext
import net.weero.measix.pilot.service.runtime.resolveAssistantRequest

internal fun testTurnRequestContext(
    settings: Settings,
    model: Model,
    assistant: Assistant,
    tools: List<Tool> = emptyList(),
    mediaCapabilities: RequestMediaCapabilities = RequestMediaCapabilities.NONE,
    promptInputs: net.weero.measix.pilot.service.runtime.FrozenTurnPromptInputs = testPromptInputs(),
): TurnRequestContext {
    val frozen = freezeToolSet(tools)
    return TurnRequestContext(
        assistant = resolveAssistantRequest(assistant),
        model = ResolvedModelRequest(
            model = model,
            providerShape = net.weero.measix.pilot.service.runtime.freezeProviderWireShape(
                model.findProvider(settings.providers) ?: error("Provider not found in test Settings"),
                model,
            ),
            transportLease = net.weero.measix.pilot.service.runtime.ProviderTransportLease {
                model.findProvider(settings.providers) ?: error("Provider not found in test Settings")
            },
        ),
        mediaCapabilities = mediaCapabilities,
        promptInputs = promptInputs,
        toolDefinitions = frozen.definitions,
        toolBindingsByName = frozen.bindingsByName,
    )
}
