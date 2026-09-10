package net.weero.measix.pilot.data.datastore

import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.subassistant.buildToolCreatedAssistant
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class AssistantManagementMutationTest {
    @Test fun `enterprise and user callers create one shared definition with a grant only in their current principal`() {
        val packet = exampleEnterprisePackage()
        val state = appliedConfiguration(packet)
        val user = Assistant(name = "My caller", localTools = listOf(LocalToolOption.AssistantManagement))
        for (caller in listOf(user.id, packet.identity.reference(packet.configuration.assistants.first().id))) {
            val initial = UserSettingsDocument.empty().copy(configuration = UserConfiguration(assistants = listOf(user))).let {
                it.copy(preferences = it.preferences.withAssistantUsage(packet.identity.scope,
                    AssistantUsagePreferences(caller, localTools = UsageValue(listOf(LocalToolOption.AssistantManagement)))))
            }
            val child = buildToolCreatedAssistant("Child", "Research", "Find evidence")
            val created = initial.manageAssistant(packet.identity.scope, state, caller, AssistantManagementChange.Create(child))
            assertEquals(listOf(user, child), created.document.configuration.assistants.filter { it.id == user.id || it.id == child.id })
            val usage = created.document.preferences.assistantUsage(packet.identity.scope, caller)!!
            assertEquals(setOf(child.id), usage.additionalSubAssistantIds)
            assertNull(created.document.preferences.assistantUsage(packet.identity.scope.copy(userId = "another"), caller))
            val resolved = ConfigurationResolver.resolve(created.document, packet.identity.scope, state)
            assertTrue(SubAssistantAccessPolicy.canAccess(resolved.assistants.getValue(caller), resolved.assistants.getValue(child.id)))
            assertFalse(child.id in created.document.configuration.assistants.first().allowedSubAssistantIds)
            val denied = appliedConfiguration(packet.copy(configuration = packet.configuration.copy(
                policy = packet.configuration.policy.copy(allowLocalAssistants = false))))
            for (change in listOf(AssistantManagementChange.Create(buildToolCreatedAssistant("Another", "d", "p")),
                AssistantManagementChange.Update(child.id, "Changed", null, null), AssistantManagementChange.Delete(child.id))) {
                try { created.document.manageAssistant(packet.identity.scope, denied, caller, change); fail("policy bypass") }
                catch (_: IllegalArgumentException) { }
            }
            try { created.document.manageAssistant(packet.identity.scope, state, caller,
                AssistantManagementChange.Update(packet.identity.reference(packet.configuration.assistants.last().id), "Changed", null, null))
                fail("enterprise definition changed")
            } catch (failure: IllegalArgumentException) { assertEquals("enterprise_assistant_read_only", failure.message) }
        }
    }

    @Test fun `deletion removes all scoped usage and incremental grants while retaining unrelated fields and enterprise selections`() {
        val packet = exampleEnterprisePackage()
        val caller = Assistant(name = "Caller", localTools = listOf(LocalToolOption.AssistantManagement))
        val child = buildToolCreatedAssistant("Child", "d", "p")
        val other = packet.identity.scope.copy(userId = "other")
        val base = UserSettingsDocument.empty().withPersonalSettings(Settings(assistants = listOf(caller.copy(allowedSubAssistantIds = setOf(child.id)), child), assistantId = child.id))
        val initial = base.copy(preferences = base.preferences
            .withAssistantUsage(packet.identity.scope, AssistantUsagePreferences(caller.id, additionalSubAssistantIds = setOf(child.id), temperature = UsageValue(0.7f)))
            .withAssistantUsage(packet.identity.scope, AssistantUsagePreferences(child.id, enableMemory = UsageValue(true)))
            .withAssistantUsage(other, AssistantUsagePreferences(caller.id, additionalSubAssistantIds = setOf(child.id))))
        val mutation = initial.manageAssistant(ConfigurationScope.Personal, EnterpriseState.Loading, caller.id, AssistantManagementChange.Delete(child.id))
        assertEquals(caller, mutation.document.configuration.assistants.single { it.id == caller.id })
        assertEquals(caller.id, mutation.document.preferences.forScope(ConfigurationScope.Personal).assistantId)
        assertEquals(UsageValue(0.7f), mutation.document.preferences.assistantUsage(packet.identity.scope, caller.id)?.temperature)
        assertTrue(mutation.document.preferences.scopes.all { scope -> scope.assistantUsage.none { it.assistantId == child.id || child.id in it.additionalSubAssistantIds } })
        assertEquals(listOf(child.id), mutation.document.internalState.pendingAssistantDeletions.map { it.assistantId })
        assertEquals(child, mutation.result.assistant)
        assertEquals(initial.configuration.providers, mutation.document.configuration.providers)
    }
    @Test fun `built in assistants absent from storage participate in management just as they do in the catalog`() {
        val custom = Assistant(name = "Custom")
        val initial = UserSettingsDocument.empty().withPersonalSettings(Settings(assistants = listOf(custom), assistantId = custom.id))
        val builtin = DEFAULT_ASSISTANTS.first()
        val renamed = initial.manageAssistant(ConfigurationScope.Personal, EnterpriseState.Loading, null,
            AssistantManagementChange.Update(builtin.id, "Renamed default", null, null)).document
        assertEquals("Renamed default", renamed.configuration.assistants.single { it.id == builtin.id }.name)
        val deleted = initial.manageAssistant(ConfigurationScope.Personal, EnterpriseState.Loading, null, AssistantManagementChange.Delete(custom.id)).document
        assertEquals(DEFAULT_ASSISTANTS, deleted.configuration.assistants)
        assertEquals(builtin.id, deleted.preferences.forScope(ConfigurationScope.Personal).assistantId)
    }

}
