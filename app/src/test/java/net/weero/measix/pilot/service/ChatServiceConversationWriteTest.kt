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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.ai.GenerationChunk
import net.weero.measix.pilot.data.ai.GenerationHandler
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.GenerationToolSetFactory
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.utils.SoundEffectPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceConversationWriteTest {
    private val assistantId = Uuid.random()
    private val modelId = Uuid.random()
    private val artifactUrl = "file:///data/user/0/net.weero.measix.pilot/files/upload/x.jpg"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `session update without a file does not delete the previous artifact`() = runTest {
        val conversationId = Uuid.random()
        val withArtifact = conversationWithImage(conversationId)
        val withoutArtifact = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "stale",
            messageNodes = listOf(UIMessage.user("hi").toMessageNode()),
        )
        val filesManager = mockk<FilesManager>(relaxed = true)
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(repository = repository, filesManager = filesManager)
        env.sessionRegistry.getOrCreateSessionWithConversation(conversationId, withArtifact)

        env.service.saveConversation(conversationId, withoutArtifact)

        verify(exactly = 0) { filesManager.deleteChatFiles(any()) }
        coVerify(exactly = 1) { repository.updateConversation(withoutArtifact) }
    }

    @Test
    fun `delayed title write patches title without rolling back tool output`() = runTest {
        val conversationId = Uuid.random()
        val live = conversationWithImage(conversationId)
        val stale = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "",
            messageNodes = listOf(UIMessage.user("draw a cat").toMessageNode()),
        )
        val filesManager = mockk<FilesManager>(relaxed = true)
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(conversationId) } returns stale
        val env = createEnv(repository = repository, filesManager = filesManager)
        env.sessionRegistry.getOrCreateSessionWithConversation(conversationId, live)

        env.service.generateTitle(stale)

        val current = env.service.getConversationFlow(conversationId).value
        assertEquals("A generated title", current.title)
        assertTrue(current.hasGenerateImageArtifact())
        coVerify { repository.updateConversationTitle(conversationId, "A generated title") }
        coVerify(exactly = 0) { repository.updateConversation(any()) }
        verify(exactly = 0) { filesManager.deleteChatFiles(any()) }
    }

    @Test
    fun `delayed suggestion write patches suggestions without rolling back tool output`() = runTest {
        val conversationId = Uuid.random()
        val live = conversationWithImage(conversationId)
        val stale = live.copy(title = "kept", chatSuggestions = emptyList())
        val filesManager = mockk<FilesManager>(relaxed = true)
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(conversationId) } returns stale
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(
            repository = repository,
            filesManager = filesManager,
            enableSuggestion = true,
            suggestionText = "Ask about the image\nTry another style",
        )
        env.sessionRegistry.getOrCreateSessionWithConversation(conversationId, live)

        env.service.generateSuggestion(conversationId, stale)

        val current = env.service.getConversationFlow(conversationId).value
        assertEquals(listOf("Ask about the image", "Try another style"), current.chatSuggestions)
        assertTrue(current.hasGenerateImageArtifact())
        coVerify { repository.updateConversationSuggestions(conversationId, listOf("Ask about the image", "Try another style")) }
        coVerify(exactly = 0) { repository.updateConversation(any()) }
        verify(exactly = 0) { filesManager.deleteChatFiles(any()) }
    }

    @Test
    fun `awaiting approval does not generate title or suggestions`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(
            repository = repository,
            finishReason = FinishedReason.AWAITING_APPROVAL,
            enableSuggestion = true,
        )
        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )

        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("draw a cat")))
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateConversationTitle(any(), any()) }
        coVerify(exactly = 0) { repository.updateConversationSuggestions(any(), any()) }
    }

    @Test
    fun `completed generation writes title`() = runTest {
        val conversationId = Uuid.random()
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.existsConversationById(conversationId) } returns true
        val env = createEnv(
            repository = repository,
            finishReason = FinishedReason.COMPLETED,
        )
        env.sessionRegistry.getOrCreateSessionWithConversation(
            conversationId,
            Conversation(id = conversationId, assistantId = assistantId, messageNodes = emptyList()),
        )

        env.service.sendMessage(conversationId, listOf(UIMessagePart.Text("hello")))
        advanceUntilIdle()

        coVerify { repository.updateConversationTitle(conversationId, "A generated title") }
    }

    @Test
    fun `initializeConversation does not replace a generating session tree`() = runTest {
        val conversationId = Uuid.random()
        val live = conversationWithImage(conversationId)
        val stale = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "from-db",
            messageNodes = listOf(UIMessage.user("old").toMessageNode()),
        )
        val filesManager = mockk<FilesManager>(relaxed = true)
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(conversationId) } returns stale
        val env = createEnv(repository = repository, filesManager = filesManager)
        val session = env.sessionRegistry.getOrCreateSessionWithConversation(conversationId, live)
        session.setJob(Job())

        env.service.initializeConversation(conversationId)

        val current = env.service.getConversationFlow(conversationId).value
        assertTrue(current.hasGenerateImageArtifact())
        verify(exactly = 0) { filesManager.deleteChatFiles(any()) }
        session.setJob(null)
    }

    @Test
    fun `initializeConversation keeps a newer idle live tree`() = runTest {
        val conversationId = Uuid.random()
        val live = conversationWithImage(conversationId).copy(updateAt = java.time.Instant.parse("2026-08-20T12:00:00Z"))
        val stale = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "from-db",
            updateAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
            messageNodes = listOf(UIMessage.user("old").toMessageNode()),
        )
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(conversationId) } returns stale
        val env = createEnv(repository = repository)
        env.sessionRegistry.getOrCreateSessionWithConversation(conversationId, live)

        env.service.initializeConversation(conversationId)

        assertTrue(env.service.getConversationFlow(conversationId).value.hasGenerateImageArtifact())
    }

    @Test
    fun `initializeConversation loads a newer persisted tree when idle`() = runTest {
        val conversationId = Uuid.random()
        val live = conversationWithImage(conversationId).copy(updateAt = java.time.Instant.parse("2026-08-20T11:00:00Z"))
        val persisted = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "from-db",
            updateAt = java.time.Instant.parse("2026-08-20T12:00:00Z"),
            messageNodes = listOf(UIMessage.user("newer persisted").toMessageNode()),
        )
        val repository = mockk<ConversationRepository>(relaxed = true)
        coEvery { repository.getConversationById(conversationId) } returns persisted
        val env = createEnv(repository = repository)
        env.sessionRegistry.getOrCreateSessionWithConversation(conversationId, live)

        env.service.initializeConversation(conversationId)

        val current = env.service.getConversationFlow(conversationId).value
        assertEquals("from-db", current.title)
        assertEquals("newer persisted", current.currentMessages.single().toText())
    }

    private fun conversationWithImage(conversationId: Uuid): Conversation {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "generate_image",
            input = """{"prompt":"cat","set_as_background":true}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"completed","media_id":76,"file":{"path":"/upload/x.jpg","mime_type":"image/jpeg"},"background":{"requested":true,"updated":true}}""",
                ),
                UIMessagePart.Image(url = artifactUrl),
            ),
            metadata = buildJsonObject {
                put(
                    "artifact",
                    buildJsonObject {
                        put("version", 1)
                        put("relativePath", "upload/x.jpg")
                        put("mimeType", "image/jpeg")
                    },
                )
            },
        )
        return Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "",
            messageNodes = listOf(
                UIMessage.user("generate an image and set it as the background").toMessageNode(),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)).toMessageNode(),
            ),
        )
    }

    private fun createEnv(
        repository: ConversationRepository,
        filesManager: FilesManager = mockk(relaxed = true),
        finishReason: FinishedReason? = null,
        enableSuggestion: Boolean = false,
        suggestionText: String = "A generated title",
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
            enableSuggestion = enableSuggestion,
        )
        val settingsStore = mockk<SettingsStore>(relaxed = true)
        every { settingsStore.settingsFlow } returns MutableStateFlow(settings)
        every { settingsStore.settingsFlowRaw } returns flowOf(settings)
        val appScope = AppScope()
        val sessionRegistry = ConversationSessionRegistry(appScope, settingsStore)
        val generationHandler = mockk<GenerationHandler>(relaxed = true)
        if (finishReason != null) {
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
                    imageAdaptMode = any(),
                    currentTaskMessageId = any(),
                )
            } returns flowOf(GenerationChunk.Finished(finishReason))
        }
        val provider = mockk<Provider<ProviderSetting>>(relaxed = true)
        val titleChunk = MessageChunk(
            id = "title",
            model = "test-chat",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage.assistant(suggestionText),
                    finishReason = "stop",
                ),
            ),
        )
        coEvery { provider.generateText(any(), any(), any()) } returns titleChunk
        val providerManager = mockk<ProviderManager>(relaxed = true)
        every { providerManager.getProviderByType(any()) } returns provider
        val lifecycleOwner = mockk<LifecycleOwner>()
        every { lifecycleOwner.lifecycle } returns mockk<Lifecycle>(relaxed = true)
        mockkObject(ProcessLifecycleOwner)
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner
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
                filesManager = filesManager,
                toolSetFactory = mockk<GenerationToolSetFactory>(relaxed = true),
                workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
                folderRepository = mockk<FolderRepository>(relaxed = true),
                soundEffectPlayer = mockk<SoundEffectPlayer>(relaxed = true),
                assistantToolFactory = mockk<AssistantToolFactory>(relaxed = true),
                subAssistantCoordinator = mockk<SubAssistantCoordinator>(relaxed = true),
                sessionRegistry = sessionRegistry,
                json = JsonInstant,
            )
        } finally {
            unmockkObject(ProcessLifecycleOwner)
        }
        return TestEnv(service, sessionRegistry)
    }

    private data class TestEnv(
        val service: ChatService,
        val sessionRegistry: ConversationSessionRegistry,
    )

    private fun Conversation.hasGenerateImageArtifact(): Boolean =
        currentMessages.any { message ->
            message.getTools().any { tool ->
                tool.toolName == "generate_image" &&
                    tool.output.any { part -> part is UIMessagePart.Image && part.url == artifactUrl }
            }
        }
}
