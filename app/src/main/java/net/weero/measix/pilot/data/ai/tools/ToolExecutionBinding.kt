package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.freeze
import me.rerere.ai.ui.UIMessagePart
import java.util.Collections

/** Execution-only half of a Tool; Provider adapters can never access these closures. */
internal data class ToolExecutionBinding(
    val name: String,
    val interactionRequirement: (JsonObject) -> ToolInteractionRequirement,
    val validateArguments: (JsonElement) -> JsonObject?,
    val outputPolicy: ToolOutputPolicy,
    val successfulOutputPolicy: (List<UIMessagePart>) -> ToolOutputPolicy,
    val execute: suspend ToolExecutionContext.(JsonElement) -> List<UIMessagePart>,
) {
    fun parseArguments(input: String, json: Json): JsonObject {
        val parsed = try {
            json.parseToJsonElement(input.ifBlank { "{}" })
        } catch (_: SerializationException) {
            throw invalidArguments("Arguments must be valid JSON.")
        }
        val arguments = parsed as? JsonObject
            ?: throw invalidArguments("Arguments must be a JSON object.")
        validateArguments(arguments)?.let { throw ToolArgumentsException(it) }
        return arguments
    }
}

internal data class FrozenToolSet(
    val definitions: List<FrozenToolDefinition>,
    val bindingsByName: Map<String, ToolExecutionBinding>,
)

/** Materializes ordered wire definitions and their one-to-one execution index at START. */
internal fun freezeToolSet(tools: List<Tool>): FrozenToolSet {
    val definitions = ArrayList<FrozenToolDefinition>(tools.size)
    val bindings = LinkedHashMap<String, ToolExecutionBinding>(tools.size)
    tools.forEach { tool ->
        require(tool.name.isNotBlank()) { "Tool name must not be blank" }
        require(tool.name !in bindings) { "Duplicate tool name: ${tool.name}" }
        definitions += tool.freeze()
        bindings[tool.name] = ToolExecutionBinding(
            name = tool.name,
            interactionRequirement = tool.interactionRequirement,
            validateArguments = tool.validateArguments,
            outputPolicy = tool.outputPolicy,
            successfulOutputPolicy = tool.successfulOutputPolicy,
            execute = { arguments -> tool.executeWithContext(this, arguments) },
        )
    }
    return FrozenToolSet(
        definitions = Collections.unmodifiableList(definitions),
        bindingsByName = Collections.unmodifiableMap(bindings),
    )
}

private fun invalidArguments(detail: String): ToolArgumentsException = ToolArgumentsException(
    buildJsonObject {
        put("error", JsonPrimitive("invalid_arguments"))
        put("detail", JsonPrimitive(detail))
    },
)
