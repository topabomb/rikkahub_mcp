package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus

@Dao
interface ToolExecutionDAO {
    @Upsert
    suspend fun upsert(execution: ToolExecutionEntity)

    @Upsert
    suspend fun upsertAll(executions: List<ToolExecutionEntity>)

    @Query("SELECT * FROM tool_execution WHERE execution_id = :executionId")
    suspend fun getById(executionId: String): ToolExecutionEntity?

    @Query("SELECT * FROM tool_execution WHERE turn_id = :turnId ORDER BY created_at ASC, tool_ordinal ASC")
    suspend fun getByTurnId(turnId: String): List<ToolExecutionEntity>

    @Query(
        "UPDATE tool_execution SET status = :targetStatus, reason = :reason, updated_at = :updatedAt " +
            "WHERE status = :sourceStatus AND turn_id IN (" +
            "SELECT turn_id FROM turn_execution WHERE status IN (:sourceTurnStatuses))"
    )
    suspend fun updateStatusForTurns(
        sourceStatus: ToolExecutionStatus,
        sourceTurnStatuses: List<TurnExecutionStatus>,
        targetStatus: ToolExecutionStatus,
        reason: String,
        updatedAt: Long,
    ): Int
}
