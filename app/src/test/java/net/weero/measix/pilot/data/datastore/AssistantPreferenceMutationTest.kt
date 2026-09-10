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

    @Test
    fun `stale page preserves concurrent overrides and reset affects only current principal`() {
        val packet = exampleEnterprisePackage()
        val user = net.weero.measix.pilot.data.model.Assistant(temperature = 0.3f, topP = 0.8f)
        val realm = packet.identity.scope
        val other = realm.copy(userId = "other")
        val original = UserSettingsDocument.empty().let { it.copy(configuration = it.configuration.copy(assistants = listOf(user)),
            preferences = it.preferences.withAssistantUsage(other, AssistantUsagePreferences(user.id, temperature = UsageValue(0.9f)))) }
        val latest = original.changeAssistantPreference(realm, appliedConfiguration(packet), user.id,
            AssistantPreferenceChange.EditUsage(user, user.copy(topP = 0.5f)))
        val edited = latest.changeAssistantPreference(realm, appliedConfiguration(packet), user.id,
            AssistantPreferenceChange.EditUsage(user, user.copy(temperature = null)))
        val usage = edited.preferences.assistantUsage(realm, user.id)!!
        assertEquals(UsageValue<Float?>(null), usage.temperature)
        assertEquals(UsageValue<Float?>(0.5f), usage.topP)
        assertNull(usage.messageTemplate)
        assertEquals(original.configuration, edited.configuration)
        val reset = edited.changeAssistantPreference(realm, appliedConfiguration(packet), user.id, AssistantPreferenceChange.ResetUsage)
        assertNull(reset.preferences.assistantUsage(realm, user.id))
        assertEquals(original.preferences.assistantUsage(other, user.id), reset.preferences.assistantUsage(other, user.id))
        assertEquals(user, ConfigurationResolver.resolve(reset, realm, appliedConfiguration(packet)).assistants.getValue(user.id))
    }

    @Test
    fun `usage editor rejects definition changes invalid numbers and missing tags`() {
        val packet = exampleEnterprisePackage()
        val document = UserSettingsDocument.empty()
        val id = packet.identity.reference(packet.configuration.assistants.first().id)
        val baseline = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet)).assistants.getValue(id)
        listOf(baseline.copy(name = "changed"), baseline.copy(systemPrompt = "changed"), baseline.copy(chatModelId = null),
            baseline.copy(allowConversationSystemPrompt = true), baseline.copy(mcpServers = emptySet()),
            baseline.copy(allowedSubAssistantIds = setOf(me.rerere.common.configuration.ConfigurationReference.random())),
            baseline.copy(temperature = Float.NaN), baseline.copy(topP = 2f), baseline.copy(maxTokens = 0),
            baseline.copy(tags = listOf(me.rerere.common.configuration.ConfigurationReference.random()))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), id, AssistantPreferenceChange.EditUsage(baseline, invalid))
            }
        }
    }

    @Test
    fun `stale editor does not promote removed managed MCP binding into extras`() {
        val packet = exampleEnterprisePackage()
        val id = packet.identity.reference(packet.configuration.assistants.first().id)
        val document = UserSettingsDocument.empty()
        val baseline = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet)).assistants.getValue(id)
        val withoutBinding = packet.copy(configuration = packet.configuration.copy(assistants = packet.configuration.assistants.map {
            if (packet.identity.reference(it.id) == id) it.copy(mcpServerIds = emptyList()) else it
        }))
        val edited = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(withoutBinding), id,
            AssistantPreferenceChange.EditUsage(baseline, baseline.copy(temperature = 0.7f)))
        assertNull(edited.preferences.assistantUsage(packet.identity.scope, id)!!.mcpServers)
        assertTrue(ConfigurationResolver.resolve(edited, packet.identity.scope, appliedConfiguration(withoutBinding)).assistants.getValue(id).mcpServers.isEmpty())
    }

    @Test
    fun `child additions obey admission while inherited bindings cannot be removed`() {
        val packet = exampleEnterprisePackage()
        val child = net.weero.measix.pilot.data.model.Assistant(allowAsSubAssistant = true)
        val parent = net.weero.measix.pilot.data.model.Assistant(allowedSubAssistantIds = setOf(child.id))
        val document = UserSettingsDocument.empty().let { it.copy(configuration = it.configuration.copy(assistants = listOf(parent, child))) }
        assertThrows(IllegalArgumentException::class.java) {
            document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), parent.id, AssistantPreferenceChange.SubAssistant(child.id, false))
        }
        val managed = packet.identity.reference(packet.configuration.assistants.first().id)
        val added = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), managed, AssistantPreferenceChange.SubAssistant(child.id, true))
        assertEquals(setOf(child.id), added.preferences.assistantUsage(packet.identity.scope, managed)!!.additionalSubAssistantIds)
        val denied = packet.copy(configuration = packet.configuration.copy(policy = packet.configuration.policy.copy(allowLocalAssistants = false)))
        assertThrows(SettingsLockedException::class.java) {
            document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(denied), managed, AssistantPreferenceChange.SubAssistant(child.id, true))
        }
        val removed = added.changeAssistantPreference(packet.identity.scope, appliedConfiguration(denied), managed, AssistantPreferenceChange.SubAssistant(child.id, false))
        assertTrue(removed.preferences.assistantUsage(packet.identity.scope, managed)?.additionalSubAssistantIds.orEmpty().isEmpty())
    }

    @Test
    fun `tag creation merges catalog without replacing concurrent names and stores scoped selection`() {
        val packet = exampleEnterprisePackage()
        val id = packet.identity.reference(packet.configuration.assistants.first().id)
        val existing = net.weero.measix.pilot.data.model.Tag(me.rerere.common.configuration.ConfigurationReference.random(), "latest")
        val created = net.weero.measix.pilot.data.model.Tag(me.rerere.common.configuration.ConfigurationReference.random(), "new")
        val document = UserSettingsDocument.empty().let { it.copy(configuration = it.configuration.copy(assistantTags = listOf(existing))) }
        val edited = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), id,
            AssistantPreferenceChange.Tags(listOf(created.id), listOf(existing.copy(name = "stale"), created)))
        assertEquals(listOf(existing, created), edited.configuration.assistantTags)
        assertEquals(listOf(created.id), edited.preferences.assistantUsage(packet.identity.scope, id)!!.tags!!.value)
    }
}
