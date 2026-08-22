package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_4_5 = object : Migration(4, 5) {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `turn_execution` (
                `turn_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `assistant_message_id` TEXT,
                `status` TEXT NOT NULL,
                `reason` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`turn_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_turn_execution_conversation_id` " +
                "ON `turn_execution` (`conversation_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_turn_execution_status` ON `turn_execution` (`status`)"
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tool_execution` (
                `execution_id` TEXT NOT NULL,
                `turn_id` TEXT NOT NULL,
                `tool_ordinal` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `reason` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`execution_id`),
                FOREIGN KEY(`turn_id`) REFERENCES `turn_execution`(`turn_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_execution_turn_id` ON `tool_execution` (`turn_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_execution_status` ON `tool_execution` (`status`)"
        )
    }
}
