package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.weero.measix.pilot.data.configuration.PERSONAL_SCOPE_KEY

/** Existing records keep their identities and graph; their initial principal is the personal space. */
val Migration_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("ConversationEntity", "MemoryEntity", "artifact", "GenMediaEntity", "conversation_folder", "favorites").forEach { table ->
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `scope` TEXT NOT NULL DEFAULT '$PERSONAL_SCOPE_KEY'")
        }
    }
}
