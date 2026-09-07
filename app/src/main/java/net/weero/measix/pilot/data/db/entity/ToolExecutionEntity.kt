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

/**
 * The durable execution fact of one Tool Call, created only after a side effect begins.
 *
 * Identity is `(turn_id, local_call_id)` — a call is located by its owning Turn and its own local
 * call id, never by a positional ordinal. `step_id` records which Step requested the call.
 * `child_turn_id` / `sub_assistant_run_id` carry the Child lineage for `assistant_call` executions.
 */
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
    indices = [
        Index("turn_id"),
        Index("child_conversation_id"),
        Index("child_turn_id"),
        Index("sub_assistant_run_id"),
        Index(value = ["turn_id", "local_call_id"], unique = true),
    ],
)
data class ToolExecutionEntity(
    @PrimaryKey
    @ColumnInfo("execution_id")
    val executionId: String,
    @ColumnInfo("turn_id")
    val turnId: String,
    @ColumnInfo("step_id")
    val stepId: String,
    @ColumnInfo("local_call_id")
    val localCallId: String,
    @ColumnInfo("status")
    val status: ToolExecutionStatus,
    @ColumnInfo("reason")
    val reason: String?,
    /** assistant_call 派生的 Child 会话 id；其余工具为 null（调用↔Child 关系归位到执行事实行）。 */
    @ColumnInfo("child_conversation_id")
    val childConversationId: String? = null,
    /** assistant_call 派生的 Child Turn id；与 child_conversation_id 一起表达父子执行链。 */
    @ColumnInfo("child_turn_id")
    val childTurnId: String? = null,
    /** assistant_call 的稳定运行 id（sub_assistant_run）。 */
    @ColumnInfo("sub_assistant_run_id")
    val subAssistantRunId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
