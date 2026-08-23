package net.weero.measix.pilot.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.dao.ArtifactReferenceDAO
import net.weero.measix.pilot.data.db.dao.ConversationDAO
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
    ],
    version = 7,
    autoMigrations = [],
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

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
