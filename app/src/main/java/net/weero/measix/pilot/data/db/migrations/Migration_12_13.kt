package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Drops the retired URL-derived source from every durable enterprise principal and reference. */
val Migration_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "ConversationEntity",
            "MemoryEntity",
            "artifact",
            "GenMediaEntity",
            "conversation_folder",
            "favorites",
        ).forEach { table ->
            db.execSQL(
                """UPDATE `$table`
                    SET `scope` = 'enterprise~' || substr(`scope`, 12 + instr(substr(`scope`, 12), '~'))
                    WHERE `scope` LIKE 'enterprise~platform:%~%~%'""".trimIndent(),
            )
        }
        listOf(
            "ConversationEntity" to "assistant_id",
            "MemoryEntity" to "assistant_id",
            "conversation_folder" to "assistant_id",
        ).forEach { (table, column) ->
            db.execSQL(
                """UPDATE `$table`
                    SET `$column` = 'managed~' || substr(`$column`, 9 +
                        instr(substr(`$column`, 9), '~') +
                        instr(substr(substr(`$column`, 9), instr(substr(`$column`, 9), '~') + 1), '~'))
                    WHERE `$column` LIKE 'managed~platform~%~%~%'""".trimIndent(),
            )
        }

        listOf(
            "index_ConversationEntity_parent_conversation_id_is_pinned_update_at",
            "index_ConversationEntity_assistant_id_parent_conversation_id_is_pinned_update_at",
            "index_ConversationEntity_assistant_id_parent_conversation_id_folder_id_is_pinned_update_at",
            "index_ConversationEntity_folder_id_parent_conversation_id_is_pinned_update_at",
            "index_MemoryEntity_assistant_id",
            "index_conversation_folder_assistant_id_sort_index_create_at",
            "index_favorites_type_created_at",
        ).forEach { index -> db.execSQL("DROP INDEX `$index`") }
        listOf(
            "CREATE INDEX `index_ConversationEntity_parent_conversation_id` ON `ConversationEntity` (`parent_conversation_id`)",
            "CREATE INDEX `index_ConversationEntity_scope_parent_conversation_id_is_pinned_update_at` ON `ConversationEntity` (`scope`, `parent_conversation_id`, `is_pinned`, `update_at`)",
            "CREATE INDEX `index_ConversationEntity_scope_assistant_id_parent_conversation_id_is_pinned_update_at` ON `ConversationEntity` (`scope`, `assistant_id`, `parent_conversation_id`, `is_pinned`, `update_at`)",
            "CREATE INDEX `index_ConversationEntity_scope_assistant_id_parent_conversation_id_folder_id_is_pinned_update_at` ON `ConversationEntity` (`scope`, `assistant_id`, `parent_conversation_id`, `folder_id`, `is_pinned`, `update_at`)",
            "CREATE INDEX `index_ConversationEntity_scope_folder_id_parent_conversation_id_is_pinned_update_at` ON `ConversationEntity` (`scope`, `folder_id`, `parent_conversation_id`, `is_pinned`, `update_at`)",
            "CREATE INDEX `index_MemoryEntity_scope_assistant_id` ON `MemoryEntity` (`scope`, `assistant_id`)",
            "CREATE INDEX `index_artifact_scope_folder_created_at` ON `artifact` (`scope`, `folder`, `created_at`)",
            "CREATE INDEX `index_artifact_scope_created_at` ON `artifact` (`scope`, `created_at`)",
            "CREATE INDEX `index_GenMediaEntity_scope_create_at` ON `GenMediaEntity` (`scope`, `create_at`)",
            "CREATE INDEX `index_conversation_folder_scope_assistant_id_sort_index_create_at` ON `conversation_folder` (`scope`, `assistant_id`, `sort_index`, `create_at`)",
            "CREATE INDEX `index_favorites_scope_type_created_at` ON `favorites` (`scope`, `type`, `created_at`)",
        ).forEach(db::execSQL)
    }
}
