package net.weero.measix.pilot.service

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.SoundEffectPlayer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceRecoveryGateTest {
    @Test
    fun `history deletion cannot reach repository before startup recovery completes`() = runTest {
        val gate = AssistantDataRecoveryGate()
        val repository = mockk<ConversationRepository>(relaxed = true)
        val sessionRegistry = mockk<ConversationSessionRegistry>(relaxed = true)
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = emptyList(),
        )
        coEvery { repository.getChildConversations(conversation.id) } returns emptyList()
        every { sessionRegistry.getSession(conversation.id) } returns null
        val lifecycleOwner = mockk<LifecycleOwner>()
        every { lifecycleOwner.lifecycle } returns mockk<Lifecycle>(relaxed = true)
        mockkObject(ProcessLifecycleOwner)
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner
        val service = try {
            createService(gate, repository, sessionRegistry)
        } finally {
            unmockkObject(ProcessLifecycleOwner)
        }

        val deletion = launch { service.deleteConversation(conversation) }
        runCurrent()
        coVerify(exactly = 0) { repository.deleteConversation(any()) }

        gate.complete()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteConversation(conversation) }
        assertTrue(deletion.isCompleted)
    }

    private fun createService(
        gate: AssistantDataRecoveryGate,
        repository: ConversationRepository,
        sessionRegistry: ConversationSessionRegistry,
    ) = ChatService(
        context = mockk<Application>(relaxed = true),
        appScope = mockk<AppScope>(relaxed = true),
        appEventBus = mockk<AppEventBus>(relaxed = true),
        settingsStore = mockk<SettingsStore>(relaxed = true),
        conversationRepo = repository,
        memoryRepository = mockk<MemoryRepository>(relaxed = true),
        generationHandler = mockk<GenerationHandler>(relaxed = true),
        templateTransformer = mockk<TemplateTransformer>(relaxed = true),
        providerManager = mockk(relaxed = true),
        mcpManager = mockk<McpManager>(relaxed = true),
        filesManager = mockk<FilesManager>(relaxed = true),
        toolSetFactory = mockk<GenerationToolSetFactory>(relaxed = true),
        workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
        folderRepository = mockk<FolderRepository>(relaxed = true),
        soundEffectPlayer = mockk<SoundEffectPlayer>(relaxed = true),
        assistantToolFactory = mockk<AssistantToolFactory>(relaxed = true),
        subAssistantCoordinator = mockk<SubAssistantCoordinator>(relaxed = true),
        sessionRegistry = sessionRegistry,
        recoveryGate = gate,
        json = JsonInstant,
    )
}
