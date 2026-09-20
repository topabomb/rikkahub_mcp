package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.weero.measix.pilot.data.configuration.LegacyEnterprisePrincipalEncoding

/** Drops the retired URL-derived source from every durable enterprise principal and reference. */
val Migration_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val scopedTables = listOf(
            "ConversationEntity",
            "MemoryEntity",
            "artifact",
            "GenMediaEntity",
            "conversation_folder",
            "favorites",
        )
        scopedTables.forEach { table ->
            val replacements = mutableListOf<Pair<String, String>>()
            db.query("SELECT DISTINCT `scope` FROM `$table` WHERE `scope` <> 'personal'").use { cursor ->
                while (cursor.moveToNext()) {
                    val old = cursor.getString(0)
                    val new = requireNotNull(LegacyEnterprisePrincipalEncoding.migrateScopeStorageKey(old)) {
                        "invalid_legacy_enterprise_scope"
                    }
                    replacements += old to new
                }
            }
            replacements.forEach { (old, new) ->
                db.execSQL("UPDATE `$table` SET `scope` = ? WHERE `scope` = ?", arrayOf(new, old))
            }
        }
        listOf(
            "ConversationEntity" to "assistant_id",
            "MemoryEntity" to "assistant_id",
            "conversation_folder" to "assistant_id",
        ).forEach { (table, column) ->
            val replacements = mutableListOf<Pair<String, String>>()
            db.query("SELECT DISTINCT `$column` FROM `$table` WHERE `$column` LIKE 'managed~%'").use { cursor ->
                while (cursor.moveToNext()) {
                    val old = cursor.getString(0)
                    val new = requireNotNull(LegacyEnterprisePrincipalEncoding.migrateReference(old)) {
                        "invalid_legacy_enterprise_reference"
                    }
                    replacements += old to new
                }
            }
            replacements.forEach { (old, new) ->
                db.execSQL("UPDATE `$table` SET `$column` = ? WHERE `$column` = ?", arrayOf(new, old))
            }
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
