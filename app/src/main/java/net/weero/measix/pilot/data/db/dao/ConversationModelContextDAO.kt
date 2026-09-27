package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.entity.ConversationContextAdmissionEntity
import net.weero.measix.pilot.data.db.entity.ConversationContextUseEntity
import net.weero.measix.pilot.data.db.entity.ConversationOpeningEntity

/** 同一条目或接纳身份已提交不同内容：不可变历史不能被覆盖，命令必须失败。 */
class ModelContextConflictException(message: String) : IllegalStateException(message)

/**
 * `conversation_model_context` 的唯一 DAO。
 *
 * 写入只有 insert-once 一种语义：**不使用** `@Upsert`、`@Update` 或
 * `OnConflictStrategy.REPLACE`。entry id 由 (owner_node_id, owner_message_id, occurrence) 派生；
 * 一个 variant 可拥有多个不可变输入，Fork 的新 node 身份决定新的幂等域。
 * - 首次 key 插入成功；
 * - 相同 key + 相同 row 的命令重放幂等；
 * - 相同 key + 任何字段不同（payload / anchor）明确冲突，绝不覆盖历史 entry。
 *
 * Conversation 归属只由 owner node 推导，因此装载走 `owner_node_id JOIN message_node`；
 * 本表不保存 `conversation_id`，避免第二个可能冲突的归属事实源。
 */
@Dao
interface ConversationModelContextDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAdmissionIgnoring(entity: ConversationContextAdmissionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUsesIgnoring(entities: List<ConversationContextUseEntity>): List<Long>

    @Query("SELECT * FROM conversation_context_admission WHERE id = :id")
    suspend fun findAdmission(id: String): ConversationContextAdmissionEntity?

    @Query("SELECT * FROM conversation_context_use WHERE admission_id = :id ORDER BY ordinal")
    suspend fun getUses(id: String): List<ConversationContextUseEntity>

    @Query("SELECT admission.* FROM conversation_context_admission admission " +
        "JOIN message_node owner ON owner.id = admission.owner_node_id " +
        "WHERE owner.conversation_id = :conversationId ORDER BY owner.node_index, admission.owner_message_id")
    suspend fun getAdmissionsOfConversation(conversationId: String): List<ConversationContextAdmissionEntity>

    @Query("SELECT contribution.* FROM conversation_context_use contribution " +
        "JOIN conversation_context_admission admission ON admission.id = contribution.admission_id " +
        "JOIN message_node owner ON owner.id = admission.owner_node_id " +
        "WHERE owner.conversation_id = :conversationId ORDER BY contribution.admission_id, contribution.ordinal")
    suspend fun getUsesOfConversation(conversationId: String): List<ConversationContextUseEntity>

    @Delete
    suspend fun deleteAdmissions(entities: List<ConversationContextAdmissionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOpeningIgnoring(entity: ConversationOpeningEntity): Long

    @Query("SELECT conversation_id, '' AS payload FROM conversation_opening WHERE conversation_id = :conversationId")
    suspend fun getOpeningHeader(conversationId: String): ConversationOpeningEntity?

    @Query("SELECT substr(payload, :start, 262144) FROM conversation_opening WHERE conversation_id = :conversationId")
    suspend fun readOpeningSlice(conversationId: String, start: Int): String?

    suspend fun getOpening(conversationId: String): ConversationOpeningEntity? =
        getOpeningHeader(conversationId)?.copy(payload = readContextText { readOpeningSlice(conversationId, it) })

    @Transaction
    suspend fun insertOpeningOnce(entity: ConversationOpeningEntity) {
        if (insertOpeningIgnoring(entity) == -1L && getOpening(entity.conversationId) != entity) {
            throw ModelContextConflictException("conversation opening already committed different content")
        }
    }

    @Transaction
    suspend fun insertAdmissionOnce(
        entity: ConversationContextAdmissionEntity,
        uses: List<ConversationContextUseEntity>,
    ) {
        require(uses.map { it.ordinal } == uses.indices.toList() && uses.all { it.admissionId == entity.id }) {
            "invalid_context_admission_contributions"
        }
        if (insertAdmissionIgnoring(entity) == -1L) {
            if (findAdmission(entity.id) != entity || getUses(entity.id) != uses) {
                throw ModelContextConflictException("request boundary already committed different application input")
            }
            return
        }
        val receipts = insertUsesIgnoring(uses)
        check(receipts.size == uses.size && receipts.none { it == -1L }) {
            "context_admission_contribution_conflict"
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(entities: List<ConversationModelContextEntity>): List<Long>

    @Query(
        "SELECT id, owner_node_id, owner_message_id, anchor_node_id, anchor_message_id, " +
            "occurrence, step_id, source_kind, '' AS payload FROM conversation_model_context " +
            "WHERE id = :id",
    )
    suspend fun findHeaderById(id: String): ConversationModelContextEntity?

    @Query("SELECT substr(payload, :start, 262144) FROM conversation_model_context WHERE id = :id")
    suspend fun readEntrySlice(id: String, start: Int): String?

    suspend fun findById(id: String): ConversationModelContextEntity? =
        findHeaderById(id)?.copy(payload = readContextText { readEntrySlice(id, it) })

    /** 装载某个 Conversation 的全部条目；顺序由 owner node 在消息树中的位置决定。 */
    @Query(
        "SELECT context.id, context.owner_node_id, context.owner_message_id, context.anchor_node_id, " +
            "context.anchor_message_id, context.occurrence, context.step_id, context.source_kind, '' AS payload " +
            "FROM conversation_model_context context " +
            "JOIN message_node owner ON owner.id = context.owner_node_id " +
            "WHERE owner.conversation_id = :conversationId " +
            "ORDER BY owner.node_index ASC, context.owner_message_id ASC, context.occurrence ASC",
    )
    suspend fun getEntryHeadersOfConversation(conversationId: String): List<ConversationModelContextEntity>

    suspend fun getEntriesOfConversation(conversationId: String): List<ConversationModelContextEntity> =
        getEntryHeadersOfConversation(conversationId).map { row ->
            row.copy(payload = readContextText { readEntrySlice(row.id, it) })
        }

    /**
     * 收口消失 entry 的唯一删除路径：按 entry id 精确删除，
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
            val committed = findById(entity.id)
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

/** SQL substr counts Unicode code points; each chunk remains below Android's CursorWindow limit. */
private suspend fun readContextText(slice: suspend (Int) -> String?): String = buildString {
    var start = 1
    while (true) {
        val text = checkNotNull(slice(start)) { "context_payload_disappeared_during_read" }
        if (text.isEmpty()) break
        append(text)
        start += text.codePointCount(0, text.length)
    }
}
