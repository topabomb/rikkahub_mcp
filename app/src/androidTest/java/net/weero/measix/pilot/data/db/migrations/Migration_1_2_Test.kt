package net.weero.measix.pilot.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migration v1 to v2: exported schema validation and preservation of conversation data with default tags. */
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
        assertTrue(
            db.insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, values) != -1L
        )
        db.close()

        // 运行迁移到版本 2
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migration_1_2)

        // 验证数据仍然存在
        val cursor = migratedDb.query(
            "SELECT id, title, tags FROM ConversationEntity WHERE id = ?",
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
