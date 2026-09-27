package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `conversation_model_context` —— 模型上下文条目的唯一 durable 落点。
 *
 * 独立表而不是给 [ConversationEntity] 加大 JSON 列：Conversation 是列表、最近会话、Child、
 * 删除和 header 查询的热行，不应携带可能很大的 Memory/Catalog 文本；条目是随历史追加与删除
 * 的事实，不是一个被反复覆盖的 header 属性。
 *
 * 归属只由 owner node 推导：故意不保存 `conversation_id`，避免与 owner node 的归属形成第二个
 * 可能冲突的事实源。按 Conversation 装载时以 `owner_node_id` JOIN `message_node` 并按
 * `message_node.conversation_id` 过滤。
 *
 * 主键由 (owner_node_id, owner_message_id, occurrence) 派生；同一消息 variant 可拥有多个
 * 不可变条目，Step 接纳记录另存实际使用位置。insert-once 按完整行判断幂等或冲突。
 * Fork / Child clone 的新 node 身份形成新的幂等域，保留的 message id 不会跨会话冲突。
 * owner_node_id 前缀查找由 occurrence 唯一索引覆盖；anchor 索引用于因果引用的级联收口。
 */
@Entity(
    tableName = "conversation_model_context",
    foreignKeys = [
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["owner_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["anchor_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["owner_node_id", "owner_message_id", "occurrence"], unique = true),
        Index("anchor_node_id"),
    ],
)
data class ConversationModelContextEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("owner_node_id")
    val ownerNodeId: String,
    @ColumnInfo("owner_message_id")
    val ownerMessageId: String,
    @ColumnInfo("anchor_node_id")
    val anchorNodeId: String,
    @ColumnInfo("anchor_message_id")
    val anchorMessageId: String,
    @ColumnInfo("occurrence")
    val occurrence: Int,
    @ColumnInfo("step_id")
    val stepId: String?,
    @ColumnInfo("source_kind")
    val sourceKind: String,
    @ColumnInfo("payload")
    val payload: String,
)
