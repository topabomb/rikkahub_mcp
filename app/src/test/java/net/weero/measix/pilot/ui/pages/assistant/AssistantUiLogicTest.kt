package net.weero.measix.pilot.ui.pages.assistant

import me.rerere.common.configuration.ConfigurationReference

import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.ui.pages.assistant.detail.eligibleSubAssistantIds
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantUiLogicTest {
    @Test
    fun `reorder visible assistants preserves hidden sub assistant slots`() {
        val normalA = Assistant(id = ConfigurationReference.random(), name = "A")
        val hiddenTarget = Assistant(id = ConfigurationReference.random(), name = "Target", allowAsSubAssistant = true)
        val normalB = Assistant(id = ConfigurationReference.random(), name = "B")

        val reordered = reorderVisibleAssistants(
            source = listOf(normalA, hiddenTarget, normalB),
            visible = listOf(normalA, normalB),
            fromIndex = 0,
            toIndex = 1,
        )

        assertEquals(listOf(normalB.id, hiddenTarget.id, normalA.id), reordered.map { it.id })
    }

    @Test
    fun `invalid visible reorder leaves source unchanged`() {
        val assistant = Assistant(id = ConfigurationReference.random(), name = "A")
        val source = listOf(assistant)

        assertEquals(source, reorderVisibleAssistants(source, source, fromIndex = 0, toIndex = 4))
    }

    @Test
    fun `eligible target ids exclude caller ordinary and stale assistants`() {
        val caller = Assistant(id = ConfigurationReference.random(), name = "Caller", allowAsSubAssistant = true)
        val target = Assistant(id = ConfigurationReference.random(), name = "Target", allowAsSubAssistant = true)
        val ordinary = Assistant(id = ConfigurationReference.random(), name = "Ordinary")
        val staleId = Uuid.random()
        val settings = Settings(
            assistants = listOf(caller, target, ordinary),
        )

        assertEquals(setOf(target.id), eligibleSubAssistantIds(settings, caller.id))
        assertEquals(
            setOf(target.id),
            setOf(caller.id, target.id, ordinary.id, staleId).filterTo(mutableSetOf()) {
                it in eligibleSubAssistantIds(settings, caller.id)
            },
        )
    }
}
