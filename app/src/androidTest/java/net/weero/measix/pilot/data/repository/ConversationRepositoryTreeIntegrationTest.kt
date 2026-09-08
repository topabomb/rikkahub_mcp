package net.weero.measix.pilot.data.repository

import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import net.weero.measix.pilot.service.runtime.toSnapshot
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
            modelContextDAO = database.conversationModelContextDao(),
            artifactStore = artifactStore,
        )
    }

    @After
    fun tearDown() {
        if (::appScope.isInitialized) appScope.cancel()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun summaryTreeCommitRollsBackMasterAndProjectionsWhenChildDeletionFails() = runBlocking<Unit> {
        val assistant = ConfigurationReference.random()
        val owned = artifactStore.createFromBytes(byteArrayOf(1, 2, 3), "summary.txt", origin = ArtifactOrigin.USER)
        val attachment = UIMessagePart.Document(owned.uri.toString(), "summary.txt", "text/plain")
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("request"), attachment)).toMessageNode()
        val answer = UIMessage.assistant("answer").toMessageNode()
        val master = conversation(Uuid.random(), assistant, null)
            .copy(chatSuggestions = listOf("suggestion"), messageNodes = listOf(user, answer))
        val child = conversation(Uuid.random(), assistant, master.id, attachment)
        val defaults = net.weero.measix.pilot.data.datastore.Settings()
        val entry = net.weero.measix.pilot.data.model.ConversationModelContextEntry(
            answer.id, answer.currentMessage.id, user.id, user.currentMessage.id,
            net.weero.measix.pilot.service.ConversationDisclosureSnapshotService.render(
                net.weero.measix.pilot.service.ConversationDisclosureSnapshotService.Candidate(
                    assistant = defaults.assistants.first(), allAssistants = defaults.assistants, memories = emptyList(),
                ),
            ),
        )
        val original = master.toSnapshot(modelContextEntries = listOf(entry))
        repository.insertConversationTree(original, listOf(child.toSnapshot()))
        val favorites = listOf(master.id to user.id, child.id to child.messageNodes.single().id).map { (id, node) ->
            net.weero.measix.pilot.data.db.entity.FavoriteEntity(Uuid.random().toString(), "node",
                "node:$id:$node", "{}", "{}", createdAt = 1, updatedAt = 1)
        }
        favorites.forEach { database.favoriteDao().upsert(it) }
        val expectedMaster = original.copy(nodes = original.nodes.map { it.copy(isFavorite = it.id == user.id) })
        val expectedChild = child.toSnapshot().copy(nodes = child.messageNodes.map { it.copy(isFavorite = true) })
        fun indexedText(): List<String> = database.openHelper.readableDatabase
            .query("SELECT text FROM message_fts ORDER BY text").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        val locks = net.weero.measix.pilot.service.runtime.ConversationOperationLocks()
        val registry = net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() }
        val coordinator = net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator(registry, repository, gate, locks)
        val rootRuntime = coordinator.load(master.id)
        val childRuntime = coordinator.load(child.id)
        val replacements = mapOf(master.id to net.weero.measix.pilot.service.runtime.ReplaceMessageTree(
            listOf(UIMessage.user("summary").toMessageNode()), clearSuggestions = true,
        ))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_child_delete BEFORE DELETE ON ConversationEntity " +
                "WHEN OLD.id = '${child.id}' BEGIN SELECT RAISE(ABORT, 'child deletion failed'); END"
        )
        try {
            coordinator.commitTreeMutation(master.scope, master.id, replacements, setOf(child.id))
            org.junit.Assert.fail("Expected child deletion failure")
        } catch (expected: android.database.SQLException) {
            assertTrue(expected.message.orEmpty().contains("child deletion failed"))
        }
        assertEquals(expectedMaster, repository.getConversationSnapshotById(master.id))
        assertEquals(expectedChild, repository.getConversationSnapshotById(child.id))
        assertEquals(expectedMaster, rootRuntime.durable)
        assertEquals(expectedChild, childRuntime.durable)
        assertEquals(listOf("answer", "request"), indexedText())
        favorites.forEach { assertEquals(it, database.favoriteDao().getByRefKey(it.refKey)) }
        assertEquals(setOf(master.id.toString(), child.id.toString()),
            database.artifactReferenceDao().referencingConversationIds(owned.entity.id).toSet())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_child_delete")
        coordinator.commitTreeMutation(master.scope, master.id, replacements, setOf(child.id))
        val persisted = requireNotNull(repository.getConversationSnapshotById(master.id))
        assertEquals(replacements.getValue(master.id).nodes, persisted.nodes)
        assertTrue(persisted.header.chatSuggestions.isEmpty())
        assertTrue(persisted.modelContextEntries.isEmpty())
        assertEquals(persisted, rootRuntime.durable)
        assertNull(repository.getConversationSnapshotById(child.id))
        assertNull(registry.findRuntime(child.id))
        assertEquals(listOf("summary"), indexedText())
        favorites.forEach { assertNull(database.favoriteDao().getByRefKey(it.refKey)) }
        assertFalse(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))
    }

    @Test
    fun enterpriseConfigurationReferencesRoundTripWithoutChangingConversationOrMessageIds() = runBlocking {
        val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_example"), "alice")
        val assistantId = ConfigurationReference.parse("managed~local~example~dep_example~assistant_review")
        val modelId = ConfigurationReference.parse("managed~local~example~dep_example~mdl_chat")
        val original = conversation(Uuid.random(), assistantId, null).let { conversation ->
            conversation.copy(scope = scope, messageNodes = listOf(UIMessage(
                role = MessageRole.ASSISTANT,
                modelId = modelId,
                parts = listOf(UIMessagePart.Text("reply")),
            ).toMessageNode()))
        }
        repository.insertConversationTree(original.toSnapshot(), emptyList())
        val restored = requireNotNull(repository.getConversationById(original.id))
        assertEquals(original.id, restored.id)
        assertEquals(assistantId, restored.assistantId)
        assertEquals(original.messageNodes.single().messages.single().id, restored.messageNodes.single().messages.single().id)
        assertEquals(modelId, restored.messageNodes.single().messages.single().modelId)
        assertEquals(scope, restored.scope)
        assertEquals(scope, repository.getConversationHeader(original.id)?.scope)
        assertEquals(scope, repository.getConversationSnapshotById(original.id)?.header?.scope)
    }

    @Test
    fun childInsertAndTreeImportRejectAnotherPrincipalWithoutPublishingRows() = runBlocking {
        val authority = EnterpriseAuthority("local:example", "dep_example")
        val alice = ConfigurationScope.Enterprise(authority, "alice")
        val bob = ConfigurationScope.Enterprise(authority, "bob")
        val assistantId = ConfigurationReference.random()
        val master = conversation(Uuid.random(), assistantId, null).copy(scope = alice)
        val child = conversation(Uuid.random(), assistantId, master.id).copy(scope = bob)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.insertConversationTree(master.toSnapshot(), listOf(child.toSnapshot())) }
        }
        assertNull(repository.getConversationById(master.id))
        assertNull(repository.getConversationById(child.id))

        repository.insertConversation(master)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.insertConversation(child) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.insertConversationSnapshot(child.toSnapshot()) }
        }
        assertNull(repository.getConversationById(child.id))
        assertTrue(database.messageNodeDao().getNodeHeadersOfConversation(child.id.toString()).isEmpty())

        repository.insertConversation(child.copy(scope = alice))
        assertEquals(alice, repository.getConversationById(child.id)?.scope)
        assertEquals(master.id, repository.getConversationById(child.id)?.parentConversationId)
    }

    @Test
    fun treeDeleteAndRestoreKeepScopedNodesAndArtifactReferencesAtomic() = runBlocking {
        val masterId = Uuid.random()
        val assistantId = ConfigurationReference.random()
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
        val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "dep_example"), "alice")
        val master = conversation(masterId, assistantId, null, part).copy(scope = scope)
        val child = conversation(Uuid.random(), assistantId, masterId, part).copy(scope = scope)

        repository.insertConversationTree(master.toSnapshot(), listOf(child.toSnapshot()))

        assertNotNull(repository.getConversationById(master.id))
        assertNotNull(repository.getConversationById(child.id))
        assertEquals(
            setOf(master.id.toString(), child.id.toString()),
            database.artifactReferenceDao().referencingConversationIds(owned.entity.id).toSet(),
        )
        val postCommitDiscard = artifactStore.discardUnpublished(owned)
        assertTrue(postCommitDiscard is ArtifactDeleteResult.Failed)
        assertEquals("artifact_already_published", (postCommitDiscard as ArtifactDeleteResult.Failed).reason)

        val locks = net.weero.measix.pilot.service.runtime.ConversationOperationLocks()
        val registry = net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() }
        val coordinator = net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator(registry, repository, gate, locks)
        coordinator.load(master.id)
        coordinator.load(child.id)
        var retention: net.weero.measix.pilot.data.files.ArtifactRetentionLease? = null
        try {
            val deleted = coordinator.withRootTree(scope, master.id) {
                coordinator.deleteCapturingTree(master.id) { tree ->
                    retention = artifactStore.retainNodesForUndo((listOf(tree.root) + tree.children).map { it.nodes })
                }
            }
            assertNull(repository.getConversationById(master.id))
            assertNull(repository.getConversationById(child.id))
            assertNull(registry.findRuntime(master.id))
            assertNull(registry.findRuntime(child.id))
            assertFalse(database.artifactReferenceDao().existsByArtifactId(owned.entity.id))
            assertTrue(artifactStore.file(owned.entity).isFile)

            coordinator.createTree(deleted.root, deleted.children)
            assertEquals(master.toSnapshot(), repository.getConversationSnapshotById(master.id))
            assertEquals(child.toSnapshot(), repository.getConversationSnapshotById(child.id))
            assertEquals(scope, registry.findRuntime(master.id)?.durable?.header?.scope)
            assertEquals(setOf(master.id.toString(), child.id.toString()),
                database.artifactReferenceDao().referencingConversationIds(owned.entity.id).toSet())
        } finally { retention?.close() }
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
        val conversation = conversation(Uuid.random(), ConfigurationReference.random(), null, part)
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
        val conversation = conversation(Uuid.random(), ConfigurationReference.random(), null).copy(
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
        val conversation = conversation(Uuid.random(), ConfigurationReference.random(), null)
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
        val conversation = conversation(Uuid.random(), ConfigurationReference.random(), null)
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
        val stepId = Uuid.random().toString()
        val localCallId = Uuid.random().toString()
        val tool = ToolExecutionEntity(
            executionId = executionId,
            turnId = turnId.toString(),
            stepId = stepId,
            localCallId = localCallId,
            status = ToolExecutionStatus.STARTED,
            reason = null,
            childConversationId = childId,
            createdAt = 11L,
            updatedAt = 11L,
        )
        repository.applyMutation(mutation, ExecutionFacts(null, tool))
        listOf(
            tool.copy(turnId = Uuid.random().toString(), status = ToolExecutionStatus.COMPLETED),
            tool.copy(localCallId = Uuid.random().toString(), status = ToolExecutionStatus.COMPLETED),
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
        val conversation = conversation(Uuid.random(), ConfigurationReference.random(), null)
        repository.insertConversation(conversation)
        val turnId = Uuid.random()
        val running = turn(conversation.id, turnId, TurnExecutionStatus.RUNNING, 10L)
        val mutation = emptyMutation(conversation.id)
        repository.applyMutation(mutation, ExecutionFacts(running, null, TurnExecutionOperation.START))
        val tool = ToolExecutionEntity(
            executionId = Uuid.random().toString(),
            turnId = turnId.toString(),
            stepId = Uuid.random().toString(),
            localCallId = Uuid.random().toString(),
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
        val conversation = conversation(Uuid.random(), ConfigurationReference.random(), null)
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
            stepId = Uuid.random().toString(),
            localCallId = Uuid.random().toString(),
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 11L,
            updatedAt = 11L,
        )
        // Fault injection: emulate a dangling STARTED row beside an already-terminal turn.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO tool_execution " +
                "(execution_id, turn_id, step_id, local_call_id, status, reason, child_conversation_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                tool.executionId,
                tool.turnId,
                tool.stepId,
                tool.localCallId,
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
        assistantId: ConfigurationReference,
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
