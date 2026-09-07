package net.weero.measix.pilot.data.db.migrations

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Room acceptance for `Migration_10_11`. The payload mapping matrix lives in
 * the pure [net.weero.measix.pilot.data.db.transcript.LegacyTurnTranscriptMigratorTest]; this test
 * only proves the durable contracts that need a real SQLite/Room schema: the migrated v11 schema is
 * byte-isomorphic with a fresh v11, the `transcript_schema` sentinel lands on 3, the two
 * `turn_execution` status tokens are rewritten, `tool_execution` is rebuilt from a positional
 * ordinal onto the `(turn_id, local_call_id)` locator that matches the converted transcript, and a
 * replay of the migration is a no-op.
 */
@RunWith(AndroidJUnit4::class)
class Migration_10_11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // `turn_execution.assistant_message_id` and the assistant transcript message id are parsed as
    // `Uuid` by the migration, so the fixtures must carry real UUIDs that match each other.
    private val assistantMessageId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val unmappableAssistantMessageId = "16fd2706-8baf-433b-82eb-8f7fada847da"

    @Test
    fun historicalChainReachesFreshV11Schema() {
        val name = "migration-v1-v11-schema"
        context.deleteDatabase(name)
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('old-c','old title','[]',11,12)")
            db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES('old-n','old-c',0,?,0)",
                arrayOf(legacyAssistantTranscript()))
            db.execSQL("INSERT INTO MemoryEntity(assistant_id,content) VALUES('old-a','preserved memory')")
            db.execSQL("INSERT INTO managed_files(folder,relative_path,display_name,mime_type,size_bytes,created_at,updated_at) " +
                "VALUES('upload','upload/old.png','old.png','image/png',123,11,12)")
            db.execSQL("INSERT INTO GenMediaEntity(path,model_id,prompt,create_at) VALUES('images/old.png','old-model','old prompt',11)")
        }
        val migrated = helper.runMigrationsAndValidate(
            name, 11, true,
            Migration_1_2, Migration_2_3, Migration_3_4, Migration_4_5, Migration_5_6,
            Migration_6_7, Migration_7_8, Migration_8_9, Migration_9_10, Migration_10_11,
        )
        assertEquals("old title", single(migrated, "SELECT title FROM ConversationEntity WHERE id = 'old-c'"))
        assertEquals("preserved memory", single(migrated, "SELECT content FROM MemoryEntity"))
        assertEquals("upload/old.png", single(migrated, "SELECT relative_path FROM artifact"))
        assertEquals("images/old.png", single(migrated, "SELECT path FROM GenMediaEntity"))
        assertTrue(single(migrated, "SELECT messages FROM message_node WHERE id = 'old-n'").contains("call_1"))
        assertTrue(rows(migrated, "PRAGMA foreign_key_check").isEmpty())
        val fresh = helper.createDatabase("migration-v11-fresh-schema", 11)
        assertEquals(tableNames(fresh), tableNames(migrated))
        tableNames(fresh).forEach { table ->
            fun columns(db: SupportSQLiteDatabase) = rows(db, "PRAGMA table_info(`$table`)")
                .associate { column -> requireNotNull(column[1]) to column.drop(2) }
            assertEquals(columns(fresh), columns(migrated))
            assertEquals(indexInfo(fresh, table), indexInfo(migrated, table))
            assertEquals(foreignKeyInfo(fresh, table), foreignKeyInfo(migrated, table))
        }
        migrated.close()
        fresh.close()
        context.deleteDatabase(name)
    }

    @Test
    fun transcriptSentinelStatusRewriteAndToolLocatorRebuild() {
        val name = "migration-v10-v11-contracts"
        context.deleteDatabase(name)
        val v10 = helper.createDatabase(name, 10)
        seedV10(v10)
        v10.close()

        val db = helper.runMigrationsAndValidate(name, 11, true, Migration_10_11)

        // Every transcript row carries the fail-closed sentinel value 3.
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM message_node WHERE transcript_schema != 3"))
        assertTrue(scalar(db, "SELECT COUNT(*) FROM message_node") > 0)

        // The two status tokens move; a user-pending Turn is never marked terminal here.
        assertEquals("AWAITING_USER", single(db, "SELECT status FROM turn_execution WHERE turn_id = 'turn-await'"))
        assertEquals("INTERRUPTED", single(db, "SELECT status FROM turn_execution WHERE turn_id = 'turn-created'"))
        assertEquals(
            "schema_upgrade",
            single(db, "SELECT reason FROM turn_execution WHERE turn_id = 'turn-created'"),
        )

        // tool_execution is rebuilt onto the locator; the positional ordinal is gone.
        val toolColumns = rows(db, "PRAGMA table_info(`tool_execution`)").map { it[1] }
        assertTrue("step_id", "step_id" in toolColumns)
        assertTrue("local_call_id", "local_call_id" in toolColumns)
        assertFalse("tool_ordinal must be physically dropped", "tool_ordinal" in toolColumns)
        // The only explicit UNIQUE index is the rebuilt locator. SQLite's incidental auto-index for
        // the inline `PRIMARY KEY(execution_id)` is excluded (it exists identically in a fresh v11).
        assertEquals(
            setOf("index_tool_execution_turn_id_local_call_id:1"),
            indexInfo(db, "tool_execution").keys
                .filter { it.endsWith(":1") && !it.startsWith("sqlite_autoindex") }
                .toSet(),
        )
        // The rebuilt locator must match the tool the transcript conversion produced for ordinal 0.
        // execution_id is normalized to the runtime scheme "tool:<local_call_id>", so locate by turn.
        val transcriptToolCallId = convertedToolLocalCallId(db)
        assertEquals(
            transcriptToolCallId,
            single(db, "SELECT local_call_id FROM tool_execution WHERE turn_id = 'turn-await'"),
        )
        assertEquals(
            "tool:$transcriptToolCallId",
            single(db, "SELECT execution_id FROM tool_execution WHERE turn_id = 'turn-await'"),
        )
        assertTrue(foreignKeyInfo(db, "tool_execution").values.all { it == "turn_execution:CASCADE" })
        assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrationReplayIsANoOp() {
        val name = "migration-v10-v11-idempotent"
        context.deleteDatabase(name)
        val v10 = helper.createDatabase(name, 10)
        seedV10(v10)
        v10.close()
        helper.runMigrationsAndValidate(name, 11, true, Migration_10_11).close()

        val reopened = helper.createDatabase(name, 11)
        val before = rows(reopened, "SELECT execution_id, turn_id, step_id, local_call_id, status FROM tool_execution ORDER BY execution_id")
        // Re-invoking migrate on an already-v11 schema must not rebuild or lose the execution facts.
        Migration_10_11.migrate(reopened)
        val after = rows(reopened, "SELECT execution_id, turn_id, step_id, local_call_id, status FROM tool_execution ORDER BY execution_id")
        assertEquals(before, after)
        reopened.close()
        context.deleteDatabase(name)
    }

    @Test
    fun startedToolExecutionWithoutTranscriptToolFailsMigration() {
        val name = "migration-v10-v11-unmappable"
        context.deleteDatabase(name)
        val v10 = helper.createDatabase(name, 10)
        v10.execSQL(
            "INSERT INTO ConversationEntity(id, assistant_id, title, create_at, update_at, suggestions, " +
                "is_pinned, custom_system_prompt, mode_injection_ids, workspace_cwd, tags, folder_id, " +
                "parent_conversation_id) VALUES ('conv-u','assistant-1','t',1,1,'[]',0,'','[]','','','',NULL)",
        )
        // Assistant message carries only Text — there is no Tool at ordinal 0 to map onto.
        v10.execSQL(
            "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES " +
                "('node-a','conv-u',0,'[{\"id\":\"$unmappableAssistantMessageId\",\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"text\":\"x\"}]}]',0)",
        )
        v10.execSQL(
            "INSERT INTO turn_execution(turn_id, conversation_id, assistant_message_id, status, reason, created_at, updated_at) VALUES " +
                "('turn-r','conv-u','$unmappableAssistantMessageId','RUNNING',NULL,1,1)",
        )
        v10.execSQL(
            "INSERT INTO tool_execution(execution_id, turn_id, tool_ordinal, status, reason, child_conversation_id, created_at, updated_at) VALUES " +
                "('exec-u','turn-r',0,'STARTED',NULL,NULL,1,1)",
        )
        // A STARTED execution on a live turn that cannot be mapped to a transcript Tool must
        // fail the migration rather than silently drop the durable execution fact.
        v10.close()
        assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(name, 11, true, Migration_10_11)
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { unchanged ->
            assertEquals(10, unchanged.version)
            unchanged.rawQuery("SELECT messages FROM message_node", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getString(0).contains("\"type\":\"step\""))
            }
            unchanged.rawQuery("SELECT tool_ordinal FROM tool_execution", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun largeTranscriptSurvivesCursorWindowAndToolMapping() {
        val name = "migration-large-transcript"
        context.deleteDatabase(name)
        val payload = "😀 retained output ".repeat(200_000)
        helper.createDatabase(name, 10).use { db ->
            seedV10(db)
            db.execSQL("UPDATE message_node SET messages = ? WHERE id = 'node-assistant'",
                arrayOf(legacyAssistantTranscript().replace("\"r\"", "\"$payload\"")))
        }
        helper.runMigrationsAndValidate(name, 11, true, Migration_10_11).use { db ->
            val converted = net.weero.measix.pilot.data.db.transcript.readTranscriptPayload(db, "node-assistant")
            assertTrue(converted.contains(payload))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM tool_execution"))
            assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
        }
        context.deleteDatabase(name)
    }

    @Test
    fun rebuildRecoversEachDurableBoundaryAndRejectsWrongTemporaryLocator() {
        listOf("empty", "partial", "filled", "dropped", "renamed").forEach { boundary ->
            val name = "migration-boundary-$boundary"
            context.deleteDatabase(name)
            helper.createDatabase(name, 10).use { db -> seedV10(db) }
            helper.runMigrationsAndValidate(name, 11, true, Migration_10_11).use { db ->
                // Reconstruct each persisted rebuild boundary from one valid converted transcript.
                when (boundary) {
                    "dropped" -> db.execSQL("ALTER TABLE tool_execution RENAME TO tool_execution_v3")
                    "empty", "partial", "filled" -> {
                        db.execSQL("ALTER TABLE tool_execution RENAME TO tool_execution_v3")
                        db.execSQL("CREATE TABLE tool_execution(execution_id TEXT NOT NULL PRIMARY KEY, turn_id TEXT NOT NULL, " +
                            "tool_ordinal INTEGER NOT NULL, status TEXT NOT NULL, reason TEXT, child_conversation_id TEXT, " +
                            "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO tool_execution VALUES ('legacy','turn-await',0,'COMPLETED',NULL,NULL,1,1)")
                        if (boundary == "empty") db.execSQL("DELETE FROM tool_execution_v3")
                        if (boundary == "partial") db.execSQL("UPDATE tool_execution_v3 SET local_call_id = 'invalid'")
                    }
                    else -> Unit
                }
                Migration_10_11.migrate(db)
                assertEquals(1, scalar(db, "SELECT COUNT(*) FROM tool_execution"))
                assertEquals(convertedToolLocalCallId(db), single(db, "SELECT local_call_id FROM tool_execution"))
                assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
                if (boundary == "dropped") {
                    db.execSQL("ALTER TABLE tool_execution RENAME TO tool_execution_v3")
                    db.execSQL("UPDATE tool_execution_v3 SET local_call_id = 'invalid'")
                    assertThrows(IllegalStateException::class.java) { Migration_10_11.migrate(db) }
                    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM tool_execution_v3"))
                }
            }
            context.deleteDatabase(name)
        }
    }

    // ---- fixtures ----

    private fun seedV10(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ConversationEntity(id, assistant_id, title, create_at, update_at, suggestions, " +
                "is_pinned, custom_system_prompt, mode_injection_ids, workspace_cwd, tags, folder_id, " +
                "parent_conversation_id) VALUES ('conv-1','assistant-1','t',1,1,'[]',0,'','[]','','','',NULL)",
        )
        db.execSQL(
            "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES " +
                "('node-user','conv-1',0,'[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"hi\"}]}]',0)",
        )
        db.execSQL(
            "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES " +
                "('node-assistant','conv-1',1,'" + legacyAssistantTranscript() + "',0)",
        )
        db.execSQL(
            "INSERT INTO turn_execution(turn_id, conversation_id, assistant_message_id, status, reason, created_at, updated_at) VALUES " +
                "('turn-await','conv-1','$assistantMessageId','AWAITING_APPROVAL',NULL,1,1)," +
                "('turn-created','conv-1','$assistantMessageId','CREATED',NULL,1,1)",
        )
        db.execSQL(
            "INSERT INTO tool_execution(execution_id, turn_id, tool_ordinal, status, reason, child_conversation_id, created_at, updated_at) VALUES " +
                "('exec-1','turn-await',0,'COMPLETED',NULL,NULL,1,1)",
        )
    }

    private fun legacyAssistantTranscript(): String =
        """[{"id":"$assistantMessageId","role":"assistant","parts":[{"type":"tool","toolCallId":"call_1","toolName":"read","input":"{}","output":[{"type":"text","text":"r"}],"approvalState":{"type":"auto"}}]}]"""

    private fun convertedToolLocalCallId(db: SupportSQLiteDatabase): String {
        val messages = single(db, "SELECT messages FROM message_node WHERE id = 'node-assistant'")
        val parts = JsonInstant.parseToJsonElement(messages).jsonArray.first().jsonObject["parts"]!!.jsonArray
        val tool = parts.first { it.jsonObject["type"]?.jsonPrimitive?.content == "tool" }.jsonObject
        return tool["localCallId"]!!.jsonPrimitive.content
    }

    // ---- helpers ----

    private fun single(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue("expected one row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun tableNames(db: SupportSQLiteDatabase): List<String> =
        rows(
            db,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name != 'room_master_table' ORDER BY name",
        ).map { requireNotNull(it.single()) }

    private fun indexInfo(db: SupportSQLiteDatabase, table: String): Map<String, List<List<String?>>> = buildMap {
        db.query("PRAGMA index_list(`" + table + "`)").use { cursor ->
            while (cursor.moveToNext()) {
                val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                put(indexName + ":" + unique, rows(db, "PRAGMA index_info(`" + indexName + "`)").map { column ->
                    listOf(column[0], column[2])
                })
            }
        }
    }

    private fun foreignKeyInfo(db: SupportSQLiteDatabase, table: String): Map<String, String> = buildMap {
        db.query("PRAGMA foreign_key_list(`" + table + "`)").use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow("id")
            val targetColumn = cursor.getColumnIndexOrThrow("table")
            val fromColumn = cursor.getColumnIndexOrThrow("from")
            val onDelete = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) {
                val key = cursor.getInt(idColumn).toString() + ":" + cursor.getString(fromColumn)
                put(key, cursor.getString(targetColumn) + ":" + cursor.getString(onDelete))
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
