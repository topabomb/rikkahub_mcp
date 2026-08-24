package net.weero.measix.pilot.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus

/** 非终态 turn 事实行 + 所属会话域（Master / Child）——恢复路径的定点输入。 */
data class ScopedTurnExecution(
    @Embedded val execution: TurnExecutionEntity,
    /** 所属会话是否为 Child（parent_conversation_id 非空）。 */
    @ColumnInfo(name = "is_child") val isChild: Boolean,
    @ColumnInfo(name = "parent_conversation_id") val parentConversationId: String?,
)

@Dao
interface TurnExecutionDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(execution: TurnExecutionEntity): Long

    @Query(
        "UPDATE turn_execution SET status = :targetStatus, reason = :reason, updated_at = :updatedAt " +
            "WHERE turn_id = :turnId AND conversation_id = :conversationId " +
            "AND assistant_message_id = :assistantMessageId AND status IN (:sourceStatuses)"
    )
    suspend fun transition(
        turnId: String,
        conversationId: String,
        sourceStatuses: List<TurnExecutionStatus>,
        targetStatus: TurnExecutionStatus,
        reason: String?,
        assistantMessageId: String?,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM turn_execution WHERE turn_id = :turnId")
    suspend fun getById(turnId: String): TurnExecutionEntity?

    @Query("SELECT * FROM turn_execution WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getByConversationId(conversationId: String): List<TurnExecutionEntity>

    /** 恢复候选：非终态 turn 事实 JOIN 会话表区分 Master/Child（恢复成本与库大小解耦）。 */
    @Query(
        "SELECT turn_execution.*, ConversationEntity.parent_conversation_id, " +
            "CASE WHEN ConversationEntity.parent_conversation_id IS NULL THEN 0 ELSE 1 END AS is_child " +
            "FROM turn_execution " +
            "JOIN ConversationEntity ON turn_execution.conversation_id = ConversationEntity.id " +
            "WHERE turn_execution.status IN (:statuses) " +
            "ORDER BY turn_execution.updated_at ASC"
    )
    suspend fun getByStatusesWithScope(statuses: List<TurnExecutionStatus>): List<ScopedTurnExecution>

    /** 恢复候选（仅 Master 会话；Child 会话的 turn 由子助手恢复域全权收口）。 */
    @Query(
        "SELECT turn_execution.* FROM turn_execution " +
            "JOIN ConversationEntity ON turn_execution.conversation_id = ConversationEntity.id " +
            "WHERE turn_execution.status IN (:statuses) " +
            "AND ConversationEntity.parent_conversation_id IS NULL " +
            "ORDER BY turn_execution.updated_at ASC"
    )
    suspend fun getMasterByStatuses(statuses: List<TurnExecutionStatus>): List<TurnExecutionEntity>

}
