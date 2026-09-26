package net.weero.measix.pilot.service

import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.appliedConfiguration
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test

class StarterOpeningSelectionTest {
    private val packet = exampleEnterprisePackage()
    private val candidate = packet.toCandidate()
    private val starter = packet.configuration.starters.first { it.enabled }
    private val opening = candidate.opening(starter)
    private fun resolved(value: EnterprisePackage = packet) = ConfigurationResolver.resolve(
        UserSettingsDocument.empty(), value.identity.scope, appliedConfiguration(value))

    @Test fun `navigation contains only identity and preserves original source across unrelated publication`() {
        val reference = opening.navigationReference()
        val wire = Json.encodeToString(StarterOpeningReference.serializer(), reference)
        assertFalse(wire.contains("systemPrompt"))
        assertFalse(wire.contains("initialContexts"))
        assertFalse(wire.contains(starter.prompt))
        val next = candidate.copy(configuration = candidate.configuration.copy(generation = candidate.configuration.generation + 1),
            execution = (candidate.execution as EnterpriseExecution.Platform).copy(releaseId = "rel_00000000-0000-4000-8000-000000000099"))
        assertEquals(opening, Json.decodeFromString(StarterOpeningReference.serializer(), wire).restore(next, starter))
        requireCurrentStarterOpening(opening, opening.assistant, resolved(packet.copy(configuration = next.configuration)))
    }

    @Test fun `all selected definition changes invalidate navigation and first send without mutating source`() {
        val variants = listOf(starter.copy(prompt = starter.prompt + "edited"), starter.copy(description = "changed"),
            starter.copy(openingSnapshot = starter.openingSnapshot!!.copy(systemPrompt = "changed")),
            starter.copy(openingSnapshot = starter.openingSnapshot!!.copy(initialContexts = starter.openingSnapshot.initialContexts.reversed())))
        variants.forEach { changed ->
            val error = assertThrows(StarterOpeningException::class.java) { opening.navigationReference().restore(candidate, changed) }
            assertEquals(StarterOpeningIssue.UPDATED, error.issue)
            val changedPacket = packet.copy(configuration = packet.configuration.copy(starters = packet.configuration.starters.map {
                if (it.id == starter.id) changed else it
            }))
            assertEquals(StarterOpeningIssue.UPDATED, assertThrows(StarterOpeningException::class.java) {
                requireCurrentStarterOpening(opening, opening.assistant, resolved(changedPacket))
            }.issue)
            assertEquals(starter, opening.definition)
        }
    }

    @Test fun `disabled missing and foreign targets stay distinct from publication changes`() {
        val disabled = packet.copy(configuration = packet.configuration.copy(starters = packet.configuration.starters.map { it.copy(enabled = false) }))
        assertEquals(StarterOpeningIssue.UNAVAILABLE, assertThrows(StarterOpeningException::class.java) {
            requireCurrentStarterOpening(opening, opening.assistant, resolved(disabled))
        }.issue)
        assertEquals(StarterOpeningIssue.ASSISTANT_CHANGED, assertThrows(StarterOpeningException::class.java) {
            requireCurrentStarterOpening(opening, opening.assistant.copy(id = "asd_other"), resolved())
        }.issue)
        assertEquals(StarterOpeningIssue.NOT_SUPPLIED, assertThrows(StarterOpeningException::class.java) {
            candidate.opening(starter.copy(openingSnapshot = null))
        }.issue)
        requireCurrentStarterOpening(null, opening.assistant, resolved(disabled))
    }
}
