package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.model.DisclosureBuiltinTool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.data.model.ContextAdmissionReason
import net.weero.measix.pilot.data.model.DisclosureSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationDisclosureReconciliationTest {
    private val all = DisclosureSection.entries.toSet()
    private val target = "11111111-1111-1111-1111-111111111111"
    private val other = "22222222-2222-2222-2222-222222222222"
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun state(memory: List<Pair<Int, String>> = emptyList(), assistants: List<List<String>> = emptyList()): String =
        ConversationDisclosureSnapshotService.renderSections(linkedMapOf(
            DisclosureSection.MEMORY to buildJsonObject {
                put("enabled", true); put("scope", "local")
                put("header", buildJsonArray { add("id"); add("content") })
                put("rows", JsonArray(memory.map { (id, text) -> buildJsonArray { add(id); add(text) } }))
            },
            DisclosureSection.SUB_ASSISTANTS to buildJsonObject {
                put("mode", "both")
                put("header", buildJsonArray { add("id"); add("name"); add("description") })
                put("rows", JsonArray(assistants.map { cells -> JsonArray(cells.map(::JsonPrimitive)) }))
            },
            DisclosureSection.ENTERPRISE_MEMORY_SEEDS to obj("""{"header":["id","content"],"rows":[]}"""),
        ))
    private fun snapshot(content: String, applicable: Set<DisclosureSection> = all) = DisclosureFact.Snapshot(content, applicable)
    private fun replaceSection(content: String, section: DisclosureSection, value: JsonObject): String =
        ConversationDisclosureSnapshotService.renderSections(
            ConversationDisclosureSnapshotService.readSections(content) + (section to value))
    private fun memory(input: String, output: String? = null,
        outcome: DisclosureToolOutcome = DisclosureToolOutcome.CONFIRMED_SUCCESS,
        applies: Boolean = true) = DisclosureFact.Tool(DisclosureBuiltinTool.MEMORY, applies, outcome,
            obj(input), output?.let(::obj))
    private fun assistant(input: String, output: String? = null,
        outcome: DisclosureToolOutcome = DisclosureToolOutcome.CONFIRMED_SUCCESS) =
        DisclosureFact.Tool(DisclosureBuiltinTool.ASSISTANT_MANAGE, true, outcome, obj(input), output?.let(::obj))
    private fun reconcile(current: String, vararg facts: DisclosureFact,
        previous: Set<DisclosureSection> = emptySet()) =
        ConversationDisclosureReconciliation.reconcile(current, facts.toList(), previous)

    @Test fun `memory compact create edit delete results fully describe own writes`() {
        val empty = state()
        val create = memory("""{"action":"create","content":"A"}""", """{"id":17}""")
        val edit = memory("""{"action":"edit","id":17,"content":"B"}""", """{"success":true,"id":17}""")
        val delete = memory("""{"action":"delete","id":17}""", """{"success":true,"id":17}""")
        assertNull(reconcile(state(listOf(17 to "A")), snapshot(empty), create).content)
        assertNull(reconcile(state(listOf(17 to "B")), snapshot(empty), create, edit).content)
        assertNull(reconcile(empty, snapshot(empty), create, edit, delete).content)
    }

    @Test fun `complete own effects remain known when a target was absent from the old baseline`() {
        val empty = state()
        val edit = memory("""{"action":"edit","id":17,"content":"B"}""", """{"success":true,"id":17}""")
        val delete = memory("""{"action":"delete","id":17}""", """{"success":true,"id":17}""")
        assertNull(reconcile(state(listOf(17 to "B")), snapshot(empty), edit).content)
        assertNull(reconcile(empty, snapshot(empty), delete).content)
        val update = assistant("""{"action":"UPDATE","assistant_id":"$target","name":"Name","description":"new route"}""",
            """{"action":"update","id":"$target"}""")
        assertNull(reconcile(state(assistants = listOf(listOf(target, "Name", "new route"))), snapshot(empty), update).content)
        val remove = assistant("""{"action":"DELETE","assistant_id":"$target"}""", """{"action":"delete","id":"$target"}""")
        assertNull(reconcile(empty, snapshot(empty), remove).content)
    }

    @Test fun `own write then external overwrite or revert emits full current partition`() {
        val baseline = state(listOf(17 to "A"))
        val own = memory("""{"action":"edit","id":17,"content":"B"}""", """{"success":true,"id":17}""")
        for (rows in listOf(listOf(17 to "C"), listOf(17 to "A"), listOf(17 to "B", 18 to "external"))) {
            val current = state(rows)
            val result = reconcile(current, snapshot(baseline), own)
            assertEquals(mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE), result.reasons)
            assertEquals(ConversationDisclosureSnapshotService.selectSections(current, setOf(DisclosureSection.MEMORY)), result.content)
        }
        assertNull(reconcile(baseline, snapshot(baseline)).content)
        assertNull(reconcile(state(listOf(17 to "B")), snapshot(baseline), own).content)
    }

    @Test fun `assistant causal chain uses normalized legacy input and only touched applied fields`() {
        val baseline = state(assistants = listOf(listOf(other, "Other", "unchanged")))
        val create = assistant("""{"action":"CREATE","name":"  New  ","description":"  two   words  ","instructions":" role "}""",
            """{"action":"create","id":"$target"}""")
        val created = listOf(listOf(target, "New", "two words"), listOf(other, "Other", "unchanged"))
        assertNull(reconcile(state(assistants = created), snapshot(baseline), create).content)
        val update = assistant("""{"action":"UPDATE","assistant_id":"$target","name":"renamed"}""",
            """{"action":"update","id":"$target","applied":{"name":"Actual name","description":"untouched"}}""")
        val updated = listOf(listOf(target, "Actual name", "two words"), listOf(other, "Other", "unchanged"))
        assertNull(reconcile(state(assistants = updated), snapshot(baseline), create, update).content)
        val delete = assistant("""{"action":"DELETE","assistant_id":"$target"}""", """{"action":"delete","id":"$target","cleanup_pending":true}""")
        assertNull(reconcile(baseline, snapshot(baseline), create, update, delete).content)
        val result = reconcile(state(assistants = updated), snapshot(baseline), create, delete, update)
        assertEquals(ContextAdmissionReason.BASELINE_RESTORE, result.reasons[DisclosureSection.SUB_ASSISTANTS])
    }

    @Test fun `instructions only do not conceal an external directory change`() {
        val baseline = state(assistants = listOf(listOf(target, "A", "old")))
        val call = assistant("""{"action":"UPDATE","assistant_id":"$target","instructions":"new role"}""",
            """{"action":"update","id":"$target"}""")
        assertNull(reconcile(baseline, snapshot(baseline), call).content)
        assertEquals(ContextAdmissionReason.EXTERNAL_STATE,
            reconcile(baseline, snapshot(state()), call).reasons[DisclosureSection.SUB_ASSISTANTS])
        val current = state(assistants = listOf(listOf(target, "A", "external")))
        assertEquals(mapOf(DisclosureSection.SUB_ASSISTANTS to ContextAdmissionReason.EXTERNAL_STATE),
            reconcile(current, snapshot(baseline), call).reasons)
    }

    @Test fun `missing or unconfirmed writes recover while never executed failures keep known state`() {
        val baseline = state(listOf(17 to "A"))
        val current = state(listOf(17 to "B"))
        val input = """{"action":"edit","id":17,"content":"B"}"""
        val denied = memory(input, outcome = DisclosureToolOutcome.NOT_EXECUTED)
        assertEquals(ContextAdmissionReason.EXTERNAL_STATE, reconcile(current, snapshot(baseline), denied).reasons[DisclosureSection.MEMORY])
        val unknown = memory(input, outcome = DisclosureToolOutcome.UNCONFIRMED_EXECUTION)
        assertEquals(ContextAdmissionReason.BASELINE_RESTORE, reconcile(current, snapshot(baseline), unknown).reasons[DisclosureSection.MEMORY])
        assertEquals(ContextAdmissionReason.BASELINE_RESTORE,
            reconcile(current, snapshot(baseline), memory(input, """{"success":true}""")).reasons[DisclosureSection.MEMORY])
        assertNull(reconcile(current, snapshot(baseline), unknown, snapshot(current)).content)
    }

    @Test fun `malformed success and mismatched identifiers never hide a state difference`() {
        val baseline = state(listOf(17 to "A"))
        val current = state(listOf(17 to "B"))
        val input = """{"action":"edit","id":17,"content":"B"}"""
        val outputs = listOf("""{"success":true,"id":18}""", """{"success":"true","id":17}""",
            """{"success":true,"id":"17"}""", """{"success":false,"id":17}""")
        outputs.forEach { output ->
            assertEquals(ContextAdmissionReason.BASELINE_RESTORE,
                reconcile(current, snapshot(baseline), memory(input, output)).reasons[DisclosureSection.MEMORY])
        }
        val invalidInput = memory("""{"action":"edit","id":17,"content":123}""", """{"success":true,"id":17}""")
        assertEquals(ContextAdmissionReason.BASELINE_RESTORE,
            reconcile(current, snapshot(baseline), invalidInput).reasons[DisclosureSection.MEMORY])
    }

    @Test fun `mixed initial restored and external sections have separate reasons`() {
        val baseline = state(listOf(17 to "A"))
        val oldFormat = baseline.replace("\"format\":3", "\"format\":1")
            .replace(",\"enterprise_memory_seeds\":{\"header\":[\"id\",\"content\"],\"rows\":[]}", "")
        val current = state(listOf(17 to "B"))
        val initial = reconcile(current)
        assertEquals(all.associateWith { ContextAdmissionReason.INITIAL }, initial.reasons)
        val result = reconcile(current, snapshot(oldFormat), DisclosureFact.Missing(setOf(DisclosureSection.SUB_ASSISTANTS)))
        assertEquals(mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE,
            DisclosureSection.SUB_ASSISTANTS to ContextAdmissionReason.BASELINE_RESTORE,
            DisclosureSection.ENTERPRISE_MEMORY_SEEDS to ContextAdmissionReason.INITIAL), result.reasons)
        assertEquals(all.associateWith { ContextAdmissionReason.BASELINE_RESTORE }, reconcile(current, previous = all).reasons)
    }

    @Test fun `partial snapshots and missing markers follow causal order per section`() {
        val baseline = state(listOf(17 to "A"))
        val current = state(listOf(17 to "B"))
        val memoryOnly = ConversationDisclosureSnapshotService.selectSections(current, setOf(DisclosureSection.MEMORY))
        assertNull(reconcile(current, snapshot(baseline), snapshot(memoryOnly)).content)
        assertNull(reconcile(current, snapshot(baseline), DisclosureFact.Missing(setOf(DisclosureSection.MEMORY)), snapshot(memoryOnly)).content)
        assertEquals(mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.BASELINE_RESTORE),
            reconcile(current, snapshot(baseline), snapshot(memoryOnly), DisclosureFact.Missing(setOf(DisclosureSection.MEMORY))).reasons)
    }

    @Test fun `other namespace writes cannot cancel a real current namespace difference`() {
        val baseline = state(listOf(17 to "A"))
        val current = state(listOf(17 to "B"))
        val otherNamespace = memory("""{"action":"edit","id":17,"content":"B"}""", """{"success":true,"id":17}""", applies = false)
        assertEquals(ContextAdmissionReason.EXTERNAL_STATE,
            reconcile(current, snapshot(baseline), otherNamespace).reasons[DisclosureSection.MEMORY])
        assertEquals(ContextAdmissionReason.BASELINE_RESTORE,
            reconcile(current, snapshot(baseline, all - DisclosureSection.MEMORY), previous = all).reasons[DisclosureSection.MEMORY])
    }

    @Test fun `semantic row order matches legacy snapshots but complete candidate is always validated`() {
        val current = state(listOf(1 to "one", 2 to "two"), listOf(listOf(target, "A", "a"), listOf(other, "B", "b")))
        val reversed = state(listOf(2 to "two", 1 to "one"), listOf(listOf(other, "B", "b"), listOf(target, "A", "a")))
        assertNull(reconcile(current, snapshot(reversed)).content)
        val partial = ConversationDisclosureSnapshotService.selectSections(current, setOf(DisclosureSection.MEMORY))
        assertThrows(DisclosureContentException::class.java) { reconcile(partial) }
        val tooLarge = current.replace("one", "x".repeat(ConversationDisclosureSnapshotService.MAX_CANONICAL_CONTENT_UTF8_BYTES))
        assertThrows(DisclosureContentException::class.java) { reconcile(tooLarge, snapshot(current)) }
    }

    @Test fun `recorded changes compare against confirmed tool effects rather than the last snapshot`() {
        val baseline = state(listOf(17 to "A", 18 to "remove", 19 to "unchanged"))
        val own = memory("""{"action":"edit","id":17,"content":"B"}""", """{"success":true,"id":17}""")
        val current = state(listOf(17 to "B", 19 to "unchanged", 20 to "added"))
        val delta = reconcile(current, snapshot(baseline), own).changes.getValue(DisclosureSection.MEMORY)
        assertEquals(listOf("20"), delta.addedIds)
        assertEquals(emptyList<JsonArray>(), delta.updatedBefore)
        assertEquals(listOf(JsonArray(listOf(JsonPrimitive(18), JsonPrimitive("remove")))), delta.removedBefore)
        assertEquals(emptyMap<String, JsonPrimitive>(), delta.attributesBefore)
        val overwrite = reconcile(state(listOf(17 to "external", 18 to "remove", 19 to "unchanged")),
            snapshot(baseline), own).changes.getValue(DisclosureSection.MEMORY)
        assertEquals(listOf(JsonArray(listOf(JsonPrimitive(17), JsonPrimitive("B")))), overwrite.updatedBefore)
        assertEquals(emptyMap<DisclosureSection, Any>(), reconcile(state(listOf(17 to "B", 18 to "remove", 19 to "unchanged")),
            snapshot(baseline), own).changes)
    }

    @Test fun `clear and property-only changes keep actual old rows and old properties`() {
        val baseline = state(listOf(17 to "old"))
        val clear = reconcile(state(), snapshot(baseline)).changes.getValue(DisclosureSection.MEMORY)
        assertEquals(listOf(JsonArray(listOf(JsonPrimitive(17), JsonPrimitive("old")))), clear.removedBefore)
        assertEquals(emptyMap<String, JsonPrimitive>(), clear.attributesBefore)
        val disabled = replaceSection(baseline, DisclosureSection.MEMORY,
            obj("""{"enabled":false,"scope":"disabled","header":["id","content"],"rows":[]}"""))
        val change = reconcile(disabled, snapshot(baseline)).changes.getValue(DisclosureSection.MEMORY)
        assertEquals(mapOf("enabled" to JsonPrimitive(true), "scope" to JsonPrimitive("local")), change.attributesBefore)
        assertEquals(clear.removedBefore, change.removedBefore)
        val global = replaceSection(state(), DisclosureSection.MEMORY,
            obj("""{"enabled":true,"scope":"global","header":["id","content"],"rows":[]}"""))
        assertEquals(mapOf("scope" to JsonPrimitive("local")),
            reconcile(global, snapshot(state())).changes.getValue(DisclosureSection.MEMORY).attributesBefore)
        val mode = replaceSection(state(), DisclosureSection.SUB_ASSISTANTS,
            obj("""{"mode":"delegation_only","header":["id","name","description"],"rows":[]}"""))
        val modeChange = reconcile(mode, snapshot(state())).changes.getValue(DisclosureSection.SUB_ASSISTANTS)
        assertEquals(mapOf("mode" to JsonPrimitive("both")), modeChange.attributesBefore)
        assertEquals(emptyList<String>(), modeChange.addedIds)
        assertEquals(emptyList<JsonArray>(), modeChange.updatedBefore)
    }

    @Test fun `seed additions are distinct from actual surviving-row reorder`() {
        val target = "managed~test~seed_a"
        val other = "managed~test~seed_b"
        fun seeds(vararg ids: String) = replaceSection(state(), DisclosureSection.ENTERPRISE_MEMORY_SEEDS,
            buildJsonObject {
                put("header", buildJsonArray { add("id"); add("content") })
                put("rows", JsonArray(ids.map { buildJsonArray { add(it); add("background") } }))
            })
        val baseline = seeds(target, other)
        val reordered = reconcile(seeds(other, target), snapshot(baseline)).changes.getValue(DisclosureSection.ENTERPRISE_MEMORY_SEEDS)
        assertEquals(listOf(target, other), reordered.orderBefore)
        assertEquals(emptyList<JsonArray>(), reordered.updatedBefore)
        assertEquals(emptyList<String>(), reordered.addedIds)
        val added = reconcile(baseline, snapshot(seeds(other))).changes.getValue(DisclosureSection.ENTERPRISE_MEMORY_SEEDS)
        assertEquals(listOf(target), added.addedIds)
        assertNull(added.orderBefore)
    }

    @Test fun `initial and restored sections never acquire fabricated change details`() {
        val current = state(listOf(17 to "new"))
        assertEquals(emptyMap<DisclosureSection, Any>(), reconcile(current).changes)
        assertEquals(emptyMap<DisclosureSection, Any>(), reconcile(current, previous = all).changes)
        val result = reconcile(current, snapshot(state()), DisclosureFact.Missing(setOf(DisclosureSection.SUB_ASSISTANTS)))
        assertEquals(setOf(DisclosureSection.MEMORY), result.changes.keys)
    }

    @Test fun `assistant change keeps the actual known name after own tool normalization`() {
        val baseline = state(assistants = listOf(listOf(target, "Original", "old route")))
        val own = assistant("""{"action":"UPDATE","assistant_id":"$target","name":"  Own   name  "}""",
            """{"action":"update","id":"$target","applied":{"name":"Own name"}}""")
        val current = state(assistants = listOf(listOf(target, "Own name", "external route")))
        val delta = reconcile(current, snapshot(baseline), own).changes.getValue(DisclosureSection.SUB_ASSISTANTS)
        assertEquals(listOf(JsonArray(listOf(JsonPrimitive(target), JsonPrimitive("Own name"), JsonPrimitive("old route")))),
            delta.updatedBefore)
        assertEquals(emptyList<String>(), delta.addedIds)
        assertEquals(emptyList<JsonArray>(), delta.removedBefore)
    }
}
