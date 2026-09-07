package net.weero.measix.pilot.service.runtime
import net.weero.measix.pilot.service.turn.TurnCommitter
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnPause
import net.weero.measix.pilot.service.turn.androidTestTurnContext
import net.weero.measix.pilot.service.turn.disclosureCandidate

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
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.service.turn.TurnRunInputs
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
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
import net.weero.measix.pilot.service.TurnEntry
import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.applyToolInteractionDecision
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
class TurnInteractionContinuationIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var artifactStore: ArtifactStore
    private lateinit var repository: ConversationRepository
    private lateinit var registry: ConversationRuntimeRegistry
    private lateinit var coordinator: ConversationCommandCoordinator
    private lateinit var turnFinalizer: TurnFinalizer
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
            modelContextDAO = database.conversationModelContextDao(),
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
        turnFinalizer = TurnFinalizer(repository, registry, coordinator, Json)
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
    fun approvalContinuationReusesFrozenToolBindingOnOriginalTurn() = runBlocking {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val assistant = Assistant(chatModelId = model.id, enableMemory = false)
        val settings = Settings(
            chatModelId = model.id,
            assistantId = assistant.id,
            providers = listOf(providerSetting),
            assistants = listOf(assistant),
        )
        val turnRunner = TurnRunner(
            context = context,
            providerManager = ProviderManager(httpClient, context),
            json = Json,
            attachmentResolver = AttachmentResolver(
                artifactStore = artifactStore,
            ),
            toolOutputStore = net.weero.measix.pilot.data.ai.tools.ToolOutputStore(artifactStore),
        )

        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val toolStepId = Uuid.random()
        val toolLocalCallId = Uuid.random()
        val userMessage = UIMessage.user("Run the approved tool")
        val runtime = coordinator.create(
            Conversation(
                id = conversationId,
                assistantId = assistant.id,
                messageNodes = listOf(userMessage.toMessageNode()),
            ),
        )
        var toolExecuted = false
        val revocableTool = Tool(
            name = "revocable_tool",
            description = "Requires approval before execution.",
            interactionRequirement = { ToolInteractionRequirement.Approval },
            execute = {
                toolExecuted = true
                listOf(UIMessagePart.Text("executed"))
            },
        )
        val turnContext = androidTestTurnContext(
            settings = settings,
            model = model,
            assistant = assistant,
            tools = listOf(revocableTool),
        )

        var startedTurn: TurnCommitter.StartedTurn? = null
        var initialFailure: Throwable? = null
        val initialWorker = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                val worker = requireNotNull(coroutineContext[Job])
                assertSame(worker, runtime.currentWorker())
                runtime.bindTurnContext(turnId, worker, turnContext)
                val started = TurnCommitter.start(
                    commandCoordinator = coordinator,
                    runtime = runtime,
                    turnId = turnId,
                    modelContextCandidate = disclosureCandidate(),
                    turnFinalizer = turnFinalizer,
                )
                // 与 ConversationTurnService.launchRun 同一协议：START 提交后立即绑定冻结
                // projection，审批续接才能复用同一引用。
                runtime.bindModelContextProjection(
                    turnId,
                    worker,
                    TurnTransition.projectTurnModelContext(runtime.durable),
                )
                startedTurn = started
                val waitingMessage = UIMessage(
                    id = started.assistantMessageId,
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            localCallId = toolLocalCallId,
                            stepId = toolStepId,
                            providerCallId = "call-revocable",
                            toolName = revocableTool.name,
                            input = "{}",
                            output = emptyList(),
                        ),
                    ),
                )
                val waitingOutcome = turnRunner.run(
                    TurnRunInputs(
                        turnContext = turnContext,
                        handle = started.handle,
                        messages = listOf(userMessage, waitingMessage),
                        maxSteps = 1,
                        assistantMessageId = waitingMessage.id,
                        onCheckpoint = started.turnCommitter::onCheckpoint,
                        onAssistantObserved = started.turnCommitter::observeAssistant,
                        onStreamDelta = started.turnCommitter::publishStream,
                        onResult = started.turnCommitter::commitRunResult,
                        cancelReason = { runtime.peekCancelReason(turnId) },
                    ),
                )
                assertTrue(waitingOutcome is TurnPause)
                val waitingOwner = requireNotNull(runtime.snapshot.value.stream)
                runtime.retainAwaitingUser(
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
                if (!runtime.isAwaitingUser(turnId)) {
                    runtime.releaseTurnWorker(turnId, coroutineContext[Job])
                }
            }
        }.also(workers::add)
        registry.installAndStartTurnWorker(conversationId, turnId, initialWorker)
        initialWorker.join()
        initialFailure?.let { throw AssertionError("initial turn worker failed", it) }
        val started = requireNotNull(startedTurn)

        val pendingMessage = runtime.snapshot.value.toPresentationSnapshot().currentMessages().last()
        assertTrue(pendingMessage.getTools().single().interactionState is ToolInteractionState.AwaitingApproval)
        val waitingOwner = requireNotNull(runtime.snapshot.value.stream)
        val originalHandle = TurnHandle(
            conversationId = conversationId,
            epoch = waitingOwner.epoch,
            turnId = waitingOwner.turnId,
            assistantMessageId = waitingOwner.assistantMessageId,
        )

        var installedContinuation: TurnHandle? = null
        var continuationWorker: Job? = null
        var continuationFailure: Throwable? = null
        val executionStatuses = mutableListOf<ToolExecutionStatus>()
        val pendingTool = pendingMessage.getTools().single()
        applyToolInteractionDecision(
            locator = ToolCallLocator(pendingMessage.id, pendingTool.stepId, pendingTool.localCallId),
            decision = ToolInteractionDecision.Approve,
            awaitPreviousGeneration = { initialWorker.join() },
            currentSnapshot = { runtime.snapshot.value },
            submit = { command -> coordinator.executeOrThrow(conversationId, command) },
            onMoreApprovalsPending = { error("the only tool approval should resume immediately") },
            continueTurn = { owner, entry ->
                assertEquals(TurnEntry.CONTINUE_USER_INTERACTION, entry)
                val handle = TurnHandle(
                    conversationId = conversationId,
                    epoch = owner.epoch,
                    turnId = owner.turnId,
                    assistantMessageId = owner.assistantMessageId,
                )
                val worker = appScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val activeWorker = requireNotNull(coroutineContext[Job])
                        assertSame(activeWorker, runtime.currentWorker())
                        assertSame(turnContext, runtime.requireTurnContext(turnId, activeWorker))
                        val approvedMessages = runtime.snapshot.value.toPresentationSnapshot().currentMessages()
                        assertEquals(
                            ToolInteractionState.Approved,
                            approvedMessages.last().getTools().single().interactionState,
                        )
                        val continuation = TurnCommitter.continueActive(
                            commandCoordinator = coordinator,
                            runtime = runtime,
                            expectedTurnId = turnId,
                            messages = approvedMessages,
                            turnFinalizer = turnFinalizer,
                        )
                        assertEquals(started.assistantMessageId, continuation.assistantMessageId)
                        turnRunner.run(
                            TurnRunInputs(
                                turnContext = turnContext,
                                handle = continuation.handle,
                                messages = approvedMessages,
                                maxSteps = 1,
                                assistantMessageId = continuation.assistantMessageId,
                                onCheckpoint = { checkpoint ->
                                    continuation.turnCommitter.onCheckpoint(checkpoint)
                                    (checkpoint as? ToolExecutionCheckpoint)?.toolExecution?.let { event ->
                                        executionStatuses += event.status
                                    }
                                },
                                onAssistantObserved = continuation.turnCommitter::observeAssistant,
                                onStreamDelta = continuation.turnCommitter::publishStream,
                                onResult = continuation.turnCommitter::commitRunResult,
                                cancelReason = { runtime.peekCancelReason(turnId) },
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        continuationFailure = error
                        throw error
                    } finally {
                        if (!runtime.isAwaitingUser(turnId)) {
                            runtime.releaseTurnWorker(turnId, coroutineContext[Job])
                        }
                    }
                }.also(workers::add)
                continuationWorker = worker
                registry.installAndStartUserInteractionContinuation(conversationId, handle, worker)
                installedContinuation = handle
            },
        )
        assertEquals(originalHandle, installedContinuation)
        requireNotNull(continuationWorker).join()
        continuationFailure?.let { throw AssertionError("approval continuation worker failed", it) }
        assertNull(runtime.currentWorker())

        assertTrue(toolExecuted)
        assertEquals(listOf("STARTED", "COMPLETED"), executionStatuses.map { it.name })
        val turns = repository.getTurnExecutions(conversationId)
        assertEquals(1, turns.size)
        assertEquals(turnId.toString(), turns.single().turnId)
        assertEquals(started.assistantMessageId.toString(), turns.single().assistantMessageId)
        // maxSteps=1 被已批准的工具执行消耗，本 fixture 没有后续 Provider 应答，Turn 以 INCOMPLETE 收口；
        // 本用例锁定的是 continuation 复用同一 turn/assistant 与冻结 binding，而非终态为 COMPLETED。
        assertEquals(TurnExecutionStatus.INCOMPLETE, turns.single().status)

        val executions = repository.getToolExecutions(turnId.toString())
        assertEquals(1, executions.size)

        val reloaded = requireNotNull(repository.getConversationById(conversationId))
        val durableTool = reloaded.currentMessages.last().getTools().single()
        assertEquals("executed", durableTool.output.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(ToolLivePhase.COMPLETED, resolveToolLivePhase(durableTool, null))
        assertEquals(runtime.snapshot.value.toPresentationSnapshot().currentMessages().last().getTools().single(), durableTool)
        assertNull(runtime.snapshot.value.stream)
    }
}
