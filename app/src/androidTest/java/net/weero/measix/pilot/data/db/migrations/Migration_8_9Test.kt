package net.weero.measix.pilot.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_8_9Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun indexPreservesDuplicatePathsAndAllExistingValues() {
        val name = "migration-v8-v9-data"
        val old = helper.createDatabase(name, 8)
        val historicalPath = "images/809278de-6677-4bc1-9249-d94c85b0930c.png"
        listOf(historicalPath, historicalPath, "images/OldFile.PNG").forEachIndexed { index, path ->
            old.execSQL(
                "INSERT INTO GenMediaEntity(id, path, model_id, prompt, create_at, type, source_paths) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(index + 1, path, "model-$index", "prompt-$index", 100L + index,
                    "image_generation", "[\"upload/old-$index.png\"]"),
            )
        }
        val beforeRows = rows(old, "SELECT * FROM GenMediaEntity ORDER BY id")
        val beforeTables = tableNames(old)
        val beforeColumns = beforeTables.associateWith { rows(old, "PRAGMA table_info(`$it`)") }
        val beforeForeignKeys = beforeTables.associateWith { rows(old, "PRAGMA foreign_key_list(`$it`)") }
        val beforeUniqueIndexes = beforeTables.associateWith { indexInfo(old, it, uniqueOnly = true) }
        old.close()

        val migrated = helper.runMigrationsAndValidate(name, 9, true, Migration_8_9)
        assertEquals(beforeRows, rows(migrated, "SELECT * FROM GenMediaEntity ORDER BY id"))
        assertEquals(beforeTables, tableNames(migrated))
        beforeTables.forEach { table ->
            assertEquals(beforeColumns[table], rows(migrated, "PRAGMA table_info(`$table`)"))
            assertEquals(beforeForeignKeys[table], rows(migrated, "PRAGMA foreign_key_list(`$table`)"))
            assertEquals(beforeUniqueIndexes[table], indexInfo(migrated, table, uniqueOnly = true))
        }
        migrated.query("PRAGMA index_list(GenMediaEntity)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_GenMediaEntity_path") {
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
                    found = true
                }
            }
            assertTrue(found)
        }
        migrated.query(
            "EXPLAIN QUERY PLAN SELECT EXISTS(SELECT 1 FROM GenMediaEntity WHERE path = ?)",
            arrayOf<Any?>(historicalPath),
        ).use { cursor ->
            var usesIndex = false
            while (cursor.moveToNext()) {
                val detail = cursor.getString(cursor.getColumnIndexOrThrow("detail"))
                usesIndex = usesIndex || ("SEARCH" in detail && "index_GenMediaEntity_path" in detail)
            }
            assertTrue(usesIndex)
        }
        migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        migrated.close()
    }

    @Test
    fun historicalChainReachesFreshV9Schema() {
        val name = "migration-v1-v9-schema"
        helper.createDatabase(name, 1).close()
        val migrated = helper.runMigrationsAndValidate(
            name, 9, true,
            Migration_1_2, Migration_2_3, Migration_3_4, Migration_4_5,
            Migration_5_6, Migration_6_7, Migration_7_8, Migration_8_9,
        )
        val fresh = helper.createDatabase("migration-v9-fresh-schema", 9)
        assertEquals(tableNames(fresh), tableNames(migrated))
        tableNames(fresh).forEach { table ->
            // Historical ALTER TABLE appends columns; their physical order is not a Room contract.
            fun columns(db: SupportSQLiteDatabase) = rows(db, "PRAGMA table_info(`$table`)")
                .associate { column -> requireNotNull(column[1]) to column.drop(2) }
            assertEquals(columns(fresh), columns(migrated))
            assertEquals(indexInfo(fresh, table), indexInfo(migrated, table))
        }
        migrated.close()
        fresh.close()
    }

    @Test
    fun indexedReadPathsAvoidUnnecessarySorting() {
        val name = "migration-v8-v9-query-plans"
        helper.createDatabase(name, 8).close()
        val db = helper.runMigrationsAndValidate(name, 9, true, Migration_8_9)
        val queries = listOf(
            "SELECT * FROM GenMediaEntity ORDER BY create_at DESC LIMIT 20" to "index_GenMediaEntity_create_at",
            "SELECT * FROM GenMediaEntity WHERE create_at <= 100 ORDER BY create_at DESC" to "index_GenMediaEntity_create_at",
            "SELECT * FROM MemoryEntity WHERE assistant_id = 'assistant'" to "index_MemoryEntity_assistant_id",
            "SELECT * FROM message_node WHERE conversation_id = 'conversation' ORDER BY node_index ASC" to
                "index_message_node_conversation_id_node_index",
            "SELECT * FROM artifact WHERE folder = 'upload' AND created_at <= 100 ORDER BY created_at DESC" to
                "index_artifact_folder_created_at",
            "SELECT * FROM artifact WHERE state = 'ACTIVE' AND created_at <= 100" to
                "index_artifact_state_created_at",
            "SELECT * FROM ConversationEntity WHERE parent_conversation_id IS NULL AND assistant_id = 'assistant' " +
                "ORDER BY is_pinned DESC, update_at DESC LIMIT 20" to
                "index_ConversationEntity_assistant_id_parent_conversation_id_is_pinned_update_at",
            "SELECT * FROM ConversationEntity WHERE parent_conversation_id IS NULL AND assistant_id = 'assistant' " +
                "AND folder_id = '' ORDER BY is_pinned DESC, update_at DESC LIMIT 20" to
                "index_ConversationEntity_assistant_id_parent_conversation_id_folder_id_is_pinned_update_at",
            "SELECT * FROM ConversationEntity WHERE parent_conversation_id IS NULL AND folder_id = 'folder' " +
                "ORDER BY is_pinned DESC, update_at DESC LIMIT 20" to
                "index_ConversationEntity_folder_id_parent_conversation_id_is_pinned_update_at",
            "SELECT * FROM ConversationEntity WHERE parent_conversation_id IS NULL AND is_pinned = 1 " +
                "ORDER BY update_at DESC" to "index_ConversationEntity_parent_conversation_id_is_pinned_update_at",
            "SELECT * FROM conversation_folder WHERE assistant_id = 'assistant' ORDER BY sort_index ASC, create_at ASC" to
                "index_conversation_folder_assistant_id_sort_index_create_at",
            "SELECT * FROM favorites WHERE type = 'node' ORDER BY created_at DESC" to "index_favorites_type_created_at",
            "SELECT EXISTS(SELECT 1 FROM artifact_reference WHERE artifact_id = 1)" to
                "index_artifact_reference_artifact_id_node_id_reference_type",
        )
        queries.forEach { (sql, expectedIndex) ->
            val details = buildList {
                db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                }
            }
            assertTrue("$sql: $details", details.any { expectedIndex in it })
            assertFalse("$sql: $details", details.any { "TEMP B-TREE" in it })
        }
        // Both equality prefixes preserve the requested time ordering; the planner owns the choice.
        db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM artifact WHERE folder = 'upload' AND state = 'ACTIVE' " +
                "ORDER BY created_at DESC",
        ).use { cursor ->
            val details = buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
            }
            assertTrue(details.toString(), details.any {
                "index_artifact_folder_created_at" in it || "index_artifact_state_created_at" in it
            })
            assertFalse(details.toString(), details.any { "TEMP B-TREE" in it })
        }
        db.close()
    }

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        rows(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name != 'room_master_table' ORDER BY name").map { requireNotNull(it.single()) }

    private fun indexInfo(
        db: SupportSQLiteDatabase,
        table: String,
        uniqueOnly: Boolean = false,
    ): Map<String, List<List<String?>>> = buildMap {
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                if (uniqueOnly && unique == 0) continue
                val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                put("$indexName:$unique", rows(db, "PRAGMA index_info(`$indexName`)").map { column ->
                    listOf(column[0], column[2])
                })
            }
        }
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = buildList {
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).map { column ->
                    if (cursor.isNull(column)) null else cursor.getString(column)
                })
            }
        }
    }
}
