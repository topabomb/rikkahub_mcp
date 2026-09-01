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

    /** scoped read 授权检查：该会话的消息节点是否以指定类型引用此 artifact。 */
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM artifact_reference ar
        JOIN message_node mn ON mn.id = ar.node_id
        WHERE ar.artifact_id = :artifactId AND mn.conversation_id = :conversationId AND ar.reference_type = :referenceType)
        """
    )
    suspend fun existsInConversation(artifactId: Long, conversationId: String, referenceType: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(references: List<ArtifactReferenceEntity>)

    @Query("DELETE FROM artifact_reference")
    suspend fun deleteAll()

    /** upserted 节点的引用替换语义第一步（第二步 insertAll） */
    @Query("DELETE FROM artifact_reference WHERE node_id IN (:nodeIds)")
    suspend fun deleteByNodeIds(nodeIds: List<String>)

}
