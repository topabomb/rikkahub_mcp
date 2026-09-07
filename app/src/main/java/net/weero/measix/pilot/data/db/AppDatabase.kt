package net.weero.measix.pilot.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.dao.ArtifactReferenceDAO
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.ConversationModelContextDAO
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.dao.FolderDAO
import net.weero.measix.pilot.data.db.dao.GenMediaDAO
import net.weero.measix.pilot.data.db.dao.MemoryDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.SystemMetaDAO
import net.weero.measix.pilot.data.db.dao.ToolExecutionDAO
import net.weero.measix.pilot.data.db.dao.TurnExecutionDAO
import net.weero.measix.pilot.data.db.dao.WorkspaceDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.db.entity.FolderEntity
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.db.entity.MemoryEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.entity.SystemMetaEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.WorkspaceEntity
import net.weero.measix.pilot.utils.JsonInstant

/**
 * Room schema 版本。新增表/列只允许 additive migration，且必须同步
 * `app/schemas/.../<version>.json`、`AppDatabaseFactory.createAppDatabase` 的迁移注册与对应 `Migration_N_MTest`。
 *
 * v11：V3 Turn/Step/Tool typed transcript。`message_node` 新增 `transcript_schema`；
 * `tool_execution` 以 `step_id` + `local_call_id` 取代 `tool_ordinal` 并新增 Child 链列；
 * `turn_execution.status` 文本 `AWAITING_APPROVAL`→`AWAITING_USER`、`CREATED` 收口为 `INTERRUPTED`。
 * 历史 `messages` payload 由 `Migration_10_11` 经 `LegacyTurnTranscriptMigrator` 逐行转换。
 */
const val APP_DATABASE_VERSION = 11

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ArtifactEntity::class,
        ArtifactReferenceEntity::class,
        SystemMetaEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        TurnExecutionEntity::class,
        ToolExecutionEntity::class,
        ConversationModelContextEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    autoMigrations = [],
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun conversationModelContextDao(): ConversationModelContextDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun artifactDao(): ArtifactDAO

    abstract fun artifactReferenceDao(): ArtifactReferenceDAO

    abstract fun systemMetaDao(): SystemMetaDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun turnExecutionDao(): TurnExecutionDAO

    abstract fun toolExecutionDao(): ToolExecutionDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
