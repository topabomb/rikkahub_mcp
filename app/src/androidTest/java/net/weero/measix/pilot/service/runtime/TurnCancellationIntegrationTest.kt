package net.weero.measix.pilot.service.runtime

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.CheckpointKind
import net.weero.measix.pilot.data.ai.GenerationLoop
import net.weero.measix.pilot.data.ai.GenerationRequest
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactSettingsCoordinator
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.TurnFinalization
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TurnCancellationIntegrationTest {
    private lateinit var application: Context
    private lateinit var payloadRoot: File
    private lateinit var payloadContext: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var artifactStore: ArtifactStore
    private lateinit var repository: ConversationRepository
    private lateinit var registry: ConversationRuntimeRegistry
    private lateinit var coordinator: ConversationCommandCoordinator
    private lateinit var turnFinalization: TurnFinalization
    private lateinit var generationLoop: GenerationLoop
    private lateinit var httpClient: OkHttpClient
    private val workers = mutableListOf<Job>()

    private val model = Model(modelId = "test-model", displayName = "Test Model")
    private val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
    private val assistant = Assistant(chatModelId = model.id, enableMemory = false)
    private val settings = Settings(
        chatModelId = model.id,
        assistantId = assistant.id,
        providers = listOf(providerSetting),
        assistants = listOf(assistant),
    )

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        payloadRoot = Files.createTempDirectory(application.cacheDir.toPath(), "turn-cancel-").toFile()
        payloadContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = payloadRoot
        }
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS message_fts(" +
                "text, node_id, message_id, conversation_id, title, update_at)",
        )
        appScope = AppScope()
        val settingsStore = SettingsStore(application, appScope)
        artifactStore = ArtifactStore(
            payloadStore = ArtifactPayloadStore(payloadContext),
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(settingsStore),
            transactionRunner = RoomDatabaseTransactionRunner(database),
            fileNameCandidates = { listOf("aaa111", "bbb2222", "ccc33333", "Ddd44444") },
        )
        repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            messageFtsManager = MessageFtsManager(database),
            turnExecutionDAO = database.turnExecutionDao(),
            toolExecutionDAO = database.toolExecutionDao(),
            artifactStore = artifactStore,
        )
        val operationLocks = ConversationOperationLocks()
        registry = ConversationRuntimeRegistry(appScope, repository, operationLocks)
        coordinator = ConversationCommandCoordinator(
            registry = registry,
            repository = repository,
            recoveryGate = ApplicationRecoveryGate().apply { ready() },
            operationLocks = operationLocks,
        )
        turnFinalization = TurnFinalization(repository, registry, coordinator, Json)
        httpClient = OkHttpClient()
        generationLoop = GenerationLoop(
            context = payloadContext,
            providerManager = ProviderManager(httpClient, payloadContext),
            json = Json,
            memoryRepo = MemoryRepository(database.memoryDao()),
            attachmentResolver = AttachmentResolver(
                artifactStore = artifactStore,
            ),
        )
    }

    @After
    fun tearDown() = runBlocking {
        workers.forEach { it.cancel() }
        workers.joinAll()
        if (::appScope.isInitialized) appScope.cancel()
        if (::httpClient.isInitialized) {
            httpClient.dispatcher.executorService.shutdownNow()
            httpClient.connectionPool.evictAll()
        }
        if (::database.isInitialized) database.close()
        if (::payloadRoot.isInitialized) check(payloadRoot.deleteRecursively())
    }

    @Test
    fun cancellationAfterToolResultCheckpointKeepsPublishedArtifactRooted() = runBlocking {
        val checkpointCommitted = CompletableDeferred<Unit>()
        val releaseCheckpoint = CompletableDeferred<Unit>()
        lateinit var owned: OwnedArtifact
        val tool = artifactTool("persist_after_checkpoint") { context ->
            owned = createOwnedImage("after-checkpoint.png")
            context.registerUnpublishedResource(artifactStore.unpublishedLease(owned))
            listOf(UIMessagePart.Image(owned.uri.toString()))
        }

        val fixture = startResumedToolTurn(
            tool = tool,
            onCheckpoint = { engine, checkpoint ->
                engine.onCheckpoint(checkpoint)
                if (checkpoint.kind == CheckpointKind.TOOL_RESULT_COMPLETED) {
                    checkpointCommitted.complete(Unit)
                    releaseCheckpoint.await()
                }
            },
        )

        try {
            withTimeout(5_000) { checkpointCommitted.await() }
            fixture.worker.cancel(CancellationException("cancel after durable tool checkpoint"))
        } finally {
            releaseCheckpoint.complete(Unit)
            withTimeout(5_000) { fixture.worker.join() }
        }
        fixture.failure?.let { throw AssertionError("turn worker failed", it) }

        val artifact = owned.entity
        val durableConversation = requireNotNull(repository.getConversationById(fixture.conversationId))
        val terminalAssistant = durableConversation.currentMessages.last()
        val durableImage = terminalAssistant.getTools().single().output.single() as UIMessagePart.Image

        assertEquals(TurnExecutionStatus.CANCELLED, repository.getTurnExecution(fixture.turnId.toString())!!.status)
        assertEquals(MessageTerminalStatus.CANCELLED, terminalAssistant.terminalStatus)
        assertEquals(owned.uri.toString(), durableImage.url)
        assertTrue(artifactStore.file(owned.localRef).isFile)
        assertTrue(database.artifactReferenceDao().existsByArtifactId(artifact.id))
        assertEquals(ToolExecutionStatus.COMPLETED, repository.getToolExecutions(fixture.turnId.toString()).single().status)
        assertNull(fixture.runtime.snapshot.value.activeTurn)
    }

    @Test
    fun cancellationBeforeToolResultCheckpointRollsBackUnpublishedArtifact() = runBlocking {
        val artifactCreated = CompletableDeferred<OwnedArtifact>()
        val neverRelease = CompletableDeferred<Unit>()
        val tool = artifactTool("cancel_before_checkpoint") { context ->
            val owned = createOwnedImage("before-checkpoint.png")
            context.registerUnpublishedResource(artifactStore.unpublishedLease(owned))
            artifactCreated.complete(owned)
            neverRelease.await()
            listOf(UIMessagePart.Image(owned.uri.toString()))
        }

        val fixture = startResumedToolTurn(tool = tool)
        val owned = withTimeout(5_000) { artifactCreated.await() }
        val file = artifactStore.file(owned.localRef)

        fixture.worker.cancel(CancellationException("cancel before durable tool checkpoint"))
        withTimeout(5_000) { fixture.worker.join() }
        fixture.failure?.let { throw AssertionError("turn worker failed", it) }

        val durableConversation = requireNotNull(repository.getConversationById(fixture.conversationId))
        val terminalAssistant = durableConversation.currentMessages.last()
        val durableTool = terminalAssistant.getTools().single()

        assertEquals(TurnExecutionStatus.CANCELLED, repository.getTurnExecution(fixture.turnId.toString())!!.status)
        assertEquals(MessageTerminalStatus.CANCELLED, terminalAssistant.terminalStatus)
        assertFalse(file.exists())
        assertNull(database.artifactDao().getById(owned.entity.id))
        assertFalse(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))
        assertTrue(durableTool.output.none { part ->
            part is UIMessagePart.Image && part.url == owned.uri.toString()
        })
        assertEquals(ToolExecutionStatus.CANCELLED, repository.getToolExecutions(fixture.turnId.toString()).single().status)
        assertNull(fixture.runtime.snapshot.value.activeTurn)
    }

    private fun artifactTool(
        name: String,
        block: suspend (ToolExecutionContext) -> List<UIMessagePart>,
    ): Tool = Tool(
        name = name,
        description = "Creates one managed artifact for cancellation handoff regression coverage.",
        execute = { error("contextual execution required") },
        contextualExecute = { block(this) },
    )

    private suspend fun createOwnedImage(displayName: String): OwnedArtifact =
        artifactStore.createFromBytes(
            bytes = Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=",
                Base64.NO_WRAP,
            ),
            displayName = displayName,
            mimeType = "image/png",
            origin = ArtifactOrigin.SYSTEM,
        )

    private suspend fun startResumedToolTurn(
        tool: Tool,
        onCheckpoint: suspend (TurnEngine, net.weero.measix.pilot.data.ai.GenerationCheckpoint) -> Unit =
            { engine, checkpoint -> engine.onCheckpoint(checkpoint) },
    ): RunningTurnFixture {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val userMessage = UIMessage.user("Run ${tool.name}")
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-${tool.name}",
                    toolName = tool.name,
                    input = "{}",
                ),
            ),
        )
        val runtime = coordinator.create(
            Conversation(
                id = conversationId,
                assistantId = assistant.id,
                messageNodes = listOf(userMessage.toMessageNode(), assistantMessage.toMessageNode()),
            ),
        )
        val fixture = RunningTurnFixture(
            conversationId = conversationId,
            turnId = turnId,
            runtime = runtime,
        )
        val worker = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                assertSame(coroutineContext[Job], runtime.currentWorker())
                val currentMessages = runtime.snapshot.value.currentMessages()
                val started = TurnEngine.start(
                    commandCoordinator = coordinator,
                    runtime = runtime,
                    turnId = turnId,
                    messages = currentMessages,
                    turnFinalization = turnFinalization,
                )
                started.engine.bind(
                    generationLoop.run(
                        GenerationRequest(
                            settings = settings,
                            model = model,
                            mediaCapabilities = RequestMediaCapabilities.NONE,
                            messages = currentMessages,
                            assistant = assistant,
                            toolProvider = { listOf(tool) },
                            maxSteps = 1,
                            assistantMessageId = started.assistantMessageId,
                            onCheckpoint = { checkpoint -> onCheckpoint(started.engine, checkpoint) },
                            onMessagesObserved = started.engine::observeMessages,
                        ),
                    ),
                ).toList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fixture.failure = error
                throw error
            } finally {
                runtime.releaseActiveRequest(turnId, coroutineContext[Job])
            }
        }
        fixture.worker = worker
        workers += worker
        registry.installAndStartActiveRequest(conversationId, turnId, worker)
        return fixture
    }

    private class RunningTurnFixture(
        val conversationId: Uuid,
        val turnId: Uuid,
        val runtime: ConversationRuntime,
    ) {
        lateinit var worker: Job
        var failure: Throwable? = null
    }
}
