package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.PendingAssistantDeletion
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantManagementServiceTest {

    private val callerId = Uuid.random()

    private fun createService(
        initialAssistants: List<Assistant>,
    ): AssistantManagementService {
        val settings = Settings(
            assistants = initialAssistants,
            assistantId = initialAssistants.firstOrNull()?.id ?: Uuid.random(),
        )
        val settingsFlow = MutableStateFlow(settings)

        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value

        // Capture the transform and apply it to the settings flow
        coEvery { settingsStore.updateAtomic(any()) } answers {
            val fn = firstArg<(Settings) -> Settings>()
            val newSettings = fn(settingsFlow.value)
            settingsFlow.value = newSettings
        }

        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        val filesManager = mockk<FilesManager>(relaxed = true)

        return AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = memoryRepo,
            conversationRepo = conversationRepo,
            filesManager = filesManager,
            sessionRegistry = mockk(relaxed = true),
            subAssistantCoordinator = mockk(relaxed = true),
        )
    }

    @Test
    fun `create assistant with default local tools and restricted extensions`() = runTest {
        val initialAssistant = Assistant(id = callerId, name = "Caller")
        val service = createService(listOf(initialAssistant))

        val result = service.createAssistant(
            name = "New Assistant",
            description = "A helpful sub-assistant",
            instructions = "You are helpful.",
        )

        assertTrue(result.isSuccess)
        val assistant = result.getOrThrow()
        assertEquals("New Assistant", assistant.name)
        assertEquals("A helpful sub-assistant", assistant.description)
        assertEquals("You are helpful.", assistant.systemPrompt)
        assertTrue(assistant.allowAsSubAssistant)
        assertFalse(assistant.enableWebSearch)
        assertFalse(assistant.enableRecentChatsReference)
        assertEquals(null, assistant.chatModelId)
        val ordinaryDefaultTools = listOf(
            LocalToolOption.TimeInfo,
            LocalToolOption.Tts,
            LocalToolOption.AskUser,
        )
        assertEquals(ordinaryDefaultTools, Assistant().localTools)
        assertEquals(ordinaryDefaultTools, assistant.localTools)
        assertTrue(assistant.mcpServers.isEmpty())
        assertTrue(assistant.enabledSkills.isEmpty())
    }

    @Test
    fun `create fails when name is empty`() = runTest {
        val service = createService(listOf(Assistant(id = callerId, name = "Caller")))
        val result = service.createAssistant("", "desc", "instructions")
        assertTrue(result.isFailure)
    }

    @Test
    fun `create fails when description is empty`() = runTest {
        val service = createService(listOf(Assistant(id = callerId, name = "Caller")))
        val result = service.createAssistant("name", "", "instructions")
        assertTrue(result.isFailure)
    }

    @Test
    fun `create fails when instructions is empty`() = runTest {
        val service = createService(listOf(Assistant(id = callerId, name = "Caller")))
        val result = service.createAssistant("name", "desc", "")
        assertTrue(result.isFailure)
    }

    @Test
    fun `update assistant only modifies name description and instructions`() = runTest {
        val targetId = Uuid.random()
        val target = Assistant(
            id = targetId,
            name = "Original",
            description = "Original description",
            systemPrompt = "Original instructions",
            allowAsSubAssistant = false,
            enableWebSearch = true,
            chatModelId = Uuid.random(),
        )
        val service = createService(listOf(Assistant(id = callerId, name = "Caller"), target))

        val result = service.updateAssistant(
            assistantId = targetId,
            name = "Updated",
            description = "Updated description",
            instructions = "Updated instructions",
        )

        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        assertEquals("Updated", updated.name)
        assertEquals("Updated description", updated.description)
        assertEquals("Updated instructions", updated.systemPrompt)
        // These should NOT change
        assertFalse(updated.allowAsSubAssistant)
        assertTrue(updated.enableWebSearch)
        assertEquals(target.chatModelId, updated.chatModelId)
    }

    @Test
    fun `update fails when no fields provided`() = runTest {
        val targetId = Uuid.random()
        val service = createService(listOf(Assistant(id = callerId, name = "Caller"), Assistant(id = targetId, name = "Target")))

        val result = service.updateAssistant(targetId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `update fails when assistant not found`() = runTest {
        val service = createService(listOf(Assistant(id = callerId, name = "Caller")))

        val result = service.updateAssistant(
            assistantId = Uuid.random(),
            name = "New Name",
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `delete assistant cleans up memory conversations and files`() = runTest {
        val targetId = Uuid.random()
        val target = Assistant(id = targetId, name = "Target")
        val service = createService(listOf(Assistant(id = callerId, name = "Caller"), target))

        val result = service.deleteAssistant(targetId)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `delete commits tombstone then removes it only after cleanup succeeds`() = runTest {
        val targetId = Uuid.random()
        val caller = Assistant(id = callerId, name = "Caller")
        val target = Assistant(id = targetId, name = "Target")
        val settingsFlow = MutableStateFlow(Settings(assistants = listOf(caller, target), assistantId = targetId))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        coEvery { settingsStore.updateAtomic(any()) } answers {
            settingsFlow.value = firstArg<(Settings) -> Settings>()(settingsFlow.value)
        }
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        val sessionRegistry = mockk<ConversationSessionRegistry>(relaxed = true)
        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = memoryRepo,
            conversationRepo = conversationRepo,
            filesManager = mockk(relaxed = true),
            sessionRegistry = sessionRegistry,
            subAssistantCoordinator = mockk(relaxed = true),
        )

        val result = service.deleteAssistant(targetId).getOrThrow()

        assertFalse(result.cleanupPending)
        assertFalse(settingsFlow.value.assistants.any { it.id == targetId })
        assertTrue(settingsFlow.value.pendingAssistantDeletions.isEmpty())
        assertEquals(callerId, settingsFlow.value.assistantId)
        coVerify(exactly = 1) { sessionRegistry.cancelGenerationsForAssistant(targetId, "assistant_removed") }
        coVerify(exactly = 1) { memoryRepo.deleteMemoriesOfAssistant(targetId.toString()) }
        coVerify(exactly = 1) { conversationRepo.deleteConversationOfAssistant(targetId) }
    }

    @Test
    fun `failed cleanup remains durable and startup retry consumes tombstone`() = runTest {
        val targetId = Uuid.random()
        val settingsFlow = MutableStateFlow(
            Settings(
                assistants = listOf(Assistant(id = callerId), Assistant(id = targetId)),
                assistantId = callerId,
            )
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        coEvery { settingsStore.updateAtomic(any()) } answers {
            settingsFlow.value = firstArg<(Settings) -> Settings>()(settingsFlow.value)
        }
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        coEvery { conversationRepo.deleteConversationOfAssistant(targetId) } throws IllegalStateException("disk")
        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = mockk(relaxed = true),
            conversationRepo = conversationRepo,
            filesManager = mockk(relaxed = true),
            sessionRegistry = mockk(relaxed = true),
            subAssistantCoordinator = mockk(relaxed = true),
        )

        val deletion = service.deleteAssistant(targetId).getOrThrow()
        assertTrue(deletion.cleanupPending)
        assertEquals(listOf(targetId), settingsFlow.value.pendingAssistantDeletions.map { it.assistantId })

        coEvery { conversationRepo.deleteConversationOfAssistant(targetId) } returns Unit
        service.performPendingDeletionCleanup()

        assertTrue(settingsFlow.value.pendingAssistantDeletions.isEmpty())
    }

    @Test
    fun `startup retry drops stale tombstone when same assistant id was restored`() = runTest {
        val restoredId = Uuid.random()
        val settingsFlow = MutableStateFlow(
            Settings(
                assistants = listOf(Assistant(id = restoredId)),
                pendingAssistantDeletions = listOf(PendingAssistantDeletion(restoredId)),
            )
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        coEvery { settingsStore.updateAtomic(any()) } answers {
            settingsFlow.value = firstArg<(Settings) -> Settings>()(settingsFlow.value)
        }
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = memoryRepo,
            conversationRepo = conversationRepo,
            filesManager = mockk(relaxed = true),
            sessionRegistry = mockk(relaxed = true),
            subAssistantCoordinator = mockk(relaxed = true),
        )

        service.performPendingDeletionCleanup()

        assertTrue(settingsFlow.value.pendingAssistantDeletions.isEmpty())
        coVerify(exactly = 0) { memoryRepo.deleteMemoriesOfAssistant(any()) }
        coVerify(exactly = 0) { conversationRepo.deleteConversationOfAssistant(any()) }
    }

    @Test
    fun `tool update revalidates management permission inside atomic transform`() = runTest {
        val targetId = Uuid.random()
        val caller = Assistant(id = callerId, localTools = listOf(LocalToolOption.TimeInfo))
        val target = Assistant(
            id = targetId,
            allowAsSubAssistant = true,
            description = "Target",
            isSubAssistantGloballyVisible = true,
        )
        val service = createService(listOf(caller, target))

        val result = service.updateAssistant(
            assistantId = targetId,
            name = "Changed",
            callerAssistantId = callerId,
        )

        assertTrue(result.isFailure)
        assertEquals("tool_not_permitted", result.exceptionOrNull()?.message)
    }

    @Test
    fun `delete fails when deleting caller`() = runTest {
        val service = createService(listOf(Assistant(id = callerId, name = "Caller"), Assistant(name = "Other")))

        val result = service.deleteAssistant(callerId, callerId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `delete fails when deleting last assistant`() = runTest {
        val onlyAssistant = Assistant(id = callerId, name = "Only")
        val service = createService(listOf(onlyAssistant))

        val result = service.deleteAssistant(callerId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `delete fails when assistant not found`() = runTest {
        val service = createService(listOf(Assistant(id = callerId, name = "Caller"), Assistant(name = "Other")))

        val result = service.deleteAssistant(Uuid.random())
        assertTrue(result.isFailure)
    }

    @Test
    fun `list assistant memory returns local memories`() = runTest {
        val targetId = Uuid.random()
        val target = Assistant(
            id = targetId,
            name = "Target",
            enableMemory = true,
            useGlobalMemory = false,
        )

        val settingsFlow = MutableStateFlow(Settings(assistants = listOf(Assistant(id = callerId, name = "Caller"), target)))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value

        val memoryRepo = mockk<MemoryRepository>()
        coEvery { memoryRepo.getMemoriesOfAssistant(targetId.toString()) } returns listOf(
            AssistantMemory(1, "Memory 1"),
            AssistantMemory(2, "Memory 2"),
        )

        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = memoryRepo,
            conversationRepo = mockk(relaxed = true),
            filesManager = mockk(relaxed = true),
            sessionRegistry = mockk(relaxed = true),
            subAssistantCoordinator = mockk(relaxed = true),
        )

        val result = service.listAssistantMemory(targetId)
        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(targetId.toString(), data.assistantId)
        assertEquals("Target", data.assistantName)
        assertEquals("local", data.delegatedMemoryScope)
        assertEquals(2, data.memories.size)
        assertEquals(1, data.memories[0].id)
        assertEquals("Memory 1", data.memories[0].content)
    }

    @Test
    fun `list assistant memory returns disabled scope when memory is disabled`() = runTest {
        val targetId = Uuid.random()
        val target = Assistant(
            id = targetId,
            name = "Target",
            enableMemory = false,
        )

        val settingsFlow = MutableStateFlow(Settings(assistants = listOf(Assistant(id = callerId, name = "Caller"), target)))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value

        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = mockk(relaxed = true),
            conversationRepo = mockk(relaxed = true),
            filesManager = mockk(relaxed = true),
            sessionRegistry = mockk(relaxed = true),
            subAssistantCoordinator = mockk(relaxed = true),
        )

        val result = service.listAssistantMemory(targetId)
        assertTrue(result.isSuccess)
        assertEquals("disabled", result.getOrThrow().delegatedMemoryScope)
    }

    @Test
    fun `list assistant memory returns global scope when using global memory`() = runTest {
        val targetId = Uuid.random()
        val target = Assistant(
            id = targetId,
            name = "Target",
            enableMemory = true,
            useGlobalMemory = true,
        )

        val settingsFlow = MutableStateFlow(Settings(assistants = listOf(Assistant(id = callerId, name = "Caller"), target)))
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value

        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = mockk(relaxed = true),
            conversationRepo = mockk(relaxed = true),
            filesManager = mockk(relaxed = true),
            sessionRegistry = mockk(relaxed = true),
            subAssistantCoordinator = mockk(relaxed = true),
        )

        val result = service.listAssistantMemory(targetId)
        assertTrue(result.isSuccess)
        assertEquals("global", result.getOrThrow().delegatedMemoryScope)
    }

    @Test
    fun `concurrent updates do not lose data`() = runTest {
        val initialAssistants = listOf(
            Assistant(id = callerId, name = "Caller"),
            Assistant(id = Uuid.random(), name = "A2"),
            Assistant(id = Uuid.random(), name = "A3"),
        )
        val settingsFlow = MutableStateFlow(Settings(assistants = initialAssistants))

        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value

        // Simulate atomic updates by applying transforms sequentially
        coEvery { settingsStore.updateAtomic(any()) } answers {
            val fn = firstArg<(Settings) -> Settings>()
            settingsFlow.value = fn(settingsFlow.value)
        }

        val service = AssistantManagementService(
            settingsStore = settingsStore,
            memoryRepository = mockk(relaxed = true),
            conversationRepo = mockk(relaxed = true),
            filesManager = mockk(relaxed = true),
            sessionRegistry = mockk(relaxed = true),
            subAssistantCoordinator = mockk(relaxed = true),
        )

        // Create two assistants "concurrently"
        service.createAssistant("New1", "Desc1", "Instructions1")
        service.createAssistant("New2", "Desc2", "Instructions2")

        // Both should be present
        val finalAssistants = settingsFlow.value.assistants
        assertEquals(5, finalAssistants.size)
        assertTrue(finalAssistants.any { it.name == "New1" })
        assertTrue(finalAssistants.any { it.name == "New2" })
    }
}
