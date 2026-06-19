package net.weero.mersix.pilot.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import net.weero.mersix.pilot.data.db.dao.ConversationDAO
import net.weero.mersix.pilot.data.db.dao.FavoriteDAO
import net.weero.mersix.pilot.data.db.dao.GenMediaDAO
import net.weero.mersix.pilot.data.db.dao.ManagedFileDAO
import net.weero.mersix.pilot.data.db.dao.MemoryDAO
import net.weero.mersix.pilot.data.db.dao.MessageNodeDAO
import net.weero.mersix.pilot.data.db.dao.WorkspaceDAO
import net.weero.mersix.pilot.data.db.entity.ConversationEntity
import net.weero.mersix.pilot.data.db.entity.FavoriteEntity
import net.weero.mersix.pilot.data.db.entity.GenMediaEntity
import net.weero.mersix.pilot.data.db.entity.ManagedFileEntity
import net.weero.mersix.pilot.data.db.entity.MemoryEntity
import net.weero.mersix.pilot.data.db.entity.MessageNodeEntity
import net.weero.mersix.pilot.data.db.entity.WorkspaceEntity
import net.weero.mersix.pilot.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
    ],
    version = 2,
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO
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
