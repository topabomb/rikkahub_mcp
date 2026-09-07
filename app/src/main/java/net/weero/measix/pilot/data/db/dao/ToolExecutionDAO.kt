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
            "(execution_id, turn_id, step_id, local_call_id, status, reason, child_conversation_id, " +
            "child_turn_id, sub_assistant_run_id, created_at, updated_at) " +
            "SELECT :executionId, :turnId, :stepId, :localCallId, 'STARTED', :reason, :childConversationId, " +
            ":childTurnId, :subAssistantRunId, :createdAt, :updatedAt " +
            "WHERE EXISTS (SELECT 1 FROM turn_execution WHERE turn_id = :turnId " +
            "AND status IN ('RUNNING', 'AWAITING_USER'))"
    )
    suspend fun insertStartedIfTurnActive(
        executionId: String,
        turnId: String,
        stepId: String,
        localCallId: String,
        reason: String?,
        childConversationId: String?,
        childTurnId: String?,
        subAssistantRunId: String?,
        createdAt: Long,
        updatedAt: Long,
    ): Long

    /** Updates a repeated STARTED checkpoint only while both tool and owning turn remain active. */
    @Query(
        "UPDATE tool_execution SET reason = :reason, " +
            "child_conversation_id = COALESCE(child_conversation_id, :childConversationId), " +
            "child_turn_id = COALESCE(child_turn_id, :childTurnId), " +
            "sub_assistant_run_id = COALESCE(sub_assistant_run_id, :subAssistantRunId), updated_at = :updatedAt " +
            "WHERE execution_id = :executionId AND turn_id = :turnId AND local_call_id = :localCallId " +
            "AND status = 'STARTED' " +
            "AND (:childConversationId IS NULL OR child_conversation_id IS NULL " +
            "OR child_conversation_id = :childConversationId) " +
            "AND EXISTS (SELECT 1 FROM turn_execution " +
            "WHERE turn_id = :turnId AND status IN ('RUNNING', 'AWAITING_USER'))"
    )
    suspend fun updateStartedIfTurnActive(
        executionId: String,
        turnId: String,
        localCallId: String,
        reason: String?,
        childConversationId: String?,
        childTurnId: String?,
        subAssistantRunId: String?,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE tool_execution SET status = :targetStatus, reason = :reason, " +
            "child_conversation_id = COALESCE(child_conversation_id, :childConversationId), " +
            "child_turn_id = COALESCE(child_turn_id, :childTurnId), " +
            "sub_assistant_run_id = COALESCE(sub_assistant_run_id, :subAssistantRunId), updated_at = :updatedAt " +
            "WHERE execution_id = :executionId AND turn_id = :turnId AND local_call_id = :localCallId " +
            "AND status IN (:sourceStatuses) " +
            "AND ((:childConversationId IS NULL AND child_conversation_id IS NULL) " +
            "OR child_conversation_id = :childConversationId)"
    )
    suspend fun transition(
        executionId: String,
        turnId: String,
        localCallId: String,
        sourceStatuses: List<ToolExecutionStatus>,
        targetStatus: ToolExecutionStatus,
        reason: String?,
        childConversationId: String?,
        childTurnId: String?,
        subAssistantRunId: String?,
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

    @Query("SELECT * FROM tool_execution WHERE turn_id = :turnId ORDER BY created_at ASC, local_call_id ASC")
    suspend fun getByTurnId(turnId: String): List<ToolExecutionEntity>

}
