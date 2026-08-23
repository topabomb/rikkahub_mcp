package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import io.mockk.every
import io.mockk.mockk
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.ConversationHeaderPatch
import net.weero.measix.pilot.service.runtime.ConversationMutation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationRepositoryTreeIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var repository: ConversationRepository
    private val testFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_fts(
                text,
                node_id,
                message_id,
                conversation_id,
                title,
                update_at
            )
            """.trimIndent()
        )
        appScope = AppScope()
        val filesManager = FilesManager(
            context = context,
            artifactDAO = database.artifactDao(),
            appScope = appScope,
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns kotlinx.coroutines.flow.MutableStateFlow(Settings())
        val artifactStore = ArtifactStore(
            filesManager = filesManager,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsStore = settingsStore,
        )
        repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            filesManager = filesManager,
            messageFtsManager = MessageFtsManager(database),
            turnExecutionDAO = database.turnExecutionDao(),
            toolExecutionDAO = database.toolExecutionDao(),
            artifactStore = artifactStore,
        )
    }

    @After
    fun tearDown() {
        testFiles.forEach { it.delete() }
        if (::appScope.isInitialized) appScope.cancel()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun masterTreeDeleteIsAtomicAndPreservesFilesReferencedOutsideTree() = runBlocking {
        val assistantId = Uuid.random()
        val masterId = Uuid.random()
        val sharedFile = createTestFile()
        val sharedPart = UIMessagePart.Document(
            url = sharedFile.toUri().toString(),
            fileName = sharedFile.name,
            mime = "text/plain",
        )
        val master = conversation(masterId, assistantId, null, sharedPart)
        val child = conversation(Uuid.random(), assistantId, masterId, sharedPart)
        val outside = conversation(Uuid.random(), assistantId, null, sharedPart)

        repository.insertConversation(outside)
        repository.insertConversationTree(master, listOf(child))
        repository.deleteConversation(master)

        assertNull(repository.getConversationById(master.id))
        assertNull(repository.getConversationById(child.id))
        assertNotNull(repository.getConversationById(outside.id))
        // 文件清理走 GC：引用投影级联清除后由
        // collectUnreferencedArtifacts 兜底回收（保护窗内保留）
        assertTrue(sharedFile.exists())

        repository.deleteConversation(outside)
        // 保护窗内不回收；零窗 GC 验证最终回收
        assertTrue(sharedFile.exists())
        repository.collectUnreferencedArtifacts(protectionWindowMillis = 0)
        assertFalse(sharedFile.exists())
    }

    @Test
    fun childRetentionShrinksRetainedAndDeletesRemovedChildren() = runBlocking {
        val assistantId = Uuid.random()
        val master = conversation(Uuid.random(), assistantId, null)
        val retained = conversation(Uuid.random(), assistantId, master.id)
        val deleted = conversation(Uuid.random(), assistantId, master.id)
        repository.insertConversationTree(master, listOf(retained, deleted))

        val truncatedRetained = retained.copy(messageNodes = retained.messageNodes.take(1))
        repository.updateChildRetention(
            retainedChildren = listOf(truncatedRetained),
            deletedChildren = listOf(deleted),
        )

        assertNotNull(repository.getConversationById(retained.id))
        assertNull(repository.getConversationById(deleted.id))
    }

    @Test
    fun gcKeepsMetadataArtifactReferencesAndReleasesUnsharedFiles() = runBlocking {
        val assistantId = Uuid.random()
        val unshared = createTestFile("unshared")
        val artifact = createTestFile("artifact")
        val url = artifact.toUri().toString()
        val relativePath = "upload/${artifact.name}"
        val conversation = conversation(
            id = Uuid.random(),
            assistantId = assistantId,
            parentId = null,
            part = UIMessagePart.Document(
                url = unshared.toUri().toString(),
                fileName = unshared.name,
                mime = "text/plain",
            ),
        )
        val withArtifactMetadata = conversation.copy(
            messageNodes = conversation.messageNodes + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "generate_image",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("""{"status":"completed"}""")),
                        metadata = kotlinx.serialization.json.buildJsonObject {
                            put(
                                "artifact",
                                kotlinx.serialization.json.buildJsonObject {
                                    put("version", 1)
                                    put("relativePath", relativePath)
                                    put("mimeType", "image/jpeg")
                                },
                            )
                        },
                    ),
                ),
            ).toMessageNode(),
        )
        repository.insertConversation(withArtifactMetadata)

        // 压缩：树替换为 summary（unshared 附件节点消失，metadata-only 引用保留）
        val reduced = conversation.copy(
            messageNodes = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("summary"))).toMessageNode(),
            ),
        )
        repository.updateConversation(reduced)
        // 引用投影替换后，零窗 GC：metadata 相对路径引用阻止回收
        repository.collectUnreferencedArtifacts(protectionWindowMillis = 0)

        assertFalse(unshared.exists())
        assertTrue(artifact.exists())
    }

    @Test
    fun applyMutationCheckpointPreservesNarrowColumnWritesMadeAfterSnapshot() = runBlocking {
        val conversation = conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            parentId = null,
        ).copy(
            title = "stale-title",
            chatSuggestions = listOf("stale-suggestion"),
            folderId = null,
            isPinned = false,
        )
        repository.insertConversation(conversation)

        val checkpointNodes = listOf(UIMessage.user("checkpoint-message").toMessageNode())
        val headerPatch = ConversationHeaderPatch()
        database.conversationDao().updateTitle(conversation.id.toString(), "new-title")
        database.conversationDao().updatePinStatus(conversation.id.toString(), true)
        database.conversationDao().updateFolderId(conversation.id.toString(), "new-folder")
        database.conversationDao().updateChatSuggestions(conversation.id.toString(), "[\"new-suggestion\"]")

        repository.applyMutation(
            ConversationMutation(
                conversationId = conversation.id,
                headerPatch = headerPatch,
                deletedNodeIds = emptyList(),
                upsertedNodes = checkpointNodes,
                updateAt = 9_999L,
            ),
            executionFacts = null,
        )

        val entity = requireNotNull(database.conversationDao().getConversationById(conversation.id.toString()))
        assertEquals("new-title", entity.title)
        assertTrue(entity.isPinned)
        assertEquals("new-folder", entity.folderId)
        assertEquals("[\"new-suggestion\"]", entity.chatSuggestions)
        assertEquals(9_999L, entity.updateAt)
        val restored = requireNotNull(repository.getConversationById(conversation.id))
        assertEquals("checkpoint-message", restored.currentMessages.single().toText())
    }

    @Test
    fun recoveryAtomicallyMarksRunningTurnsAndStartedToolsUnknown() = runBlocking {
        val conversation = conversation(Uuid.random(), Uuid.random(), null)
        repository.insertConversation(conversation)
        val turnId = Uuid.random().toString()
        val executionId = "$turnId:0"
        val runningTurn = TurnExecutionEntity(
            turnId = turnId,
            conversationId = conversation.id.toString(),
            assistantMessageId = Uuid.random().toString(),
            status = TurnExecutionStatus.RUNNING,
            reason = null,
            createdAt = 10L,
            updatedAt = 10L,
        )
        val startedTool = ToolExecutionEntity(
            executionId = executionId,
            turnId = turnId,
            toolOrdinal = 0,
            status = ToolExecutionStatus.STARTED,
            reason = null,
            createdAt = 11L,
            updatedAt = 11L,
        )
        repository.upsertTurnExecution(runningTurn)
        repository.upsertToolExecution(startedTool)
        val recoverable = repository.getRecoverableTurnExecutionsByConversation()
        assertEquals(listOf(turnId), recoverable[conversation.id]?.map { it.turnId })

        // Updating the parent through Room @Upsert must not REPLACE it and cascade-delete its tools.
        repository.upsertTurnExecution(
            requireNotNull(repository.getTurnExecution(turnId)).copy(updatedAt = 12L)
        )
        assertNotNull(repository.getToolExecution(executionId))

        val awaitingTurnId = Uuid.random().toString()
        val awaitingExecutionId = "$awaitingTurnId:0"
        repository.upsertTurnExecution(
            runningTurn.copy(
                turnId = awaitingTurnId,
                status = TurnExecutionStatus.AWAITING_APPROVAL,
            )
        )
        repository.upsertToolExecution(
            startedTool.copy(
                executionId = awaitingExecutionId,
                turnId = awaitingTurnId,
            )
        )

        val recovered = repository.recoverInterruptedExecutions(updatedAt = 20L)

        assertEquals(1, recovered.turns)
        assertEquals(1, recovered.tools)
        val turn = requireNotNull(repository.getTurnExecution(turnId))
        assertEquals(TurnExecutionStatus.INTERRUPTED, turn.status)
        assertEquals("process_restarted", turn.reason)
        assertEquals(20L, turn.updatedAt)
        val tool = requireNotNull(repository.getToolExecution(executionId))
        assertEquals(ToolExecutionStatus.UNKNOWN, tool.status)
        assertEquals("process_restarted", tool.reason)
        assertEquals(20L, tool.updatedAt)
        assertEquals(
            TurnExecutionStatus.AWAITING_APPROVAL,
            repository.getTurnExecution(awaitingTurnId)?.status,
        )
        assertEquals(
            ToolExecutionStatus.STARTED,
            repository.getToolExecution(awaitingExecutionId)?.status,
        )
    }

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
        messageNodes = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(part)).toMessageNode(),
        ),
    )

    private fun createTestFile(prefix: String = "subassistant-repository-"): File {
        val uploadDir = File(context.filesDir, "upload").apply { mkdirs() }
        return File.createTempFile(prefix, ".txt", uploadDir).also {
            it.writeText("shared")
            testFiles += it
        }
    }
}
