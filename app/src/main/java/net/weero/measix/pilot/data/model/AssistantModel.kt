package net.weero.measix.pilot.data.model

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model

/** Null inherits the model definition; explicit choices affect only this assistant's request. */
fun Model.withAssistantSearch(assistant: Assistant): Model = when (assistant.builtInSearch) {
    null -> this
    true -> copy(tools = tools + BuiltInTools.Search)
    false -> copy(tools = tools - BuiltInTools.Search)
}
