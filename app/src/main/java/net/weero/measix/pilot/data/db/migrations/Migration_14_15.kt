package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.weero.measix.pilot.data.db.transcript.migrateEnterpriseTranscriptReferences

/** Repairs identity slots missed by the original enterprise principal migration. No schema changes. */
val Migration_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        migrateEnterpriseTranscriptReferences(db)
    }
}
