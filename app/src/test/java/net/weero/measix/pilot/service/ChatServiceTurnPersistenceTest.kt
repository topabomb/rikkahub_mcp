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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.ai.GenerationCheckpoint
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.DelegationCoordinator
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.SoundEffectPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTurnPersistenceTest {
    private val assistantId = Uuid.random()
    private val modelId = Uuid.random()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stopping mid-stream persists partial assistant text and cancelled status`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository, GenerateMode.HangAfterPartial)

        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )
        env.service.addConversationReference(conversationId)
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("hello")))
        advanceUntilIdle()

        env.service.stopGeneration(conversationId)
        advanceUntilIdle()

        val live = env.service.getConversationFlow(conversationId).value
        assertTrue(live.currentMessages.any { it.toText().contains("partial reply") })
        val assistant = live.currentMessages.last { it.role == MessageRole.ASSISTANT }
        assertEquals(MessageTerminalStatus.CANCELLED, assistant.terminalStatus)
        // 终态持久化走 FinalizeTurn 命令 → applyMutation（delta + turn 事实同事务）
        coVerify {
            repository.applyMutation(
                match { mutation ->
                    mutation.upsertedNodes.any { node ->
                        node.messages.any { message ->
                            message.role == MessageRole.ASSISTANT &&
                                message.toText().contains("partial reply") &&
                                message.terminalStatus == MessageTerminalStatus.CANCELLED
                        }
                    }
                },
                match { facts -> facts?.turn?.status == TurnExecutionStatus.CANCELLED },
            )
        }
    }

    @Test
    fun `provider failure persists partial assistant text and failed status`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository, GenerateMode.FailAfterPartial)

        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )
        env.service.addConversationReference(conversationId)
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("hello")))
        advanceUntilIdle()

        val live = env.service.getConversationFlow(conversationId).value
        val assistant = live.currentMessages.last { it.role == MessageRole.ASSISTANT }
        assertTrue(assistant.toText().contains("partial reply"))
        assertEquals(MessageTerminalStatus.FAILED, assistant.terminalStatus)
        // 终态持久化走 FinalizeTurn 命令 → applyMutation
        coVerify {
            repository.applyMutation(
                match { mutation ->
                    mutation.upsertedNodes.any { node ->
                        node.messages.any { message ->
                            message.role == MessageRole.ASSISTANT &&
                                message.toText().contains("partial reply") &&
                                message.terminalStatus == MessageTerminalStatus.FAILED
                        }
                    }
                },
                match { facts -> facts?.turn?.status == TurnExecutionStatus.FAILED },
            )
        }
    }

    @Test
    fun `superseding a turn persists cancelled with superseded reason before the new user`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository, GenerateMode.HangAfterPartial)

        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )
        env.service.addConversationReference(conversationId)
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("first")))
        advanceUntilIdle()
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("second")))
        advanceUntilIdle()

        coVerify {
            repository.applyMutation(
                match { mutation ->
                    mutation.upsertedNodes.any { node ->
                        node.messages.any { message ->
                            message.role == MessageRole.ASSISTANT &&
                                message.toText().contains("partial reply") &&
                                message.terminalStatus == MessageTerminalStatus.CANCELLED &&
                                message.terminalReason ==
                                me.rerere.ai.ui.TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN
                        }
                    }
                },
                match { facts ->
                    facts?.turn?.status == TurnExecutionStatus.CANCELLED &&
                        facts.turn.reason == me.rerere.ai.ui.TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN
                },
            )
        }
        coVerify(exactly = 0) {
            repository.applyMutation(
                any(),
                match { facts -> facts?.turn?.status == TurnExecutionStatus.INTERRUPTED },
            )
        }
        env.service.stopGeneration(conversationId)
        advanceUntilIdle()
    }

    @Test
    fun `step checkpoint persists assistant snapshot before completion`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository, GenerateMode.CheckpointThenComplete)

        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )
        env.service.addConversationReference(conversationId)
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("hello")))
        advanceUntilIdle()

        // checkpoint 持久化走 applyMutation，turn 状态 RUNNING 同事务落库
        coVerify {
            repository.applyMutation(
                match { mutation ->
                    mutation.upsertedNodes.any { node -> node.messages.any { it.toText().contains("partial reply") } }
                },
                match { facts -> facts?.turn?.status == TurnExecutionStatus.RUNNING },
            )
        }
        coVerify {
            repository.applyMutation(
                any(),
                match { facts -> facts?.turn?.status == TurnExecutionStatus.COMPLETED },
            )
        }
    }

    @Test
    fun `regenerate failure before first provider chunk preserves old assistant branch`() = runTest {
        val conversationId = Uuid.random()
        val originalAssistant = UIMessage.assistant("completed answer")
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository, GenerateMode.FailBeforePartial)
        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(
                id = conversationId,
                assistantId = assistantId,
                messageNodes = listOf(
                    UIMessage.user("question").toMessageNode(),
                    originalAssistant.toMessageNode(),
                ),
            ),
        )
        env.service.addConversationReference(conversationId)

        env.service.regenerateAtMessage(conversationId, originalAssistant)
        advanceUntilIdle()

        val assistantNode = env.service.getConversationFlow(conversationId).value.messageNodes[1]
        val preserved = assistantNode.messages.single { it.id == originalAssistant.id }
        assertEquals("completed answer", preserved.toText())
        assertNull(preserved.terminalStatus)
        assertEquals(2, assistantNode.messages.size)
        assertNotEquals(originalAssistant.id, assistantNode.currentMessage.id)
        assertEquals(MessageTerminalStatus.FAILED, assistantNode.currentMessage.terminalStatus)
        env.service.removeConversationReference(conversationId)
    }

    @Test
    fun `cancel after successful finalization does not overwrite completed terminal status`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository, GenerateMode.Complete)

        // 慢订阅者：SUCCESS finalize 提交后，job 挂在 generationDoneFlow 的 emit 上，
        // 此时用户请求停止会以 CANCELLED 再次进入 finalizer——已提交终态不允许被覆盖
        val releaseSubscriber = CompletableDeferred<Unit>()
        backgroundScope.launch {
            env.service.generationDoneFlow.collect {
                releaseSubscriber.await()
            }
        }
        advanceUntilIdle()

        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )
        env.service.addConversationReference(conversationId)
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("hello")))
        advanceUntilIdle()

        env.service.stopGeneration(conversationId)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.applyMutation(
                any(),
                match { facts -> facts?.turn?.status == TurnExecutionStatus.COMPLETED },
            )
        }
        coVerify(exactly = 0) {
            repository.applyMutation(
                any(),
                match { facts -> facts?.turn?.status == TurnExecutionStatus.CANCELLED },
            )
        }
        releaseSubscriber.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `sendMessage after completed turn does not persist INTERRUPTED fact`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository, GenerateMode.Complete)
        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )
        env.service.addConversationReference(conversationId)
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("first")))
        advanceUntilIdle()
        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("second")))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            repository.applyMutation(
                any(),
                match { facts -> facts?.turn?.status == TurnExecutionStatus.INTERRUPTED },
            )
        }
        env.service.removeConversationReference(conversationId)
    }

    private enum class GenerateMode {
        HangAfterPartial,
        FailAfterPartial,
        FailBeforePartial,
        CheckpointThenComplete,
        Complete,
    }

    private fun createEnv(
        repository: ConversationRepository,
        mode: GenerateMode,
    ): TestEnv {
        val model = Model(
            id = modelId,
            modelId = "test-chat",
            displayName = "Test Chat",
            type = ModelType.CHAT,
            abilities = listOf(ModelAbility.TOOL),
        )
        val providerSetting = ProviderSetting.OpenAI(enabled = true, models = listOf(model))
        val settings = Settings(
            assistantId = assistantId,
            assistants = listOf(Assistant(id = assistantId, chatModelId = modelId)),
            providers = listOf(providerSetting),
            chatModelId = modelId,
            fastModelId = modelId,
            titleModelId = modelId,
            suggestionModelId = modelId,
        )
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.settingsFlow } returns MutableStateFlow(settings)
        every { settingsStore.settingsFlowRaw } returns flowOf(settings)
        val appScope = AppScope()
        val sessionRegistry = ConversationRuntimeRegistry(appScope, settingsStore, repository)
        val generationHandler = mockk<GenerationHandler>(relaxed = true)
        every {
            generationHandler.generateText(
                settings = any(),
                model = any(),
                messages = any(),
                inputTransformers = any(),
                outputTransformers = any(),
                assistant = any(),
                memories = any(),
                tools = any(),
                maxSteps = any(),
                processingStatus = any(),
                conversationSystemPrompt = any(),
                conversationModeInjectionIds = any(),
                workspaceCwd = any(),
                toolProvider = any(),
                nonInteractive = any(),
                interactiveToolNames = any(),
                memoryToolAllowed = any(),
                assistantMessageId = any(),
                onCheckpoint = any(),
            )
        } answers {
            val input = args[2] as List<UIMessage>
            @Suppress("UNCHECKED_CAST")
            val onCheckpoint = args[18] as suspend (GenerationCheckpoint) -> Unit
            streamingFlow(input, mode, onCheckpoint)
        }
        val provider = mockk<Provider<ProviderSetting>>(relaxed = true)
        val providerManager = mockk<ProviderManager>(relaxed = true)
        every { providerManager.getProviderByType(any()) } returns provider
        val lifecycleOwner = mockk<LifecycleOwner>()
        every { lifecycleOwner.lifecycle } returns mockk<Lifecycle>(relaxed = true)
        mockkObject(ProcessLifecycleOwner)
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner
        val delegationCoordinator = mockk<DelegationCoordinator>(relaxed = true)
        coEvery { delegationCoordinator.recoverMasterForMutation(any()) } answers { firstArg() }
        val service = try {
            ChatService(
                context = mockk<Application>(relaxed = true),
                appScope = appScope,
                appEventBus = mockk<AppEventBus>(relaxed = true),
                settingsStore = settingsStore,
                conversationRepo = repository,
                memoryRepository = mockk<MemoryRepository>(relaxed = true),
                generationHandler = generationHandler,
                templateTransformer = mockk<TemplateTransformer>(relaxed = true),
                providerManager = providerManager,
                mcpManager = mockk<McpManager>(relaxed = true),
                filesManager = mockk<FilesManager>(relaxed = true),
                toolSetFactory = mockk<GenerationToolSetFactory>(relaxed = true),
                workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
                folderRepository = mockk<FolderRepository>(relaxed = true),
                soundEffectPlayer = mockk<SoundEffectPlayer>(relaxed = true),
                assistantToolFactory = mockk<AssistantToolFactory>(relaxed = true),
                delegationCoordinator = delegationCoordinator,
                sessionRegistry = sessionRegistry,
                json = JsonInstant,
            )
        } finally {
            unmockkObject(ProcessLifecycleOwner)
        }
        return TestEnv(service, sessionRegistry)
    }

    private fun streamingFlow(
        input: List<UIMessage>,
        mode: GenerateMode,
        onCheckpoint: suspend (GenerationCheckpoint) -> Unit,
    ) = flow {
        if (mode == GenerateMode.FailBeforePartial) {
            error("provider failed before first chunk")
        }
        val assistant = if (input.lastOrNull()?.role == MessageRole.ASSISTANT) {
            input.last().copy(parts = listOf(UIMessagePart.Text("partial reply")))
        } else {
            UIMessage.assistant("partial reply")
        }
        val messages = if (input.lastOrNull()?.role == MessageRole.ASSISTANT) {
            input.dropLast(1) + assistant
        } else {
            input + assistant
        }
        emit(GenerationChunk.Messages(messages))
        when (mode) {
            GenerateMode.HangAfterPartial -> awaitCancellation()
            GenerateMode.FailAfterPartial -> error("provider failed")
            GenerateMode.FailBeforePartial -> error("unreachable")
            GenerateMode.CheckpointThenComplete -> {
                onCheckpoint(GenerationCheckpoint(CheckpointKind.STEP_COMPLETED, messages))
                emit(GenerationChunk.Checkpoint(CheckpointKind.STEP_COMPLETED))
                emit(GenerationChunk.Finished(FinishedReason.COMPLETED))
            }
            GenerateMode.Complete -> {
                emit(GenerationChunk.Finished(FinishedReason.COMPLETED))
            }
        }
    }

    private data class TestEnv(
        val service: ChatService,
        val sessionRegistry: ConversationRuntimeRegistry,
    )
}
