package net.weero.measix.pilot.data.datastore

import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test

class AssistantPreferenceMutationTest {
    @Test
    fun `enabling a newly fixed MCP does not turn the fixed binding into a persisted user choice`() {
        val packet = exampleEnterprisePackage()
        val assistant = packet.configuration.assistants.first()
        val ref = packet.identity.reference(assistant.id)
        val fixed = packet.identity.reference(assistant.mcpServerIds.first())
        val document = UserSettingsDocument.empty()
        val updated = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), ref,
            AssistantPreferenceChange.Mcp(fixed, true))
        assertEquals(document, updated)
        val unbound = packet.copy(configuration = packet.configuration.copy(assistants = packet.configuration.assistants.map {
            if (it.id == assistant.id) it.copy(mcpServerIds = emptyList()) else it
        }))
        assertFalse(ConfigurationResolver.resolve(updated, packet.identity.scope, appliedConfiguration(unbound)).assistants.getValue(ref).mcpServers.contains(fixed))
    }

    @Test
    fun `editing another MCP retains an earlier explicit choice while it is temporarily fixed`() {
        val packet = exampleEnterprisePackage()
        val assistant = packet.configuration.assistants.first()
        val ref = packet.identity.reference(assistant.id)
        val fixed = packet.identity.reference(assistant.mcpServerIds.first())
        val absent = packet.identity.reference("mcp_removed")
        val document = UserSettingsDocument.empty().let { it.copy(preferences = it.preferences.withAssistantUsage(packet.identity.scope,
            AssistantUsagePreferences(ref, mcpServers = UsageValue(setOf(fixed, absent))))) }
        val updated = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), ref,
            AssistantPreferenceChange.Mcp(absent, false))
        assertEquals(setOf(fixed), updated.preferences.assistantUsage(packet.identity.scope, ref)!!.mcpServers!!.value)
        assertEquals(document.configuration, updated.configuration)
    }
}
