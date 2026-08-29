package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT id, node_index AS nodeIndex, select_index AS selectIndex, " +
            "length(messages) AS messagesLength FROM message_node " +
            "WHERE conversation_id = :conversationId ORDER BY node_index ASC"
    )
    suspend fun getNodeHeadersOfConversation(conversationId: String): List<MessageNodePayloadHeader>

    /** Reads one bounded TEXT slice so a single large message cannot overflow CursorWindow. */
    @Query("SELECT substr(messages, :startOneBased, :length) FROM message_node WHERE id = :nodeId")
    suspend fun getMessagesSlice(nodeId: String, startOneBased: Int, length: Int): String?

    /**
     * IGNORE + UPDATE 组合的真 upsert（不使用 REPLACE）。
     *
     * SQLite 的 INSERT OR REPLACE 对已存在主键执行 DELETE + INSERT，会触发
     * artifact_reference 的 FK 级联并掩盖引用投影的显式写协议。IGNORE 跳过已存在行，
     * UPDATE 命中全列更新：无 DELETE 语义、无隐式级联。
     */
    @Insert
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(nodes: List<MessageNodeEntity>): List<Long>

    /** delta upsert 原语（delta 持久化）。 */
    @Transaction
    suspend fun upsertAll(nodes: List<MessageNodeEntity>) {
        if (nodes.isEmpty()) return
        val insertResults = insertAllIgnoringConflicts(nodes)
        check(insertResults.size == nodes.size) { "message node insert receipt count mismatch" }
        nodes.forEachIndexed { index, node ->
            if (insertResults[index] == -1L) {
                check(updateOwned(
                    id = node.id,
                    conversationId = node.conversationId,
                    nodeIndex = node.nodeIndex,
                    messages = node.messages,
                    selectIndex = node.selectIndex,
                ) == 1) {
                    "message node ${node.id} belongs to another conversation"
                }
            }
        }
    }

    @Query(
        "UPDATE message_node SET node_index = :nodeIndex, messages = :messages, " +
            "select_index = :selectIndex WHERE id = :id AND conversation_id = :conversationId"
    )
    suspend fun updateOwned(
        id: String,
        conversationId: String,
        nodeIndex: Int,
        messages: String,
        selectIndex: Int,
    ): Int

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("DELETE FROM message_node WHERE id IN (:nodeIds)")
    suspend fun deleteByIds(nodeIds: List<String>)

    /** Recovery integrity probe; false positives are intentionally fail-closed. */
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :needle) > 0)")
    suspend fun existsMessagesJsonContaining(needle: String): Boolean

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
    val coreNonExactMessages: Int = 0,
    val cacheReadNonExactMessages: Int = 0,
)

data class MessageNodePayloadHeader(
    val id: String,
    val nodeIndex: Int,
    val selectIndex: Int,
    val messagesLength: Int,
)

class MessagePayloadReadException(message: String) : IllegalStateException(message)

/** Single bounded TEXT reader shared by aggregate loading and every recovery projection. */
suspend fun MessageNodeDAO.readMessagesPayload(
    header: MessageNodePayloadHeader,
    owner: String,
): String {
    var consumedCharacters = 0
    var startOneBased = 1
    val payload = buildString(header.messagesLength) {
        while (consumedCharacters < header.messagesLength) {
            val requested = minOf(MESSAGE_PAYLOAD_SLICE_CHARS, header.messagesLength - consumedCharacters)
            val slice = getMessagesSlice(header.id, startOneBased, requested)
                ?: throw MessagePayloadReadException("missing message payload: $owner, node=${header.id}")
            if (slice.isEmpty() && requested > 0) {
                throw MessagePayloadReadException("truncated message payload: $owner, node=${header.id}")
            }
            append(slice)
            val sliceCharacters = slice.codePointCount(0, slice.length)
            consumedCharacters += sliceCharacters
            startOneBased += sliceCharacters
        }
    }
    if (consumedCharacters != header.messagesLength) {
        throw MessagePayloadReadException("message payload length mismatch: $owner, node=${header.id}")
    }
    return payload
}

private const val MESSAGE_PAYLOAD_SLICE_CHARS = 256 * 1024

data class MessageDayCount(val day: String, val count: Int)

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(CASE WHEN c.parent_conversation_id IS NULL THEN 1 END) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS inputTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS outputTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cacheReadInputTokens, " +
        "COALESCE(SUM(CASE WHEN json_extract(j.value, '$.role') = 'assistant' " +
        "AND COALESCE(json_extract(j.value, '$.usage.coreCompleteness'), 'LEGACY') != 'COMPLETE' " +
        "THEN 1 ELSE 0 END), 0) AS coreNonExactMessages, " +
        "COALESCE(SUM(CASE WHEN json_extract(j.value, '$.role') = 'assistant' " +
        "AND COALESCE(json_extract(j.value, '$.usage.cacheReadCompleteness'), 'LEGACY') != 'COMPLETE' " +
        "THEN 1 ELSE 0 END), 0) AS cacheReadNonExactMessages " +
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

