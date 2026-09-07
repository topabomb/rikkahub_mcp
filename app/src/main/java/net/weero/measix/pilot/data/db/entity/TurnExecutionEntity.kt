package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TurnExecutionStatus {
    RUNNING,
    AWAITING_USER,
    COMPLETED,
    CANCELLED,
    FAILED,
    INCOMPLETE,
    INTERRUPTED,
}

@Entity(
    tableName = "turn_execution",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id"), Index("status")],
)
data class TurnExecutionEntity(
    @PrimaryKey
    @ColumnInfo("turn_id")
    val turnId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("assistant_message_id")
    val assistantMessageId: String?,
    @ColumnInfo("status")
    val status: TurnExecutionStatus,
    @ColumnInfo("reason")
    val reason: String?,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
