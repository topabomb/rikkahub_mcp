package net.weero.measix.pilot.data.configuration

import kotlinx.serialization.encodeToString
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.datastore.ScopedUserPreferences
import net.weero.measix.pilot.data.datastore.UserPreferences
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class AssistantUsagePreferencesTest {
    @Test
    fun `missing and explicit null survive serialization with different meanings`() {
        val definition = Assistant(temperature = 0.7f, chatModelId = ConfigurationReference.random())
        val inherited = AssistantUsagePreferences(definition.id)
        val cleared = AssistantUsagePreferences(definition.id, temperature = UsageValue(null), chatModelId = UsageValue(null))
        val roundTrip = JsonInstant.decodeFromString<AssistantUsagePreferences>(JsonInstant.encodeToString(cleared))
        assertEquals(cleared, roundTrip)
        assertEquals(0.7f, resolveUserAssistantUsage(definition, inherited).temperature)
        assertNull(resolveUserAssistantUsage(definition, roundTrip).temperature)
        assertNull(resolveUserAssistantUsage(definition, roundTrip).chatModelId)
        assertEquals(0.7f, definition.temperature)
        assertNotNull(definition.chatModelId)
    }

    @Test
    fun `only explicitly overridden fields stop following the shared user definition`() {
        val definition = Assistant(name = "before", temperature = 0.1f, topP = 0.9f)
        val preference = AssistantUsagePreferences(definition.id, temperature = UsageValue(0.5f))
        val changed = definition.copy(name = "after", temperature = 0.8f, topP = 0.6f)
        val resolved = resolveUserAssistantUsage(changed, preference)
        assertEquals("after", resolved.name)
        assertEquals(0.5f, resolved.temperature)
        assertEquals(0.6f, resolved.topP)
    }

    @Test
    fun `enterprise owned fields and child references remain fixed while extensions can be selected`() {
        val packet = exampleEnterprisePackage()
        val fixed = packet.configuration.assistants.first()
        val id = packet.identity.reference(fixed.id)
        val additionalMcp = ConfigurationReference.random()
        val additionalChild = ConfigurationReference.random()
        val usage = AssistantUsagePreferences(id, mcpServers = UsageValue(setOf(additionalMcp)), enableWebSearch = UsageValue(true),
            additionalSubAssistantIds = setOf(additionalChild))
        val resolved = resolveEnterpriseAssistantUsage(packet.identity, fixed, usage)
        assertEquals(packet.identity.reference(fixed.modelId), resolved.chatModelId)
        assertEquals(fixed.systemPrompt, resolved.systemPrompt)
        assertEquals(fixed.name, resolved.name)
        assertFalse(resolved.allowConversationSystemPrompt)
        assertTrue(resolved.enableWebSearch)
        assertEquals(fixed.mcpServerIds.map { packet.identity.reference(it) }.toSet() + additionalMcp, resolved.mcpServers)
        assertEquals(fixed.allowedSubAssistantIds.map { packet.identity.reference(it) }.toSet() + additionalChild, resolved.allowedSubAssistantIds)
        assertThrows(IllegalArgumentException::class.java) { AssistantUsagePreferences(id, chatModelId = UsageValue(null)) }
        assertThrows(IllegalArgumentException::class.java) { AssistantUsagePreferences(id, allowConversationSystemPrompt = UsageValue(true)) }
    }

    @Test
    fun `principal preferences do not copy definitions and reject foreign references`() {
        val identity = exampleEnterprisePackage().identity
        val alice = identity.scope
        val bob = alice.copy(userId = "bob")
        val assistant = Assistant(name = "shared")
        val usage = AssistantUsagePreferences(assistant.id, chatModelId = UsageValue(identity.reference("mdl_chat")))
        val document = UserSettingsDocument.empty().let { initial ->
            initial.copy(configuration = initial.configuration.copy(assistants = listOf(assistant)),
                preferences = initial.preferences.withAssistantUsage(alice, usage))
        }
        val decoded = JsonInstant.decodeFromString<UserSettingsDocument>(JsonInstant.encodeToString(document))
        assertEquals(listOf(assistant), decoded.configuration.assistants)
        assertEquals(usage, decoded.preferences.assistantUsage(alice, assistant.id))
        assertNull(decoded.preferences.assistantUsage(bob, assistant.id))
        assertNull(decoded.preferences.resetAssistantUsage(alice, assistant.id).assistantUsage(alice, assistant.id))
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(scopes = listOf(ScopedUserPreferences(ConfigurationScope.Personal, assistantUsage = listOf(usage))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decoded.preferences.withAssistantUsage(alice.copy(authority = alice.authority.copy(deploymentId = "different")), usage)
        }
    }
}
