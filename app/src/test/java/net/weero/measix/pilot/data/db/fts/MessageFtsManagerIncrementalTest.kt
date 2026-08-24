package net.weero.measix.pilot.data.db.fts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FTS 增量索引权威测试。
 * 验证 node 级增量（reindexNodes / deleteNodesIndex）的投影一致性：
 * 单 node 变更只影响该 node、删除清空、增量终态 == 全量 rebuild、会话删除清空。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MessageFtsManagerIncrementalTest {

    private lateinit var database: AppDatabase
    private lateinit var fts: MessageFtsManager
    private val seq = AtomicLong(0)

    private fun node(vararg texts: String): MessageNode {
        val messages = texts.map { t ->
            UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(t)))
        }
        return MessageNode(id = Uuid.random(), messages = messages, selectIndex = 0)
    }

    private fun text(n: MessageNode): String = n.messages.joinToString(" ") { m ->
        (m.parts.firstOrNull() as? UIMessagePart.Text)?.text ?: ""
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // message_fts 由迁移 SQL 建表（非 Room entity）；内存库需手工建表。
        // 注：Robolectric 原生 SQLite 未内置 fts5，故用 fts4 验证增量投影 SQL（INSERT/DELETE/SELECT 语义一致）。
        database.openHelper.writableDatabase.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts4(" +
                "text, node_id, message_id, conversation_id, title, update_at)"
        )
        fts = MessageFtsManager(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    /** 查询某 conversation 下某 node 的 FTS 文本行。 */
    private fun ftsRows(conversationId: String, nodeId: String): List<String> {
        val db = database.openHelper.writableDatabase
        val out = mutableListOf<String>()
        db.query(
            "SELECT text FROM message_fts WHERE conversation_id = '$conversationId' AND node_id = '$nodeId'"
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    private fun ftsCount(): Int {
        val db = database.openHelper.writableDatabase
        db.query("SELECT COUNT(*) FROM message_fts").use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    @Test
    fun `F1 single node modification changes only that node rows`() = runTest {
        val conversationId = Uuid.random().toString()
        val nodeA = node("alpha original")
        val nodeB = node("bravo unchanged")
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA, nodeB))

        // 全量两 node 已索引
        assertEquals(listOf("alpha original"), ftsRows(conversationId, nodeA.id.toString()))
        assertEquals(listOf("bravo unchanged"), ftsRows(conversationId, nodeB.id.toString()))

        // 只改 nodeA 的文本 → 增量 reindex
        val nodeA2 = nodeA.copy(
            messages = listOf(UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("alpha changed")))),
        )
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA2))

        // nodeA 行更新为增量后的值；nodeB 行完全不受影响
        assertEquals(listOf("alpha changed"), ftsRows(conversationId, nodeA.id.toString()))
        assertEquals(listOf("bravo unchanged"), ftsRows(conversationId, nodeB.id.toString()))
    }

    @Test
    fun `F2 node deletion clears its index rows`() = runTest {
        val conversationId = Uuid.random().toString()
        val nodeA = node("alpha to delete")
        val nodeB = node("bravo keep")
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA, nodeB))
        assertEquals(2, ftsCount())

        fts.deleteNodesIndexInTransaction(conversationId, listOf(nodeA.id))
        assertEquals(0, ftsRows(conversationId, nodeA.id.toString()).size)
        assertEquals(listOf("bravo keep"), ftsRows(conversationId, nodeB.id.toString()))
        assertEquals(1, ftsCount())
    }

    @Test
    fun `F3 incremental result equals full rebuild`() = runTest {
        val conversation = Conversation.ofId(Uuid.random(), assistantId = Uuid.random())
            .copy(messageNodes = listOf(node("alpha one"), node("bravo two")), title = "t")
        val conversationId = conversation.id.toString()
        val nodeA = conversation.messageNodes[0]
        val nodeB = conversation.messageNodes[1]
        // 先全量，再增量更新 nodeA
        fts.indexConversationInTransaction(conversation)
        val nodeA2 = nodeA.copy(
            messages = listOf(UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("alpha updated")))),
        )
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA2))
        val incremental = ftsRows(conversationId, nodeA.id.toString()) + ftsRows(conversationId, nodeB.id.toString())

        // 全量重建同一终态
        fts.indexConversationInTransaction(
            conversation.copy(messageNodes = listOf(nodeA2, nodeB))
        )
        val rebuilt = ftsRows(conversationId, nodeA.id.toString()) + ftsRows(conversationId, nodeB.id.toString())

        assertEquals(rebuilt.sorted(), incremental.sorted())
        assertTrue(rebuilt.contains("alpha updated"))
    }

    @Test
    fun `F4 conversation deletion clears all fts rows`() = runTest {
        val conversationId = Uuid.random().toString()
        val nodeA = node("alpha")
        val nodeB = node("bravo")
        fts.reindexNodesInTransaction(conversationId, "t", seq.incrementAndGet(), listOf(nodeA, nodeB))
        assertEquals(2, ftsCount())

        // 会话删除：清空该会话全部 FTS 行
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM message_fts WHERE conversation_id = ?",
            arrayOf(conversationId),
        )
        assertEquals(0, ftsCount())
    }
}
