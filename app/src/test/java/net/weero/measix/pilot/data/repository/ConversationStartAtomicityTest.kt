package net.weero.measix.pilot.data.repository

import net.weero.measix.pilot.service.runtime.inlineContextText
import net.weero.measix.pilot.service.runtime.disclosurePayload
import me.rerere.common.configuration.ConfigurationReference

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactReferenceDelta
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.AdmitRequestContext
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationOperationLocks
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.TurnTransition
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ConversationHeaderPatch
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import net.weero.measix.pilot.service.runtime.TurnExecutionOperation
import net.weero.measix.pilot.service.runtime.toSnapshot
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationStartAtomicityTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private val conversationId = Uuid.random()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.withLifecycleLock<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { artifactStore.prepareReferenceDelta(any(), any(), any(), any()) } returns
            ArtifactReferenceDelta(emptyList(), emptyList(), emptyList())
        coJustRun { artifactStore.applyReferenceDeltaInTransaction(any()) }
        repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            messageFtsManager = mockk<MessageFtsManager>(relaxed = true),
            turnExecutionDAO = database.turnExecutionDao(),
            toolExecutionDAO = database.toolExecutionDao(),
            modelContextDAO = database.conversationModelContextDao(),
            artifactStore = artifactStore,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `Assistant slot context and turn fact commit and reload together`() = runTest {
        insertConversationHeader()
        val fixture = fixture("answer-a")

        repository.applyMutation(fixture.mutation, fixture.executionFacts)

        val loaded = repository.getConversationSnapshotById(conversationId)
        assertNotNull(loaded)
        assertEquals(listOf(fixture.context), loaded!!.modelContextEntries)
        assertEquals(2, loaded.nodes.size)
        assertEquals(
            TurnExecutionStatus.RUNNING,
            database.turnExecutionDao().getById(fixture.turnId.toString())?.status,
        )
    }

    /**
     * Master fork 保留 message id、只重建 node id。克隆 Conversation 必须拥有
     * 自己的 context rows——同 (ownerMessageId, content) 绝不能被 DAO 误判为幂等重放而静默跳过。
     */
    @Test
    fun `fork clone with preserved message ids reloads its own context entries`() = runTest {
        insertConversationHeader()
        val committed = fixture("baseline")
        repository.applyMutation(committed.mutation, committed.executionFacts)

        val forkId = Uuid.random()
        val nodeIdMap = committed.mutation.upsertedNodes.associate { node ->
            node.id to Uuid.random()
        }
        val clonedNodes = committed.mutation.upsertedNodes.map { node ->
            node.copy(id = nodeIdMap.getValue(node.id))
        }
        val clonedEntries = ConversationModelContextApplicability.remapForClone(
            entries = listOf(committed.context),
            nodeIdMap = nodeIdMap,
            messageIdMap = emptyMap(),
            clonedNodes = clonedNodes,
        )
        assertEquals(listOf(committed.context.inlineContextText()), clonedEntries.map { it.inlineContextText() })

        repository.insertConversationSnapshot(
            Conversation.ofId(forkId)
                .copy(messageNodes = clonedNodes)
                .toSnapshot(modelContextEntries = clonedEntries),
        )

        val loaded = repository.getConversationSnapshotById(forkId)
        assertNotNull(loaded)
        assertEquals(clonedEntries, loaded!!.modelContextEntries)
        assertEquals(
            "cloning must not touch the source conversation rows",
            listOf(committed.context),
            repository.getConversationSnapshotById(conversationId)!!.modelContextEntries,
        )
    }

    @Test
    fun `context conflict rolls back new node and turn fact without replacing baseline`() = runTest {
        insertConversationHeader()
        val committed = fixture("baseline")
        repository.applyMutation(committed.mutation, committed.executionFacts)
        val extraNode = MessageNode.of(UIMessage.user("must roll back"))
        val conflictingTurnId = Uuid.random()
        val conflicting = committed.context.copy(payload = disclosurePayload(committed.context.inlineContextText() + " "))
        val conflictMutation = ConversationMutation(
            conversationId = conversationId,
            headerPatch = null,
            upsertedNodes = listOf(extraNode),
            deletedNodeIds = emptyList(),
            updateAt = 2,
            upsertedNodeIndices = listOf(2),
            insertedModelContextEntries = listOf(conflicting),
            indexForSearch = false,
        )
        val conflictFacts = ExecutionFacts(
            turn = turn(conflictingTurnId, committed.context.ownerMessageId),
            toolExecution = null,
            turnOperation = TurnExecutionOperation.START,
        )

        val failure = runCatching { repository.applyMutation(conflictMutation, conflictFacts) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(2, database.messageNodeDao().getNodeHeadersOfConversation(conversationId.toString()).size)
        assertNull(database.turnExecutionDao().getById(conflictingTurnId.toString()))
        assertEquals(
            committed.context.inlineContextText(),
            database.conversationModelContextDao().findById(committed.context.id.toString())?.inlineContextText(),
        )
    }

    /**
     * 端到端成功路径：START 经唯一的 `ConversationCommandCoordinator.startTurn`，
     * Assistant slot 与 turn_execution 在同一个 Room 事务落库；上下文经后续接纳事务提交，
     * 并且只有提交成功后 Runtime snapshot 才携带当前 Turn 的流式投影与新节点。
     */
    @Test
    fun `START commits owner first and request admission atomically preserves retry after failure`() = runTest {
        val user = UIMessage.user("committed request")
        val userNode = MessageNode.of(user)
        repository.insertConversation(
            Conversation.ofId(conversationId).copy(messageNodes = listOf(userNode)),
        )
        val appScope = AppScope(StandardTestDispatcher())
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val coordinator = ConversationCommandCoordinator(
            registry = registry,
            repository = repository,
            recoveryGate = ApplicationRecoveryGate().apply { ready() },
            operationLocks = locks,
        )
        val runtime = coordinator.load(conversationId)
        val turnId = Uuid.random()
        val assistantMessageId = Uuid.random()
        runtime.installTurnWorker(turnId, Job())
        val handle = coordinator.startTurn(
            conversationId,
            TurnTransition.buildStartTurnCommand(
                current = runtime.durable,
                turnId = turnId,

                assistantMessageId = assistantMessageId,
            ),
        )

        val published = runtime.snapshot.value
        assertEquals(assistantMessageId, published.stream?.assistantMessageId)
        assertEquals(2, published.durable.nodes.size)
        assertEquals(emptyList<ConversationModelContextEntry>(), published.durable.modelContextEntries)
        assertEquals(
            TurnExecutionStatus.RUNNING,
            database.turnExecutionDao().getById(turnId.toString())?.status,
        )
        assertEquals(2, database.messageNodeDao().getNodeHeadersOfConversation(conversationId.toString()).size)
        val assistantNode = published.durable.nodes.last()
        val step = assistantNode.currentMessage.parts.filterIsInstance<UIMessagePart.Step>().single()
        val owner = ContextMessageLocator(assistantNode.id, assistantMessageId)
        val anchor = ContextMessageLocator(userNode.id, user.id)
        val entry = ConversationModelContextEntry(owner.nodeId, owner.messageId, anchor.nodeId, anchor.messageId,
            ConversationContextPayload(source = ConversationContextSource.System(listOf(
                SystemContextContribution(SystemContextKind.DOMAIN, "assistant", "stable system"))),
                body = ConversationContextBody.Inline("stable system")), stepId = step.stepId)
        val admission = ConversationContextAdmission(owner, step.stepId, anchor,
            TurnContextSelection(systemEntryId = entry.id, ruleEntryIds = emptyList(), timeReminderEnabled = false, timeZoneId = "UTC"),
            listOf(ConversationContextUse(entry.id, MessageRole.SYSTEM, ContextPlacement.System)))
        val command = AdmitRequestContext(handle, listOf(entry), admission)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_context BEFORE INSERT ON conversation_context_admission BEGIN SELECT RAISE(ABORT, 'injected admission failure'); END")
        val beforeAdmission = runtime.snapshot.value
        val failure = runCatching { coordinator.executeOrThrow(conversationId, command) }.exceptionOrNull()
        assertNotNull(failure)
        org.junit.Assert.assertSame(beforeAdmission, runtime.snapshot.value)
        assertEquals(emptyList<ConversationModelContextEntry>(), repository.getConversationSnapshotById(conversationId)!!.modelContextEntries)
        assertEquals(TurnExecutionStatus.RUNNING, database.turnExecutionDao().getById(turnId.toString())?.status)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_context")
        coordinator.executeOrThrow(conversationId, command)
        coordinator.executeOrThrow(conversationId, command)
        val reopened = repository.getConversationSnapshotById(conversationId)!!
        assertEquals(listOf(entry), reopened.modelContextEntries)
        assertEquals(listOf(admission), reopened.contextAdmissions)
        assertEquals(reopened.modelContextEntries, runtime.durable.modelContextEntries)
        appScope.cancel()
    }

    @Test
    fun `turn insert failure rolls back START slot and runtime publication`() = runTest {
        val userNode = MessageNode.of(UIMessage.user("preserved request"))
        repository.insertConversation(Conversation.ofId(conversationId).copy(messageNodes = listOf(userNode)))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_start BEFORE INSERT ON turn_execution BEGIN SELECT RAISE(ABORT, 'injected turn insert failure'); END",
        )
        val appScope = AppScope(StandardTestDispatcher(testScheduler))
        try {
            val locks = ConversationOperationLocks()
            val registry = ConversationRuntimeRegistry(appScope, repository, locks)
            val coordinator = ConversationCommandCoordinator(
                registry, repository, ApplicationRecoveryGate().apply { ready() }, locks,
            )
            val runtime = coordinator.load(conversationId)
            val turnId = Uuid.random()
            runtime.installTurnWorker(turnId, Job())
            val before = runtime.snapshot.value
            val command = TurnTransition.buildStartTurnCommand(runtime.durable, turnId)

            val failure = runCatching { coordinator.startTurn(conversationId, command) }.exceptionOrNull()

            assertNotNull(failure)
            org.junit.Assert.assertSame(before, runtime.snapshot.value)
            assertEquals(listOf(userNode.id.toString()), database.messageNodeDao()
                .getNodeHeadersOfConversation(conversationId.toString()).map { it.id })
            assertEquals(emptyList<ConversationModelContextEntity>(), database.conversationModelContextDao()
                .getEntriesOfConversation(conversationId.toString()))
            assertNull(database.turnExecutionDao().getById(turnId.toString()))
        } finally {
            appScope.cancel()
        }
    }

    private suspend fun insertConversationHeader() {
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId.toString(),
                assistantId = ConfigurationReference.random().toString(),
                title = "atomic",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
    }

    private fun fixture(answer: String): Fixture {
        val anchor = UIMessage.user("request")
        val owner = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(answer)),
        )
        val anchorNode = MessageNode.of(anchor)
        val ownerNode = MessageNode.of(owner)
        val context = ConversationModelContextEntry(
            ownerNodeId = ownerNode.id,
            ownerMessageId = owner.id,
            anchorNodeId = anchorNode.id,
            anchorMessageId = anchor.id,
            payload = disclosurePayload(canonicalContent()),
        )
        val turnId = Uuid.random()
        return Fixture(
            context = context,
            turnId = turnId,
            mutation = ConversationMutation(
                conversationId = conversationId,
                headerPatch = null,
                upsertedNodes = listOf(anchorNode, ownerNode),
                deletedNodeIds = emptyList(),
                updateAt = 1,
                upsertedNodeIndices = listOf(0, 1),
                insertedModelContextEntries = listOf(context),
                indexForSearch = false,
            ),
            executionFacts = ExecutionFacts(
                turn = turn(turnId, owner.id),
                toolExecution = null,
                turnOperation = TurnExecutionOperation.START,
            ),
        )
    }

    private fun turn(turnId: Uuid, ownerMessageId: Uuid) = TurnExecutionEntity(
        turnId = turnId.toString(),
        conversationId = conversationId.toString(),
        assistantMessageId = ownerMessageId.toString(),
        status = TurnExecutionStatus.RUNNING,
        reason = null,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun canonicalContent(): String = ConversationDisclosureSnapshotService.render(
        ConversationDisclosureSnapshotService.Candidate(
            assistant = net.weero.measix.pilot.data.datastore.Settings().assistants.first(),
            allAssistants = net.weero.measix.pilot.data.datastore.Settings().assistants,
            memories = emptyList(),
        ),
    )

    private data class Fixture(
        val mutation: ConversationMutation,
        val executionFacts: ExecutionFacts,
        val context: ConversationModelContextEntry,
        val turnId: Uuid,
    )
}
