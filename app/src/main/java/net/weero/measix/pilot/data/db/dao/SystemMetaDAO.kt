package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import net.weero.measix.pilot.data.db.entity.SystemMetaEntity

@Dao
interface SystemMetaDAO {
    @Query("SELECT value FROM system_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: SystemMetaEntity)
}
