package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus

@Dao
interface TurnExecutionDAO {
    @Upsert
    suspend fun upsert(execution: TurnExecutionEntity)

    @Query("SELECT * FROM turn_execution WHERE turn_id = :turnId")
    suspend fun getById(turnId: String): TurnExecutionEntity?

    @Query("SELECT * FROM turn_execution WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun getByConversationId(conversationId: String): List<TurnExecutionEntity>

    @Query("SELECT * FROM turn_execution WHERE status IN (:statuses) ORDER BY updated_at ASC")
    suspend fun getByStatuses(statuses: List<TurnExecutionStatus>): List<TurnExecutionEntity>

    @Query(
        "UPDATE turn_execution SET status = :targetStatus, reason = :reason, updated_at = :updatedAt " +
            "WHERE status IN (:sourceStatuses)"
    )
    suspend fun updateStatuses(
        sourceStatuses: List<TurnExecutionStatus>,
        targetStatus: TurnExecutionStatus,
        reason: String,
        updatedAt: Long,
    ): Int
}
