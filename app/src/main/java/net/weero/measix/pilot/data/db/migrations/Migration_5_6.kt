package net.weero.measix.pilot.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1) Artifact 语义统一（列与数据原样保留）
        db.execSQL("ALTER TABLE managed_files RENAME TO artifact")

        // 1b) 既有索引随表迁移（SQLite RENAME 不改索引名，Room 校验要求名随 @Entity 声明走）
        db.execSQL("DROP INDEX IF EXISTS index_managed_files_relative_path")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artifact_relative_path ON artifact(relative_path)")
        db.execSQL("DROP INDEX IF EXISTS index_managed_files_folder")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_folder ON artifact(folder)")

        // 2) 状态机（幂等屏障 + 续删 + 缺失诊断）
        db.execSQL("ALTER TABLE artifact ADD COLUMN state TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_state ON artifact(state)")

        // 2b) 诞生方式（存量行无法回溯，统一按用户引入处理；新生成/系统产物自此准确标注）
        db.execSQL("ALTER TABLE artifact ADD COLUMN origin TEXT NOT NULL DEFAULT 'USER'")

        // 3) 引用投影（双 FK 级联：artifact 行删除 / node 行删除均自动清理）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artifact_reference (
                rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                artifact_id INTEGER NOT NULL,
                node_id TEXT NOT NULL,
                reference_type TEXT NOT NULL,
                CONSTRAINT fk_artifact_reference_artifact
                    FOREIGN KEY(artifact_id) REFERENCES artifact(id) ON DELETE CASCADE,
                CONSTRAINT fk_artifact_reference_node
                    FOREIGN KEY(node_id) REFERENCES message_node(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artifact_reference_artifact_id_node_id_reference_type ON artifact_reference(artifact_id, node_id, reference_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_reference_artifact_id ON artifact_reference(artifact_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_artifact_reference_node_id ON artifact_reference(node_id)")

        // 4) 软件级一次性标记
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS system_meta (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}
