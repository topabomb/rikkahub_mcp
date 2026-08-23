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

/**
 * Migration v5→v6 权威测试。
 *
 * 对齐上游范式：androidTest 插桩测试，用 [MigrationTestHelper] 从插桩测试 assets 读取
 * schema JSON（build.gradle.kts 中 androidTest.assets.srcDirs("$projectDir/schemas")）。
 * 用例：
 *  - M1/M2 RENAME 后数据与列完整保留，新列 state 默认 'ACTIVE'、origin 默认 'USER'
 *  - M3   既有索引随表改名（index_artifact_relative_path/folder），旧名移除，新增 state 索引
 *  - M4   artifact_reference 双 FK 级联（删 artifact 行 / 删 message_node 行均清理引用）
 *  - M5   runMigrationsAndValidate schema 校验（v5→v6 走 Room 迁移算法）
 *  - M6   relative_path 唯一性保留 + system_meta put→get 往返
 *  - M7   schema 校验通过 + 表结构符合 v6 实体声明
 *  - M8   迁移库与直接 v6 新建库 schema 语义同构
 */
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
        db.close()
    }

    @Test
    fun m3_indicesRenamed_andNewStateIndexPresent_oldNamesGone() {
        val db = migratedWithData()
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

    @Test
    fun m7_schemaMatchesV6EntityDeclarations() {
        // runMigrationsAndValidate 已在 migratedWithData() 内部执行（Room 迁移算法校验）
        val db = migratedWithData()
        val artifactCols = columns(db, "artifact")
        assertEquals(
            setOf(
                "id", "folder", "relative_path", "display_name", "mime_type",
                "size_bytes", "created_at", "updated_at", "state", "origin",
            ),
            artifactCols
        )
        val refCols = columns(db, "artifact_reference")
        assertEquals(setOf("rowId", "artifact_id", "node_id", "reference_type"), refCols)
        val metaCols = columns(db, "system_meta")
        assertEquals(setOf("key", "value"), metaCols)
        db.close()
    }

    @Test
    fun m6_systemMetaPutGetRoundTrip() {
        val db = migratedWithData()
        val key = "artifact_reference_backfilled"
        val value = "true"
        db.execSQL("INSERT INTO system_meta (key, value) VALUES (?, ?)", arrayOf(key, value))
        val c = query(db, "SELECT value FROM system_meta WHERE key = '$key'")
        c.use {
            assertTrue(it.moveToFirst())
            assertEquals(value, it.getString(0))
            assertTrue(!it.moveToNext())
        }
        db.close()
    }

    @Test
    fun m8_migratedSchemaEqualsFreshV6Schema() {
        val migratedDb = migratedWithData()
        val freshDb = helper.createDatabase("migration-test-v6-fresh", 6)
        assertEquals("migrated vs fresh v6 tables", dumpTables(freshDb), dumpTables(migratedDb))
        assertEquals("migrated vs fresh v6 indices", dumpIndices(freshDb), dumpIndices(migratedDb))
        migratedDb.close()
        freshDb.close()
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

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val cols = mutableSetOf<String>()
        query(db, "PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) cols.add(c.getString(c.getColumnIndexOrThrow("name")))
        }
        return cols
    }

    private fun dumpTables(db: SupportSQLiteDatabase): Map<String, List<String>> {
        val tables = mutableListOf<String>()
        query(
            db,
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                "AND name != 'room_master_table' AND name != 'message_fts' " +
                "ORDER BY name"
        ).use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        return tables.associateWith { table -> tableStructure(db, table) }
    }

    private fun tableStructure(db: SupportSQLiteDatabase, table: String): List<String> {
        val cols = mutableListOf<String>()
        query(db, "PRAGMA table_info($table)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            val typeIdx = c.getColumnIndexOrThrow("type")
            val notNullIdx = c.getColumnIndexOrThrow("notnull")
            val pkIdx = c.getColumnIndexOrThrow("pk")
            while (c.moveToNext()) {
                cols.add("${c.getString(nameIdx)}:${c.getString(typeIdx)}:${c.getInt(notNullIdx)}:${c.getInt(pkIdx)}")
            }
        }
        return cols
    }

    private fun dumpIndices(db: SupportSQLiteDatabase): Map<String, String> {
        val indices = mutableMapOf<String, String>()
        // 用 PRAGMA 语义提取（不受 DDL 文本格式差异影响）：对每个 user 表取 index_list + index_info
        val tables = mutableListOf<String>()
        query(
            db,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' AND name != 'room_master_table' AND name != 'message_fts' ORDER BY name"
        ).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
        tables.forEach { table ->
            val indexEntries = mutableListOf<Pair<String, Boolean>>() // (name, unique)
            query(db, "PRAGMA index_list($table)").use { c ->
                val nameIdx = c.getColumnIndexOrThrow("name")
                val uniqueIdx = c.getColumnIndexOrThrow("unique")
                while (c.moveToNext()) indexEntries.add(c.getString(nameIdx) to (c.getInt(uniqueIdx) != 0))
            }
            indexEntries.sortedBy { it.first }.forEach { (indexName, unique) ->
                val cols = mutableListOf<String>()
                query(db, "PRAGMA index_info($indexName)").use { c ->
                    val colIdx = c.getColumnIndexOrThrow("name")
                    while (c.moveToNext()) cols.add(c.getString(colIdx))
                }
                indices[indexName] = "$table|unique=$unique|cols=${cols.sorted()}"
            }
        }
        return indices
    }
}
