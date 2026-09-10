package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.subassistant.SubAssistantRunCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantManagementServiceTest {
    @Test
    fun `create validates input and atomically grants caller access`() = runTest {
        val caller = Assistant(
            id = ConfigurationReference.random(),
            name = "Caller",
            localTools = Assistant().localTools + LocalToolOption.AssistantManagement,
        )
        val env = Env(Settings(assistants = listOf(caller), assistantId = caller.id))

        assertTrue(env.service.createAssistant("", "Description", "Prompt", AssistantManagementCaller(caller.id, RealmAccess.Personal)).isFailure)

        val created = env.service.createAssistant(
            name = " Target ",
            description = " Description ",
            instructions = " Prompt ",
            caller = AssistantManagementCaller(caller.id, RealmAccess.Personal),
        ).getOrThrow()

        assertEquals("Target", created.name)
        assertTrue(created.allowAsSubAssistant)
        assertTrue(env.settings.value.assistants.single { it.id == caller.id }.allowedSubAssistantIds.contains(created.id))
        assertEquals(created, env.settings.value.assistants.single { it.id == created.id })
    }

    @Test
    fun `update changes only the explicitly managed fields`() = runTest {
        val caller = managementCaller()
        val target = Assistant(
            id = ConfigurationReference.random(),
            name = "Old",
            description = "Old description",
            systemPrompt = "Old prompt",
            enableWebSearch = true,
            chatModelId = ConfigurationReference.random(),
            allowAsSubAssistant = true,
        )
        val env = Env(
            Settings(
                assistants = listOf(caller.copy(allowedSubAssistantIds = setOf(target.id)), target),
                assistantId = caller.id,
            )
        )

        val updated = env.service.updateAssistant(
            assistantId = target.id,
            name = "New",
            instructions = "New prompt",
            caller = AssistantManagementCaller(caller.id, RealmAccess.Personal),
        ).getOrThrow()

        assertEquals("New", updated.name)
        assertEquals("Old description", updated.description)
        assertEquals("New prompt", updated.systemPrompt)
        assertEquals(target.chatModelId, updated.chatModelId)
        assertTrue(updated.enableWebSearch)
    }

    @Test
    fun `delete commits tombstone then removes it only after ordered cleanup succeeds`() = runTest {
        val caller = managementCaller()
        val target = Assistant(id = ConfigurationReference.random(), name = "Target", allowAsSubAssistant = true)
        val env = Env(
            Settings(
                assistants = listOf(caller.copy(allowedSubAssistantIds = setOf(target.id)), target),
                assistantId = target.id,
            )
        )

        val result = env.service.deleteAssistant(target.id, AssistantManagementCaller(caller.id, RealmAccess.Personal)).getOrThrow()

        assertFalse(result.cleanupPending)
        assertEquals(caller.id, env.settings.value.assistantId)
        assertFalse(env.settings.value.assistants.any { it.id == target.id })
        assertFalse(env.settings.value.assistants.single { it.id == caller.id }.allowedSubAssistantIds.contains(target.id))
        assertTrue(env.settings.value.pendingAssistantDeletions.isEmpty())
        coVerifyOrder {
            env.coordinator.cancelRunsForAssistant(target.id)
            env.conversations.cancelGenerationsForAssistant(target.id, "assistant_removed")
            env.memory.deleteAll(net.weero.measix.pilot.data.model.MemoryAddress(
                net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal,
                net.weero.measix.pilot.data.model.MemoryOwner.Assistant(target.id),
            ))
            env.conversations.deleteOfAssistantFromPendingCleanup(target.id)
            env.artifacts.collectGarbage(protectionWindowMillis = 0)
        }
    }

    @Test
    fun `failed cleanup keeps durable tombstone and recovery retry consumes it`() = runTest {
        val caller = managementCaller()
        val target = Assistant(id = ConfigurationReference.random(), name = "Target", allowAsSubAssistant = true)
        val env = Env(
            Settings(
                assistants = listOf(caller.copy(allowedSubAssistantIds = setOf(target.id)), target),
                assistantId = caller.id,
            )
        )
        coEvery { env.conversations.deleteOfAssistantFromPendingCleanup(target.id) } throws
            IllegalStateException("storage unavailable")

        val result = env.service.deleteAssistant(target.id, AssistantManagementCaller(caller.id, RealmAccess.Personal)).getOrThrow()

        assertTrue(result.cleanupPending)
        assertEquals(listOf(target.id), env.settings.value.pendingAssistantDeletions.map { it.assistantId })

        coEvery { env.conversations.deleteOfAssistantFromPendingCleanup(target.id) } returns Unit
        env.service.performPendingDeletionCleanupDuringRecovery()

        assertTrue(env.settings.value.pendingAssistantDeletions.isEmpty())
        coVerify(exactly = 2) { env.conversations.deleteOfAssistantFromPendingCleanup(target.id) }
    }

    @Test
    fun `restored assistant identity cancels stale tombstone without deleting restored data`() = runTest {
        val restored = Assistant(id = ConfigurationReference.random(), name = "Restored")
        val env = Env(
            Settings(
                assistants = listOf(restored),
                assistantId = restored.id,
                pendingAssistantDeletions = listOf(
                    net.weero.measix.pilot.data.datastore.PendingAssistantDeletion(restored.id)
                ),
            )
        )

        env.service.performPendingDeletionCleanupDuringRecovery()

        assertTrue(env.settings.value.pendingAssistantDeletions.isEmpty())
        coVerify(exactly = 0) { env.coordinator.cancelRunsForAssistant(any()) }
        coVerify(exactly = 0) { env.conversations.deleteOfAssistantFromPendingCleanup(any()) }
    }

    private fun managementCaller(): Assistant = Assistant(
        id = ConfigurationReference.random(),
        name = "Caller",
        localTools = Assistant().localTools + LocalToolOption.AssistantManagement,
    )

    private class Env(initial: Settings) {
        val settings = MutableStateFlow(initial)
        private var document = UserSettingsDocument.empty().withPersonalSettings(initial)
        val sessions = mockk<EnterpriseSessionController>()
        val settingsStore = mockk<SettingsStore>()
        val artifacts = mockk<ArtifactStore>(relaxed = true)
        val memory = mockk<MemoryRepository>(relaxed = true)
        val coordinator = mockk<SubAssistantRunCoordinator>(relaxed = true)
        val conversations = mockk<ConversationApplicationService>(relaxed = true)
        val recoveryGate = mockk<ApplicationRecoveryGate>(relaxed = true)

        init {
            every { settingsStore.userSettings } returns settings
            every { sessions.state } returns MutableStateFlow<EnterpriseState>(EnterpriseState.Loading)
            every { sessions.requirePublishedRealmAccess(any()) } returns Unit
            coEvery { sessions.withRealmAccess<AssistantManagementResult>(any(), any()) } coAnswers {
                secondArg<suspend () -> AssistantManagementResult>()()
            }
            coEvery { artifacts.manageAssistantReferences(any(), any(), any(), any(), any()) } coAnswers {
                arg<() -> Unit>(4)()
                val mutation = document.manageAssistant(firstArg(), secondArg(), thirdArg(), arg(3))
                document = mutation.document
                settings.value = document.personalSettings()
                mutation.result
            }
            coEvery { artifacts.updateSettingsReferences(any()) } coAnswers {
                settings.value = firstArg<(Settings) -> Settings>()(settings.value)
                document = document.withPersonalSettings(settings.value)
                settings.value
            }
        }

        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = memory,
            artifactStore = artifacts,
            subAssistantRunCoordinator = coordinator,
            recoveryGate = recoveryGate,
            conversationApplicationService = conversations,
            sessions = sessions,
        )
    }
}
