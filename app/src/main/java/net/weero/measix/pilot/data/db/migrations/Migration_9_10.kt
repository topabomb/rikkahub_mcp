package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 → v10：唯一新增 `conversation_model_context` 表与 anchor node 索引。
 *
 * additive-only（权威方案 §13.1）：
 * - 不扫描历史 Conversation，不为旧会话生成 Snapshot；
 * - 不重写 `ConversationEntity`、`message_node.messages` 或 Memory 行；
 * - 不增加默认 context 行。
 *
 * 旧会话缺少 entry 是合法的未初始化状态；第一次新的 `START` 才在新 Assistant owner 下
 * 保存当前完整 Snapshot 并指向该请求实际使用的 USER anchor。
 *
 * 两条 DDL 与 Room 为 `ConversationModelContextEntity` 生成的 fresh schema 逐字一致，
 * `Migration_9_10Test` 用 `runMigrationsAndValidate` 锁定这一点。
 */
val Migration_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversation_model_context` (
                `owner_node_id` TEXT NOT NULL,
                `owner_message_id` TEXT NOT NULL,
                `anchor_node_id` TEXT NOT NULL,
                `anchor_message_id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                PRIMARY KEY(`owner_node_id`, `owner_message_id`),
                FOREIGN KEY(`owner_node_id`) REFERENCES `message_node`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`anchor_node_id`) REFERENCES `message_node`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_conversation_model_context_anchor_node_id` " +
                "ON `conversation_model_context` (`anchor_node_id`)",
        )
    }
}
