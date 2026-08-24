package net.weero.measix.pilot.data.db.migrations

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration v6→v7 权威测试。
 *
 * 对齐 Migration_5_6Test 范式。用例：
 *  - M1  孤儿清理：parent 悬挂的 Child 被删除（含其消息节点），正常主/子全保留
 *  - M2  数据完整：nodes 死列删除，其余列逐字段保留
 *  - M3  FK 级联：删 Master → Child → Child 的 message_node 逐级级联消失
 *  - M4  FK 约束：parent 悬挂的 Child 插入被数据库拒绝（孤儿结构性不可能）
 *  - M5  tool_execution.child_conversation_id 列存在且可写
 *  - M6  schema 校验：runMigrationsAndValidate 走 Room 迁移算法
 *  - M7  迁移库与直接 v7 新建库 schema 语义同构
 *  - M8  v5→v7 全链路：managed_files→artifact 改名数据、会话树、消息节点全部无损到达 v7
 */
@RunWith(AndroidJUnit4::class)
class Migration_6_7Test {
    private val TEST_DB = "migration-test-v6-v7"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * 创建 v6 库并预置主/子会话数据（含孤儿 Child 与消息节点），跑迁移到 v7。
     *
     * 预置形态：
     *  - master-1（顶层）+ child-1（parent=master-1）各带 1 个消息节点
     *  - orphan-child（parent=ghost，悬挂）带 1 个消息节点
     *  - turn_execution + tool_execution（master-1 的执行事实）
     */
    private fun migratedWithData(dbName: String = TEST_DB): SupportSQLiteDatabase {
        val db = helper.createDatabase(dbName, 6)
        db.execSQL(
            "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at, suggestions, is_pinned, " +
                "custom_system_prompt, mode_injection_ids, workspace_cwd, tags, folder_id, parent_conversation_id) " +
                "VALUES ('master-1', 'Master', '[]', 1000, 1000, '[\"s1\"]', 0, 'p', '[\"m1\"]', '/w', '[\"t1\"]', 'f1', NULL)"
        )
        db.execSQL(
            "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at, parent_conversation_id) " +
                "VALUES ('child-1', 'Child', '[]', 1001, 1001, 'master-1')"
        )
        db.execSQL(
            "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at, parent_conversation_id) " +
                "VALUES ('orphan-child', 'Orphan', '[]', 1002, 1002, 'ghost')"
        )
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('node-m1', 'master-1', 0, '[]', 0)"
        )
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('node-c1', 'child-1', 0, '[]', 0)"
        )
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('node-orphan', 'orphan-child', 0, '[]', 0)"
        )
        db.execSQL(
            "INSERT INTO turn_execution (turn_id, conversation_id, assistant_message_id, status, reason, created_at, updated_at) " +
                "VALUES ('turn-1', 'master-1', 'msg-1', 'RUNNING', NULL, 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO tool_execution (execution_id, turn_id, tool_ordinal, status, reason, created_at, updated_at) " +
                "VALUES ('exec-1', 'turn-1', 0, 'STARTED', NULL, 1000, 1000)"
        )
        db.close()
        return helper.runMigrationsAndValidate(dbName, 7, true, Migration_6_7)
    }

    @Test
    fun m1_orphanChildPurged_validMasterAndChildRetained() {
        val db = migratedWithData()
        // 孤儿 Child 及其消息节点被清除
        assertEquals(0, count(db, "ConversationEntity", "id = 'orphan-child'"))
        assertEquals(0, count(db, "message_node", "id = 'node-orphan'"))
        // 正常主/子与其消息节点全保留
        assertEquals(1, count(db, "ConversationEntity", "id = 'master-1'"))
        assertEquals(1, count(db, "ConversationEntity", "id = 'child-1'"))
        assertEquals(1, count(db, "message_node", "id = 'node-m1'"))
        assertEquals(1, count(db, "message_node", "id = 'node-c1'"))
        db.close()
    }

    @Test
    fun m2_nodesDropped_allOtherColumnsPreserved() {
        val db = migratedWithData()
        val cols = columns(db, "ConversationEntity")
        assertFalse("nodes 死列应已删除", "nodes" in cols)
        assertEquals(
            setOf(
                "id", "assistant_id", "title", "create_at", "update_at", "suggestions",
                "is_pinned", "custom_system_prompt", "mode_injection_ids", "workspace_cwd",
                "tags", "folder_id", "parent_conversation_id",
            ),
            cols
        )
        query(db, "SELECT * FROM ConversationEntity WHERE id = 'master-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Master", c.getString(c.getColumnIndexOrThrow("title")))
            assertEquals("[\"s1\"]", c.getString(c.getColumnIndexOrThrow("suggestions")))
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("is_pinned")))
            assertEquals("p", c.getString(c.getColumnIndexOrThrow("custom_system_prompt")))
            assertEquals("[\"m1\"]", c.getString(c.getColumnIndexOrThrow("mode_injection_ids")))
            assertEquals("/w", c.getString(c.getColumnIndexOrThrow("workspace_cwd")))
            assertEquals("[\"t1\"]", c.getString(c.getColumnIndexOrThrow("tags")))
            assertEquals("f1", c.getString(c.getColumnIndexOrThrow("folder_id")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("parent_conversation_id")))
            assertEquals(1000L, c.getLong(c.getColumnIndexOrThrow("create_at")))
            assertEquals(1000L, c.getLong(c.getColumnIndexOrThrow("update_at")))
        }
        // 执行事实行保留
        assertEquals(1, count(db, "turn_execution", "turn_id = 'turn-1'"))
        assertEquals(1, count(db, "tool_execution", "execution_id = 'exec-1'"))
        db.close()
    }

    @Test
    fun m3_deletingMasterCascadesToChildAndNodes() {
        val db = migratedWithData()
        db.execSQL("PRAGMA foreign_keys = ON")
        assertEquals(2, count(db, "ConversationEntity")) // master-1 + child-1（孤儿已清）
        assertEquals(2, count(db, "message_node"))       // node-m1 + node-c1
        assertEquals(1, count(db, "turn_execution"))
        assertEquals(1, count(db, "tool_execution"))
        db.execSQL("DELETE FROM ConversationEntity WHERE id = 'master-1'")
        // Child 级联消失 → Child 的消息节点、执行事实逐级级联消失
        assertEquals(0, count(db, "ConversationEntity"))
        assertEquals(0, count(db, "message_node"))
        assertEquals(0, count(db, "turn_execution"))
        assertEquals(0, count(db, "tool_execution"))
        db.close()
    }

    @Test
    fun m4_danglingChildInsertRejectedByForeignKey() {
        val db = migratedWithData()
        db.execSQL("PRAGMA foreign_keys = ON")
        var thrown: Exception? = null
        try {
            db.execSQL(
                "INSERT INTO ConversationEntity (id, title, create_at, update_at, parent_conversation_id) " +
                    "VALUES ('new-dangling', 't', 1, 1, 'not-exist')"
            )
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("外键约束应拒绝悬挂 Child 插入", thrown)
        assertEquals(0, count(db, "ConversationEntity", "id = 'new-dangling'"))
        db.close()
    }

    @Test
    fun m5_toolExecutionChildColumnWritable() {
        val db = migratedWithData()
        val cols = columns(db, "tool_execution")
        assertTrue("child_conversation_id 列应存在", "child_conversation_id" in cols)
        // 既有行新列为 NULL
        query(db, "SELECT child_conversation_id FROM tool_execution WHERE execution_id = 'exec-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
        // 可写入并按索引列查询
        db.execSQL("UPDATE tool_execution SET child_conversation_id = 'child-1' WHERE execution_id = 'exec-1'")
        query(db, "SELECT execution_id FROM tool_execution WHERE child_conversation_id = 'child-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("exec-1", c.getString(0))
        }
        db.close()
    }

    @Test
    fun m6_schemaMatchesV7EntityDeclarations() {
        // runMigrationsAndValidate 已在 migratedWithData() 内部执行（Room 迁移算法校验含 FK/索引/默认值）
        val db = migratedWithData()
        val names = mutableSetOf<String>()
        query(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('ConversationEntity', 'tool_execution')").use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        assertTrue("index_ConversationEntity_parent_conversation_id", "index_ConversationEntity_parent_conversation_id" in names)
        assertTrue("index_ConversationEntity_assistant_id", "index_ConversationEntity_assistant_id" in names)
        assertTrue("index_tool_execution_child_conversation_id", "index_tool_execution_child_conversation_id" in names)
        // FK 声明以 PRAGMA 语义校验
        query(db, "PRAGMA foreign_key_list(ConversationEntity)").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("parent_conversation_id", c.getString(c.getColumnIndexOrThrow("from")))
            assertEquals("id", c.getString(c.getColumnIndexOrThrow("to")))
            assertEquals("CASCADE", c.getString(c.getColumnIndexOrThrow("on_delete")))
        }
        db.close()
    }

    @Test
    fun m7_migratedSchemaEqualsFreshV7Schema() {
        val migratedDb = migratedWithData("migration-test-v7-migrated")
        val freshDb = helper.createDatabase("migration-test-v7-fresh", 7)
        assertEquals("migrated vs fresh v7 tables", dumpTables(freshDb), dumpTables(migratedDb))
        assertEquals("migrated vs fresh v7 indices", dumpIndices(freshDb), dumpIndices(migratedDb))
        migratedDb.close()
        freshDb.close()
    }

    /**
     * v5→v7 全链路：从 v5 库（managed_files 时代）一路迁移到 v7。
     * 验证 artifact 改名数据、会话表数据、消息节点在两级迁移后全部无损，且 v7 关系约束生效。
     */
    @Test
    fun m8_v5ToV7FullChainPreservesAllData() {
        val db = helper.createDatabase("migration-test-v5-v7", 5)
        db.execSQL(
            "INSERT INTO managed_files (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at) " +
                "VALUES ('upload', 'upload/a.png', 'a.png', 'image/png', 10, 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at, parent_conversation_id) " +
                "VALUES ('chain-master', 'Chain Master', '[{\"legacy\":\"payload\"}]', 10, 20, NULL)"
        )
        db.execSQL(
            "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at, parent_conversation_id) " +
                "VALUES ('chain-child', 'Chain Child', '[]', 11, 21, 'chain-master')"
        )
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('chain-node', 'chain-master', 0, '[{\"id\":\"m\"}]', 0)"
        )
        db.close()

        // 5 → 6 → 7 全链路（Room 按版本顺序执行两个 migration 并做 schema 校验）
        val migrated = helper.runMigrationsAndValidate(
            "migration-test-v5-v7", 7, true, Migration_5_6, Migration_6_7
        )

        // v5→v6：artifact 改名数据无损
        query(migrated, "SELECT relative_path, state FROM artifact ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("upload/a.png", c.getString(0))
            assertEquals("ACTIVE", c.getString(1))
            assertFalse(c.moveToNext())
        }
        // v6→v7：会话与子会话数据无损（legacy nodes JSON 随死列丢弃，属预期）
        query(migrated, "SELECT title, parent_conversation_id FROM ConversationEntity WHERE id = 'chain-master'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Chain Master", c.getString(0))
            assertTrue(c.isNull(1))
        }
        query(migrated, "SELECT title, parent_conversation_id FROM ConversationEntity WHERE id = 'chain-child'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Chain Child", c.getString(0))
            assertEquals("chain-master", c.getString(1))
        }
        // 消息节点无损
        assertEquals(1, count(migrated, "message_node", "id = 'chain-node'"))
        // v7 关系约束在链式迁移库上同样生效
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("DELETE FROM ConversationEntity WHERE id = 'chain-master'")
        assertEquals(0, count(migrated, "ConversationEntity", "id = 'chain-child'"))
        assertEquals(0, count(migrated, "message_node", "id = 'chain-node'"))
        migrated.close()
    }

    /**
     * M9 历史悬挂行清理：FK 从未启用期间应用层删除未级联的残留
     * （conversation 已删但残留的 message_node / node 已删但残留的 artifact_reference）
     * 在迁移中被清零；正常数据全保留。
     */
    @Test
    fun m9_danglingNodesAndReferencesPurged_validDataRetained() {
        val db = helper.createDatabase("migration-test-v6-dangling", 6)
        db.execSQL(
            "INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at, parent_conversation_id) " +
                "VALUES ('keep-master', 'Keep', '[]', 1000, 1000, NULL)"
        )
        // 正常节点 + 引用（artifact 需存在，v6 的 artifact 表）
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('keep-node', 'keep-master', 0, '[]', 0)"
        )
        db.execSQL(
            "INSERT INTO artifact (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at, state) " +
                "VALUES ('upload', 'upload/keep.png', 'keep.png', 'image/png', 1, 1000, 1000, 'ACTIVE')"
        )
        db.execSQL(
            "INSERT INTO artifact_reference (artifact_id, node_id, reference_type) " +
                "VALUES ((SELECT id FROM artifact WHERE relative_path = 'upload/keep.png'), 'keep-node', 'ATTACHMENT')"
        )
        // 悬挂行：conversation 已删除的节点 + node 已删除的引用（FK OFF 时代残留）
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('dangling-node', 'deleted-conversation', 0, '[]', 0)"
        )
        db.execSQL(
            "INSERT INTO artifact_reference (artifact_id, node_id, reference_type) " +
                "VALUES ((SELECT id FROM artifact WHERE relative_path = 'upload/keep.png'), 'dangling-node-2', 'ATTACHMENT')"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "migration-test-v6-dangling", 7, true, Migration_6_7
        )
        // 悬挂行清零
        assertEquals(0, count(migrated, "message_node", "id = 'dangling-node'"))
        assertEquals(0, count(migrated, "artifact_reference", "node_id = 'dangling-node-2'"))
        // 正常数据全保留
        assertEquals(1, count(migrated, "message_node", "id = 'keep-node'"))
        assertEquals(1, count(migrated, "artifact_reference", "node_id = 'keep-node'"))
        assertEquals(1, count(migrated, "artifact", "relative_path = 'upload/keep.png'"))
        migrated.close()
    }

    /**
     * M10 upsert 不触发级联：FK ON 下对已存在节点做 IGNORE+UPDATE upsert，
     * 该节点的 artifact_reference 行必须保留（REPLACE 的 DELETE 语义会级联清引用，
     * 在事务提交与引用重建之间留下 GC 误删窗口——v7 修复的行为锁定）。
     */
    @Test
    fun m10_nodeUpsertDoesNotCascadeReferenceRows() {
        val db = migratedWithData("migration-test-upsert-cascade")
        db.execSQL("PRAGMA foreign_keys = ON")
        // 前置：artifact 行（引用行的 FK 目标）
        db.execSQL(
            "INSERT INTO artifact (folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at, state) " +
                "VALUES ('upload', 'upload/u.png', 'u.png', 'image/png', 1, 1000, 1000, 'ACTIVE')"
        )
        db.execSQL(
            "INSERT INTO artifact_reference (artifact_id, node_id, reference_type) " +
                "VALUES ((SELECT id FROM artifact WHERE relative_path = 'upload/u.png'), 'node-m1', 'ATTACHMENT')"
        )
        // 对已存在节点执行 INSERT OR IGNORE + UPDATE（MessageNodeDAO.upsertAll 的实际 SQL 语义）
        db.execSQL(
            "INSERT OR IGNORE INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('node-m1', 'master-1', 0, '[{\"id\":\"updated\"}]', 0)"
        )
        db.execSQL(
            "UPDATE message_node SET messages = '[{\"id\":\"updated\"}]' WHERE id = 'node-m1'"
        )
        // IGNORE+UPDATE 路径：引用行必须保留（INSERT OR REPLACE 的 DELETE 语义会级联清引用）
        assertEquals(1, count(db, "artifact_reference", "node_id = 'node-m1'"))
        db.close()
    }

    private fun query(db: SupportSQLiteDatabase, sql: String): Cursor =
        db.query(sql, emptyArray<Any?>())

    private fun count(db: SupportSQLiteDatabase, table: String, where: String? = null): Int {
        val sql = if (where != null) "SELECT COUNT(*) FROM $table WHERE $where" else "SELECT COUNT(*) FROM $table"
        val c = query(db, sql)
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
        return cols.sorted()
    }

    private fun dumpIndices(db: SupportSQLiteDatabase): Map<String, String> {
        val indices = mutableMapOf<String, String>()
        val tables = mutableListOf<String>()
        query(
            db,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' AND name != 'room_master_table' AND name != 'message_fts' ORDER BY name"
        ).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
        tables.forEach { table ->
            val indexEntries = mutableListOf<Pair<String, Boolean>>()
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
