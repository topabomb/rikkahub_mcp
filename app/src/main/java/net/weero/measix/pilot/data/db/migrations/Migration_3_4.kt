package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 → v4: 子助手 Child Conversation 支持
 *
 * 新增 parent_conversation_id 列和索引。
 * 级联删除由 Repository 层处理（Room KSP 不支持自引用外键声明）。
 *
 * 使用 additive ALTER TABLE ADD COLUMN，不重建 Conversation 主表，
 * 不触碰既有 MessageNode 外键或历史数据。
 */
val Migration_3_4 = object : Migration(3, 4) {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun migrate(database: SupportSQLiteDatabase) {
        // additive column：不重建主表
        database.execSQL(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `parent_conversation_id` TEXT DEFAULT NULL"
        )
        // 创建普通索引（不自引用外键）
        // 索引名必须与 Room KSP 导出 schema 完全一致（大小写敏感）
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_parent_conversation_id` " +
                "ON `ConversationEntity` (`parent_conversation_id`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id` " +
                "ON `ConversationEntity` (`assistant_id`)"
        )
    }
}
