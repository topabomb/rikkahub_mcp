package net.weero.measix.pilot.data.db.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal object V3TranscriptValidator {
    /** Validation only; current backups never pass through legacy conversion. */
    fun validateNode(messagesJson: String, json: Json, nonTerminalMessageIds: Set<String> = emptySet()) {
        json.decodeFromString<List<JsonObject>>(messagesJson).forEach { message ->
            val decoded = json.decodeFromJsonElement(UIMessage.serializer(), message)
            if (decoded.role == MessageRole.ASSISTANT) {
                requireNotNull(message["parts"] as? JsonArray).forEach { raw ->
                    val part = raw.jsonObject
                    require("toolCallId" !in part && "approvalState" !in part) { "Mixed legacy and V3 transcript" }
                }
                validateParts(decoded.parts, allowOpen = decoded.id.toString() in nonTerminalMessageIds)
            }
        }
    }

    fun validateParts(parts: List<UIMessagePart>, allowOpen: Boolean) {
        require(parts.firstOrNull() is UIMessagePart.Step) { "Assistant transcript must start with a Step" }
        val steps = parts.filterIsInstance<UIMessagePart.Step>()
        require(steps.none { it.stepId == kotlin.uuid.Uuid.NIL }) { "Missing Step identity" }
        require(steps.map { it.ordinal } == steps.indices.toList()) { "Non-contiguous Step ordinals" }
        require(steps.map { it.stepId }.distinct().size == steps.size) { "Duplicate Step identity" }
        val tools = parts.filterIsInstance<UIMessagePart.Tool>()
        require(tools.none { it.localCallId == kotlin.uuid.Uuid.NIL || it.stepId == kotlin.uuid.Uuid.NIL }) { "Missing Tool identity" }
        require(tools.map { it.localCallId }.distinct().size == tools.size) { "Duplicate Tool identity" }
        var currentStep: UIMessagePart.Step? = null
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Step -> {
                    require(currentStep == null || currentStep!!.outcome == StepOutcome.Continue) {
                        "Only a continuing Step may precede another Step"
                    }
                    currentStep = part
                    if (!allowOpen) require(part.outcome != null) { "Terminal Turn contains an open Step" }
                }
                is UIMessagePart.Tool -> {
                    require(part.stepId == currentStep?.stepId) { "Tool belongs to a different Step" }
                    require(part.metadata?.containsKey("tool_runtime") != true) { "Legacy runtime metadata in V3 Tool" }
                    requireOutputWithoutSteps(part.output)
                    if (!allowOpen || currentStep?.outcome != null) {
                        require(part.resultStatus != null && !part.isPending) { "Closed Step contains an open Tool" }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun requireOutputWithoutSteps(output: List<UIMessagePart>) {
        val remaining = ArrayDeque(output)
        while (remaining.isNotEmpty()) {
            val part = remaining.removeFirst()
            require(part !is UIMessagePart.Step) { "Tool output contains a Step" }
            if (part is UIMessagePart.Tool) remaining.addAll(part.output)
        }
    }

}
