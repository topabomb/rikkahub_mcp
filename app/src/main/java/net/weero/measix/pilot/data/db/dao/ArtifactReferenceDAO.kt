package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity

@Dao
interface ArtifactReferenceDAO {
    @Query("SELECT EXISTS(SELECT 1 FROM artifact_reference WHERE artifact_id = :artifactId)")
    suspend fun existsByArtifactId(artifactId: Long): Boolean

    /** 引用会话（诊断信息）：走 artifact_id 索引 + message_node 主键 join */
    @Query(
        """
        SELECT DISTINCT mn.conversation_id FROM artifact_reference ar
        JOIN message_node mn ON mn.id = ar.node_id
        WHERE ar.artifact_id = :artifactId
        """
    )
    suspend fun referencingConversationIds(artifactId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(references: List<ArtifactReferenceEntity>)

    /** upserted 节点的引用替换语义第一步（第二步 insertAll） */
    @Query("DELETE FROM artifact_reference WHERE node_id IN (:nodeIds)")
    suspend fun deleteByNodeIds(nodeIds: List<String>)

    /** 会话级兜底清理（node FK 级联已覆盖主路径，此为显式备份入口） */
    @Query("DELETE FROM artifact_reference WHERE node_id IN (SELECT id FROM message_node WHERE conversation_id = :conversationId)")
    suspend fun deleteByConversationId(conversationId: String)
}
