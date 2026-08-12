package net.weero.measix.pilot.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设计文档 §13.2 — Migration_3_4_Test
 *
 * 覆盖：
 * - v1/v2 均存在到 v4 的完整历史迁移链；
 * - v3 → v4 additive column/indices 迁移后 parentConversationId == null 且 Conversation/MessageNode 完整；
 * - parent 与 assistant 索引名均与 Room schema 一致（大小写敏感）；
 * - 可插入多个相同 parent/target 的 Child lineage；
 * - 数据完整性：已有会话不丢失。
 */
@RunWith(AndroidJUnit4::class)
class Migration_3_4_Test {
    private val TEST_DB = "migration-test-3-4"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To4_hasCompleteHistoricalPath() {
        helper.createDatabase(TEST_DB, 1).apply { close() }

        helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            Migration_1_2,
            Migration_2_3,
            Migration_3_4,
        ).close()
    }

    @Test
    fun migrate2To4_preservesConversationAndAddsFolderAndParentColumns() {
        val db = helper.createDatabase(TEST_DB, 2)
        val conversationId = "test-conv-v2"
        val values = ContentValues().apply {
            put("id", conversationId)
            put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
            put("title", "Version 2 Conversation")
            put("nodes", "[]")
            put("create_at", 1L)
            put("update_at", 2L)
            put("suggestions", "[]")
            put("is_pinned", 0)
            put("custom_system_prompt", "")
            put("mode_injection_ids", "[]")
            put("workspace_cwd", "")
            put("tags", "[]")
        }
        assertTrue(db.insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, values) != -1L)
        db.close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            Migration_2_3,
            Migration_3_4,
        )
        val cursor = migratedDb.query(
            "SELECT title, folder_id, parent_conversation_id FROM ConversationEntity WHERE id = ?",
            arrayOf(conversationId),
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("Version 2 Conversation", cursor.getString(0))
        assertEquals("", cursor.getString(1))
        assertNull(cursor.getString(2))
        cursor.close()
        migratedDb.close()
    }

    @Test
    fun migrate3To4_addsParentConversationIdColumn() {
        // 创建 v3 数据库
        helper.createDatabase(TEST_DB, 3).apply { close() }

        // 运行迁移到 v4
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migration_3_4)

        // 验证 parent_conversation_id 列存在
        val cursor = db.query("SELECT * FROM ConversationEntity LIMIT 0")
        val columnNames = cursor.columnNames.toList()
        cursor.close()

        assertTrue(
            "ConversationEntity should have 'parent_conversation_id' column after migration",
            columnNames.contains("parent_conversation_id")
        )

        db.close()
    }

    @Test
    fun migrate3To4_indicesHaveCorrectNames() {
        helper.createDatabase(TEST_DB, 3).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migration_3_4)

        // 验证索引存在且名称正确（大小写敏感，与 Room schema 一致）
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='ConversationEntity'")
        val indexNames = mutableListOf<String>()
        while (cursor.moveToNext()) {
            indexNames.add(cursor.getString(0))
        }
        cursor.close()

        assertTrue(
            "Index 'index_ConversationEntity_parent_conversation_id' must exist",
            indexNames.contains("index_ConversationEntity_parent_conversation_id")
        )
        assertTrue(
            "Index 'index_ConversationEntity_assistant_id' must exist",
            indexNames.contains("index_ConversationEntity_assistant_id")
        )

        db.close()
    }

    @Test
    fun migrate3To4_preservesExistingData() {
        // 创建 v3 数据库并插入测试数据
        val db = helper.createDatabase(TEST_DB, 3)

        val conversationId = "test-conv-migrate-001"
        val values = ContentValues().apply {
            put("id", conversationId)
            put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
            put("title", "Test Conversation")
            put("nodes", "[]")
            put("create_at", System.currentTimeMillis())
            put("update_at", System.currentTimeMillis())
            put("suggestions", "[]")
            put("is_pinned", 0)
            put("custom_system_prompt", "")
            put("mode_injection_ids", "[]")
            put("workspace_cwd", "")
            put("tags", "[]")
            put("folder_id", "")
        }
        assertTrue(db.insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, values) != -1L)
        val nodeId = "test-node-migrate-001"
        val nodeValues = ContentValues().apply {
            put("id", nodeId)
            put("conversation_id", conversationId)
            put("node_index", 0)
            put("messages", "[]")
            put("select_index", 0)
        }
        assertTrue(db.insert("message_node", SQLiteDatabase.CONFLICT_NONE, nodeValues) != -1L)
        db.close()

        // 运行迁移
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migration_3_4)

        // 验证数据仍然存在
        val cursor = migratedDb.query(
            "SELECT id, title, parent_conversation_id FROM ConversationEntity WHERE id = ?",
            arrayOf(conversationId)
        )
        assertTrue("Conversation should exist after migration", cursor.moveToFirst())
        assertEquals(conversationId, cursor.getString(0))
        assertEquals("Test Conversation", cursor.getString(1))
        // parent_conversation_id 默认为 null
        assertNull(cursor.getString(2))
        cursor.close()

        val nodeCursor = migratedDb.query(
            "SELECT conversation_id, messages FROM message_node WHERE id = ?",
            arrayOf(nodeId),
        )
        assertTrue("MessageNode should exist after migration", nodeCursor.moveToFirst())
        assertEquals(conversationId, nodeCursor.getString(0))
        assertEquals("[]", nodeCursor.getString(1))
        nodeCursor.close()

        migratedDb.close()
    }

    @Test
    fun migrate3To4_canInsertMultipleChildLineages() {
        helper.createDatabase(TEST_DB, 3).apply { close() }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migration_3_4)

        // 插入两个相同 parent/target 的 Child lineage（不应有唯一约束）
        val baseValues = ContentValues().apply {
            put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
            put("title", "Child")
            put("nodes", "[]")
            put("create_at", System.currentTimeMillis())
            put("update_at", System.currentTimeMillis())
            put("suggestions", "[]")
            put("is_pinned", 0)
            put("custom_system_prompt", "")
            put("mode_injection_ids", "[]")
            put("workspace_cwd", "")
            put("tags", "[]")
            put("folder_id", "")
        }

        val cv1 = ContentValues(baseValues).apply {
            put("id", "child-1")
            put("parent_conversation_id", "master-1")
        }
        val cv2 = ContentValues(baseValues).apply {
            put("id", "child-2")
            put("parent_conversation_id", "master-1")
        }

        assertTrue(db.insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, cv1) != -1L)
        assertTrue(db.insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, cv2) != -1L)

        // 验证两条 Child 都存在
        val cursor = db.query(
            "SELECT id FROM ConversationEntity WHERE parent_conversation_id = ?",
            arrayOf("master-1")
        )
        assertTrue(cursor.count == 2)
        cursor.close()

        db.close()
    }
}
