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

@RunWith(AndroidJUnit4::class)
class Migration_4_5_Test {
    private val testDb = "migration-test-4-5"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate4To5PreservesConversationAndCreatesRecoverableExecutionTables() {
        val conversationId = "conversation-v4"
        helper.createDatabase(testDb, 4).apply {
            val values = ContentValues().apply {
                put("id", conversationId)
                put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                put("title", "Preserved title")
                put("nodes", "[]")
                put("create_at", 1L)
                put("update_at", 2L)
                put("suggestions", "[\"preserved\"]")
                put("is_pinned", 1)
                put("custom_system_prompt", "")
                put("mode_injection_ids", "[]")
                put("workspace_cwd", "")
                put("tags", "[]")
                put("folder_id", "folder-v4")
                putNull("parent_conversation_id")
            }
            assertTrue(insert("ConversationEntity", SQLiteDatabase.CONFLICT_NONE, values) != -1L)
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 5, true, Migration_4_5)
        db.query(
            "SELECT title, suggestions, is_pinned, folder_id FROM ConversationEntity WHERE id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Preserved title", cursor.getString(0))
            assertEquals("[\"preserved\"]", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("folder-v4", cursor.getString(3))
        }

        db.execSQL(
            "INSERT INTO turn_execution " +
                "(turn_id, conversation_id, assistant_message_id, status, reason, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NULL, ?, ?)",
            arrayOf<Any?>("turn-1", conversationId, "assistant-message-1", "RUNNING", 3L, 3L),
        )
        db.execSQL(
            "INSERT INTO tool_execution " +
                "(execution_id, turn_id, tool_ordinal, status, reason, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NULL, ?, ?)",
            arrayOf<Any?>("execution-1", "turn-1", 0, "STARTED", 4L, 4L),
        )
        assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM turn_execution"))
        assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM tool_execution"))

        db.execSQL("DELETE FROM ConversationEntity WHERE id = ?", arrayOf(conversationId))
        assertEquals(0, scalarCount(db, "SELECT COUNT(*) FROM turn_execution"))
        assertEquals(0, scalarCount(db, "SELECT COUNT(*) FROM tool_execution"))
        db.close()
    }

    @Test
    fun migrate1To5HasCompleteHistoricalPath() {
        helper.createDatabase(testDb, 1).close()

        helper.runMigrationsAndValidate(
            testDb,
            5,
            true,
            Migration_1_2,
            Migration_2_3,
            Migration_3_4,
            Migration_4_5,
        ).close()
    }

    private fun scalarCount(database: androidx.sqlite.db.SupportSQLiteDatabase, query: String): Int =
        database.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
