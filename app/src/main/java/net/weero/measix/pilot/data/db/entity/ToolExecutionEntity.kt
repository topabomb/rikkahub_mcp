package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ToolExecutionStatus {
    STARTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

@Entity(
    tableName = "tool_execution",
    foreignKeys = [
        ForeignKey(
            entity = TurnExecutionEntity::class,
            parentColumns = ["turn_id"],
            childColumns = ["turn_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("turn_id"), Index("status"), Index("child_conversation_id")],
)
data class ToolExecutionEntity(
    @PrimaryKey
    @ColumnInfo("execution_id")
    val executionId: String,
    @ColumnInfo("turn_id")
    val turnId: String,
    @ColumnInfo("tool_ordinal")
    val toolOrdinal: Int,
    @ColumnInfo("status")
    val status: ToolExecutionStatus,
    @ColumnInfo("reason")
    val reason: String?,
    /** assistant_call 派生的 Child 会话 id；其余工具为 null（调用↔Child 关系归位到执行事实行）。 */
    @ColumnInfo("child_conversation_id")
    val childConversationId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
