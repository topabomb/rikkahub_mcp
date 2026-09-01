package net.weero.measix.pilot.service.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.GenerationLoop
import net.weero.measix.pilot.data.ai.GenerationRequest
import net.weero.measix.pilot.data.ai.ToolExecutionEventStatus
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactSettingsCoordinator
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.MasterTurnEntry
import net.weero.measix.pilot.service.TurnFinalization
import net.weero.measix.pilot.service.applyToolUserDecision
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
class ApprovalContinuationMissingToolIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var artifactStore: ArtifactStore
    private lateinit var repository: ConversationRepository
    private lateinit var registry: ConversationRuntimeRegistry
    private lateinit var coordinator: ConversationCommandCoordinator
    private lateinit var turnFinalization: TurnFinalization
    private lateinit var httpClient: OkHttpClient
    private val workers = mutableListOf<Job>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS message_fts(" +
                "text, node_id, message_id, conversation_id, title, update_at)",
        )
        appScope = AppScope()
        val settingsStore = SettingsStore(context, appScope)
        artifactStore = ArtifactStore(
            payloadStore = ArtifactPayloadStore(context),
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(settingsStore),
            transactionRunner = RoomDatabaseTransactionRunner(database),
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
    }

    @Test
    fun approvedToolRemovedBeforeContinuationPersistsFailedResultWithoutExecutionOnOriginalTurn() = runBlocking {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, enableMemory = false)
        val settings = Settings(
            chatModelId = model.id,
            assistantId = assistant.id,
            providers = listOf(providerSetting),
            assistants = listOf(assistant),
        )
        val generationLoop = GenerationLoop(
            context = context,
            providerManager = ProviderManager(httpClient, context),
            json = Json,
            memoryRepo = MemoryRepository(database.memoryDao()),
            attachmentResolver = AttachmentResolver(
                artifactStore = artifactStore,
            ),
            toolOutputStore = net.weero.measix.pilot.data.ai.tools.ToolOutputStore(artifactStore),
        )

        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val userMessage = UIMessage.user("Run the approved tool")
        val runtime = coordinator.create(
            Conversation(
                id = conversationId,
                assistantId = assistant.id,
                messageNodes = listOf(userMessage.toMessageNode()),
            ),
        )
        var toolExecuted = false
        var toolAvailable = true
        val revocableTool = Tool(
            name = "revocable_tool",
            description = "Requires approval before execution.",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = {
                toolExecuted = true
                listOf(UIMessagePart.Text("executed"))
            },
        )
        val toolProvider: suspend () -> List<Tool> = {
            if (toolAvailable) listOf(revocableTool) else emptyList()
        }

        var startedTurn: TurnEngine.StartedTurn? = null
        var initialFailure: Throwable? = null
        val initialWorker = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                assertSame(coroutineContext[Job], runtime.currentWorker())
                val started = TurnEngine.start(
                    commandCoordinator = coordinator,
                    runtime = runtime,
                    turnId = turnId,
                    messages = listOf(userMessage),
                    turnFinalization = turnFinalization,
                )
                startedTurn = started
                val waitingMessage = UIMessage(
                    id = started.assistantMessageId,
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-revocable",
                            toolName = revocableTool.name,
                            input = "{}",
                            approvalState = ToolApprovalState.Auto,
                        ),
                    ),
                )
                val waitingEvents = started.engine.bind(
                    generationLoop.run(
                        GenerationRequest(
                            conversationId = kotlin.uuid.Uuid.random(),
                            settings = settings,
                            model = model,
                            mediaCapabilities = RequestMediaCapabilities.NONE,
                            messages = listOf(userMessage, waitingMessage),
                            assistant = assistant,
                            toolProvider = toolProvider,
                            maxSteps = 1,
                            assistantMessageId = waitingMessage.id,
                            onCheckpoint = started.engine::onCheckpoint,
                            onMessagesObserved = started.engine::observeMessages,
                        ),
                    ),
                ).toList()
                assertTrue(
                    (waitingEvents.last() as TurnEvent.Finished).outcome is TurnOutcome.AwaitingApproval,
                )
                val waitingOwner = requireNotNull(runtime.snapshot.value.activeTurn)
                runtime.retainAwaitingApproval(
                    TurnHandle(
                        conversationId = conversationId,
                        epoch = waitingOwner.epoch,
                        turnId = waitingOwner.turnId,
                        assistantMessageId = waitingOwner.assistantMessageId,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                initialFailure = error
                throw error
            } finally {
                if (!runtime.isAwaitingApproval(turnId)) {
                    runtime.releaseActiveRequest(turnId, coroutineContext[Job])
                }
            }
        }.also(workers::add)
        registry.installAndStartActiveRequest(conversationId, turnId, initialWorker)
        initialWorker.join()
        initialFailure?.let { throw AssertionError("initial turn worker failed", it) }
        val started = requireNotNull(startedTurn)

        val pendingMessage = runtime.snapshot.value.currentMessages().last()
        assertTrue(pendingMessage.getTools().single().approvalState is ToolApprovalState.Pending)
        val waitingOwner = requireNotNull(runtime.snapshot.value.activeTurn)
        val originalHandle = TurnHandle(
            conversationId = conversationId,
            epoch = waitingOwner.epoch,
            turnId = waitingOwner.turnId,
            assistantMessageId = waitingOwner.assistantMessageId,
        )

        var installedContinuation: TurnHandle? = null
        var continuationWorker: Job? = null
        var continuationFailure: Throwable? = null
        val executionStatuses = mutableListOf<ToolExecutionEventStatus>()
        applyToolUserDecision(
            locator = ToolCallLocator(pendingMessage.id, 0),
            decision = ToolUserDecision.Approve,
            awaitPreviousGeneration = { initialWorker.join() },
            currentSnapshot = { runtime.snapshot.value },
            submit = { command -> coordinator.executeOrThrow(conversationId, command) },
            onMoreApprovalsPending = { error("the only tool approval should resume immediately") },
            continueTurn = { owner, entry ->
                assertEquals(MasterTurnEntry.CONTINUE_USER_INTERACTION, entry)
                val handle = TurnHandle(
                    conversationId = conversationId,
                    epoch = owner.epoch,
                    turnId = owner.turnId,
                    assistantMessageId = owner.assistantMessageId,
                )
                toolAvailable = false
                val worker = appScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        assertSame(coroutineContext[Job], runtime.currentWorker())
                        val approvedMessages = runtime.snapshot.value.currentMessages()
                        assertEquals(
                            ToolApprovalState.Approved,
                            approvedMessages.last().getTools().single().approvalState,
                        )
                        val continuation = TurnEngine.continueActive(
                            commandCoordinator = coordinator,
                            runtime = runtime,
                            expectedTurnId = turnId,
                            messages = approvedMessages,
                            turnFinalization = turnFinalization,
                        )
                        assertEquals(started.assistantMessageId, continuation.assistantMessageId)
                        continuation.engine.bind(
                            generationLoop.run(
                                GenerationRequest(
                                    conversationId = kotlin.uuid.Uuid.random(),
                                    settings = settings,
                                    model = model,
                                    mediaCapabilities = RequestMediaCapabilities.NONE,
                                    messages = approvedMessages,
                                    assistant = assistant,
                                    toolProvider = toolProvider,
                                    maxSteps = 1,
                                    assistantMessageId = continuation.assistantMessageId,
                                    onCheckpoint = { checkpoint ->
                                        continuation.engine.onCheckpoint(checkpoint)
                                        checkpoint.toolExecution?.let { event ->
                                            executionStatuses += event.status
                                        }
                                    },
                                    onMessagesObserved = continuation.engine::observeMessages,
                                ),
                            ),
                        ).toList()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        continuationFailure = error
                        throw error
                    } finally {
                        if (!runtime.isAwaitingApproval(turnId)) {
                            runtime.releaseActiveRequest(turnId, coroutineContext[Job])
                        }
                    }
                }.also(workers::add)
                continuationWorker = worker
                registry.installAndStartApprovalContinuation(conversationId, handle, worker)
                installedContinuation = handle
            },
        )
        assertEquals(originalHandle, installedContinuation)
        requireNotNull(continuationWorker).join()
        continuationFailure?.let { throw AssertionError("approval continuation worker failed", it) }
        assertNull(runtime.currentWorker())

        assertFalse(toolExecuted)
        assertTrue(executionStatuses.isEmpty())
        val turns = repository.getTurnExecutions(conversationId)
        assertEquals(1, turns.size)
        assertEquals(turnId.toString(), turns.single().turnId)
        assertEquals(started.assistantMessageId.toString(), turns.single().assistantMessageId)
        assertEquals(TurnExecutionStatus.INCOMPLETE, turns.single().status)

        val executions = repository.getToolExecutions(turnId.toString())
        assertTrue(executions.isEmpty())

        val reloaded = requireNotNull(repository.getConversationById(conversationId))
        val durableTool = reloaded.currentMessages.last().getTools().single()
        assertTrue(
            durableTool.output.filterIsInstance<UIMessagePart.Text>()
                .single().text.contains("tool_not_available"),
        )
        assertEquals(ToolCallPhase.FAILED, resolveToolCallPhase(durableTool, null))
        assertEquals(runtime.snapshot.value.currentMessages().last().getTools().single(), durableTool)
        assertNull(runtime.snapshot.value.activeTurn)
    }
}
