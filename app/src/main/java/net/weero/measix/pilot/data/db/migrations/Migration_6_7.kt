package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 → v7：建立主/子会话关系约束并清理历史悬挂数据。
 *
 * 1) 存量孤儿 Child 清理（加 FK 前必须收敛；此后孤儿结构性不可能产生）。
 * 2) 重建 ConversationEntity 表（SQLite 无法 ALTER 添加 FK）：
 *    + 自引用 FOREIGN KEY(parent_conversation_id) ON DELETE CASCADE
 *    − 删除 nodes 死列（v1 重构后消息树存 message_node 表，该列恒为 "[]"）
 *    既有索引随表重建（index_ConversationEntity_parent_conversation_id / assistant_id）。
 * 3) tool_execution 增加 child_conversation_id 列：调用↔Child 关系归位到执行事实行
 *    （恢复路径可经 turn_execution(status) → child_conversation_id 定点收口，无需全库扫描）。
 */
val Migration_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Room 配置 setForeignKeyConstraintsEnabled(true) 后 onConfigure 先于
        // onUpgrade 执行 → 迁移期 FK 处于 ON；表重建的 DROP TABLE 会执行隐式
        // DELETE FROM 并级联清空 message_node——必须先显式关闭（Room 不为普通
        // Migration 包事务，PRAGMA 在连接级生效），迁移完成后恢复。
        db.execSQL("PRAGMA foreign_keys = OFF")
        try {
            // 1) 清理存量孤儿 Child（parent 悬挂的行直接删除；消息节点经 FK 级联消失）
            db.execSQL(
                """
                DELETE FROM ConversationEntity
                WHERE parent_conversation_id IS NOT NULL
                  AND parent_conversation_id NOT IN (SELECT id FROM ConversationEntity)
                """.trimIndent()
            )

            // 1b) 清理历史遗留悬挂行（FK 从未启用期间应用层删除未级联的残留）：
            //     conversation 已删除但残留的 message_node / node 已删除但残留的引用行
            db.execSQL(
                """
                DELETE FROM message_node
                WHERE conversation_id NOT IN (SELECT id FROM ConversationEntity)
                """.trimIndent()
            )
            db.execSQL(
                """
                DELETE FROM artifact_reference
                WHERE node_id NOT IN (SELECT id FROM message_node)
                """.trimIndent()
            )

            // 2) 重建 ConversationEntity：+ 自引用 FK，− nodes 死列
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `_new_ConversationEntity` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL DEFAULT '0950e2dc-9bd5-4801-afa3-aa887aa36b4e',
                `title` TEXT NOT NULL,
                `create_at` INTEGER NOT NULL,
                `update_at` INTEGER NOT NULL,
                `suggestions` TEXT NOT NULL DEFAULT '[]',
                `is_pinned` INTEGER NOT NULL DEFAULT 0,
                `custom_system_prompt` TEXT NOT NULL DEFAULT '',
                `mode_injection_ids` TEXT NOT NULL DEFAULT '[]',
                `workspace_cwd` TEXT NOT NULL DEFAULT '',
                `tags` TEXT NOT NULL DEFAULT '[]',
                `folder_id` TEXT NOT NULL DEFAULT '',
                `parent_conversation_id` TEXT DEFAULT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`parent_conversation_id`) REFERENCES ConversationEntity(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
            db.execSQL(
                """
                INSERT INTO `_new_ConversationEntity` (
                    `id`, `assistant_id`, `title`, `create_at`, `update_at`, `suggestions`,
                    `is_pinned`, `custom_system_prompt`, `mode_injection_ids`, `workspace_cwd`,
                    `tags`, `folder_id`, `parent_conversation_id`
                )
                SELECT
                    `id`, `assistant_id`, `title`, `create_at`, `update_at`, `suggestions`,
                    `is_pinned`, `custom_system_prompt`, `mode_injection_ids`, `workspace_cwd`,
                    `tags`, `folder_id`, `parent_conversation_id`
                FROM ConversationEntity
                """.trimIndent()
            )
            db.execSQL("DROP TABLE ConversationEntity")
            db.execSQL("ALTER TABLE `_new_ConversationEntity` RENAME TO ConversationEntity")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_parent_conversation_id` " +
                    "ON ConversationEntity (`parent_conversation_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id` " +
                    "ON ConversationEntity (`assistant_id`)"
            )

            // 3) 调用↔Child 关系归位到执行事实行
            db.execSQL("ALTER TABLE tool_execution ADD COLUMN child_conversation_id TEXT")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tool_execution_child_conversation_id` " +
                    "ON tool_execution (`child_conversation_id`)"
            )
        } finally {
            db.execSQL("PRAGMA foreign_keys = ON")
        }
    }
}
