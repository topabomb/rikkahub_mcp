package net.weero.measix.pilot.data.datastore

import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test

class AssistantPreferenceMutationTest {
    @Test
    fun `background edits are principal preferences and revoked personal assistants cannot be edited`() {
        val packet = exampleEnterprisePackage()
        val user = net.weero.measix.pilot.data.model.Assistant(name = "Personal", background = "personal-background")
        val document = UserSettingsDocument.empty().let { it.copy(configuration = it.configuration.copy(assistants = listOf(user))) }
        val updated = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), user.id,
            AssistantPreferenceChange.Background("enterprise-background"))
        assertEquals(document.configuration, updated.configuration)
        assertEquals("enterprise-background", updated.preferences.assistantUsage(packet.identity.scope, user.id)?.background?.value)
        assertNull(updated.preferences.assistantUsage(packet.identity.scope.copy(userId = "another"), user.id))
        val denied = packet.copy(configuration = packet.configuration.copy(policy = packet.configuration.policy.copy(allowLocalAssistants = false)))
        try {
            updated.changeAssistantPreference(packet.identity.scope, appliedConfiguration(denied), user.id,
                AssistantPreferenceChange.Background("must-not-write"))
            fail("revoked assistant accepted")
        } catch (_: SettingsLockedException) { }
    }

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
