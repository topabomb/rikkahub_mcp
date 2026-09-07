package net.weero.measix.pilot.data.db.migrations

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_11_12Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val roots = listOf("ConversationEntity", "MemoryEntity", "artifact", "GenMediaEntity", "conversation_folder", "favorites")

    @Test
    fun existingRootsAndReferencesKeepTheirValuesAndReceivePersonalScope() {
        val name = "migration-scope-preservation"
        context.deleteDatabase(name)
        val before = helper.createDatabase(name, 11).use { db ->
            seed(db)
            roots.associateWith { rows(db, "SELECT * FROM `$it` ORDER BY rowid") }
        }
        helper.runMigrationsAndValidate(name, 12, true, Migration_11_12).use { db ->
            roots.forEach { table ->
                val after = rows(db, "SELECT * FROM `$table` ORDER BY rowid")
                assertEquals(before.getValue(table), after.map { it.dropLast(1) })
                assertTrue(after.all { it.last() == "personal" })
            }
            assertEquals(listOf(listOf("parent")), rows(db, "SELECT parent_conversation_id FROM ConversationEntity WHERE id = 'child'"))
            assertEquals(listOf(listOf("parent")), rows(db, "SELECT conversation_id FROM message_node WHERE id = 'node'"))
            assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
            assertFalse(rows(db, "PRAGMA table_info(message_node)").any { it[1] == "scope" })
        }
        context.deleteDatabase(name)
    }

    @Test
    fun failedUpgradeRollsBackEveryRootAndReopeningCanRetry() {
        val name = "migration-scope-rollback"
        context.deleteDatabase(name)
        helper.createDatabase(name, 11).use(::seed)
        val failing = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                var changes = 0
                Migration_11_12.migrate(object : SupportSQLiteDatabase by db {
                    override fun execSQL(sql: String) {
                        if (++changes == 3) throw IllegalStateException("simulated upgrade interruption")
                        db.execSQL(sql)
                    }
                })
            }
        }
        assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(name, 12, true, failing).close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(11, db.version)
            roots.forEach { table ->
                db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                    while (cursor.moveToNext()) assertNotEquals("scope", cursor.getString(1))
                }
            }
        }
        helper.runMigrationsAndValidate(name, 12, true, Migration_11_12).use { db ->
            assertEquals(listOf(listOf("retained memory", "personal")), rows(db, "SELECT content, scope FROM MemoryEntity WHERE id = 7"))
        }
        context.deleteDatabase(name)
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,folder_id) VALUES('parent','assistant','kept title',11,12,'[]',1,'folder')")
        db.execSQL("INSERT INTO ConversationEntity(id,assistant_id,title,create_at,update_at,suggestions,is_pinned,parent_conversation_id) VALUES('child','assistant','child title',13,14,'[]',0,'parent')")
        db.execSQL("INSERT INTO message_node(id,conversation_id,node_index,messages,select_index) VALUES('node','parent',0,'[]',0)")
        db.execSQL("INSERT INTO MemoryEntity(id,assistant_id,content) VALUES(7,'assistant','retained memory')")
        db.execSQL("INSERT INTO artifact(id,folder,relative_path,display_name,mime_type,size_bytes,created_at,updated_at) VALUES(9,'upload','upload/kept.png','kept.png','image/png',123,11,12)")
        db.execSQL("INSERT INTO GenMediaEntity(id,path,model_id,prompt,create_at) VALUES(11,'images/kept.png','model','kept prompt',13)")
        db.execSQL("INSERT INTO conversation_folder(id,assistant_id,name,sort_index,create_at) VALUES('folder','assistant','kept folder',3,14)")
        db.execSQL("INSERT INTO favorites(id,type,ref_key,ref_json,snapshot_json,created_at,updated_at) VALUES('favorite','node','node:parent:node','{}','{}',15,16)")
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }
}
