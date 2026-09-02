package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity

/** 同一 owner 已提交不同 content：append-only 历史不能被覆盖，命令必须失败。 */
class ModelContextConflictException(message: String) : IllegalStateException(message)

/**
 * `conversation_model_context` 的唯一 DAO。
 *
 * 写入只有 insert-once 一种语义（权威方案 §12.2）：**不使用** `@Upsert`、`@Update` 或
 * `OnConflictStrategy.REPLACE`。key 是 (owner_node_id, owner_message_id)：Fork / Child clone
 * 保留 message id、只重建 node id，因此唯一性与幂等域都以 owner node 为作用域。
 * - 首次 key 插入成功；
 * - 相同 key + 相同 row 的命令重放幂等；
 * - 相同 key + 任何字段不同（content / anchor）明确冲突，绝不覆盖历史 entry。
 *
 * Conversation 归属只由 owner node 推导，因此装载走 `owner_node_id JOIN message_node`；
 * 本表不保存 `conversation_id`，避免第二个可能冲突的归属事实源。
 */
@Dao
interface ConversationModelContextDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(entities: List<ConversationModelContextEntity>): List<Long>

    @Query(
        "SELECT * FROM conversation_model_context " +
            "WHERE owner_node_id = :ownerNodeId AND owner_message_id = :ownerMessageId",
    )
    suspend fun findByOwner(ownerNodeId: String, ownerMessageId: String): ConversationModelContextEntity?

    /** 装载某个 Conversation 的全部条目；顺序由 owner node 在消息树中的位置决定。 */
    @Query(
        "SELECT context.* FROM conversation_model_context context " +
            "JOIN message_node owner ON owner.id = context.owner_node_id " +
            "WHERE owner.conversation_id = :conversationId " +
            "ORDER BY owner.node_index ASC, context.owner_message_id ASC",
    )
    suspend fun getEntriesOfConversation(conversationId: String): List<ConversationModelContextEntity>

    /**
     * 收口消失 entry 的唯一删除路径：按 (owner_node_id, owner_message_id) 主键精确删除，
     * 不按全局 message id——Fork / Child clone 会在其他 Conversation 保留相同 message id。
     */
    @Delete
    suspend fun deleteByPrimaryKeys(entities: List<ConversationModelContextEntity>)

    /**
     * insert-once 的唯一实现：-1 表示 key 已存在，此时只允许整行逐字相同（重放幂等），
     * 否则以 [ModelContextConflictException] 失败。调用方（Repository）负责外层 Room 事务。
     */
    @Transaction
    suspend fun insertOnce(entities: List<ConversationModelContextEntity>) {
        if (entities.isEmpty()) return
        val inserted = insertIgnoring(entities)
        check(inserted.size == entities.size) { "model context insert receipt count mismatch" }
        entities.forEachIndexed { index, entity ->
            if (inserted[index] != -1L) return@forEachIndexed
            val committed = findByOwner(entity.ownerNodeId, entity.ownerMessageId)
                ?: throw ModelContextConflictException(
                    "model context row for owner ${entity.ownerMessageId} disappeared mid-transaction",
                )
            if (committed != entity) {
                throw ModelContextConflictException(
                    "model context owner ${entity.ownerMessageId} already committed a different row",
                )
            }
        }
    }
}
