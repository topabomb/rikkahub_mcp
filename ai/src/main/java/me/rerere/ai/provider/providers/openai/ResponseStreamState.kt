package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ProviderToolCallSlot
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderTerminalStatus
import java.util.concurrent.atomic.AtomicBoolean

/** Response-local identities never become durable tool ids or fabricated wire item ids. */
internal class ResponseStreamState {
    internal class Output(val slot: ProviderToolCallSlot.Index, var item: JsonObject) {
        var itemId: String? = null
        var outputIndex: Int? = null
        val callId: String? get() = item.string("call_id")
        var started = false
        var arguments = ""
        var argumentsComplete = false

        fun finishArguments(value: String): String {
            check(arguments.isEmpty() || arguments == value) {
                "responses_arguments_conflict: complete arguments differ from streamed arguments"
            }
            val delta = if (arguments.isEmpty()) value else ""
            arguments = value
            argumentsComplete = true
            item = JsonObject(item + ("arguments" to JsonPrimitive(value)))
            return delta
        }
    }

    val reasoningTextEmittedByItemId = mutableSetOf<String>()
    private val outputs = mutableListOf<Output>()
    private val terminalSeen = AtomicBoolean(false)

    fun output(event: JsonObject, allowNew: Boolean): Output {
        val item = event["item"]?.jsonObject
        val outerId = event.string("item_id")
        val innerId = item?.string("id")
        check(outerId == null || innerId == null || outerId == innerId) { "responses_identity_conflict: item ids disagree" }
        val id = outerId ?: innerId
        val index = event["output_index"]?.jsonPrimitive?.intOrNull
        check("output_index" !in event || (index != null && index >= 0)) {
            "responses_identity_invalid: output_index must be a nonnegative integer"
        }
        val callId = item?.string("call_id") ?: event.string("call_id")
        check(!allowNew || id != null || index != null) {
            "responses_identity_missing: a new output needs item_id or output_index; call_id is only a correlation key"
        }
        val byId = id?.let { value -> outputs.singleOrNull { it.itemId == value } }
        val byIndex = index?.let { value -> outputs.singleOrNull { it.outputIndex == value } }
        check(byId == null || byIndex == null || byId === byIndex) { "responses_identity_conflict: item_id and output_index disagree" }
        var found = byId ?: byIndex
        if (found == null && id == null && index == null && callId != null) {
            val matches = outputs.filter { it.callId == callId }
            check(matches.size <= 1) { "responses_identity_ambiguous: call_id names multiple output slots" }
            found = matches.singleOrNull()
        }
        if (found == null) {
            check(allowNew && item != null && (id != null || index != null)) {
                "responses_identity_unknown: event has no known output slot"
            }
            found = Output(ProviderToolCallSlot.Index(outputs.size), item)
            outputs += found
        }
        check(id == null || found.itemId == null || found.itemId == id) { "responses_identity_conflict: item_id changed" }
        check(index == null || found.outputIndex == null || found.outputIndex == index) { "responses_identity_conflict: output_index changed" }
        check(callId == null || found.callId == null || found.callId == callId) { "responses_identity_conflict: call_id changed" }
        if (item != null) {
            check(found.item.string("type") == item.string("type")) { "responses_identity_conflict: output type changed" }
            check(found.item.string("name") == null || item.string("name") == null ||
                found.item.string("name") == item.string("name")) { "responses_identity_conflict: tool name changed" }
            found.item = JsonObject(found.item + item)
            if (found.argumentsComplete) {
                found.item = JsonObject(found.item + ("arguments" to JsonPrimitive(found.arguments)))
            }
        }
        if (id != null) found.itemId = id
        if (index != null) found.outputIndex = index
        return found
    }

    fun recordOutputItem(event: JsonObject) {
        output(event, allowNew = true)
    }

    /** Successful replay must describe precisely the tool arguments already consumed by the caller. */
    fun outputItems(terminalItems: List<JsonObject>? = null): List<JsonObject> {
        val tools = outputs.filter { it.started }
        if (terminalItems != null) {
            val terminalTools = terminalItems.withIndex().filter { it.value.string("type") == "function_call" }
            val resolved = terminalTools.map { (index, item) ->
                // A complete output array supplies the authoritative output_index, including late item ids.
                val event = JsonObject(buildMap {
                    put("item", item)
                    put("output_index", JsonPrimitive(index))
                })
                val state = output(event, allowNew = false)
                check(state.started) { "responses_unstarted_tool: terminal output has no emitted tool" }
                val args = item.string("arguments") ?: error("responses_arguments_missing: terminal tool has no arguments")
                check(state.arguments == args) { "responses_arguments_conflict: terminal arguments differ from emitted arguments" }
                state.finishArguments(args)
                state
            }
            check(resolved.toSet().size == resolved.size && resolved.toSet() == tools.toSet()) {
                "responses_output_conflict: terminal output does not contain each streamed tool exactly once"
            }
            // Raw replay pairs repeated call_id occurrences in order; never silently exchange results.
            tools.groupBy { it.callId }.filterValues { it.size > 1 }.forEach { (id, emitted) ->
                check(resolved.filter { it.callId == id } == emitted) {
                    "responses_duplicate_call_order: terminal output reverses repeated call_id slots"
                }
            }
            return terminalItems
        }
        check(tools.all { it.argumentsComplete }) { "responses_arguments_incomplete: successful terminal lacks complete tool arguments" }
        val ordered = outputs.sortedBy { it.outputIndex ?: it.slot.index }
        tools.groupBy { it.callId }.filterValues { it.size > 1 }.forEach { (id, emitted) ->
            check(ordered.filter { it.started && it.callId == id } == emitted) {
                "responses_duplicate_call_order: output_index reverses repeated call_id slots"
            }
        }
        return ordered.map { it.item }
    }

    fun markTerminal() { terminalSeen.set(true) }

    fun prematureCloseError(): HttpException? = if (terminalSeen.get()) null else HttpException(
        message = "Response stream closed before a terminal event",
        terminalStatus = ProviderTerminalStatus.INCOMPLETE,
    )
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
