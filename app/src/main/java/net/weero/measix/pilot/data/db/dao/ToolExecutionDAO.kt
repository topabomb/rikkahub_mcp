package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus

@Dao
interface ToolExecutionDAO {
    /** Atomically creates STARTED only while the owning turn is active; no turn pre-read. */
    @Query(
        "INSERT OR IGNORE INTO tool_execution " +
            "(execution_id, turn_id, tool_ordinal, status, reason, child_conversation_id, created_at, updated_at) " +
            "SELECT :executionId, :turnId, :toolOrdinal, 'STARTED', :reason, :childConversationId, " +
            ":createdAt, :updatedAt " +
            "WHERE EXISTS (SELECT 1 FROM turn_execution WHERE turn_id = :turnId " +
            "AND status IN ('RUNNING', 'AWAITING_APPROVAL'))"
    )
    suspend fun insertStartedIfTurnActive(
        executionId: String,
        turnId: String,
        toolOrdinal: Int,
        reason: String?,
        childConversationId: String?,
        createdAt: Long,
        updatedAt: Long,
    ): Long

    /** Updates a repeated STARTED checkpoint only while both tool and owning turn remain active. */
    @Query(
        "UPDATE tool_execution SET reason = :reason, " +
            "child_conversation_id = COALESCE(child_conversation_id, :childConversationId), updated_at = :updatedAt " +
            "WHERE execution_id = :executionId AND turn_id = :turnId AND tool_ordinal = :toolOrdinal " +
            "AND status = 'STARTED' " +
            "AND (:childConversationId IS NULL OR child_conversation_id IS NULL " +
            "OR child_conversation_id = :childConversationId) " +
            "AND EXISTS (SELECT 1 FROM turn_execution " +
            "WHERE turn_id = :turnId AND status IN ('RUNNING', 'AWAITING_APPROVAL'))"
    )
    suspend fun updateStartedIfTurnActive(
        executionId: String,
        turnId: String,
        toolOrdinal: Int,
        reason: String?,
        childConversationId: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE tool_execution SET status = :targetStatus, reason = :reason, " +
            "child_conversation_id = COALESCE(child_conversation_id, :childConversationId), updated_at = :updatedAt " +
            "WHERE execution_id = :executionId AND turn_id = :turnId AND tool_ordinal = :toolOrdinal " +
            "AND status IN (:sourceStatuses) " +
            "AND ((:childConversationId IS NULL AND child_conversation_id IS NULL) " +
            "OR child_conversation_id = :childConversationId)"
    )
    suspend fun transition(
        executionId: String,
        turnId: String,
        toolOrdinal: Int,
        sourceStatuses: List<ToolExecutionStatus>,
        targetStatus: ToolExecutionStatus,
        reason: String?,
        childConversationId: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE tool_execution SET status = :targetStatus, reason = :reason, updated_at = :updatedAt " +
            "WHERE turn_id = :turnId AND status = 'STARTED'"
    )
    suspend fun transitionStartedByTurn(
        turnId: String,
        targetStatus: ToolExecutionStatus,
        reason: String?,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM tool_execution WHERE execution_id = :executionId")
    suspend fun getById(executionId: String): ToolExecutionEntity?

    @Query("SELECT * FROM tool_execution WHERE turn_id = :turnId ORDER BY created_at ASC, tool_ordinal ASC")
    suspend fun getByTurnId(turnId: String): List<ToolExecutionEntity>

}
