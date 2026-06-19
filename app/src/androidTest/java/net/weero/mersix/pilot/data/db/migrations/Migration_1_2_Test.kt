package net.weero.mersix.pilot.data.db.migrations

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.mersix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration_1_2 样本测试
 *
 * 验证 v1→v2 迁移（新增 conversations.tags 列）的正确性：
 * 1. schema 正确性：tags 列存在且有默认值
 * 2. 数据完整性：已有对话数据不丢失，tags 自动填充默认值
 */
@RunWith(AndroidJUnit4::class)
class Migration_1_2_Test {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_addsTagsColumnWithCorrectSchema() {
        // 创建版本 1 的数据库
        helper.createDatabase(TEST_DB, 1).apply {
            close()
        }

        // 运行迁移到版本 2
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migration_1_2)

        // 验证 tags 列存在
        val cursor = db.query("SELECT * FROM conversations LIMIT 0")
        val columnNames = cursor.columnNames.toList()
        cursor.close()

        assertTrue("conversations table should have 'tags' column", columnNames.contains("tags"))

        db.close()
    }

    @Test
    fun migrate1To2_preservesExistingDataAndSetsDefaultTags() {
        // 创建版本 1 的数据库并插入测试数据
        val db = helper.createDatabase(TEST_DB, 1)

        val conversationId = "test-conv-001"
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
        }
        db.insert("conversations", null, values)
        db.close()

        // 运行迁移到版本 2
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migration_1_2)

        // 验证数据仍然存在
        val cursor = migratedDb.query(
            "SELECT id, title, tags FROM conversations WHERE id = ?",
            arrayOf(conversationId)
        )
        assertTrue("Conversation should exist after migration", cursor.moveToFirst())
        assertEquals(conversationId, cursor.getString(0))
        assertEquals("Test Conversation", cursor.getString(1))
        assertEquals("[]", cursor.getString(2)) // tags 默认值
        cursor.close()

        migratedDb.close()
    }
}
