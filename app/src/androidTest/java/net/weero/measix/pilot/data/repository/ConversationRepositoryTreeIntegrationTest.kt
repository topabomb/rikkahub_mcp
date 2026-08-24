package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactSettingsCoordinator
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.ConversationHeaderPatch
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import net.weero.measix.pilot.service.runtime.TurnExecutionOperation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Real Room tests for the repository's internal, transaction-only persistence boundary. */
@RunWith(AndroidJUnit4::class)
class ConversationRepositoryTreeIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var artifactStore: ArtifactStore
    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS message_fts(text, node_id, message_id, conversation_id, title, update_at)"
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
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun treeInsertAndMasterDeleteKeepConversationNodesAndArtifactReferencesAtomic() = runBlocking {
        val masterId = Uuid.random()
        val assistantId = Uuid.random()
        val owned = artifactStore.createFromBytes(
            byteArrayOf(1, 2, 3),
            "tree.txt",
            origin = ArtifactOrigin.USER,
        )
        val part = UIMessagePart.Document(
            url = owned.uri.toString(),
            fileName = "tree.txt",
            mime = "text/plain",
        )
        val master = conversation(masterId, assistantId, null, part)
        val child = conversation(Uuid.random(), assistantId, masterId, part)

        repository.insertConversationTree(master, listOf(child))

        assertNotNull(repository.getConversationById(master.id))
        assertNotNull(repository.getConversationById(child.id))
        assertEquals(
            setOf(master.id.toString(), child.id.toString()),
            database.artifactReferenceDao().referencingConversationIds(owned.entity.id).toSet(),
        )
        val postCommitDiscard = artifactStore.discardUnpublished(owned)
        assertTrue(postCommitDiscard is ArtifactDeleteResult.Failed)
        assertEquals("artifact_already_published", (postCommitDiscard as ArtifactDeleteResult.Failed).reason)

        repository.deleteConversation(master.id)

        assertNull(repository.getConversationById(master.id))
        assertNull(repository.getConversationById(child.id))
        assertFalse(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))
        assertTrue(artifactStore.file(owned.entity).isFile)
    }

    @Test
    fun messagePublicationQueuedBeforeGarbageCollectionWinsTheLifecycleLock() = runBlocking {
        artifactStore.ensureReferenceProjection()
        val owned = artifactStore.createFromBytes(
            byteArrayOf(4, 5, 6),
            "race.txt",
            origin = ArtifactOrigin.USER,
        )
        val part = UIMessagePart.Document(
            url = owned.uri.toString(),
            fileName = "race.txt",
            mime = "text/plain",
        )
        val conversation = conversation(Uuid.random(), Uuid.random(), null, part)
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            artifactStore.withLifecycleLock {
                acquired.complete(Unit)
                release.await()
            }
        }
        acquired.await()
        val publish = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository.insertConversation(conversation)
        }
        val gc = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            artifactStore.collectGarbage(0)
        }

        release.complete(Unit)
        holder.await()
        publish.await()

        assertTrue(gc.await().isEmpty())
        assertTrue(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))
        assertTrue(artifactStore.file(owned.entity).isFile)
    }

    @Test
    fun checkpointMutationDoesNotOverwriteConcurrentNarrowHeaderWrites() = runBlocking {
        val conversation = conversation(Uuid.random(), Uuid.random(), null).copy(
            title = "stale-title",
            chatSuggestions = listOf("stale-suggestion"),
        )
        repository.insertConversation(conversation)
        val newFolderId = Uuid.random()
        database.conversationDao().updateTitle(conversation.id.toString(), "new-title")
        database.conversationDao().updatePinStatus(conversation.id.toString(), true)
        database.conversationDao().updateFolderId(conversation.id.toString(), newFolderId.toString())
        val checkpointNodes = listOf(
            conversation.messageNodes.single().copy(messages = listOf(UIMessage.user("checkpoint-message")))
        )

        repository.applyMutation(
            ConversationMutation(
                conversationId = conversation.id,
                headerPatch = ConversationHeaderPatch(),
                deletedNodeIds = emptyList(),
                upsertedNodes = checkpointNodes,
                updateAt = 9_999L,
                upsertedNodeIndices = listOf(0),
            ),
        )

        val entity = requireNotNull(database.conversationDao().getConversationById(conversation.id.toString()))
        assertEquals("new-title", entity.title)
        assertTrue(entity.isPinned)
        assertEquals(newFolderId.toString(), entity.folderId)
        assertEquals(9_999L, entity.updateAt)
        assertEquals("checkpoint-message", repository.getConversationById(conversation.id)?.currentMessages?.single()?.toText())
    }

    @Test
    fun executionFactsUseInsertAndCasAndCannotReopenATerminalTurn() = runBlocking {
        val conversation = conversation(Uuid.random(), Uuid.random(), null)
        repository.insertConversation(conversation)
        val turnId = Uuid.random()
        val running = turn(conversation.id, turnId, TurnExecutionStatus.RUNNING, 10L)
        val mutation = emptyMutation(conversation.id)

        assertTrue(repository.applyMutation(mutation, ExecutionFacts(running, null, TurnExecutionOperation.START)))
        val completed = running.copy(status = TurnExecutionStatus.COMPLETED, updatedAt = 20L)
        assertTrue(repository.applyMutation(mutation, ExecutionFacts(completed, null)))
        assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecution(turnId.toString())?.status)

        assertThrows(ExecutionStateConflictException::class.java) {
            runBlocking {
                repository.applyMutation(mutation, ExecutionFacts(running.copy(updatedAt = 30L), null))
            }
        }
        assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecution(turnId.toString())?.status)
    }

    @Test
    fun turnAndToolTransitionsRequireTheExactDurableOwnerAndTerminalFacts() = runBlocking {
        val conversation = conversation(Uuid.random(), Uuid.random(), null)
        repository.insertConversation(conversation)
        val turnId = Uuid.random()
        val assistantMessageId = Uuid.random()
        val running = turn(
            conversationId = conversation.id,
            turnId = turnId,
            status = TurnExecutionStatus.RUNNING,
            now = 10L,
            assistantMessageId = assistantMessageId,
        )
        val mutation = emptyMutation(conversation.id)
        repository.applyMutation(mutation, ExecutionFacts(running, null, TurnExecutionOperation.START))

        listOf(
            running.copy(
                conversationId = Uuid.random().toString(),
                status = TurnExecutionStatus.FAILED,
                reason = "wrong-conversation",
            ),
            running.copy(
                assistantMessageId = Uuid.random().toString(),
                status = TurnExecutionStatus.FAILED,
                reason = "wrong-message",
            ),
        ).forEach { conflicting ->
            assertThrows(ExecutionStateConflictException::class.java) {
                runBlocking { repository.applyMutation(mutation, ExecutionFacts(conflicting, null)) }
            }
        }
        assertEquals(TurnExecutionStatus.RUNNING, repository.getTurnExecution(turnId.toString())?.status)

        val childId = Uuid.random().toString()
        val executionId = Uuid.random().toString()
        val tool = ToolExecutionEntity(
            executionId = executionId,
            turnId = turnId.toString(),
            toolOrdinal = 2,
            status = ToolExecutionStatus.STARTED,
            reason = null,
            childConversationId = childId,
            createdAt = 11L,
            updatedAt = 11L,
        )
        repository.applyMutation(mutation, ExecutionFacts(null, tool))
        listOf(
            tool.copy(turnId = Uuid.random().toString(), status = ToolExecutionStatus.COMPLETED),
            tool.copy(toolOrdinal = 3, status = ToolExecutionStatus.COMPLETED),
            tool.copy(childConversationId = null, status = ToolExecutionStatus.COMPLETED),
            tool.copy(childConversationId = Uuid.random().toString(), status = ToolExecutionStatus.COMPLETED),
        ).forEach { conflicting ->
            assertThrows(ExecutionStateConflictException::class.java) {
                runBlocking { repository.applyMutation(mutation, ExecutionFacts(null, conflicting)) }
            }
        }
        assertEquals(ToolExecutionStatus.STARTED, database.toolExecutionDao().getById(tool.executionId)?.status)

        val completedTool = tool.copy(
            status = ToolExecutionStatus.COMPLETED,
            reason = "completed",
            updatedAt = 12L,
        )
        repository.applyMutation(mutation, ExecutionFacts(null, completedTool))
        assertThrows(ExecutionStateConflictException::class.java) {
            runBlocking {
                repository.applyMutation(
                    mutation,
                    ExecutionFacts(null, completedTool.copy(reason = "different-terminal-fact")),
                )
            }
        }
        assertEquals("completed", database.toolExecutionDao().getById(tool.executionId)?.reason)

        val failed = running.copy(
            status = TurnExecutionStatus.FAILED,
            reason = "provider-failed",
            updatedAt = 20L,
        )
        repository.applyMutation(mutation, ExecutionFacts(failed, null))
        assertThrows(ExecutionStateConflictException::class.java) {
            runBlocking {
                repository.applyMutation(
                    mutation,
                    ExecutionFacts(failed.copy(reason = "different-terminal-fact"), null),
                )
            }
        }
        assertEquals("provider-failed", repository.getTurnExecution(turnId.toString())?.reason)
    }

    @Test
    fun terminalTurnClosesStartedToolsInTheSameTransaction() = runBlocking {
        val conversation = conversation(Uuid.random(), Uuid.random(), null)
        repository.insertConversation(conversation)
        val turnId = Uuid.random()
        val running = turn(conversation.id, turnId, TurnExecutionStatus.RUNNING, 10L)
        val mutation = emptyMutation(conversation.id)
        repository.applyMutation(mutation, ExecutionFacts(running, null, TurnExecutionOperation.START))
        val tool = ToolExecutionEntity(
            executionId = Uuid.random().toString(),
            turnId = turnId.toString(),
            toolOrdinal = 0,
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 11L,
            updatedAt = 11L,
        )
        repository.applyMutation(mutation, ExecutionFacts(null, tool))

        val completed = running.copy(
            status = TurnExecutionStatus.COMPLETED,
            reason = "done",
            updatedAt = 20L,
        )
        repository.applyMutation(mutation, ExecutionFacts(completed, null))

        assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecution(turnId.toString())?.status)
        assertEquals(ToolExecutionStatus.UNKNOWN, database.toolExecutionDao().getById(tool.executionId)?.status)
        assertEquals("done", database.toolExecutionDao().getById(tool.executionId)?.reason)
    }

    @Test
    fun failedTerminalTurnCasRollsBackStartedToolClosure() = runBlocking {
        val conversation = conversation(Uuid.random(), Uuid.random(), null)
        repository.insertConversation(conversation)
        val turnId = Uuid.random()
        val running = turn(conversation.id, turnId, TurnExecutionStatus.RUNNING, 10L)
        val mutation = emptyMutation(conversation.id)
        repository.applyMutation(mutation, ExecutionFacts(running, null, TurnExecutionOperation.START))
        repository.applyMutation(
            mutation,
            ExecutionFacts(running.copy(status = TurnExecutionStatus.COMPLETED, updatedAt = 12L), null),
        )
        val tool = ToolExecutionEntity(
            executionId = Uuid.random().toString(),
            turnId = turnId.toString(),
            toolOrdinal = 0,
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 11L,
            updatedAt = 11L,
        )
        // Fault injection: emulate a dangling STARTED row beside an already-terminal turn.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO tool_execution " +
                "(execution_id, turn_id, tool_ordinal, status, reason, child_conversation_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                tool.executionId,
                tool.turnId,
                tool.toolOrdinal,
                tool.status.name,
                tool.reason,
                tool.childConversationId,
                tool.createdAt,
                tool.updatedAt,
            ),
        )
        val conflicting = running.copy(
            assistantMessageId = Uuid.random().toString(),
            status = TurnExecutionStatus.FAILED,
            reason = "should-rollback",
            updatedAt = 20L,
        )

        assertThrows(ExecutionStateConflictException::class.java) {
            runBlocking { repository.applyMutation(mutation, ExecutionFacts(conflicting, null)) }
        }

        assertEquals(TurnExecutionStatus.COMPLETED, repository.getTurnExecution(turnId.toString())?.status)
        assertEquals(ToolExecutionStatus.STARTED, database.toolExecutionDao().getById(tool.executionId)?.status)
    }

    private fun emptyMutation(conversationId: Uuid) = ConversationMutation(
        conversationId = conversationId,
        headerPatch = null,
        upsertedNodes = emptyList(),
        deletedNodeIds = emptyList(),
        updateAt = 1L,
        upsertedNodeIndices = emptyList(),
    )

    private fun turn(
        conversationId: Uuid,
        turnId: Uuid,
        status: TurnExecutionStatus,
        now: Long,
        assistantMessageId: Uuid = Uuid.random(),
    ) = TurnExecutionEntity(
        turnId = turnId.toString(),
        conversationId = conversationId.toString(),
        assistantMessageId = assistantMessageId.toString(),
        status = status,
        reason = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun conversation(
        id: Uuid,
        assistantId: Uuid,
        parentId: Uuid?,
        part: UIMessagePart = UIMessagePart.Text("message"),
    ) = Conversation(
        id = id,
        assistantId = assistantId,
        parentConversationId = parentId,
        title = if (parentId == null) "Master" else "Child",
        messageNodes = listOf(UIMessage(role = MessageRole.USER, parts = listOf(part)).toMessageNode()),
    )
}
