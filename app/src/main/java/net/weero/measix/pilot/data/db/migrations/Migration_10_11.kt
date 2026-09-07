package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.transcript.LegacyTurnTranscriptMigrator
import kotlin.uuid.Uuid

/**
 * v10 → v11: the V3 Turn/Step/Tool transcript contract.
 *
 * Three durable facts move at once:
 *  - `message_node` gains `transcript_schema` (fail-closed sentinel, value 3) and every Assistant
 *    transcript is converted to the V3 shape exactly once by [LegacyTurnTranscriptMigrator];
 *  - `turn_execution` keeps its columns but renames two status tokens (AWAITING_APPROVAL→AWAITING_USER,
 *    CREATED→INTERRUPTED); a Turn waiting on the user is never marked terminal here — startup recovery closes it;
 *  - `tool_execution` is rebuilt from `tool_ordinal` to `step_id` + `local_call_id`, gaining
 *    `child_turn_id` / `sub_assistant_run_id`, dropping the status index and adding UNIQUE(turn_id, local_call_id).
 *
 * The whole migration is reentrant: it probes live schema state (`PRAGMA table_info` / `sqlite_master`)
 * rather than trusting in-memory progress, so a crash at any DDL boundary resumes correctly. Foreign
 * keys are off for the rebuild and restored at the end.
 */
val Migration_10_11 = object : Migration(10, 11) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF")
        try {
            val turnStatus = readTurnStatusByAssistantMessageId(db)
            convertTranscripts(db, turnStatus)
            rewriteTurnStatus(db)
            rebuildToolExecution(db)
        } finally {
            db.execSQL("PRAGMA foreign_keys=ON")
        }
    }

    // ---- transcript conversion ----

    private fun readTurnStatusByAssistantMessageId(db: SupportSQLiteDatabase): Map<Uuid, String> {
        val map = HashMap<Uuid, String>()
        db.query("SELECT assistant_message_id, status FROM turn_execution WHERE assistant_message_id IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                map[Uuid.parse(id)] = c.getString(1)
            }
        }
        return map
    }

    private fun convertTranscripts(db: SupportSQLiteDatabase, turnStatus: Map<Uuid, String>) {
        val hasColumn = columnExists(db, "message_node", "transcript_schema")
        // §11.3：游标逐行读取 (id, messages) 并就地改写，峰值内存 = 单行 transcript，绝不一次装入全库。
        // UPDATE 只命中已读过的行（按主键），不改变 rowid 扫描顺序，故游标继续安全前进。
        db.query("SELECT id, messages FROM message_node").use { c ->
            val idColumn = c.getColumnIndexOrThrow("id")
            val messagesColumn = c.getColumnIndexOrThrow("messages")
            while (c.moveToNext()) {
                val id = c.getString(idColumn)
                val messages = c.getString(messagesColumn)
                val converted = LegacyTurnTranscriptMigrator.convertNode(messages, turnStatus, json)
                if (converted != messages) {
                    db.execSQL("UPDATE message_node SET messages = ? WHERE id = ?", arrayOf(converted, id))
                }
            }
        }
        if (!hasColumn) {
            db.execSQL(
                "ALTER TABLE message_node ADD COLUMN transcript_schema INTEGER NOT NULL " +
                    "DEFAULT ${MessageNodeEntity.TRANSCRIPT_SCHEMA_VERSION}",
            )
        }
        // Fail-closed assertion: every row must now carry the current transcript schema.
        db.query(
            "SELECT COUNT(*) FROM message_node WHERE transcript_schema != ${MessageNodeEntity.TRANSCRIPT_SCHEMA_VERSION}",
        ).use { c ->
            c.moveToFirst()
            check(c.getInt(0) == 0) {
                "message_node rows left with transcript_schema != ${MessageNodeEntity.TRANSCRIPT_SCHEMA_VERSION}"
            }
        }
    }

    // ---- turn_execution status rewrite ----

    private fun rewriteTurnStatus(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE turn_execution SET status = 'AWAITING_USER' WHERE status = 'AWAITING_APPROVAL'")
        db.execSQL(
            "UPDATE turn_execution SET status = 'INTERRUPTED', reason = 'schema_upgrade' WHERE status = 'CREATED'",
        )
    }

    // ---- tool_execution rebuild ----

    private fun rebuildToolExecution(db: SupportSQLiteDatabase) {
        val oldHasLocalCallId = columnExists(db, "tool_execution", "local_call_id")
        val oldExists = tableExists(db, "tool_execution")
        val tempExists = tableExists(db, "tool_execution_v3")

        if (oldHasLocalCallId) {
            // Already at target shape (idempotent replay after a completed run).
            if (tempExists) db.execSQL("DROP TABLE tool_execution_v3")
            createToolExecutionIndexes(db)
            return
        }

        if (!oldExists) {
            // Crashed after DROP old table, before RENAME: the temp table is authoritative if complete.
            check(tempExists && tempIsComplete(db)) { "tool_execution lost during migration and cannot be recovered" }
            db.execSQL("ALTER TABLE tool_execution_v3 RENAME TO tool_execution")
            createToolExecutionIndexes(db)
            return
        }

        // Old table present with tool_ordinal. A leftover temp table is always discarded and refilled.
        if (tempExists) db.execSQL("DROP TABLE tool_execution_v3")
        db.execSQL(
            """
            CREATE TABLE tool_execution_v3 (
                `execution_id` TEXT NOT NULL,
                `turn_id` TEXT NOT NULL,
                `step_id` TEXT NOT NULL,
                `local_call_id` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `reason` TEXT,
                `child_conversation_id` TEXT,
                `child_turn_id` TEXT,
                `sub_assistant_run_id` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`execution_id`),
                FOREIGN KEY(`turn_id`) REFERENCES `turn_execution`(`turn_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        val assistantTools = readAssistantToolsByMessageId(db)
        val turnAssistant = HashMap<String, String>()
        db.query("SELECT turn_id, assistant_message_id FROM turn_execution").use { c ->
            while (c.moveToNext()) turnAssistant[c.getString(0)] = c.getString(1)
        }
        val turnStatus = HashMap<String, String>()
        db.query("SELECT turn_id, status FROM turn_execution").use { c ->
            while (c.moveToNext()) turnStatus[c.getString(0)] = c.getString(1)
        }

        val oldRows = queryOldToolRows(db)
        var inserted = 0
        var dropped = 0
        for (row in oldRows) {
            val assistantId = turnAssistant[row.turnId]
            val tools = assistantId?.let { assistantTools[it] }
            val tool = tools?.getOrNull(row.toolOrdinal)
            if (tool == null) {
                val nonTerminal = turnStatus[row.turnId] in NON_TERMINAL_TURN
                if (row.status == "STARTED" || nonTerminal) {
                    error("cannot map tool_execution ${row.executionId} to a transcript Tool on a live turn")
                }
                if (assistantId != null && tools != null && row.toolOrdinal >= tools.size) {
                    error("tool_execution ${row.executionId} ordinal ${row.toolOrdinal} out of range on a present message")
                }
                // Terminal row whose conversation/message is gone: drop the dangling execution fact.
                dropped++
                continue
            }
            val runId = tool.subAssistantRunId()
            db.execSQL(
                "INSERT INTO tool_execution_v3 (execution_id, turn_id, step_id, local_call_id, status, reason, " +
                    "child_conversation_id, child_turn_id, sub_assistant_run_id, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)",
                arrayOf<Any?>(
                    "tool:${tool.localCallId}",
                    row.turnId,
                    tool.stepId.toString(),
                    tool.localCallId.toString(),
                    row.status,
                    row.reason,
                    row.childConversationId,
                    runId,
                    row.createdAt,
                    row.updatedAt,
                ),
            )
            inserted++
        }
        // §11.6：DROP+RENAME 前校验临时表实有行数 = 映射保留行数，且每条旧行都被计入（映射或删除）。
        val v3RowCount = db.query("SELECT COUNT(*) FROM tool_execution_v3").use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        check(v3RowCount == inserted) {
            "tool_execution_v3 holds $v3RowCount rows but $inserted were mapped"
        }
        check(inserted + dropped == oldRows.size) {
            "tool_execution rebuild accounted ${inserted + dropped} of ${oldRows.size} legacy rows"
        }
        db.execSQL("DROP TABLE tool_execution")
        db.execSQL("ALTER TABLE tool_execution_v3 RENAME TO tool_execution")
        createToolExecutionIndexes(db)
    }

    // The rebuild inserts every mapped row into the temp table *before* dropping the old table, and
    // any row it cannot map fails the migration outright. So when the old table is already gone, the
    // surviving temp table necessarily holds the complete, fully-mapped set — presence of the two new
    // locator columns is the discriminator that this run built the V3 shape (not a legacy leftover).
    private fun tempIsComplete(db: SupportSQLiteDatabase): Boolean =
        columnExists(db, "tool_execution_v3", "local_call_id") && columnExists(db, "tool_execution_v3", "step_id")

    private fun createToolExecutionIndexes(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tool_execution_turn_id` ON `tool_execution` (`turn_id`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_execution_child_conversation_id` " +
                "ON `tool_execution` (`child_conversation_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_execution_child_turn_id` ON `tool_execution` (`child_turn_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_execution_sub_assistant_run_id` " +
                "ON `tool_execution` (`sub_assistant_run_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_execution_turn_id_local_call_id` " +
                "ON `tool_execution` (`turn_id`, `local_call_id`)",
        )
    }

    /**
     * Index the (already V3-converted) transcript tools by the Assistant **message** id they belong
     * to, preserving each message's own tool order. `tool_execution.tool_ordinal` is positional
     * within the assistant message named by `turn_execution.assistant_message_id`, so the map key
     * must be that message id — never the `message_node` row id — and the list must be scoped to a
     * single message, not flattened across every message in the node.
     */
    private fun readAssistantToolsByMessageId(db: SupportSQLiteDatabase): Map<String, List<UIMessagePart.Tool>> {
        val map = HashMap<String, List<UIMessagePart.Tool>>()
        db.query("SELECT messages FROM message_node").use { c ->
            while (c.moveToNext()) {
                val messages = json.parseToJsonElement(c.getString(0)).jsonArray
                for (message in messages) {
                    val obj = message.jsonObject
                    val messageId = obj["id"]?.jsonPrimitive?.content ?: continue
                    val tools = obj["parts"]?.jsonArray?.mapNotNull { part ->
                        val p = part.jsonObject
                        if (p["type"]?.jsonPrimitive?.content == "tool") {
                            json.decodeFromJsonElement(UIMessagePart.Tool.serializer(), p)
                        } else {
                            null
                        }
                    } ?: emptyList()
                    // Key every message id, including tool-less ones: a present message that carries
                    // no Tool must stay distinguishable from an absent one, so an execution row that
                    // cannot be mapped fails closed rather than being silently dropped or re-indexed.
                    map[messageId] = tools
                }
            }
        }
        return map
    }

    private fun UIMessagePart.Tool.subAssistantRunId(): String? =
        (metadata?.get("sub_assistant_call") as? JsonObject)?.get("run_id")?.jsonPrimitive?.content

    private fun queryOldToolRows(db: SupportSQLiteDatabase): List<OldToolRow> {
        val rows = ArrayList<OldToolRow>()
        db.query(
            "SELECT execution_id, turn_id, tool_ordinal, status, reason, child_conversation_id, created_at, updated_at " +
                "FROM tool_execution",
        ).use { c ->
            while (c.moveToNext()) {
                rows += OldToolRow(
                    executionId = c.getString(0),
                    turnId = c.getString(1),
                    toolOrdinal = c.getInt(2),
                    status = c.getString(3),
                    reason = if (c.isNull(4)) null else c.getString(4),
                    childConversationId = if (c.isNull(5)) null else c.getString(5),
                    createdAt = c.getLong(6),
                    updatedAt = c.getLong(7),
                )
            }
        }
        return rows
    }

    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(nameIdx) == column) return true
        }
        return false
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name = ?", arrayOf(table)).use { c ->
            return c.moveToFirst()
        }
    }
}

private val NON_TERMINAL_TURN = setOf("RUNNING", "AWAITING_APPROVAL", "AWAITING_USER", "CREATED")

private data class OldToolRow(
    val executionId: String,
    val turnId: String,
    val toolOrdinal: Int,
    val status: String,
    val reason: String?,
    val childConversationId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
