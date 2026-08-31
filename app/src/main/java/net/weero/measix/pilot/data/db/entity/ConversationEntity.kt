package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话表。主/子会话同构：Child 与 Master 唯一差别是关系，由 [parentConversationId]
 * 自引用外键表达（ON DELETE CASCADE——孤儿 Child 结构上不可能产生）。
 */
@Entity(
    tableName = "ConversationEntity",
    indices = [
        Index(value = ["parent_conversation_id", "is_pinned", "update_at"]),
        Index(value = ["assistant_id", "parent_conversation_id", "is_pinned", "update_at"]),
        Index(value = ["assistant_id", "parent_conversation_id", "folder_id", "is_pinned", "update_at"]),
        Index(value = ["folder_id", "parent_conversation_id", "is_pinned", "update_at"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("tags", defaultValue = "[]")
    val tags: String = "[]",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    @ColumnInfo("parent_conversation_id", defaultValue = "NULL")
    val parentConversationId: String? = null,
)
