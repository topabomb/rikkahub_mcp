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
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.toMessageNode
import org.junit.After
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
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
        )
        repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            filesManager = filesManager,
            messageFtsManager = MessageFtsManager(database),
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
        assertTrue(sharedFile.exists())

        repository.deleteConversation(outside)
        assertFalse(sharedFile.exists())
    }

    @Test
    fun treeUpdateRequiresAndCommitsAnExactChildPartition() = runBlocking {
        val assistantId = Uuid.random()
        val master = conversation(Uuid.random(), assistantId, null)
        val retained = conversation(Uuid.random(), assistantId, master.id)
        val deleted = conversation(Uuid.random(), assistantId, master.id)
        repository.insertConversationTree(master, listOf(retained, deleted))

        val invalidResult = runCatching {
            repository.updateConversationTree(
                master = master,
                retainedChildren = listOf(retained),
                deletedChildren = emptyList(),
            )
        }
        assertTrue(invalidResult.isFailure)
        assertNotNull(repository.getConversationById(retained.id))
        assertNotNull(repository.getConversationById(deleted.id))

        val truncatedRetained = retained.copy(messageNodes = retained.messageNodes.take(1))
        repository.updateConversationTree(
            master = master,
            retainedChildren = listOf(truncatedRetained),
            deletedChildren = listOf(deleted),
        )

        assertNotNull(repository.getConversationById(retained.id))
        assertNull(repository.getConversationById(deleted.id))
    }

    @Test
    fun deleteUnreferencedChatFilesRemovesOnlyUnsharedCompressedFiles() = runBlocking {
        val assistantId = Uuid.random()
        val unshared = createTestFile("unshared")
        val shared = createTestFile("shared")
        val conversation = conversation(
            id = Uuid.random(),
            assistantId = assistantId,
            parentId = null,
            part = UIMessagePart.Document(
                url = unshared.toUri().toString(),
                fileName = unshared.name,
                mime = "text/plain",
            ),
        ).let { current ->
            current.copy(
                messageNodes = current.messageNodes + UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Document(
                            url = shared.toUri().toString(),
                            fileName = shared.name,
                            mime = "text/plain",
                        ),
                    ),
                ).toMessageNode(),
            )
        }
        val other = conversation(
            id = Uuid.random(),
            assistantId = assistantId,
            parentId = null,
            part = UIMessagePart.Document(
                url = shared.toUri().toString(),
                fileName = shared.name,
                mime = "text/plain",
            ),
        )
        repository.insertConversation(conversation)
        repository.insertConversation(other)

        val reduced = conversation.copy(
            messageNodes = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("summary"))).toMessageNode(),
            ),
        )
        repository.updateConversation(reduced)
        repository.deleteUnreferencedChatFiles(
            previousFiles = conversation.files.toSet(),
            retainedConversations = listOf(reduced),
            excludedConversationIds = setOf(conversation.id),
        )

        assertFalse(unshared.exists())
        assertTrue(shared.exists())
    }

    @Test
    fun deleteUnreferencedChatFilesKeepsMetadataArtifactReferences() = runBlocking {
        val assistantId = Uuid.random()
        val artifact = createTestFile("artifact")
        val url = artifact.toUri().toString()
        val relativePath = "upload/${artifact.name}"
        val withImage = Conversation(
            id = Uuid.random(),
            assistantId = assistantId,
            title = "Master",
            messageNodes = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-1",
                            toolName = "generate_image",
                            input = "{}",
                            output = listOf(UIMessagePart.Image(url = url)),
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
            ),
        )
        repository.insertConversation(withImage)

        val metadataOnly = withImage.copy(
            messageNodes = listOf(
                UIMessage(
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
            ),
        )
        repository.updateConversation(metadataOnly)
        repository.deleteUnreferencedChatFiles(
            previousFiles = withImage.files.toSet(),
            retainedConversations = listOf(metadataOnly),
            excludedConversationIds = setOf(withImage.id),
        )

        assertTrue(artifact.exists())
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
