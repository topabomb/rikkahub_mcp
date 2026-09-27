package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterInitialContext
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot
import net.weero.measix.pilot.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationContextContentProjectionTest {
    private fun row(id: String, content: String) = JsonArray(listOf(JsonPrimitive(id.toInt()), JsonPrimitive(content)))
    private fun memory(rows: String) = """{"type":"conversation_disclosure_snapshot","format":3,"memory":{"enabled":true,"scope":"global","header":["id","content"],"rows":$rows}}"""

    @Test fun `exact changes show before and after without claiming unchanged rows changed`() {
        val change = DisclosureSectionChange(
            addedIds = listOf("3"), updatedBefore = listOf(row("1", "old")),
            removedBefore = listOf(row("4", "deleted")), attributesBefore = emptyMap(),
        )
        val source = ConversationContextSource.Disclosure(null,
            mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE), mapOf(DisclosureSection.MEMORY to change))
        val text = memory("""[[1,"new"],[2,"unchanged"],[3,"<literal>{{not a variable}}</literal>"]]""")
        val section = projectConversationContextContent(source, text).sections.single()
        assertTrue(section.exactChanges)
        assertEquals(ConversationContextReason.EXTERNAL, section.reason)
        assertEquals(ConversationContextScope.SHARED, section.scope)
        assertEquals(setOf("1", "3", "4"), section.rows.map { it.id }.toSet())
        assertEquals("<literal>{{not a variable}}</literal>", section.rows.single { it.id == "3" }.after)
        assertEquals("old", section.rows.single { it.id == "1" }.before)
        assertEquals("new", section.rows.single { it.id == "1" }.after)
        assertEquals(ConversationContextChangeKind.REMOVED, section.rows.single { it.id == "4" }.change)
        assertEquals("deleted", section.rows.single { it.id == "4" }.before)
        assertNull(section.rows.single { it.id == "4" }.after)
    }

    @Test fun `old external records cannot invent deltas and restoration is not an update`() {
        val text = memory("""[[1,"saved"]]""")
        for ((reason, expected) in listOf(
            ContextAdmissionReason.INITIAL to ConversationContextReason.INITIAL,
            ContextAdmissionReason.EXTERNAL_STATE to ConversationContextReason.EXTERNAL,
            ContextAdmissionReason.BASELINE_RESTORE to ConversationContextReason.RESTORE,
        )) {
            val section = projectConversationContextContent(ConversationContextSource.Disclosure(null,
                mapOf(DisclosureSection.MEMORY to reason)), text).sections.single()
            assertEquals(expected, section.reason)
            assertFalse(section.exactChanges)
            assertNull(section.rows.single().change)
            assertEquals("saved", section.rows.single().after)
        }
        assertEquals(ConversationContextReason.HISTORICAL,
            projectConversationContextContent(ConversationContextSource.Disclosure(null), text).sections.single().reason)
    }

    @Test fun `assistant names belong to headings while a rename preserves the previous name`() {
        val id = "10000000-0000-0000-0000-000000000001"
        val source = ConversationContextSource.Disclosure(null,
            mapOf(DisclosureSection.SUB_ASSISTANTS to ContextAdmissionReason.EXTERNAL_STATE),
            mapOf(DisclosureSection.SUB_ASSISTANTS to DisclosureSectionChange(updatedBefore = listOf(
                JsonArray(listOf(JsonPrimitive(id), JsonPrimitive("Previous name"), JsonPrimitive("Previous description"))),
            ))))
        val text = """{"type":"conversation_disclosure_snapshot","format":3,"sub_assistants":{"mode":"both","header":["id","name","description"],"rows":[["$id","Current name","Current description"]]}}"""
        val row = projectConversationContextContent(source, text).sections.single().rows.single()
        assertEquals("Current name", row.title)
        assertEquals("Current description", row.after)
        assertEquals("Previous name\nPrevious description", row.before)
    }

    @Test fun `attribute only update remains visible with no invented row changes`() {
        val source = ConversationContextSource.Disclosure(null,
            mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE),
            mapOf(DisclosureSection.MEMORY to DisclosureSectionChange(emptyList(), emptyList(), emptyList(),
                mapOf("scope" to JsonPrimitive("local")))))
        val section = projectConversationContextContent(source, memory("[]")).sections.single()
        assertTrue(section.exactChanges)
        assertTrue(section.rows.isEmpty())
        assertEquals(listOf(ConversationContextAttributeUiModel("scope", "local", "global")), section.attributes)
    }

    @Test fun `system ownership and variables come from the saved contribution only`() {
        val source = ConversationContextSource.System(listOf(
            SystemContextContribution(SystemContextKind.DOMAIN, "assistant", "Hello {name}", mapOf("name" to "{saved}")),
            SystemContextContribution(SystemContextKind.TOOL, "search", "Use search"),
        ))
        val sections = projectConversationContextContent(source, "Hello {saved}\n\nUse search").sections
        assertEquals(listOf(ConversationContextSystemOwner.DOMAIN, ConversationContextSystemOwner.TOOL), sections.map { it.systemOwner })
        assertEquals("Hello {saved}", sections.first().text)
        assertEquals("search", sections.last().title)
    }

    @Test fun `system domain origins are typed and internal names stay out of display titles`() {
        val source = ConversationContextSource.System(listOf(
            SystemContextContribution(SystemContextKind.DOMAIN, "conversation_override", "custom"),
            SystemContextContribution(SystemContextKind.DOMAIN, "enterprise_opening", "enterprise"),
            SystemContextContribution(SystemContextKind.APPLICATION, "disclosure_interpretation", "sync"),
        ))
        val sections = projectConversationContextContent(source, "custom\n\nenterprise\n\nsync").sections
        assertEquals(listOf(ConversationContextSystemOwner.CONVERSATION, ConversationContextSystemOwner.ENTERPRISE_OPENING,
            ConversationContextSystemOwner.CONTEXT_SYNC), sections.map { it.systemOwner })
        assertTrue(sections.all { it.title == null })
    }

    @Test fun `background reorder exposes before and after order using the saved text at each boundary`() {
        fun reference(name: String) = ConfigurationReference.Enterprise(EnterpriseAuthority("deployment"), "seed_$name").toString()
        val first = reference("first")
        val second = reference("second")
        val added = reference("added")
        val removed = reference("removed")
        fun seed(id: String, content: String) = JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(content)))
        val text = """{"type":"conversation_disclosure_snapshot","format":3,"enterprise_memory_seeds":{"header":["id","content"],"rows":[["$second","second"],["$added","added"],["$first","first after"]]}}"""
        val source = ConversationContextSource.Disclosure(null,
            mapOf(DisclosureSection.ENTERPRISE_MEMORY_SEEDS to ContextAdmissionReason.EXTERNAL_STATE),
            mapOf(DisclosureSection.ENTERPRISE_MEMORY_SEEDS to DisclosureSectionChange(
                addedIds = listOf(added), updatedBefore = listOf(seed(first, "first before")),
                removedBefore = listOf(seed(removed, "removed")), orderBefore = listOf(first, removed, second))))
        val section = projectConversationContextContent(source, text).sections.single()
        assertEquals(listOf(first, second), section.orderBefore.map { it.id })
        assertEquals(listOf(second, first), section.orderAfter.map { it.id })
        assertEquals("first before", section.orderBefore.first().after)
        assertEquals("first after", section.orderAfter.last().after)
        val pureReorder = source.copy(changes = mapOf(DisclosureSection.ENTERPRISE_MEMORY_SEEDS to
            DisclosureSectionChange(orderBefore = listOf(first, second))))
        val reorderedText = """{"type":"conversation_disclosure_snapshot","format":3,"enterprise_memory_seeds":{"header":["id","content"],"rows":[["$second","second"],["$first","first after"]]}}"""
        val onlyOrder = projectConversationContextContent(pureReorder, reorderedText).sections.single()
        assertTrue(onlyOrder.rows.isEmpty())
        assertEquals(listOf(second, first), onlyOrder.orderAfter.map { it.id })
    }

    @Test fun `starter sections preserve saved titles and literal content without duplicating system in its background use`() {
        val opening = EnterpriseStarterOpeningSnapshot(1, "saved system",
            listOf(EnterpriseStarterInitialContext("b", "Saved title", "{{literal}}\n<background>")))
        val complete = projectStarterContext(opening).sections
        assertEquals(2, complete.size)
        assertEquals("saved system", complete.first().text)
        assertEquals("Saved title", complete.last().title)
        assertEquals("{{literal}}\n<background>", complete.last().text)
        assertEquals(listOf(complete.last()), projectConversationContextContent(ConversationContextSource.Starter, "raw", opening).sections)
    }
}
