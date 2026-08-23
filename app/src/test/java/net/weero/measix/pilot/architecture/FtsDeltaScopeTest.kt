package net.weero.measix.pilot.architecture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FTS 增量投影范围契约测试。
 * 单节点修改 → message_fts 变更行仅属该节点；未修改节点行保持不变。
 * （投影一致性的 delta 范围锁定，见架构不变式 4。）
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FtsDeltaScopeTest {

    private lateinit var database: AppDatabase
    private lateinit var fts: MessageFtsManager
    private val seq = AtomicLong(0)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Robolectric 原生 SQLite 未内置 fts5，用 fts4 验证增量 SQL 语义
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

    private fun node(vararg texts: String): MessageNode {
        val messages = texts.map { t ->
            UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(t)))
        }
        return MessageNode(id = Uuid.random(), messages = messages, selectIndex = 0)
    }

    private fun ftsNodeTexts(conversationId: String, nodeId: String): List<String> {
        val out = mutableListOf<String>()
        database.openHelper.writableDatabase.query(
            "SELECT text FROM message_fts WHERE conversation_id = '$conversationId' AND node_id = '$nodeId'"
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    @Test
    fun `I6 single node change leaves other node rows unchanged`() = runTest {
        val conversationId = Uuid.random().toString()
        val nodeA = node("alpha original")
        val nodeB = node("bravo stable")
        fts.reindexNodes(conversationId, "t", seq.incrementAndGet(), listOf(nodeA, nodeB))

        val nodeA2 = nodeA.copy(
            messages = listOf(UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("alpha updated")))),
        )
        fts.reindexNodes(conversationId, "t", seq.incrementAndGet(), listOf(nodeA2))

        // 变更行仅属 nodeA；nodeB 行原样
        assertTrue(ftsNodeTexts(conversationId, nodeA.id.toString()).contains("alpha updated"))
        assertEquals(listOf("bravo stable"), ftsNodeTexts(conversationId, nodeB.id.toString()))
    }
}
