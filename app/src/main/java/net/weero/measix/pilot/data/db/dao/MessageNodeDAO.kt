package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    /**
     * IGNORE + UPDATE 组合的真 upsert（不使用 REPLACE）。
     *
     * SQLite 的 INSERT OR REPLACE 对已存在主键执行 DELETE + INSERT——FK 级联启用
     * （v7 起）后会级联删除该节点的全部 artifact_reference 行，在事务提交与引用
     * 重建（syncReferences）之间留下 GC 误删窗口。IGNORE 跳过已存在行，UPDATE
     * 命中全列更新：无 DELETE 语义、无级联。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(node: MessageNodeEntity)

    /** delta upsert 原语（delta 持久化）。 */
    @Transaction
    suspend fun upsertAll(nodes: List<MessageNodeEntity>) {
        if (nodes.isEmpty()) return
        insertAll(nodes)
        updateAll(nodes)
    }

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Update
    suspend fun updateAll(nodes: List<MessageNodeEntity>)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("DELETE FROM message_node WHERE id IN (:nodeIds)")
    suspend fun deleteByIds(nodeIds: List<String>)

    /** 会话全部 node id（ArtifactStore backfill/inspect 用）。 */
    @Query("SELECT id FROM message_node WHERE conversation_id = :conversationId")
    suspend fun getNodeIdsOfConversation(conversationId: String): List<String>

    /** 单 node 的 messages JSON（ArtifactStore backfill 用）。 */
    @Query("SELECT messages FROM message_node WHERE id = :nodeId")
    suspend fun getMessagesJsonById(nodeId: String): String?

    /**
     * 在排除指定会话后，按 LIKE 探测仍包含 [needle] 的 messages JSON。
     * 只返回 JSON 文本，不组装 Conversation / MessageNode。
     */
    @Query(
        "SELECT messages FROM message_node " +
            "WHERE conversation_id NOT IN (:excludedIds) " +
            "AND messages LIKE '%' || :needle || '%' ESCAPE '\\'"
    )
    suspend fun findMessagesJsonContaining(
        excludedIds: List<String>,
        needle: String,
    ): List<String>

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(CASE WHEN c.parent_conversation_id IS NULL THEN 1 END) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn " +
        "JOIN conversationentity c ON c.id = mn.conversation_id, " +
        "json_each(mn.messages) j"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn " +
                "JOIN conversationentity c ON c.id = mn.conversation_id, " +
                "json_each(mn.messages) j " +
                "WHERE c.parent_conversation_id IS NULL " +
                "AND json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )

