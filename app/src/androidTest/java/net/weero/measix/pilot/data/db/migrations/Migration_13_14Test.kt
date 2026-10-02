package net.weero.measix.pilot.data.db.migrations

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextCodec
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.contextEntryIdentity
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_13_14Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val conversation = Uuid.random().toString()
    private val anchorNode = Uuid.random()
    private val ownerNode = Uuid.random()
    private val anchor = UIMessage.user("原 user {{literal}}")
    private val owner = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    private val oldOne = """{"type":"conversation_disclosure_snapshot", "format":1,"memory":{"enabled":false,"scope":"disabled","header":["id","content"],"rows":[]},"sub_assistants":{"mode":"disabled","header":["id","name","description"],"rows":[]}}""" + "\r\n"
    private val oldTwo = """{"type":"conversation_disclosure_snapshot","format":2,"memory":{"enabled":true,"scope":"local","header":["id","content"],"rows":[[17,"保留 \"原文\" 与 {{literal}}"]]},"sub_assistants":{"mode":"disabled","header":["id","name","description"],"rows":[]},"enterprise_memory_seeds":{"header":["id","content"],"rows":[]}}"""

    @Test fun historicalFormatsPreserveBytesLocatorsAndUnknownSourceWithoutInventedRequests() {
        val name = "migration-context-v13-preservation"
        context.deleteDatabase(name)
        val secondOwner = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val secondNode = Uuid.random()
        val largeOwner = JsonInstant.encodeToString(listOf(owner.copy(parts = listOf(UIMessagePart.Text("数".repeat(1_000_000))))))
        helper.createDatabase(name, 13).use { db ->
            seed(db)
            db.execSQL("UPDATE message_node SET messages=? WHERE id=?", arrayOf(largeOwner, ownerNode.toString()))
            insertContext(db, oldOne)
            insertNode(db, secondNode, secondOwner, 2)
            insertContext(db, oldTwo, secondNode, secondOwner.id)
        }
        helper.runMigrationsAndValidate(name, 14, true, Migration_13_14).use { db ->
            val expected = mapOf(ownerNode.toString() to oldOne, secondNode.toString() to oldTwo)
            var count = 0
            db.query("SELECT id,owner_node_id,owner_message_id,anchor_node_id,anchor_message_id,occurrence,step_id,source_kind,payload FROM conversation_model_context").use { rows ->
                while (rows.moveToNext()) {
                    val node = Uuid.parse(rows.getString(1))
                    val message = Uuid.parse(rows.getString(2))
                    assertEquals(contextEntryIdentity(node, message, 0).toString(), rows.getString(0))
                    assertEquals(anchorNode.toString(), rows.getString(3))
                    assertEquals(anchor.id.toString(), rows.getString(4))
                    assertEquals(0, rows.getInt(5))
                    assertTrue(rows.isNull(6))
                    assertEquals("disclosure", rows.getString(7))
                    val payload = ConversationContextCodec.decode(rows.getString(8))
                    assertEquals(expected.getValue(node.toString()), (payload.body as ConversationContextBody.Inline).text)
                    val source = payload.source as ConversationContextSource.Disclosure
                    assertNull(source.namespace)
                    assertTrue(source.reasons.isEmpty())
                    count++
                }
            }
            assertEquals(2, count)
            assertEquals(listOf(listOf(largeOwner.length.toString())), rows(db,
                "SELECT length(messages) FROM message_node WHERE id='$ownerNode'"))
            listOf("conversation_context_admission", "conversation_context_use", "conversation_opening").forEach { table ->
                assertEquals(listOf(listOf("0")), rows(db, "SELECT COUNT(*) FROM $table"))
            }
            assertEquals(listOf(listOf(JsonInstant.encodeToString(listOf(anchor)))) ,
                rows(db, "SELECT messages FROM message_node WHERE id='${anchorNode}'"))
            assertEquals(emptyList<List<String?>>(), rows(db, "PRAGMA foreign_key_check"))
        }
    }

    @Test fun invalidEnvelopeAbortsAndRoomRollsBackOriginalFile() {
        val name = "migration-context-invalid-envelope"
        val invalid = oldOne.replace("\"format\":1", "\"format\":99")
        context.deleteDatabase(name)
        helper.createDatabase(name, 13).use { db -> seed(db); insertContext(db, invalid) }
        assertThrows(IllegalStateException::class.java) { helper.runMigrationsAndValidate(name, 14, true, Migration_13_14).close() }
        assertOriginalFile(name, invalid)
    }

    @Test fun inconsistentTranscriptMembershipRoleOrConversationAbortsBeforeReplacingOldRows() {
        val cases = listOf("missing_variant", "wrong_owner_role", "cross_conversation")
        for (case in cases) {
            val name = "migration-context-$case"
            context.deleteDatabase(name)
            helper.createDatabase(name, 13).use { db ->
                seed(db)
                when (case) {
                    "missing_variant" -> insertContext(db, oldOne, ownerMessageId = Uuid.random())
                    "wrong_owner_role" -> {
                        db.execSQL("UPDATE message_node SET messages=? WHERE id=?",
                            arrayOf(JsonInstant.encodeToString(listOf(owner.copy(role = MessageRole.USER))), ownerNode.toString()))
                        insertContext(db, oldOne)
                    }
                    else -> {
                        val otherConversation = Uuid.random().toString()
                        insertConversation(db, otherConversation)
                        db.execSQL("UPDATE message_node SET conversation_id=? WHERE id=?", arrayOf(otherConversation, anchorNode.toString()))
                        insertContext(db, oldOne)
                    }
                }
            }
            assertThrows(case, IllegalStateException::class.java) { helper.runMigrationsAndValidate(name, 14, true, Migration_13_14).close() }
            assertOriginalFile(name, oldOne)
        }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        insertConversation(db, conversation)
        insertNode(db, anchorNode, anchor, 0)
        insertNode(db, ownerNode, owner, 1)
    }
    private fun insertConversation(db: SupportSQLiteDatabase, id: String) {
        db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope) VALUES(?,'11111111-1111-1111-1111-111111111111','original',1,2,'[]',0,'personal')", arrayOf(id))
    }
    private fun insertNode(db: SupportSQLiteDatabase, id: Uuid, message: UIMessage, index: Int) {
        db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index,transcript_schema) VALUES(?,?,?,?,0,3)",
            arrayOf<Any>(id.toString(), conversation, index, JsonInstant.encodeToString(listOf(message))))
    }
    private fun insertContext(db: SupportSQLiteDatabase, content: String, nodeId: Uuid = ownerNode, ownerMessageId: Uuid = owner.id) {
        db.execSQL("INSERT INTO conversation_model_context(owner_node_id,owner_message_id,anchor_node_id,anchor_message_id,content) VALUES(?,?,?,?,?)",
            arrayOf(nodeId.toString(), ownerMessageId.toString(), anchorNode.toString(), anchor.id.toString(), content))
    }
    private fun assertOriginalFile(name: String, content: String) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(13, db.version)
            db.rawQuery("SELECT content FROM conversation_model_context", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(content, rows.getString(0))
                assertFalse(rows.moveToNext())
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('conversation_model_context_new','conversation_context_admission','conversation_context_use','conversation_opening')", null).use { rows ->
                assertFalse("failed migration must not leave new tables", rows.moveToFirst())
            }
        }
    }
    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }
}
