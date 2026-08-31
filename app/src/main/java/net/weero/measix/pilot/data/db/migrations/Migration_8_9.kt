package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Index-only migration; row values, foreign keys and existing uniqueness constraints stay unchanged. */
val Migration_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_GenMediaEntity_path ON GenMediaEntity(path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_GenMediaEntity_create_at ON GenMediaEntity(create_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_MemoryEntity_assistant_id ON MemoryEntity(assistant_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_message_node_conversation_id_node_index ON message_node(conversation_id, node_index)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_folder_created_at ON artifact(folder, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_state_created_at ON artifact(state, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ConversationEntity_parent_conversation_id_is_pinned_update_at ON ConversationEntity(parent_conversation_id, is_pinned, update_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ConversationEntity_assistant_id_parent_conversation_id_is_pinned_update_at ON ConversationEntity(assistant_id, parent_conversation_id, is_pinned, update_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ConversationEntity_assistant_id_parent_conversation_id_folder_id_is_pinned_update_at ON ConversationEntity(assistant_id, parent_conversation_id, folder_id, is_pinned, update_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ConversationEntity_folder_id_parent_conversation_id_is_pinned_update_at ON ConversationEntity(folder_id, parent_conversation_id, is_pinned, update_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_folder_assistant_id_sort_index_create_at ON conversation_folder(assistant_id, sort_index, create_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_type_created_at ON favorites(type, created_at)")

        db.execSQL("DROP INDEX IF EXISTS index_message_node_conversation_id")
        db.execSQL("DROP INDEX IF EXISTS index_artifact_folder")
        db.execSQL("DROP INDEX IF EXISTS index_artifact_state")
        db.execSQL("DROP INDEX IF EXISTS index_ConversationEntity_parent_conversation_id")
        db.execSQL("DROP INDEX IF EXISTS index_ConversationEntity_assistant_id")
        db.execSQL("DROP INDEX IF EXISTS index_conversation_folder_assistant_id")
        db.execSQL("DROP INDEX IF EXISTS index_favorites_type")
        db.execSQL("DROP INDEX IF EXISTS index_artifact_reference_artifact_id")
    }
}
