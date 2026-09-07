package net.weero.measix.pilot.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import net.weero.measix.pilot.data.db.fts.SimpleDictManager
import net.weero.measix.pilot.data.db.migrations.Migration_1_2
import net.weero.measix.pilot.data.db.migrations.Migration_2_3
import net.weero.measix.pilot.data.db.migrations.Migration_3_4
import net.weero.measix.pilot.data.db.migrations.Migration_4_5
import net.weero.measix.pilot.data.db.migrations.Migration_5_6
import net.weero.measix.pilot.data.db.migrations.Migration_6_7
import net.weero.measix.pilot.data.db.migrations.Migration_7_8
import net.weero.measix.pilot.data.db.migrations.Migration_8_9
import net.weero.measix.pilot.data.db.migrations.Migration_9_10
import net.weero.measix.pilot.data.db.migrations.Migration_10_11

/**
 * The single Room construction recipe for [AppDatabase]: migration chain, FTS/dictionary open
 * callback and the Requery native extension. The live DI graph and backup-restore staging upgrade
 * both open through here, so a restored aggregate is migrated by exactly the same chain the app
 * uses and never by a second, divergent rewrite path.
 */
fun createAppDatabase(context: Context, name: String): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, name)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(
            Migration_1_2,
            Migration_2_3,
            Migration_3_4,
            Migration_4_5,
            Migration_5_6,
            Migration_6_7,
            Migration_7_8,
            Migration_8_9,
            Migration_9_10,
            Migration_10_11,
        )
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // FK 约束运行时启用（每个连接）：v7 自引用 CASCADE（孤儿 child
                // 结构性不可能）与 message_node / artifact_reference 的级联清理
                // 依赖此开关。onOpen 晚于 onUpgrade → 迁移期间 FK 保持 OFF，
                // Migration_6_7 的表重建安全（DROP TABLE 的隐式 DELETE 不级联）。
                db.execSQL("PRAGMA foreign_keys = ON")
                val dictDir = SimpleDictManager.extractDict(context)
                val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                cursor.use {
                    if (it.moveToFirst()) {
                        val result = it.getString(0)
                        val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                        if (!success) {
                            android.util.Log.e(
                                "AppDatabaseFactory",
                                "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                            )
                        }
                    }
                }
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                        text,
                        node_id UNINDEXED,
                        message_id UNINDEXED,
                        conversation_id UNINDEXED,
                        title UNINDEXED,
                        update_at UNINDEXED,
                        tokenize = 'simple'
                    )
                    """.trimIndent()
                )
            }
        })
        .openHelperFactory(
            RequerySQLiteOpenHelperFactory(
                listOf(
                    RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                        options.customExtensions.add(
                            SQLiteCustomExtension(
                                context.applicationInfo.nativeLibraryDir + "/libsimple",
                                null
                            )
                        )
                        options
                    }
                )
            )
        )
        .build()
