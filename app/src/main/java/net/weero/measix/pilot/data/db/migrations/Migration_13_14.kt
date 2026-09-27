package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextCodec
import net.weero.measix.pilot.data.model.ConversationContextPayload
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.contextEntryIdentity
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.transcript.readTranscriptPayload
import net.weero.measix.pilot.data.db.transcript.migrateEnterpriseTranscriptReferences
import net.weero.measix.pilot.utils.JsonInstant

/** Retains historical disclosure bytes without inventing a Step, namespace, reason, or opening. */
val Migration_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        migrateEnterpriseTranscriptReferences(db)
        db.execSQL("""
            CREATE TABLE conversation_model_context_new (
                id TEXT NOT NULL PRIMARY KEY,
                owner_node_id TEXT NOT NULL,
                owner_message_id TEXT NOT NULL,
                anchor_node_id TEXT NOT NULL,
                anchor_message_id TEXT NOT NULL,
                occurrence INTEGER NOT NULL,
                step_id TEXT,
                source_kind TEXT NOT NULL,
                payload TEXT NOT NULL,
                FOREIGN KEY(owner_node_id) REFERENCES message_node(id) ON DELETE CASCADE,
                FOREIGN KEY(anchor_node_id) REFERENCES message_node(id) ON DELETE CASCADE
            )
        """.trimIndent())
        var copied = 0L
        db.query("SELECT owner_node_id, owner_message_id, anchor_node_id, anchor_message_id, content " +
            "FROM conversation_model_context").use { rows ->
            while (rows.moveToNext()) {
                val ownerNode = rows.getString(0)
                val ownerMessage = rows.getString(1)
                val anchorNode = rows.getString(2)
                val anchorMessage = rows.getString(3)
                validateHistoricalContextOwner(db, ownerNode, ownerMessage, anchorNode, anchorMessage)
                val content = rows.getString(4)
                ConversationDisclosureSnapshotService.requireDurableEnvelope(content)
                val payload = ConversationContextPayload(
                    source = ConversationContextSource.Disclosure(namespace = null),
                    body = ConversationContextBody.Inline(content),
                )
                db.execSQL("INSERT INTO conversation_model_context_new " +
                    "(id, owner_node_id, owner_message_id, anchor_node_id, anchor_message_id, occurrence, step_id, source_kind, payload) " +
                    "VALUES (?, ?, ?, ?, ?, 0, NULL, 'disclosure', ?)",
                    arrayOf(contextEntryIdentity(Uuid.parse(ownerNode), Uuid.parse(ownerMessage), 0).toString(),
                        ownerNode, ownerMessage, rows.getString(2), rows.getString(3), ConversationContextCodec.encode(payload)))
                copied++
            }
        }
        db.query("SELECT COUNT(*) FROM conversation_model_context_new").use { rows ->
            check(rows.moveToFirst() && rows.getLong(0) == copied) { "context_migration_row_count_mismatch" }
        }
        db.execSQL("DROP TABLE conversation_model_context")
        db.execSQL("ALTER TABLE conversation_model_context_new RENAME TO conversation_model_context")
        db.execSQL("CREATE UNIQUE INDEX index_conversation_model_context_owner_node_id_owner_message_id_occurrence " +
            "ON conversation_model_context(owner_node_id, owner_message_id, occurrence)")
        db.execSQL("CREATE INDEX index_conversation_model_context_anchor_node_id ON conversation_model_context(anchor_node_id)")
        db.execSQL("""
            CREATE TABLE conversation_context_admission (
                id TEXT NOT NULL PRIMARY KEY,
                owner_node_id TEXT NOT NULL,
                owner_message_id TEXT NOT NULL,
                step_id TEXT NOT NULL,
                window_start_node_id TEXT NOT NULL,
                window_start_message_id TEXT NOT NULL,
                selection_payload TEXT,
                FOREIGN KEY(owner_node_id) REFERENCES message_node(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_conversation_context_admission_owner_node_id_owner_message_id_step_id " +
            "ON conversation_context_admission(owner_node_id, owner_message_id, step_id)")
        db.execSQL("""
            CREATE TABLE conversation_context_use (
                admission_id TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                entry_id TEXT NOT NULL,
                role TEXT NOT NULL,
                placement TEXT NOT NULL,
                PRIMARY KEY(admission_id, ordinal),
                FOREIGN KEY(admission_id) REFERENCES conversation_context_admission(id) ON DELETE CASCADE,
                FOREIGN KEY(entry_id) REFERENCES conversation_model_context(id) ON DELETE NO ACTION
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_conversation_context_use_entry_id ON conversation_context_use(entry_id)")
        db.execSQL("""
            CREATE TABLE conversation_opening (
                conversation_id TEXT NOT NULL PRIMARY KEY,
                payload TEXT NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) ON DELETE CASCADE
            )
        """.trimIndent())
    }
}

private fun validateHistoricalContextOwner(
    db: SupportSQLiteDatabase,
    ownerNode: String,
    ownerMessage: String,
    anchorNode: String,
    anchorMessage: String,
) {
    Uuid.parse(anchorNode)
    Uuid.parse(anchorMessage)
    db.query("SELECT o.conversation_id, a.conversation_id, o.node_index, a.node_index " +
        "FROM message_node o JOIN message_node a ON a.id = ? WHERE o.id = ?",
        arrayOf(anchorNode, ownerNode)).use { row ->
        check(row.moveToFirst() && row.getString(0) == row.getString(1) && row.getInt(2) > row.getInt(3)) {
            "invalid_historical_context_node_relation: owner=$ownerNode anchor=$anchorNode"
        }
    }
    fun requireVariant(node: String, message: String, role: MessageRole) {
        val messages = JsonInstant.decodeFromString<List<UIMessage>>(readTranscriptPayload(db, node))
        check(messages.singleOrNull { it.id.toString() == message }?.role == role) {
            "invalid_historical_context_variant: node=$node message=$message role=$role"
        }
    }
    requireVariant(ownerNode, ownerMessage, MessageRole.ASSISTANT)
    requireVariant(anchorNode, anchorMessage, MessageRole.USER)
}
