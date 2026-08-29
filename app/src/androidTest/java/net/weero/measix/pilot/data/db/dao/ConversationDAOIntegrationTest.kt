package net.weero.measix.pilot.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Room/SQLite 验证普通会话查询与 Child 查询的隔离。 */
@RunWith(AndroidJUnit4::class)
class ConversationDAOIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ConversationDAO

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.conversationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun normalQueriesExcludeChildWhileControlledQueriesIncludeIt() = runBlocking {
        val assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
        val master = conversation(
            id = "00000000-0000-0000-0000-000000000101",
            assistantId = assistantId,
            title = "Visible matching conversation",
            folderId = "00000000-0000-0000-0000-000000000201",
            isPinned = true,
        )
        val child = conversation(
            id = "00000000-0000-0000-0000-000000000102",
            assistantId = assistantId,
            title = "Hidden matching conversation",
            folderId = master.folderId,
            isPinned = true,
            parentConversationId = master.id,
        )
        dao.insert(master)
        dao.insert(child)

        assertEquals(listOf(master.id), dao.getConversationsOfAssistant(assistantId).first().map { it.id })
        assertEquals(listOf(master.id), dao.getRecentConversationsOfAssistant(assistantId, 10).map { it.id })
        assertEquals(listOf(master.id), dao.getPinnedConversations().first().map { it.id })
        assertEquals(listOf(master.id), dao.getAllIds())
        assertEquals(1, dao.countAll())
        assertEquals(listOf(master.id), loadIds(dao.getConversationsOfFolderPaging(master.folderId)))

        assertEquals(listOf(child.id), dao.getChildConversations(master.id).map { it.id })
        assertEquals(setOf(master.id, child.id), dao.getAllConversations().map { it.id }.toSet())
    }

    @Test
    fun visibleMessageCountsExcludeChildWhileTokenUsageIncludesIt() = runBlocking {
        val master = conversation(
            id = "00000000-0000-0000-0000-000000000301",
            assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e",
            title = "Master",
            folderId = "",
            isPinned = false,
        )
        val child = conversation(
            id = "00000000-0000-0000-0000-000000000302",
            assistantId = master.assistantId,
            title = "Child",
            folderId = "",
            isPinned = false,
            parentConversationId = master.id,
        )
        dao.insert(master)
        dao.insert(child)
        val messageNodeDao = database.messageNodeDao()
        messageNodeDao.insertAll(
            listOf(
                node(
                    id = "master-node",
                    conversationId = master.id,
                    inputTokens = 10,
                    outputTokens = 2,
                    cacheReadInputTokens = 3,
                ),
                node(
                    id = "child-node",
                    conversationId = child.id,
                    inputTokens = 20,
                    outputTokens = 4,
                    cacheReadInputTokens = 5,
                ),
            )
        )

        val stats = messageNodeDao.getTokenStats()
        assertEquals(1, stats.totalMessages)
        assertEquals(30L, stats.inputTokens)
        assertEquals(6L, stats.outputTokens)
        assertEquals(8L, stats.cacheReadInputTokens)
        assertEquals(1, messageNodeDao.getMessageCountPerDay("2026-01-01").sumOf { it.count })
    }

    @Test
    fun boundedSlicesReconstructLargeUnicodeMessagePayloadExactly() = runBlocking {
        val conversation = conversation(
            id = "00000000-0000-0000-0000-000000000401",
            assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e",
            title = "Large payload",
            folderId = "",
            isPinned = false,
        )
        dao.insert(conversation)
        val payload = "[\"" + "🙂中文".repeat(300_000) + "\"]"
        val nodeId = "large-unicode-node"
        val messageNodeDao = database.messageNodeDao()
        messageNodeDao.insertAll(
            listOf(MessageNodeEntity(
                id = nodeId,
                conversationId = conversation.id,
                nodeIndex = 0,
                messages = payload,
                selectIndex = 0,
            ))
        )

        val header = messageNodeDao.getNodeHeadersOfConversation(conversation.id).single()
        var consumed = 0
        var start = 1
        val reconstructed = buildString(payload.length) {
            while (consumed < header.messagesLength) {
                val slice = requireNotNull(messageNodeDao.getMessagesSlice(nodeId, start, 128 * 1024))
                append(slice)
                val characters = slice.codePointCount(0, slice.length)
                consumed += characters
                start += characters
            }
        }

        assertEquals(payload, reconstructed)
    }

    @Test
    fun messageNodeUpsertCannotMoveAPrimaryKeyBetweenConversations() = runBlocking {
        val first = conversation(
            id = "00000000-0000-0000-0000-000000000501",
            assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e",
            title = "First",
            folderId = "",
            isPinned = false,
        )
        val second = first.copy(
            id = "00000000-0000-0000-0000-000000000502",
            title = "Second",
        )
        dao.insert(first)
        dao.insert(second)
        val messageNodeDao = database.messageNodeDao()
        val original = MessageNodeEntity(
            id = "shared-node-id",
            conversationId = first.id,
            nodeIndex = 0,
            messages = "[]",
            selectIndex = 0,
        )
        messageNodeDao.insertAll(listOf(original))

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                messageNodeDao.upsertAll(listOf(original.copy(conversationId = second.id, nodeIndex = 1)))
            }
        }

        assertEquals(listOf(original), messageNodeDao.getNodesOfConversation(first.id))
        assertEquals(emptyList<MessageNodeEntity>(), messageNodeDao.getNodesOfConversation(second.id))
    }

    private suspend fun loadIds(source: PagingSource<Int, LightConversationEntity>): List<String> {
        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false,
            )
        )
        return (result as PagingSource.LoadResult.Page).data.map { it.id }
    }

    private fun conversation(
        id: String,
        assistantId: String,
        title: String,
        folderId: String,
        isPinned: Boolean,
        parentConversationId: String? = null,
    ) = ConversationEntity(
        id = id,
        assistantId = assistantId,
        title = title,
        createAt = System.currentTimeMillis(),
        updateAt = System.currentTimeMillis(),
        chatSuggestions = "[]",
        isPinned = isPinned,
        customSystemPrompt = "",
        modeInjectionIds = "[]",
        workspaceCwd = "",
        tags = "[]",
        folderId = folderId,
        parentConversationId = parentConversationId,
    )

    private fun node(
        id: String,
        conversationId: String,
        inputTokens: Int,
        outputTokens: Int,
        cacheReadInputTokens: Int,
    ) = MessageNodeEntity(
        id = id,
        conversationId = conversationId,
        nodeIndex = 0,
        messages = """[{"role":"user","createdAt":"2026-08-12T10:00:00","usage":{"promptTokens":$inputTokens,"completionTokens":$outputTokens,"cachedTokens":$cacheReadInputTokens}}]""",
        selectIndex = 0,
    )
}
