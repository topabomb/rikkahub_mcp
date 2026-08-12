package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 → v3: 会话文件夹支持。
 *
 * 0.0.15 仍需保留这段历史升级路径，保证用户可以从数据库 v1/v2 直接升级到 v4。
 */
val Migration_2_3 = object : Migration(2, 3) {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''"
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_folder` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `sort_index` INTEGER NOT NULL DEFAULT 0,
                `create_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_folder_assistant_id` " +
                "ON `conversation_folder` (`assistant_id`)"
        )
    }
}
