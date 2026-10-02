package net.weero.measix.pilot.data.db.migrations

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migration v5 to v6: preserved artifact data and defaults, renamed indexes, unique paths and reference cascades. Each fixture validates the exported Room schema. */
@RunWith(AndroidJUnit4::class)
class Migration_5_6Test {
    private val TEST_DB = "migration-test-v5-v6"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /** 创建 v5 库、预置 managed_files 数据，跑迁移到 v6。 */
    private fun migratedWithData(): SupportSQLiteDatabase {
        val db = helper.createDatabase(TEST_DB, 5)
        db.execSQL(
            "INSERT INTO managed_files (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at) " +
                "VALUES ('upload', 'upload/a.png', 'a.png', 'image/png', 10, 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO managed_files (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at) " +
                "VALUES ('upload', 'upload/b.png', 'b.png', 'image/png', 20, 2000, 2000)"
        )
        db.close()
        return helper.runMigrationsAndValidate(TEST_DB, 6, true, Migration_5_6)
    }

    @Test
    fun m1m2_renamedTableRetainsRows_andStateDefaultsToActive() {
        val db = migratedWithData()
        val c = query(db, "SELECT id, folder, relative_path, display_name, size_bytes, state, origin FROM artifact ORDER BY id")
        c.use {
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(0))
            assertEquals("upload", it.getString(1))
            assertEquals("upload/a.png", it.getString(2))
            assertEquals("a.png", it.getString(3))
            assertEquals(10L, it.getLong(4))
            assertEquals("ACTIVE", it.getString(5))
            // 存量行的诞生方式无法回溯，统一默认按用户引入
            assertEquals("USER", it.getString(6))

            assertTrue(it.moveToNext())
            assertEquals(2L, it.getLong(0))
            assertEquals("upload/b.png", it.getString(2))
            assertEquals("ACTIVE", it.getString(5))
            assertEquals("USER", it.getString(6))

            assertTrue(!it.moveToNext())
        }
        val names = mutableSetOf<String>()
        query(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('artifact', 'artifact_reference')").use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        assertTrue("index_artifact_relative_path", "index_artifact_relative_path" in names)
        assertTrue("index_artifact_folder", "index_artifact_folder" in names)
        assertTrue("index_artifact_state", "index_artifact_state" in names)
        assertTrue("index_artifact_reference_artifact_id", "index_artifact_reference_artifact_id" in names)
        assertTrue("index_artifact_reference_node_id", "index_artifact_reference_node_id" in names)
        assertTrue("index_artifact_reference_artifact_id_node_id_reference_type", "index_artifact_reference_artifact_id_node_id_reference_type" in names)
        assertTrue("old relative_path removed", "index_managed_files_relative_path" !in names)
        assertTrue("old folder removed", "index_managed_files_folder" !in names)
        db.close()
    }

    @Test
    fun m6_relativePathRemainsUnique() {
        val db = migratedWithData()
        var thrown: Exception? = null
        try {
            db.execSQL(
                "INSERT INTO artifact (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at) " +
                    "VALUES ('upload', 'upload/a.png', 'dup.png', 'image/png', 1, 1, 1)"
            )
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("unique violation expected", thrown)
        db.close()
    }

    @Test
    fun m4_deletingArtifactCascadesToArtifactReference() {
        val db = migratedWithData()
        // SQLite 默认关闭外键约束；级联验证须显式开启（框架连接不自动 PRAGMA foreign_keys=ON）
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('c1', 't', '[]', 1, 1)")
        db.execSQL("INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('n1', 'c1', 0, '[]', 0)")
        db.execSQL("INSERT INTO artifact_reference (artifact_id, node_id, reference_type) VALUES (1, 'n1', 'ATTACHMENT')")
        assertEquals(1, count(db, "artifact_reference"))
        db.execSQL("DELETE FROM artifact WHERE id = 1")
        assertEquals(0, count(db, "artifact_reference"))
        db.close()
    }

    @Test
    fun m5_deletingMessageNodeCascadesToArtifactReference() {
        val db = migratedWithData()
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('c2', 't', '[]', 1, 1)")
        db.execSQL("INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('n2', 'c2', 0, '[]', 0)")
        db.execSQL("INSERT INTO artifact_reference (artifact_id, node_id, reference_type) VALUES (2, 'n2', 'TOOL_OUTPUT')")
        assertEquals(1, count(db, "artifact_reference"))
        db.execSQL("DELETE FROM message_node WHERE id = 'n2'")
        assertEquals(0, count(db, "artifact_reference"))
        db.close()
    }

    private fun query(db: SupportSQLiteDatabase, sql: String): Cursor =
        db.query(sql, emptyArray<Any?>())

    private fun count(db: SupportSQLiteDatabase, table: String): Int {
        val c = query(db, "SELECT COUNT(*) FROM $table")
        return try {
            c.moveToFirst()
            c.getInt(0)
        } finally {
            c.close()
        }
    }
}
