package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import net.weero.measix.pilot.data.ai.tools.AssistantManageAction
import net.weero.measix.pilot.data.ai.tools.parseAssistantManageArguments
import net.weero.measix.pilot.data.model.ContextAdmissionReason
import net.weero.measix.pilot.data.model.DisclosureSection
import net.weero.measix.pilot.data.model.DisclosureBuiltinTool
import me.rerere.common.configuration.ConfigurationReference

enum class DisclosureToolOutcome { NOT_EXECUTED, CONFIRMED_SUCCESS, UNCONFIRMED_EXECUTION }

/** Facts are already restricted to the actual request and ordered by committed causal position. */
sealed interface DisclosureFact {
    data class Snapshot(val content: String, val applicableSections: Set<DisclosureSection>) : DisclosureFact

    /** Scope matching includes realm, memory owner, caller and applicable capability boundaries. */
    data class Tool(
        val builtin: DisclosureBuiltinTool,
        val appliesToCurrentScope: Boolean,
        val outcome: DisclosureToolOutcome,
        val input: JsonObject,
        val output: JsonObject?,
    ) : DisclosureFact

    /** An omitted baseline or write cannot be treated as if the model had received it. */
    data class Missing(val sections: Set<DisclosureSection>) : DisclosureFact
}

data class DisclosureReconciliationResult(
    val content: String?,
    val reasons: Map<DisclosureSection, ContextAdmissionReason>,
)

/** Pure model-knowledge projection. Durable context and configuration remain owned by their callers. */
object ConversationDisclosureReconciliation {
    fun reconcile(
        currentContent: String,
        facts: List<DisclosureFact>,
        previouslyDisclosed: Set<DisclosureSection>,
    ): DisclosureReconciliationResult {
        val current = ConversationDisclosureSnapshotService.readSections(currentContent).mapValues { (key, value) ->
            normalizeRows(key, value)
        }
        if (current.keys != DisclosureSection.entries.toSet()) {
            throw DisclosureContentException("current disclosure state must contain every section")
        }
        val known = mutableMapOf<DisclosureSection, JsonObject>()
        val seen = previouslyDisclosed.toMutableSet()
        for (fact in facts) {
            when (fact) {
                is DisclosureFact.Snapshot -> {
                    ConversationDisclosureSnapshotService.readSections(fact.content).forEach { (section, value) ->
                        if (section in fact.applicableSections) {
                            known[section] = normalizeRows(section, value)
                            seen += section
                        }
                    }
                }
                is DisclosureFact.Missing -> {
                    fact.sections.forEach(known::remove)
                    seen += fact.sections
                }
                is DisclosureFact.Tool -> {
                    if (!fact.appliesToCurrentScope || fact.outcome == DisclosureToolOutcome.NOT_EXECUTED) continue
                    val section = fact.builtin.section
                    seen += section
                    val baseline = known[section]
                    val merged = if (baseline != null && fact.outcome == DisclosureToolOutcome.CONFIRMED_SUCCESS) {
                        when (fact.builtin) {
                            DisclosureBuiltinTool.MEMORY -> applyMemory(baseline, fact.input, fact.output)
                            DisclosureBuiltinTool.ASSISTANT_MANAGE -> applyAssistant(baseline, fact.input, fact.output)
                        }
                    } else null
                    if (merged == null) known.remove(section) else known[section] = normalizeRows(section, merged)
                }
            }
        }
        val reasons = buildMap {
            DisclosureSection.entries.forEach { section ->
                val before = known[section]
                if (before != current.getValue(section)) {
                    put(section, when {
                        before != null -> ContextAdmissionReason.EXTERNAL_STATE
                        section in seen -> ContextAdmissionReason.BASELINE_RESTORE
                        else -> ContextAdmissionReason.INITIAL
                    })
                }
            }
        }
        return DisclosureReconciliationResult(
            content = if (reasons.isEmpty()) null else ConversationDisclosureSnapshotService.renderSections(
                current.filterKeys { it in reasons }),
            reasons = reasons,
        )
    }

    private fun applyMemory(baseline: JsonObject, input: JsonObject, output: JsonObject?): JsonObject? {
        if (output == null || "error" in output || baseline.literalBoolean("enabled") != true) return null
        val action = input.string("action") ?: return null
        val id = output.literalInt("id")?.takeIf { it > 0 } ?: return null
        val rows = baseline.rows().associateBy { (it[0] as JsonPrimitive).intOrNull!! }.toMutableMap()
        when (action) {
            "create" -> {
                if ("success" in output && output.literalBoolean("success") != true) return null
                val text = input.string("content") ?: return null
                rows[id] = JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(text)))
            }
            "edit", "delete" -> {
                if (output.literalBoolean("success") != true || input.literalInt("id") != id) return null
                if (action == "delete") rows.remove(id)
                else rows[id] = JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(input.string("content") ?: return null)))
            }
            else -> return null
        }
        return baseline.withRows(rows.toSortedMap().values.toList())
    }

    private fun applyAssistant(baseline: JsonObject, input: JsonObject, output: JsonObject?): JsonObject? {
        if (output == null || "error" in output || baseline.string("mode") == "disabled") return null
        // This is the same input contract used before execution, including legacy normalization.
        val parameters = parseAssistantManageArguments(input) ?: return null
        if (output.string("action") != parameters.action.name.lowercase()) return null
        val id = output.string("id") ?: return null
        val reference = runCatching { ConfigurationReference.parse(id) }.getOrNull()
            ?.takeIf { it.toString() == id } ?: return null
        if (parameters.action != AssistantManageAction.CREATE && parameters.assistantId != reference) return null
        val applied = if ("applied" in output) output["applied"] as? JsonObject ?: return null else null
        val rows = baseline.rows().associateBy { (it[0] as JsonPrimitive).content }.toMutableMap()
        fun actual(field: String, normalized: String?): String? {
            if (normalized == null) return null
            return if (applied != null && field in applied) applied.string(field)?.takeIf { it.isNotEmpty() } else normalized
        }
        when (parameters.action) {
            AssistantManageAction.CREATE -> {
                val name = actual("name", parameters.name) ?: return null
                val description = actual("description", parameters.description) ?: return null
                rows[id] = JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(name), JsonPrimitive(description)))
            }
            AssistantManageAction.UPDATE -> {
                if (parameters.name == null && parameters.description == null) return baseline
                val previous = rows[id]
                val name = if (parameters.name != null) actual("name", parameters.name) ?: return null
                    else (previous?.get(1) as? JsonPrimitive)?.content ?: return null
                val description = if (parameters.description != null) actual("description", parameters.description) ?: return null
                    else (previous?.get(2) as? JsonPrimitive)?.content ?: return null
                rows[id] = JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(name), JsonPrimitive(description)))
            }
            AssistantManageAction.DELETE -> {
                rows.remove(id)
            }
        }
        return baseline.withRows(rows.toSortedMap().values.toList())
    }

    private fun normalizeRows(section: DisclosureSection, value: JsonObject): JsonObject = when (section) {
        DisclosureSection.MEMORY -> value.withRows(value.rows().sortedBy { (it[0] as JsonPrimitive).intOrNull })
        DisclosureSection.SUB_ASSISTANTS -> value.withRows(value.rows().sortedBy { (it[0] as JsonPrimitive).content })
        DisclosureSection.ENTERPRISE_MEMORY_SEEDS -> value // Binding order is meaningful for read-only background.
    }

    private fun JsonObject.rows(): List<JsonArray> = (getValue("rows") as JsonArray).map { it as JsonArray }
    private fun JsonObject.withRows(rows: List<JsonArray>): JsonObject = JsonObject(toMutableMap().apply { put("rows", JsonArray(rows)) })
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.literalInt(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.literalBoolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
