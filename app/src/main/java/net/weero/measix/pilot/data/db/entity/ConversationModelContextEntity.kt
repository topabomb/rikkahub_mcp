package net.weero.measix.pilot.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `conversation_model_context` —— 模型上下文条目的唯一 durable 落点（权威方案 §5.2）。
 *
 * 独立表而不是给 [ConversationEntity] 加大 JSON 列：Conversation 是列表、最近会话、Child、
 * 删除和 header 查询的热行，不应携带可能很大的 Memory/Catalog 文本；条目是随历史追加与删除
 * 的事实，不是一个被反复覆盖的 header 属性。
 *
 * 归属只由 owner node 推导：故意不保存 `conversation_id`，避免与 owner node 的归属形成第二个
 * 可能冲突的事实源。按 Conversation 装载时以 `owner_node_id` JOIN `message_node` 并按
 * `message_node.conversation_id` 过滤。
 *
 * 主键是 (owner_node_id, owner_message_id) 而非自增 id：一个 Assistant request variant 最多
 * 拥有一份聚合 Snapshot，且命令重放必须能被判定为幂等（同 key + 同 content）或冲突
 * （同 key + 不同 content）。唯一性以 owner node 为作用域，因为 Fork / Child clone 保留
 * message id、只重建 node id，同一 message id 合法地存在于多个 Conversation。
 * owner_node_id 前缀查找由主键索引覆盖，因此只显式声明 anchor 索引。
 */
@Entity(
    tableName = "conversation_model_context",
    primaryKeys = ["owner_node_id", "owner_message_id"],
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
        Index("anchor_node_id"),
    ],
)
data class ConversationModelContextEntity(
    @ColumnInfo("owner_node_id")
    val ownerNodeId: String,
    @ColumnInfo("owner_message_id")
    val ownerMessageId: String,
    @ColumnInfo("anchor_node_id")
    val anchorNodeId: String,
    @ColumnInfo("anchor_message_id")
    val anchorMessageId: String,
    @ColumnInfo("content")
    val content: String,
)
