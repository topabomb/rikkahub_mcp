package net.weero.measix.pilot.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.configurationScopeFromStorageKey
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.UIMessage
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import me.rerere.common.configuration.RetiredLocalEnterpriseIdentity
import net.weero.measix.pilot.data.configuration.storageKey
import net.weero.measix.pilot.data.db.transcript.readTranscriptPayload
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_12_13Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun retiredLocalRootsAndTranscriptSurviveFullUpgradeWithoutJoiningSameNamedPlatformPrincipal() {
        val name = "migration-retired-local-preservation"
        val retired = EnterpriseAuthority(RetiredLocalEnterpriseIdentity.encode("local:example", "dep_example"))
        val retiredScope = ConfigurationScope.Enterprise(retired, "usr_example").storageKey()
        val oldScope = "enterprise~local:example~dep_example~dXNyX2V4YW1wbGU"
        val oldAssistant = "managed~local~example~dep_example~asd_one"
        val newAssistant = "managed~${retired.deploymentId}~asd_one"
        val oldModel = "managed~local~example~dep_example~mdl_one"
        val message = UIMessage.user(oldModel).copy(modelId = ConfigurationReference.Enterprise(retired, "mdl_one"))
        val conversation = Uuid.random().toString()
        val node = Uuid.random().toString()
        val transcript = JsonInstant.encodeToString(listOf(message)).replace(message.modelId.toString(), oldModel)
        helper.createDatabase(name, 12).use { db ->
            db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope,custom_system_prompt) VALUES(?,?,'kept local title',1,2,'[]',0,?,?)", arrayOf(conversation, oldAssistant, oldScope, oldModel))
            db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope) VALUES('platform',?,'kept platform title',1,2,'[]',0,'enterprise~platform:example~dep_example~dXNyX2V4YW1wbGU')", arrayOf("managed~platform~example~dep_example~asd_one"))
            db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope) VALUES('personal','shared','kept personal title',1,2,'[]',0,'personal')")
            db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index,transcript_schema) VALUES(?,?,0,?,0,3)", arrayOf(node, conversation, transcript))
            db.execSQL("INSERT INTO MemoryEntity(id,assistant_id,content,scope) VALUES(1,?,'kept local memory',?)", arrayOf(oldAssistant, oldScope))
            db.execSQL("INSERT INTO artifact(id,folder,relative_path,display_name,mime_type,size_bytes,created_at,updated_at,scope) VALUES(1,'upload','upload/kept-local.txt','kept','text/plain',9,1,1,?)", arrayOf(oldScope))
            db.execSQL("INSERT INTO GenMediaEntity(id,path,model_id,prompt,create_at,scope) VALUES(1,'images/kept-local.png','model','kept prompt',1,?)", arrayOf(oldScope))
            db.execSQL("INSERT INTO conversation_folder(id,assistant_id,name,sort_index,create_at,scope) VALUES('folder',?,'kept folder',0,1,?)", arrayOf(oldAssistant, oldScope))
            db.execSQL("INSERT INTO favorites(id,type,ref_key,ref_json,snapshot_json,created_at,updated_at,scope) VALUES('favorite','node','kept-key','{}','kept snapshot',1,1,?)", arrayOf(oldScope))
        }
        helper.runMigrationsAndValidate(name, 15, true, Migration_12_13, Migration_13_14, Migration_14_15).use { db ->
            assertEquals(listOf("personal"), values(db, "SELECT scope FROM ConversationEntity WHERE id='personal'"))
            assertEquals(listOf("enterprise~dep_example~dXNyX2V4YW1wbGU"), values(db, "SELECT scope FROM ConversationEntity WHERE id='platform'"))
            assertEquals(listOf(retiredScope), values(db, "SELECT scope FROM ConversationEntity WHERE id='$conversation'"))
            listOf("MemoryEntity", "artifact", "GenMediaEntity", "conversation_folder", "favorites").forEach { table ->
                assertEquals(listOf(retiredScope), values(db, "SELECT scope FROM `$table`"))
            }
            assertEquals(listOf(newAssistant), values(db, "SELECT assistant_id FROM ConversationEntity WHERE id='$conversation'"))
            assertEquals(listOf("kept local memory"), values(db, "SELECT content FROM MemoryEntity"))
            assertEquals(listOf("upload/kept-local.txt"), values(db, "SELECT relative_path FROM artifact"))
            assertEquals(listOf("images/kept-local.png"), values(db, "SELECT path FROM GenMediaEntity"))
            assertEquals(listOf("kept snapshot"), values(db, "SELECT snapshot_json FROM favorites"))
            assertEquals(listOf(oldModel), values(db, "SELECT custom_system_prompt FROM ConversationEntity WHERE id='$conversation'"))
            assertEquals(message, JsonInstant.decodeFromString<List<UIMessage>>(readTranscriptPayload(db, node)).single())
            assertEquals("local:example" to "dep_example", RetiredLocalEnterpriseIdentity.decode(
                (configurationScopeFromStorageKey(retiredScope) as ConfigurationScope.Enterprise).authority.deploymentId))
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun enterpriseDataKeepsDeploymentUserAndResourceIdentityWithoutOriginSource() {
        val name = "migration-enterprise-principal"
        val hash = "a".repeat(64)
        val oldScope = "enterprise~platform:$hash~dep_example~dXNlcn5vbmU"
        val newScope = "enterprise~dep_example~dXNlcn5vbmU"
        val oldReference = "managed~platform~$hash~dep_example~assistant_one"
        val newReference = "managed~dep_example~assistant_one"
        helper.createDatabase(name, 12).use { db ->
            db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope) VALUES('conversation',?,'title',1,2,'[]',0,?)", arrayOf(oldReference, oldScope))
            db.execSQL("INSERT INTO MemoryEntity(id,assistant_id,content,scope) VALUES(1,?,'memory',?)", arrayOf(oldReference, oldScope))
            db.execSQL("INSERT INTO artifact(id,folder,relative_path,display_name,mime_type,size_bytes,created_at,updated_at,scope) VALUES(1,'upload','upload/a','a','text/plain',1,1,1,?)", arrayOf(oldScope))
            db.execSQL("INSERT INTO GenMediaEntity(id,path,model_id,prompt,create_at,scope) VALUES(1,'images/a','model','prompt',1,?)", arrayOf(oldScope))
            db.execSQL("INSERT INTO conversation_folder(id,assistant_id,name,sort_index,create_at,scope) VALUES('folder',?,'folder',0,1,?)", arrayOf(oldReference, oldScope))
            db.execSQL("INSERT INTO favorites(id,type,ref_key,ref_json,snapshot_json,created_at,updated_at,scope) VALUES('favorite','node','key','{}','{}',1,1,?)", arrayOf(oldScope))
        }
        helper.runMigrationsAndValidate(name, 13, true, Migration_12_13).use { db ->
            listOf("ConversationEntity", "MemoryEntity", "artifact", "GenMediaEntity", "conversation_folder", "favorites").forEach { table ->
                assertEquals(listOf(newScope), values(db, "SELECT scope FROM `$table`"))
            }
            listOf(
                "ConversationEntity" to "assistant_id",
                "MemoryEntity" to "assistant_id",
                "conversation_folder" to "assistant_id",
            ).forEach { (table, column) ->
                assertEquals(listOf(newReference), values(db, "SELECT `$column` FROM `$table`"))
            }
            assertEquals(
                ConfigurationScope.Enterprise(
                    me.rerere.common.configuration.EnterpriseAuthority("dep_example"),
                    "user~one",
                ),
                configurationScopeFromStorageKey(newScope),
            )
        }
    }

    @Test
    fun scopePartitionedReadPathsUseCurrentMigrationIndexesWithoutExtraSorting() {
        val name = "migration-enterprise-scope-indexes"
        helper.createDatabase(name, 12).close()
        helper.runMigrationsAndValidate(name, 13, true, Migration_12_13).use { db ->
            val scope = "enterprise~dep_example~dXNlcg"
            listOf(
                "SELECT * FROM ConversationEntity WHERE scope='$scope' AND parent_conversation_id IS NULL " +
                    "AND assistant_id='managed~dep_example~assistant' ORDER BY is_pinned DESC, update_at DESC" to
                    "index_ConversationEntity_scope_assistant_id_parent_conversation_id_is_pinned_update_at",
                "SELECT * FROM ConversationEntity WHERE scope='$scope' AND parent_conversation_id IS NULL " +
                    "AND assistant_id='managed~dep_example~assistant' AND folder_id='' " +
                    "ORDER BY is_pinned DESC, update_at DESC" to
                    "index_ConversationEntity_scope_assistant_id_parent_conversation_id_folder_id_is_pinned_update_at",
                "SELECT * FROM ConversationEntity WHERE scope='$scope' AND parent_conversation_id IS NULL " +
                    "AND folder_id='folder' ORDER BY is_pinned DESC, update_at DESC" to
                    "index_ConversationEntity_scope_folder_id_parent_conversation_id_is_pinned_update_at",
                "SELECT * FROM ConversationEntity WHERE scope='$scope' AND parent_conversation_id IS NULL " +
                    "AND is_pinned=1 ORDER BY update_at DESC" to
                    "index_ConversationEntity_scope_parent_conversation_id_is_pinned_update_at",
                "SELECT * FROM MemoryEntity WHERE scope='$scope' AND assistant_id='managed~dep_example~assistant' " +
                    "ORDER BY id ASC" to "index_MemoryEntity_scope_assistant_id",
                "SELECT * FROM artifact WHERE scope='$scope' AND folder='upload' AND state='ACTIVE' " +
                    "ORDER BY created_at DESC" to "index_artifact_scope_folder_created_at",
                "SELECT * FROM artifact WHERE scope='$scope' ORDER BY created_at DESC" to
                    "index_artifact_scope_created_at",
                "SELECT * FROM GenMediaEntity WHERE scope='$scope' ORDER BY create_at DESC" to
                    "index_GenMediaEntity_scope_create_at",
                "SELECT * FROM conversation_folder WHERE scope='$scope' " +
                    "AND assistant_id='managed~dep_example~assistant' ORDER BY sort_index, create_at" to
                    "index_conversation_folder_scope_assistant_id_sort_index_create_at",
                "SELECT * FROM favorites WHERE scope='$scope' AND type='node' ORDER BY created_at DESC" to
                    "index_favorites_scope_type_created_at",
            ).forEach { (sql, index) -> assertQueryPlan(db, sql, index) }
            assertQueryPlan(
                db,
                "SELECT * FROM ConversationEntity WHERE parent_conversation_id='parent'",
                "index_ConversationEntity_parent_conversation_id",
            )
        }
    }

    @Test
    fun malformedLegacyEnterprisePrincipalAbortsInsteadOfLeavingUnreadableRows() {
        val name = "migration-enterprise-malformed-principal"
        helper.createDatabase(name, 12).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,scope) " +
                    "VALUES('conversation','managed~platform~bad~dep_example~asd_one','title',1,2,'[]',0," +
                    "'enterprise~platform:bad!source~dep_example~dXNlcg')",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            helper.runMigrationsAndValidate(name, 13, true, Migration_12_13).close()
        }
    }

    private fun assertQueryPlan(db: SupportSQLiteDatabase, sql: String, expectedIndex: String) {
        val details = values(db, "EXPLAIN QUERY PLAN $sql")
        assertTrue("$sql: $details", details.any { expectedIndex in it })
        assertFalse("$sql: $details", details.any { "TEMP B-TREE" in it })
    }

    private fun values(db: SupportSQLiteDatabase, sql: String): List<String> = db.query(sql).use { cursor ->
        buildList {
            val column = if (cursor.getColumnIndex("detail") >= 0) cursor.getColumnIndexOrThrow("detail") else 0
            while (cursor.moveToNext()) add(cursor.getString(column))
        }
    }
}
