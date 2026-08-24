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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies every supported pre-v8 database path against the current schema. */
@RunWith(AndroidJUnit4::class)
class Migration_7_8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v7ToV8PreservesArtifactAndAddsNullablePayloadToken() {
        val db = helper.createDatabase("migration-v7-v8", 7)
        seedArtifact(db, "upload/v7.png", "GENERATED")
        db.close()

        val migrated = helper.runMigrationsAndValidate("migration-v7-v8", 8, true, Migration_7_8)
        assertArtifact(migrated, "upload/v7.png", "GENERATED")
        assertTrue(columns(migrated, "artifact").contains("payload_token"))
        assertNoForeignKeyViolations(migrated)
        migrated.close()
    }

    @Test
    fun v6ToV8PreservesDataAcrossBothMigrations() {
        val db = helper.createDatabase("migration-v6-v8", 6)
        seedArtifact(db, "upload/v6.png", "USER")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "migration-v6-v8",
            8,
            true,
            Migration_6_7,
            Migration_7_8,
        )
        assertArtifact(migrated, "upload/v6.png", "USER")
        assertNoForeignKeyViolations(migrated)
        migrated.close()
    }

    @Test
    fun v5ToV8PreservesManagedFileAcrossFullMigrationChain() {
        val db = helper.createDatabase("migration-v5-v8", 5)
        db.execSQL(
            "INSERT INTO managed_files " +
                "(folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at) " +
                "VALUES ('upload', 'upload/v5.png', 'asset.png', 'image/png', 17, 100, 200)"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "migration-v5-v8",
            8,
            true,
            Migration_5_6,
            Migration_6_7,
            Migration_7_8,
        )
        assertArtifact(migrated, "upload/v5.png", "USER")
        assertNoForeignKeyViolations(migrated)
        migrated.close()
    }

    @Test
    fun v1ToV8ExecutesCompleteHistoricalChainAndPreservesConversation() {
        val id = "conversation-v1-v8"
        val db = helper.createDatabase("migration-v1-v8", 1)
        db.execSQL(
            "INSERT INTO ConversationEntity " +
                "(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, " +
                "custom_system_prompt, mode_injection_ids, workspace_cwd) " +
                "VALUES (?, ?, ?, '[]', 10, 20, '[]', 1, '', '[]', '')",
            arrayOf<Any?>(id, "0950e2dc-9bd5-4801-afa3-aa887aa36b4e", "from-v1"),
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "migration-v1-v8",
            8,
            true,
            Migration_1_2,
            Migration_2_3,
            Migration_3_4,
            Migration_4_5,
            Migration_5_6,
            Migration_6_7,
            Migration_7_8,
        )
        query(migrated, "SELECT title, is_pinned FROM ConversationEntity WHERE id = '$id'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("from-v1", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertFalse(cursor.moveToNext())
        }
        assertNoForeignKeyViolations(migrated)
        migrated.close()
    }

    @Test
    fun migratedV8ArtifactSchemaMatchesFreshV8Schema() {
        val old = helper.createDatabase("migration-v8-schema-migrated", 7)
        old.close()
        val migrated = helper.runMigrationsAndValidate(
            "migration-v8-schema-migrated",
            8,
            true,
            Migration_7_8,
        )
        val fresh = helper.createDatabase("migration-v8-schema-fresh", 8)

        assertEquals(tableInfo(fresh, "artifact"), tableInfo(migrated, "artifact"))
        assertEquals(indexInfo(fresh, "artifact"), indexInfo(migrated, "artifact"))
        migrated.close()
        fresh.close()
    }

    private fun seedArtifact(db: SupportSQLiteDatabase, path: String, origin: String) {
        db.execSQL(
            "INSERT INTO artifact " +
                "(folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at, state, origin) " +
                "VALUES ('upload', '$path', 'asset.png', 'image/png', 17, 100, 200, 'ACTIVE', '$origin')"
        )
    }

    private fun assertArtifact(db: SupportSQLiteDatabase, path: String, origin: String) {
        query(
            db,
            "SELECT folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at, " +
                "state, origin, payload_token FROM artifact WHERE relative_path = '$path'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("upload", cursor.getString(0))
            assertEquals(path, cursor.getString(1))
            assertEquals("asset.png", cursor.getString(2))
            assertEquals("image/png", cursor.getString(3))
            assertEquals(17L, cursor.getLong(4))
            assertEquals(100L, cursor.getLong(5))
            assertEquals(200L, cursor.getLong(6))
            assertEquals("ACTIVE", cursor.getString(7))
            assertEquals(origin, cursor.getString(8))
            assertTrue(cursor.isNull(9))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        query(db, "PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        tableInfo(db, table).mapTo(mutableSetOf()) { it.substringBefore(':') }

    private fun tableInfo(db: SupportSQLiteDatabase, table: String): List<String> {
        val result = mutableListOf<String>()
        query(db, "PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) {
                result += listOf("name", "type", "notnull", "dflt_value", "pk")
                    .joinToString(":") { key ->
                        val index = cursor.getColumnIndexOrThrow(key)
                        if (cursor.isNull(index)) "NULL" else cursor.getString(index)
                    }
            }
        }
        return result.sorted()
    }

    private fun indexInfo(db: SupportSQLiteDatabase, table: String): Map<String, List<String>> {
        val result = sortedMapOf<String, List<String>>()
        query(db, "PRAGMA index_list($table)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val columns = mutableListOf<String>()
                query(db, "PRAGMA index_info($name)").use { indexCursor ->
                    while (indexCursor.moveToNext()) {
                        columns += indexCursor.getString(indexCursor.getColumnIndexOrThrow("name"))
                    }
                }
                result[name] = columns
            }
        }
        return result
    }

    private fun query(db: SupportSQLiteDatabase, sql: String): Cursor = db.query(sql, emptyArray<Any?>())
}
