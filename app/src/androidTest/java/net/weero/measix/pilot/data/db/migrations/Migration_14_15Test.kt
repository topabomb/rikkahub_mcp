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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.db.transcript.readTranscriptPayload
import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextCodec
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_14_15Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val conversation = Uuid.random().toString()
    private val anchorNode = Uuid.random().toString()
    private val ownerNode = Uuid.random().toString()
    private val anchor = UIMessage.user("original user")
    private val oldPrefix = "managed~platform~${"a".repeat(64)}~dep_upgrade~"
    private val newPrefix = "managed~dep_upgrade~"
    private val oldModel = oldPrefix + "model_one"
    private val newModel = newPrefix + "model_one"
    private val disclosure = """{"type":"conversation_disclosure_snapshot","format":2,"memory":{"enabled":false,"scope":"disabled","header":["id","content"],"rows":[]},"sub_assistants":{"mode":"delegation_only","header":["id","name","description"],"rows":[["${oldPrefix}assistant_child","Child","original description"]]},"enterprise_memory_seeds":{"header":["id","content"],"rows":[["${oldPrefix}mem_one","original seed"]]}}""" + "\r\n"

    @Test fun twelveAndStrandedThirteenUpgradeBeforeDisclosureVariantDecoding() {
        for (version in listOf(12, 13)) {
            val name = "migration-enterprise-message-$version"
            context.deleteDatabase(name)
            val message = assistant()
            helper.createDatabase(name, version).use { db ->
                seed(db, version, message)
                db.execSQL("INSERT INTO conversation_model_context(owner_node_id,owner_message_id,anchor_node_id,anchor_message_id,content) VALUES(?,?,?,?,?)",
                    arrayOf(ownerNode, message.id.toString(), anchorNode, anchor.id.toString(), disclosure))
            }
            helper.runMigrationsAndValidate(name, 15, true,
                Migration_12_13, Migration_13_14, Migration_14_15).use { db ->
                assertPreserved(db, message)
                db.query("SELECT payload FROM conversation_model_context").use { rows ->
                    assertTrue(rows.moveToFirst())
                    val payload = ConversationContextCodec.decode(rows.getString(0))
                    assertEquals(disclosure, (payload.body as ConversationContextBody.Inline).text)
                    assertFalse(rows.moveToNext())
                }
                assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM conversation_context_admission"))
                assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM conversation_opening"))
            }
        }
    }

    @Test fun installedFourteenRepairsUnreadHistoryThroughTheProductionDatabaseFactory() {
        val name = "migration-enterprise-message-live-open"
        context.deleteDatabase(name)
        val message = assistant()
        helper.createDatabase(name, 14).use { seed(it, 14, message) }
        val room = createAppDatabase(context, name)
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(15, db.version)
            assertPreserved(db, message)
        } finally {
            room.close()
        }
        // Reopening must not rewrite current records or require the old principal again.
        val reopened = createAppDatabase(context, name)
        try {
            assertPreserved(reopened.openHelper.writableDatabase, message)
        } finally {
            reopened.close()
        }
    }

    @Test fun supplementaryCharactersAndLargeTranscriptSurviveChunkedMigration() {
        val name = "migration-enterprise-message-large"
        context.deleteDatabase(name)
        val message = assistant("\uD83D\uDE80汉".repeat(400_000) + oldModel)
        helper.createDatabase(name, 14).use { seed(it, 14, message) }
        helper.runMigrationsAndValidate(name, 15, true, Migration_14_15).use { db ->
            assertPreserved(db, message)
        }
    }

    @Test fun malformedReferenceRollsBackAllRowsAndCanRetryAfterSourceRepair() {
        val name = "migration-enterprise-message-invalid"
        context.deleteDatabase(name)
        val message = assistant()
        val badNode = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        val bad = legacyTranscript(message.copy(id = Uuid.random()), "managed~platform~broken")
        val original = legacyTranscript(message)
        helper.createDatabase(name, 14).use { db ->
            seed(db, 14, message)
            insertNode(db, badNode, 2, bad)
        }
        repeat(2) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                helper.runMigrationsAndValidate(name, 15, true, Migration_14_15).close()
            }
            assertEquals("invalid_enterprise_resource_id", failure.message)
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(14, db.version)
                db.rawQuery("SELECT messages FROM message_node WHERE id=?", arrayOf(ownerNode)).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(original, rows.getString(0))
                }
                db.rawQuery("SELECT messages FROM message_node WHERE id=?", arrayOf(badNode)).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(bad, rows.getString(0))
                }
            }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE message_node SET messages=? WHERE id=?", arrayOf(legacyTranscript(message.copy(id = Uuid.random())), badNode))
        }
        helper.runMigrationsAndValidate(name, 15, true, Migration_14_15).use { db -> assertPreserved(db, message) }
    }

    private fun assistant(text: String = "verbatim $oldModel {{literal}}\r\n"): UIMessage {
        val step = Uuid.random()
        val metadata = buildJsonObject {
            put("sub_assistant_call", buildJsonObject {
                put("schema_version", 2)
                put("run_id", Uuid.random().toString())
                put("target_assistant_id", oldPrefix + "assistant_child")
                put("target_name_snapshot", oldModel)
                put("state", "completed")
            })
            put("opaque_model_id", oldModel)
        }
        return UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Step(step, 0, Instant.fromEpochMilliseconds(0), outcome = StepOutcome.Final),
                UIMessagePart.Text(text),
                UIMessagePart.Tool(Uuid.random(), step, "provider-call", "call_sub_assistant",
                    input = "{\"assistant_id\":\"$oldModel\"}",
                    output = listOf(UIMessagePart.Text(oldModel)), resultStatus = ToolResultStatus.COMPLETED,
                    metadata = metadata),
            ),
            providerMetadata = buildJsonObject { put("modelId", oldModel) },
        )
    }

    private fun legacyTranscript(message: UIMessage, model: String = oldModel): String {
        val raw = JsonInstant.encodeToJsonElement(UIMessage.serializer(), message).jsonObject
        return JsonArray(listOf(JsonObject(raw + ("modelId" to JsonPrimitive(model))))).toString()
    }

    private fun seed(db: SupportSQLiteDatabase, version: Int, message: UIMessage) {
        val scope = if (version == 12) "enterprise~platform:${"a".repeat(64)}~dep_upgrade~dXNlcg" else "enterprise~dep_upgrade~dXNlcg"
        val assistant = (if (version == 12) oldPrefix else newPrefix) + "assistant_one"
        db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope,mode_injection_ids,custom_system_prompt) VALUES(?,?,'retained title',1,2,'[]',0,?,?,?)",
            arrayOf(conversation, assistant, scope, "[\"${oldPrefix}injection_one\"]", oldModel))
        insertNode(db, anchorNode, 0, JsonInstant.encodeToString(listOf(anchor)))
        insertNode(db, ownerNode, 1, legacyTranscript(message))
    }

    private fun insertNode(db: SupportSQLiteDatabase, id: String, index: Int, payload: String) {
        db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index,transcript_schema) VALUES(?,?,?,?,0,3)",
            arrayOf<Any>(id, conversation, index, payload))
    }

    private fun assertPreserved(db: SupportSQLiteDatabase, original: UIMessage) {
        val decoded = JsonInstant.decodeFromString<List<UIMessage>>(readTranscriptPayload(db, ownerNode)).single()
        val tool = original.parts.filterIsInstance<UIMessagePart.Tool>().single()
        val originalMetadata = requireNotNull(tool.metadata)
        val child = originalMetadata.getValue("sub_assistant_call").jsonObject
        val expectedMetadata = JsonObject(originalMetadata + ("sub_assistant_call" to JsonObject(child +
            ("target_assistant_id" to JsonPrimitive(newPrefix + "assistant_child")))))
        assertEquals(original.copy(modelId = ConfigurationReference.parse(newModel), parts = original.parts.map {
            if (it is UIMessagePart.Tool) it.copy(metadata = expectedMetadata) else it
        }), decoded)
        assertEquals(JsonInstant.encodeToString(listOf(anchor)), readTranscriptPayload(db, anchorNode))
        db.query("SELECT assistant_id,mode_injection_ids,custom_system_prompt,title FROM ConversationEntity WHERE id=?", arrayOf(conversation)).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals(newPrefix + "assistant_one", rows.getString(0))
            assertEquals(listOf(ConfigurationReference.parse(newPrefix + "injection_one")),
                JsonInstant.decodeFromString<List<ConfigurationReference>>(rows.getString(1)))
            assertEquals(oldModel, rows.getString(2))
            assertEquals("retained title", rows.getString(3))
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }
}
