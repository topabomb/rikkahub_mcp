package net.weero.mersix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 样本迁移
 *
 * 新增 ConversationEntity.tags 列，演示完整的 Room Migration 编写范式。
 * 这是精简版保留的唯一一次迁移，作为后续开发的参考。
 *
 * 要点：
 * 1. ALTER TABLE ... ADD COLUMN 必须指定 NOT NULL DEFAULT（SQLite 限制）
 * 2. 默认值用 JSON 数组字符串 "[]"，与实体类中的默认值一致
 * 3. 迁移完成后旧数据自动获得默认值，不影响现有对话
 */
val Migration_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE conversations ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'"
        )
    }
}
