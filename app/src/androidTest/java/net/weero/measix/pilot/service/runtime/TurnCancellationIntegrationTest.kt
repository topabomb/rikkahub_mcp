package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.service.turn.TurnCommitter
import net.weero.measix.pilot.service.turn.TurnContext
import net.weero.measix.pilot.service.turn.TurnDisclosureSource
import net.weero.measix.pilot.service.turn.androidTestTurnContext

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.TurnTerminalReasons
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnRunResult
import net.weero.measix.pilot.service.turn.TurnRunInputs
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.ai.request.ContextBudget
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactSettingsCoordinator
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.ContextAdmissionReason
import net.weero.measix.pilot.data.model.DisclosureNamespace
import net.weero.measix.pilot.data.model.DisclosureSection
import net.weero.measix.pilot.data.model.MIN_CONTEXT_MESSAGE_LIMIT
import net.weero.measix.pilot.data.model.memoryAddress
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.MemoryRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationTitleCoordinator
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.subassistant.SubAssistantRunGate
import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.turn.TurnRunPhase
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
import kotlin.time.Clock
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TurnCancellationIntegrationTest {
    private lateinit var application: Context
    private lateinit var payloadRoot: File
    private lateinit var payloadContext: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var settingsStore: SettingsStore
    private lateinit var artifactStore: ArtifactStore
    private lateinit var repository: ConversationRepository
    private lateinit var registry: ConversationRuntimeRegistry
    private lateinit var coordinator: ConversationCommandCoordinator
    private lateinit var turnFinalizer: TurnFinalizer
    private lateinit var turnRunner: TurnRunner
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
        settingsStore = SettingsStore(application, appScope)
        artifactStore = ArtifactStore(
            payloadStore = ArtifactPayloadStore(payloadContext),
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            contextDAO = database.conversationModelContextDao(),
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
        turnRunner = TurnRunner(
            artifactStore = artifactStore,
            context = payloadContext,
            providerManager = ProviderManager(httpClient, payloadContext),
            json = Json,
            attachmentResolver = AttachmentResolver(
                artifactStore = artifactStore,
            ),
            toolOutputStore = net.weero.measix.pilot.data.ai.tools.ToolOutputStore(artifactStore),
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
                if (checkpoint is ToolResultCheckpoint) {
                    checkpointCommitted.complete(Unit)
                    releaseCheckpoint.await()
                }
            },
        )

        try {
            fixture.awaitEvent(checkpointCommitted)
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
        assertNull(fixture.runtime.snapshot.value.stream)
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
        val owned = fixture.awaitEvent(artifactCreated)
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
        assertEquals(ToolExecutionStatus.UNKNOWN, repository.getToolExecutions(fixture.turnId.toString()).single().status)
        assertNull(fixture.runtime.snapshot.value.stream)
    }

    @Test
    fun rollingCompactionCheckpointPublishesArchiveAndPruneReleasesItsRoot() = runBlocking {
        val history = compactionHistory()
        val original = history[1].getTools().single()
        val requests = mutableListOf<List<ModelRequestMessage>>()
        var compactionCheckpoints = 0
        val fixture = startCompactionTurn(history, requests, onCheckpoint = { committer, checkpoint ->
            if (checkpoint is ModelResponseCheckpoint) {
                val patch = checkpoint.toolOutputCompactionPatches.single()
                val archive = requireNotNull(patch.archive)
                assertEquals(original.localCallId, patch.locator.localCallId)
                val before = requireNotNull(repository.getConversationById(checkpoint.turn.conversationId))
                assertEquals(original, before.currentMessages[1].getTools().single())
                assertFalse(database.artifactReferenceDao().existsByArtifactId(archive.ref))
                assertTrue(artifactStore.file(requireNotNull(database.artifactDao().getById(archive.ref))).isFile)
                committer.onCheckpoint(checkpoint)
                val committed = requireNotNull(repository.getConversationById(checkpoint.turn.conversationId))
                    .currentMessages[1].getTools().single()
                assertEquals(listOf(patch.marker), committed.output)
                assertEquals(archive, committed.runtimeState.archive)
                assertTrue(database.artifactReferenceDao().existsByArtifactId(archive.ref))
                compactionCheckpoints++
            } else {
                committer.onCheckpoint(checkpoint)
            }
        })
        withTimeout(15_000) { fixture.worker.join() }
        fixture.failure?.let { throw AssertionError("compaction worker failed", it) }

        assertEquals(1, compactionCheckpoints)
        assertEquals(2, requests.size)
        val persisted = requireNotNull(repository.getConversationById(fixture.conversationId))
        val compacted = persisted.currentMessages[1].getTools().single()
        val archive = requireNotNull(compacted.runtimeState.archive)
        val archiveFile = artifactStore.file(requireNotNull(database.artifactDao().getById(archive.ref)))
        val originalText = (original.output.single() as UIMessagePart.Text).text
        assertEquals(originalText, artifactStore.withToolOutputText(fixture.conversationId, archive.ref) { it.readText() })
        assertEquals(compacted, fixture.runtime.durable.currentMessages()[1].getTools().single())
        assertEquals(1, persisted.currentMessages.last().usage?.successfulToolOutputCompactionBatchCount)
        assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecution(fixture.turnId.toString())!!.status)
        assertNull(fixture.runtime.snapshot.value.stream)
        listOf(3, 5).forEach { index ->
            assertEquals(history[index], persisted.currentMessages[index])
        }
        val firstReplay = requests.first().flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            .single { it.providerCallId == original.providerCallId }
        val secondReplay = requests.last().flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            .single { it.providerCallId == original.providerCallId }
        assertEquals(original.output, firstReplay.output)
        assertEquals(compacted.output, secondReplay.output)

        artifactStore.ensureReferenceProjection()
        coordinator.executeOrThrow(fixture.conversationId, TruncateToNodeIndex(1))
        assertEquals(originalText, artifactStore.withToolOutputText(fixture.conversationId, archive.ref) { it.readText() })
        assertTrue(artifactStore.collectGarbage(0).isEmpty())
        coordinator.executeOrThrow(fixture.conversationId, TruncateToNodeIndex(0))
        assertFalse(database.artifactReferenceDao().existsByArtifactId(archive.ref))
        assertNull(artifactStore.withToolOutputText(fixture.conversationId, archive.ref) { it.readText() })
        // GC after pruning also proves the successful checkpoint released the unpublished creation pin.
        assertEquals(listOf(archive.ref), artifactStore.collectGarbage(0).map { it.id })
        assertNull(database.artifactDao().getById(archive.ref))
        assertFalse(archiveFile.exists())
    }

    @Test
    fun cancellationAfterCompactionStageBeforeCheckpointKeepsInlineAndDiscardsOnlyItsArchive() = runBlocking {
        val unrelated = createOwnedImage("unrelated.png")
        val unrelatedBefore = requireNotNull(database.artifactDao().getById(unrelated.entity.id))
        val history = compactionHistory()
        val staged = CompletableDeferred<ArtifactEntity>()
        val neverCommit = CompletableDeferred<Unit>()
        val requests = mutableListOf<List<ModelRequestMessage>>()
        var modelCheckpoints = 0
        val fixture = startCompactionTurn(
            history, requests,
            onPhase = { phase ->
                if (phase == TurnRunPhase.TOOL_PREPARING) {
                    // StepRunner reaches this cancellable phase after registering the real archive lease,
                    // before entering the non-cancellable ModelResponseCheckpoint handoff.
                    val archive = database.artifactDao().listInScope(ConfigurationScope.Personal)
                        .single { it.id != unrelated.entity.id }
                    assertTrue(artifactStore.file(archive).isFile)
                    assertFalse(database.artifactReferenceDao().existsByArtifactId(archive.id))
                    staged.complete(archive)
                    neverCommit.await()
                }
            },
            onCheckpoint = { committer, checkpoint ->
                if (checkpoint is ModelResponseCheckpoint) modelCheckpoints++
                committer.onCheckpoint(checkpoint)
            },
        )
        val archive = fixture.awaitEvent(staged)
        val archiveFile = artifactStore.file(archive)
        assertEquals((history[1].getTools().single().output.single() as UIMessagePart.Text).text,
            archiveFile.readText())
        val before = requireNotNull(repository.getConversationById(fixture.conversationId))
        assertEquals(history, before.currentMessages.take(history.size))
        fixture.worker.cancel(CancellationException("cancel staged compaction before checkpoint"))
        withTimeout(15_000) { fixture.worker.join() }
        fixture.failure?.let { throw AssertionError("compaction cancellation failed", it) }

        val persisted = requireNotNull(repository.getConversationById(fixture.conversationId))
        assertEquals(1, requests.size)
        assertEquals(0, modelCheckpoints)
        assertEquals(history, persisted.currentMessages.take(history.size))
        assertEquals(history, fixture.runtime.durable.currentMessages().take(history.size))
        assertEquals(0, persisted.currentMessages.last().usage?.successfulToolOutputCompactionBatchCount ?: 0)
        assertEquals(TurnExecutionStatus.CANCELLED, repository.getTurnExecution(fixture.turnId.toString())!!.status)
        assertNull(fixture.runtime.snapshot.value.stream)
        assertNull(database.artifactDao().getById(archive.id))
        assertFalse(database.artifactReferenceDao().existsByArtifactId(archive.id))
        assertFalse(archiveFile.exists())
        assertEquals(unrelatedBefore, database.artifactDao().getById(unrelated.entity.id))
        assertTrue(artifactStore.file(unrelated.localRef).isFile)
    }

    @Test
    fun rollingCompactionFinalizationPublishesArchiveWithTheCompletedTurn() = runBlocking {
        val history = compactionHistory()
        val original = history[1].getTools().single()
        val requests = mutableListOf<List<ModelRequestMessage>>()
        var terminalCommits = 0
        val fixture = startCompactionTurn(
            history, requests, finalResponseFirst = true,
            onResult = { committer, result, runtime ->
                assertTrue(result is TurnOutcome.Completed)
                val completed = result as TurnOutcome.Completed
                val patch = completed.toolOutputCompactionPatches.single()
                val archive = requireNotNull(patch.archive)
                assertEquals(original.localCallId, patch.locator.localCallId)
                assertEquals(original, repository.getConversationById(runtime.id)!!.currentMessages[1].getTools().single())
                assertEquals(TurnExecutionStatus.RUNNING, repository.getTurnExecutions(runtime.id).single().status)
                assertFalse(database.artifactReferenceDao().existsByArtifactId(archive.ref))
                assertTrue(artifactStore.file(requireNotNull(database.artifactDao().getById(archive.ref))).isFile)

                committer.commitRunResult(result)

                val persisted = requireNotNull(repository.getConversationById(runtime.id))
                assertEquals(listOf(patch.marker), persisted.currentMessages[1].getTools().single().output)
                assertEquals(archive, persisted.currentMessages[1].getTools().single().runtimeState.archive)
                val finalAssistant = persisted.currentMessages.last()
                assertEquals(completed.assistantMessage.id, finalAssistant.id)
                assertEquals("done", finalAssistant.toText())
                assertNull(finalAssistant.terminalStatus)
                val finalStep = finalAssistant.parts.filterIsInstance<UIMessagePart.Step>().single()
                assertEquals(StepOutcome.Final, finalStep.outcome)
                assertTrue(finalStep.finishedAt != null && finalStep.modelResult != null)
                assertEquals(1, persisted.currentMessages.last().usage?.successfulToolOutputCompactionBatchCount)
                assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecutions(runtime.id).single().status)
                assertTrue(database.artifactReferenceDao().existsByArtifactId(archive.ref))
                terminalCommits++
            },
            onCheckpoint = { _, _ -> error("A first-response Final must commit through FinalizeTurn") },
        )
        withTimeout(15_000) { fixture.worker.join() }
        fixture.failure?.let { throw AssertionError("compaction finalization failed", it) }
        assertEquals(1, terminalCommits)
        assertEquals(1, requests.size)
        val persisted = requireNotNull(repository.getConversationById(fixture.conversationId))
        val compacted = persisted.currentMessages[1].getTools().single()
        val archive = requireNotNull(compacted.runtimeState.archive)
        val archiveFile = artifactStore.file(requireNotNull(database.artifactDao().getById(archive.ref)))
        assertEquals((original.output.single() as UIMessagePart.Text).text,
            artifactStore.withToolOutputText(fixture.conversationId, archive.ref) { it.readText() })
        assertEquals(persisted.currentMessages, fixture.runtime.durable.currentMessages())
        assertNull(fixture.runtime.snapshot.value.stream)
        listOf(3, 5).forEach { assertEquals(history[it], persisted.currentMessages[it]) }
        assertEquals(original.output, requests.single().flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            .single { it.providerCallId == original.providerCallId }.output)

        artifactStore.ensureReferenceProjection()
        assertTrue(artifactStore.collectGarbage(0).isEmpty())
        coordinator.executeOrThrow(fixture.conversationId, TruncateToNodeIndex(0))
        assertFalse(database.artifactReferenceDao().existsByArtifactId(archive.ref))
        // Pruning must release the durable root; successful finalization must have released its creation pin.
        assertEquals(listOf(archive.ref), artifactStore.collectGarbage(0).map { it.id })
        assertNull(database.artifactDao().getById(archive.ref))
        assertFalse(archiveFile.exists())
    }

    @Test
    fun failedCompactionFinalizationRollsBackRoomAndDiscardsOnlyItsArchive() = runBlocking {
        val unrelated = createOwnedImage("unrelated-finalization.png")
        val unrelatedBefore = requireNotNull(database.artifactDao().getById(unrelated.entity.id))
        val history = compactionHistory()
        val requests = mutableListOf<List<ModelRequestMessage>>()
        lateinit var staged: ArtifactEntity
        lateinit var before: ConversationAggregateSnapshot
        lateinit var beforeTurn: net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
        var terminalAttempts = 0
        val fixture = startCompactionTurn(
            history, requests, finalResponseFirst = true,
            onResult = { committer, result, runtime ->
                assertTrue(result is TurnOutcome.Completed)
                val patch = (result as TurnOutcome.Completed).toolOutputCompactionPatches.single()
                staged = requireNotNull(database.artifactDao().getById(requireNotNull(patch.archive).ref))
                before = requireNotNull(repository.getConversationSnapshotById(runtime.id))
                beforeTurn = repository.getTurnExecutions(runtime.id).single()
                assertEquals(before, runtime.durable)
                assertEquals(history, before.currentMessages().take(history.size))
                assertFalse(database.artifactReferenceDao().existsByArtifactId(staged.id))
                assertTrue(artifactStore.file(staged).isFile)
                // Fail inside the real Room transaction after message/turn writes, at reference publication.
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_compaction_reference BEFORE INSERT ON artifact_reference " +
                        "WHEN NEW.artifact_id = ${staged.id} BEGIN " +
                        "SELECT CASE WHEN (SELECT status FROM turn_execution WHERE turn_id = '${beforeTurn.turnId}') = 'COMPLETED' " +
                        "THEN RAISE(ABORT, 'compaction finalization transaction rejected') " +
                        "ELSE RAISE(ABORT, 'compaction failure reached before terminal writes') END; END",
                )
                terminalAttempts++
                try {
                    committer.commitRunResult(result)
                    throw AssertionError("The reference trigger must reject the terminal transaction")
                } finally {
                    database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_compaction_reference")
                }
            },
            onCheckpoint = { _, _ -> error("A first-response Final must commit through FinalizeTurn") },
        )
        withTimeout(15_000) { fixture.worker.join() }
        val failure = requireNotNull(fixture.failure) { "The Room failure must propagate to the owner" }
        assertTrue(generateSequence(failure) { it.cause }
            .any { it.message?.contains("compaction finalization transaction rejected") == true })
        assertEquals(1, terminalAttempts)
        assertEquals(1, requests.size)
        assertEquals(before, repository.getConversationSnapshotById(fixture.conversationId))
        assertEquals(before, fixture.runtime.durable)
        assertEquals(beforeTurn, repository.getTurnExecution(fixture.turnId.toString()))
        assertEquals(0, before.currentMessages().last().usage?.successfulToolOutputCompactionBatchCount ?: 0)
        assertNull(database.artifactDao().getById(staged.id))
        assertFalse(database.artifactReferenceDao().existsByArtifactId(staged.id))
        assertFalse(artifactStore.file(staged).exists())
        assertEquals(unrelatedBefore, database.artifactDao().getById(unrelated.entity.id))
        assertTrue(artifactStore.file(unrelated.localRef).isFile)
    }

    @Test
    fun forkOfRealCompactionKeepsIndependentReferencesUntilBothBranchesArePruned() = runBlocking {
        val history = compactionHistory()
        val original = history[1].getTools().single()
        val originalText = (original.output.single() as UIMessagePart.Text).text
        val requests = mutableListOf<List<ModelRequestMessage>>()
        var compactedBatches = 0
        val fixture = startCompactionTurn(
            history, requests, finalResponseFirst = true,
            onResult = { committer, result, _ ->
                val patch = (result as TurnOutcome.Completed).toolOutputCompactionPatches.single()
                assertEquals(original.localCallId, patch.locator.localCallId)
                assertTrue(patch.archive != null)
                committer.commitRunResult(result)
                compactedBatches++
            },
            onCheckpoint = { _, _ -> error("The first Final response must use FinalizeTurn") },
        )
        withTimeout(15_000) { fixture.worker.join() }
        fixture.failure?.let { throw AssertionError("compaction before fork failed", it) }
        assertEquals(1, compactedBatches)
        assertEquals(1, requests.size)
        val source = requireNotNull(repository.getConversationSnapshotById(fixture.conversationId))
        val compacted = source.currentMessages()[1].getTools().single()
        val archive = requireNotNull(compacted.runtimeState.archive)
        assertTrue(compacted.output != original.output)
        val archiveFile = artifactStore.file(requireNotNull(database.artifactDao().getById(archive.ref)))
        assertEquals(originalText, artifactStore.withToolOutputText(source.conversationId, archive.ref) { it.readText() })
        assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecution(fixture.turnId.toString())!!.status)

        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(payloadRoot, "fork-session")))
        sessions.recover()
        val service = ConversationApplicationService(
            settingsStore = settingsStore,
            conversationRepo = repository,
            folderRepository = FolderRepository(database.folderDao(), database.conversationDao()),
            runtimeRegistry = registry,
            commandCoordinator = coordinator,
            recoveryGate = ApplicationRecoveryGate().apply { ready() },
            subAssistantLifecycle = SubAssistantLifecycle(repository, registry, coordinator, Json),
            sideEffects = mockk(),
            artifactStore = artifactStore,
            artifactUseCase = mockk(),
            turnFinalizer = turnFinalizer,
            json = Json,
            toolArtifactRewriter = ToolArtifactRewriter(payloadRoot, artifactStore),
            titleCoordinator = ConversationTitleCoordinator(),
            sessions = sessions,
            subAssistantRunGate = SubAssistantRunGate(),
        )
        val selection = withTimeout(5_000) { sessions.observeSelectedRealmSelection().first { it != null }!! }
        val page = ConversationViewLease(source.conversationId, selection.access, selection.revision) {}
        val forkId = try {
            withTimeout(15_000) { service.forkAtMessage(page.commandTarget, history[1].id) }
        } finally { page.close() }
        val fork = requireNotNull(repository.getConversationSnapshotById(forkId))
        assertEquals(2, fork.nodes.size)
        assertEquals(source.nodes.take(2).map { it.messages }, fork.nodes.map { it.messages })
        assertTrue(fork.nodes.none { copied -> source.nodes.any { it.id == copied.id } })
        assertEquals(archive, fork.currentMessages()[1].getTools().single().runtimeState.archive)
        assertEquals(source, repository.getConversationSnapshotById(source.conversationId))
        val references = database.artifactReferenceDao()
        assertTrue(references.existsInConversation(archive.ref, source.conversationId.toString(), "TOOL_OUTPUT"))
        assertTrue(references.existsInConversation(archive.ref, forkId.toString(), "TOOL_OUTPUT"))
        assertEquals(setOf(source.conversationId.toString(), forkId.toString()),
            references.referencingConversationIds(archive.ref).toSet())
        assertEquals(originalText, artifactStore.withToolOutputText(forkId, archive.ref) { it.readText() })
        artifactStore.ensureReferenceProjection()
        assertTrue(artifactStore.collectGarbage(0).isEmpty())

        coordinator.executeOrThrow(source.conversationId, TruncateToNodeIndex(0))
        val prunedSource = requireNotNull(repository.getConversationSnapshotById(source.conversationId))
        assertEquals(source.nodes.take(1), prunedSource.nodes)
        assertFalse(references.existsInConversation(archive.ref, source.conversationId.toString(), "TOOL_OUTPUT"))
        assertNull(artifactStore.withToolOutputText(source.conversationId, archive.ref) { it.readText() })
        assertEquals(fork, repository.getConversationSnapshotById(forkId))
        assertEquals(originalText, artifactStore.withToolOutputText(forkId, archive.ref) { it.readText() })
        assertEquals(listOf(forkId.toString()), references.referencingConversationIds(archive.ref))
        assertTrue(artifactStore.collectGarbage(0).isEmpty())
        assertTrue(archiveFile.isFile)

        coordinator.executeOrThrow(forkId, TruncateToNodeIndex(0))
        assertEquals(fork.nodes.take(1), repository.getConversationSnapshotById(forkId)!!.nodes)
        assertEquals(prunedSource, repository.getConversationSnapshotById(source.conversationId))
        assertFalse(references.existsByArtifactId(archive.ref))
        assertNull(artifactStore.withToolOutputText(forkId, archive.ref) { it.readText() })
        assertEquals(listOf(archive.ref), artifactStore.collectGarbage(0).map { it.id })
        assertNull(database.artifactDao().getById(archive.ref))
        assertFalse(archiveFile.exists())
    }

    @Test
    fun compactionPreservesDisclosureAndWindowRestoreSurvivesForkReplay() = runBlocking {
        val memoryAssistant = assistant.copy(enableMemory = true, localTools = emptyList(), streamOutput = false)
        val memoryRepository = MemoryRepository(database.memoryDao(), RoomDatabaseTransactionRunner(database))
        val address = memoryAssistant.memoryAddress(ConfigurationScope.Personal)
        val memory = memoryRepository.add(address, "Memory retained across archive and request windows") {}
        val memorySettings = settings.copy(assistants = listOf(memoryAssistant))
        val configuration = ConfigurationResolver.resolve(
            UserSettingsDocument.empty().withPersonalSettings(memorySettings),
            ConfigurationScope.Personal, EnterpriseState.Loading,
        )
        fun context(limit: Int): TurnContext {
            val selected = memoryAssistant.copy(contextMessageLimit = limit)
            return androidTestTurnContext(memorySettings, model, selected).copy(
                disclosure = TurnDisclosureSource.capture(
                    configuration, selected, DisclosureNamespace(null, selected.id),
                    readConfiguration = { configuration }, readMemory = { memoryRepository.read(address) },
                ),
            ).also { assertEquals(limit, it.assistant.contextMessageLimit) }
        }
        suspend fun snapshot(runtime: ConversationRuntime) =
            requireNotNull(repository.getConversationSnapshotById(runtime.id))
        fun disclosures(snapshot: ConversationAggregateSnapshot) = snapshot.modelContextEntries.filter {
            it.payload.source is ConversationContextSource.Disclosure
        }
        fun assertPreserved(before: ConversationAggregateSnapshot, after: ConversationAggregateSnapshot) {
            val entries = before.modelContextEntries.associateBy { it.id }
            val admissions = before.contextAdmissions.associateBy { it.id }
            assertEquals(entries, after.modelContextEntries.filter { it.id in entries }.associateBy { it.id })
            assertEquals(admissions, after.contextAdmissions.filter { it.id in admissions }.associateBy { it.id })
            assertEquals(after.modelContextEntries.size, after.modelContextEntries.map { it.id }.toSet().size)
            assertEquals(after.contextAdmissions.size, after.contextAdmissions.map { it.id }.toSet().size)
        }
        fun memoryCopies(request: List<ModelRequestMessage>) = request.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>().count { it.text.contains(memory.content) }

        val history = compactionHistory()
        val firstRequests = mutableListOf<List<ModelRequestMessage>>()
        var admittedBeforeCompaction: ConversationAggregateSnapshot? = null
        val first = startCompactionTurn(
            history, firstRequests, finalResponseFirst = true, contextOverride = context(0),
            onResult = { committer, result, runtime ->
                val patch = (result as TurnOutcome.Completed).toolOutputCompactionPatches.single()
                assertEquals(history[1].getTools().single().localCallId, patch.locator.localCallId)
                assertTrue(patch.archive != null)
                admittedBeforeCompaction = snapshot(runtime).also {
                    assertEquals(history[1].getTools(), it.currentMessages()[1].getTools())
                    assertEquals(1, disclosures(it).size)
                    assertEquals(1, it.contextAdmissions.size)
                }
                committer.commitRunResult(result)
            },
            onCheckpoint = { _, _ -> error("A Final response must use FinalizeTurn") },
        )
        withTimeout(15_000) { first.worker.join() }
        first.failure?.let { throw AssertionError("initial archive request failed", it) }
        val compacted = snapshot(first.runtime)
        val baseline = disclosures(compacted).single()
        assertEquals(DisclosureSection.entries.associateWith { ContextAdmissionReason.INITIAL },
            (baseline.payload.source as ConversationContextSource.Disclosure).reasons)
        assertPreserved(requireNotNull(admittedBeforeCompaction), compacted)
        val archive = requireNotNull(compacted.currentMessages()[1].getTools().single().runtimeState.archive)
        assertEquals((history[1].getTools().single().output.single() as UIMessagePart.Text).text,
            artifactStore.withToolOutputText(first.conversationId, archive.ref) { it.readText() })
        assertEquals(1, memoryCopies(firstRequests.single()))

        suspend fun nextRequest(runtime: ConversationRuntime, question: String, limit: Int): List<ModelRequestMessage> {
            coordinator.executeOrThrow(runtime.id, AppendUserMessage(UIMessage.user(question)))
            val requests = mutableListOf<List<ModelRequestMessage>>()
            val run = startCompactionTurn(
                emptyList(), requests, finalResponseFirst = true,
                existingRuntime = runtime, contextOverride = context(limit),
                onResult = { committer, result, _ ->
                    assertTrue((result as TurnOutcome.Completed).toolOutputCompactionPatches.isEmpty())
                    committer.commitRunResult(result)
                },
                onCheckpoint = { _, _ -> error("A Final response must use FinalizeTurn") },
            )
            withTimeout(15_000) { run.worker.join() }
            run.failure?.let { throw AssertionError("request failed: $question", it) }
            assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecution(run.turnId.toString())!!.status)
            return requests.single()
        }

        // Ordinary output is archived; the unchanged Memory baseline remains at its causal USER.
        val afterArchiveRequest = nextRequest(first.runtime, "Continue after archiving ordinary output", 0)
        val afterArchive = snapshot(first.runtime)
        assertEquals(listOf(baseline), disclosures(afterArchive))
        assertPreserved(compacted, afterArchive)
        assertEquals(compacted.contextAdmissions.size + 1, afterArchive.contextAdmissions.size)
        assertEquals(1, memoryCopies(afterArchiveRequest))
        assertEquals(compacted.currentMessages()[1].getTools().single().output,
            afterArchiveRequest.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
                .single { it.localCallId == history[1].getTools().single().localCallId }.output)

        // Build enough real committed rounds to exceed the production minimum. Positive limits
        // below that minimum are normalized by the assistant owner, not a smaller test window.
        val extraRounds = (MIN_CONTEXT_MESSAGE_LIMIT - afterArchive.nodes.size) / 2 + 1
        repeat(extraRounds) { index ->
            val request = nextRequest(first.runtime, "Retained ordinary round $index", 0)
            assertEquals(1, memoryCopies(request))
        }
        val beforeWindow = snapshot(first.runtime)
        assertTrue(beforeWindow.nodes.size > MIN_CONTEXT_MESSAGE_LIMIT)
        assertPreserved(afterArchive, beforeWindow)
        assertEquals(listOf(baseline), disclosures(beforeWindow))

        // Window loss, not compaction of PRESERVE Memory results, requires a fresh baseline at
        // this request's causal tail. The original durable baseline must remain unchanged.
        val windowQuestion = "Continue with the production minimum window"
        val windowRequest = nextRequest(first.runtime, windowQuestion, MIN_CONTEXT_MESSAGE_LIMIT)
        val restored = snapshot(first.runtime)
        assertPreserved(beforeWindow, restored)
        assertEquals(2, disclosures(restored).size)
        val restoration = disclosures(restored).single { it.id != baseline.id }
        assertEquals(DisclosureSection.entries.associateWith { ContextAdmissionReason.BASELINE_RESTORE },
            (restoration.payload.source as ConversationContextSource.Disclosure).reasons)
        assertEquals(baseline.payload.body, restoration.payload.body)
        assertEquals(1, memoryCopies(windowRequest))
        val windowUser = restored.nodes[restored.nodes.lastIndex - 1]
        val restorationAdmission = restored.contextAdmissions.single { it.owner.messageId == restored.nodes.last().currentMessage.id }
        assertEquals(windowUser.id, restoration.anchorNodeId)
        val windowStartIndex = restored.nodes.indexOfFirst { it.id == restorationAdmission.windowStart.nodeId }
        assertTrue(windowStartIndex > restored.nodes.indexOfFirst { it.id == baseline.anchorNodeId })
        assertTrue(restorationAdmission.uses.any { it.entryId == restoration.id })
        val retainedUserTexts = restored.nodes.drop(windowStartIndex).map { it.currentMessage }
            .filter { it.role == MessageRole.USER }.map { it.toText() }
        assertEquals(retainedUserTexts, windowRequest.filter { it.role == MessageRole.USER }
            .flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
            .filterNot { it.text.contains(memory.content) }.map { it.text })
        assertTrue(windowRequest.flatMap { it.parts }.none { it is UIMessagePart.Tool })

        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(payloadRoot, "disclosure-fork-session")))
        sessions.recover()
        val service = ConversationApplicationService(
            settingsStore = settingsStore, conversationRepo = repository,
            folderRepository = FolderRepository(database.folderDao(), database.conversationDao()),
            runtimeRegistry = registry, commandCoordinator = coordinator,
            recoveryGate = ApplicationRecoveryGate().apply { ready() },
            subAssistantLifecycle = SubAssistantLifecycle(repository, registry, coordinator, Json),
            sideEffects = mockk(), artifactStore = artifactStore, artifactUseCase = mockk(),
            turnFinalizer = turnFinalizer, json = Json,
            toolArtifactRewriter = ToolArtifactRewriter(payloadRoot, artifactStore),
            titleCoordinator = ConversationTitleCoordinator(), sessions = sessions,
            subAssistantRunGate = SubAssistantRunGate(),
        )
        val selection = withTimeout(5_000) { sessions.observeSelectedRealmSelection().first { it != null }!! }
        val page = ConversationViewLease(first.conversationId, selection.access, selection.revision) {}
        val forkId = try {
            withTimeout(15_000) { service.forkAtMessage(page.commandTarget, restored.nodes.last().currentMessage.id) }
        } finally { page.close() }
        val forkRuntime = registry.loadRuntime(forkId)
        val fork = snapshot(forkRuntime)
        assertEquals(restored.header.scope, fork.header.scope)
        assertEquals(restored.modelContextEntries.size, fork.modelContextEntries.size)
        assertEquals(restored.contextAdmissions.size, fork.contextAdmissions.size)
        val copiedRestoration = disclosures(fork).single {
            (it.payload.source as ConversationContextSource.Disclosure).reasons.values.toSet() == setOf(ContextAdmissionReason.BASELINE_RESTORE)
        }
        assertEquals(restoration.payload, copiedRestoration.payload)
        assertTrue(copiedRestoration.id != restoration.id)
        assertTrue(fork.nodes.none { copied -> restored.nodes.any { it.id == copied.id } })
        assertEquals(archive, fork.currentMessages()[1].getTools().single().runtimeState.archive)

        // Keep the restored USER round in view. A fork preserves admitted knowledge and must not
        // manufacture an INITIAL/RESTORE disclosure simply because node identities were cloned.
        val forkRequest = nextRequest(forkRuntime, "Continue the fork with its restored baseline", MIN_CONTEXT_MESSAGE_LIMIT)
        val forkAfter = snapshot(forkRuntime)
        assertPreserved(fork, forkAfter)
        assertEquals(disclosures(fork), disclosures(forkAfter))
        assertEquals(fork.contextAdmissions.size + 1, forkAfter.contextAdmissions.size)
        assertEquals(1, memoryCopies(forkRequest))
        assertTrue(forkRequest.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().any { it.text == windowQuestion })
        assertEquals(restored, snapshot(first.runtime))
        assertEquals(listOf(memory), memoryRepository.read(address))
        assertEquals(setOf(first.conversationId.toString(), forkId.toString()),
            database.artifactReferenceDao().referencingConversationIds(archive.ref).toSet())
        artifactStore.ensureReferenceProjection()
        assertTrue(artifactStore.collectGarbage(0).isEmpty())
    }

    private fun compactionHistory(): List<UIMessage> = buildList {
        val lengths = listOf(
            ContextBudget.TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS * 4,
            ContextBudget.TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS * 2,
            ContextBudget.TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS * 2,
        )
        lengths.forEachIndexed { index, length ->
            val toolStep = Uuid.random()
            val now = Clock.System.now()
            add(UIMessage.user("Historical request $index"))
            add(UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Step(stepId = toolStep, ordinal = 0, startedAt = now,
                        outcome = StepOutcome.Continue, finishedAt = now),
                    UIMessagePart.Tool(
                        localCallId = Uuid.random(), stepId = toolStep,
                        providerCallId = "history-$index", toolName = "history_tool", input = "{}",
                        output = listOf(UIMessagePart.Text(('a' + index).toString().repeat(length.toInt()))),
                        resultStatus = ToolResultStatus.COMPLETED,
                    ),
                    UIMessagePart.Step(stepId = Uuid.random(), ordinal = 1, startedAt = now,
                        outcome = StepOutcome.Final, finishedAt = now),
                    UIMessagePart.Text("Historical answer $index"),
                ),
            ))
        }
        add(UIMessage.user("Continue with the next tool"))
    }

    private suspend fun startCompactionTurn(
        history: List<UIMessage>,
        requests: MutableList<List<ModelRequestMessage>>,
        onPhase: suspend (TurnRunPhase) -> Unit = {},
        finalResponseFirst: Boolean = false,
        existingRuntime: ConversationRuntime? = null,
        contextOverride: TurnContext? = null,
        onResult: suspend (TurnCommitter, TurnRunResult, ConversationRuntime) -> Unit = { committer, result, _ ->
            committer.commitRunResult(result)
        },
        onCheckpoint: suspend (TurnCommitter, TurnCheckpoint) -> Unit,
    ): RunningTurnFixture {
        val providerManager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        val tool = Tool(name = "continue_tool", description = "Completes the next step.",
            execute = { listOf(UIMessagePart.Text("tool completed")) })
        coEvery { provider.generateText(providerSetting, any(), any()) } coAnswers {
            requests += secondArg<List<ModelRequestMessage>>()
            val response = if (finalResponseFirst) {
                check(requests.size == 1) { "Unexpected request after the first Final response" }
                UIMessage.assistant("done")
            } else when (requests.size) {
                1 -> UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "continue-call",
                    toolName = tool.name, input = "{}",
                )))
                2 -> UIMessage.assistant("done")
                else -> error("Unexpected extra model request")
            }
            MessageChunk(id = "response-${requests.size}", model = model.modelId, choices = listOf(
                UIMessageChoice(index = 0, delta = null, message = response,
                    finishReason = if (!finalResponseFirst && requests.size == 1) "tool_calls" else "stop"),
            ))
        }
        val runner = TurnRunner(
            context = payloadContext,
            providerManager = providerManager,
            json = Json,
            attachmentResolver = AttachmentResolver(artifactStore),
            toolOutputStore = ToolOutputStore(artifactStore),
            artifactStore = artifactStore,
        )
        val runtime = existingRuntime ?: coordinator.create(Conversation(
            id = Uuid.random(), assistantId = assistant.id, messageNodes = history.map { it.toMessageNode() },
        ))
        val fixture = RunningTurnFixture(runtime.id, Uuid.random(), runtime)
        val turnContext = contextOverride ?: androidTestTurnContext(settings, model, assistant.copy(streamOutput = false), listOf(tool))
        val worker = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                val activeWorker = requireNotNull(coroutineContext[Job])
                assertSame(activeWorker, runtime.currentWorker())
                runtime.bindModelExecution(fixture.turnId, activeWorker, turnContext.assistant.id,
                    turnContext.model.requests as ModelExecutionLease)
                runtime.bindTurnContext(fixture.turnId, activeWorker, turnContext)
                val started = TurnCommitter.start(coordinator, runtime, fixture.turnId, turnFinalizer)
                val committer = started.turnCommitter
                runner.run(TurnRunInputs(
                    turnContext = turnContext, handle = started.handle,
                    requestContext = committer.requestContext, messages = runtime.durable.currentMessages(),
                    maxSteps = 2, assistantMessageId = started.assistantMessageId,
                    onCheckpoint = { onCheckpoint(committer, it) },
                    onAssistantObserved = committer::observeAssistant, onStreamDelta = committer::publishStream,
                    onResult = { onResult(committer, it, runtime) }, onPhase = { phase, _ -> onPhase(phase) },
                    cancelReason = { runtime.peekCancelReason(fixture.turnId) },
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fixture.failure = error
            } finally {
                runtime.releaseTurnWorker(fixture.turnId, coroutineContext[Job])
            }
        }
        fixture.worker = worker
        workers += worker
        registry.installAndStartTurnWorker(fixture.conversationId, fixture.turnId, worker)
        return fixture
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
        artifactStore.createFromBytes(ConfigurationScope.Personal,
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
        onCheckpoint: suspend (TurnCommitter, net.weero.measix.pilot.service.runtime.TurnCheckpoint) -> Unit =
            { engine, checkpoint -> engine.onCheckpoint(checkpoint) },
    ): RunningTurnFixture {
        val conversationId = Uuid.random()
        val turnId = Uuid.random()
        val userMessage = UIMessage.user("Run ${tool.name}")
        val runtime = coordinator.create(
            Conversation(
                id = conversationId,
                assistantId = assistant.id,
                messageNodes = listOf(userMessage.toMessageNode()),
            ),
        )
        val fixture = RunningTurnFixture(
            conversationId = conversationId,
            turnId = turnId,
            runtime = runtime,
        )
        val turnContext = androidTestTurnContext(
            settings = settings,
            model = model,
            assistant = assistant,
            tools = listOf(tool),
        )
        val worker = appScope.launch(start = CoroutineStart.LAZY) {
            var turnCommitter: TurnCommitter? = null
            try {
                val activeWorker = requireNotNull(coroutineContext[Job])
                assertSame(activeWorker, runtime.currentWorker())
                runtime.bindModelExecution(turnId, activeWorker, turnContext.assistant.id, turnContext.model.requests as net.weero.measix.pilot.service.runtime.ModelExecutionLease)
                runtime.bindTurnContext(turnId, activeWorker, turnContext)
                val started = TurnCommitter.start(
                    commandCoordinator = coordinator,
                    runtime = runtime,
                    turnId = turnId,

                    turnFinalizer = turnFinalizer,
                )
                turnCommitter = started.turnCommitter
                val initialAssistant = runtime.durable.currentMessages().last()
                val step = initialAssistant.parts.filterIsInstance<UIMessagePart.Step>().single()
                val sampled = TurnTransition.recordModelResult(
                    initialAssistant.copy(parts = initialAssistant.parts + UIMessagePart.Tool(
                        localCallId = Uuid.random(), stepId = step.stepId,
                        providerCallId = "call-${tool.name}", toolName = tool.name,
                        input = "{}", output = emptyList(),
                    )),
                    step.stepId,
                    me.rerere.ai.ui.StepModelResult(
                        finishReason = "tool_calls", usage = me.rerere.ai.ui.StepUsage(), providerRequestCount = 1,
                        timeToFirstOutputMillis = null, requestDurationMillis = null,
                        usageCompleteness = me.rerere.ai.core.UsageCompleteness.NONE, providerMetadata = null,
                    ),
                )
                started.turnCommitter.onCheckpoint(ModelResponseCheckpoint(
                    turn = started.handle, step = StepHandle(step.stepId), assistantMessage = sampled,
                    turnStatus = TurnExecutionStatus.RUNNING,
                ))
                val currentMessages = runtime.durable.currentMessages()
                turnRunner.run(
                    TurnRunInputs(
                        turnContext = turnContext,
                        handle = started.handle,
                        requestContext = started.turnCommitter.requestContext,
                        messages = currentMessages,
                        maxSteps = 1,
                        assistantMessageId = started.assistantMessageId,
                        onCheckpoint = { checkpoint -> onCheckpoint(started.turnCommitter, checkpoint) },
                        onAssistantObserved = started.turnCommitter::observeAssistant,
                        onStreamDelta = started.turnCommitter::publishStream,
                        onResult = started.turnCommitter::commitRunResult,
                        cancelReason = { runtime.peekCancelReason(turnId) },
                    ),
                )
            } catch (cancelled: CancellationException) {
                // TurnRunner 只对生成循环内观察到的取消落终态；循环正常退出后才观察到的取消由 owner 收口
                // （生产路径见 ConversationTurnService 的 finalizeOwnerFailure）。本测试直接驱动 worker，
                // 故在此复刻 owner 的取消兜底，使 durable turn 以 CANCELLED 收口而非泄漏为 RUNNING。
                turnCommitter?.let { committer ->
                    val outcome = TurnOutcome.Cancelled(
                        runtime.peekCancelReason(turnId) ?: TurnTerminalReasons.USER_STOP,
                    )
                    try {
                        withContext(NonCancellable) { committer.finalizeOwnerFailure(outcome) }
                    } catch (finalizationError: Exception) {
                        cancelled.addSuppressed(finalizationError)
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                fixture.failure = error
                throw error
            } finally {
                runtime.releaseTurnWorker(turnId, coroutineContext[Job])
            }
        }
        fixture.worker = worker
        workers += worker
        registry.installAndStartTurnWorker(conversationId, turnId, worker)
        return fixture
    }

    private suspend fun <T> RunningTurnFixture.awaitEvent(event: Deferred<T>): T = withTimeout(5_000) {
        select {
            event.onAwait { it }
            worker.onJoin { throw AssertionError("Turn ended before the expected checkpoint", failure) }
        }
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
