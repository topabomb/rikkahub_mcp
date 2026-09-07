package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.entity.MemoryEntity

/** Ordered namespace reads keep disclosure content stable. Every operation includes the durable realm and owner. */
@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE scope = :scope AND assistant_id = :ownerId ORDER BY id ASC")
    fun observe(scope: ConfigurationScope, ownerId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE scope = :scope AND assistant_id = :ownerId ORDER BY id ASC")
    suspend fun read(scope: ConfigurationScope, ownerId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE scope = :scope AND id = :id")
    suspend fun find(scope: ConfigurationScope, id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query("UPDATE memoryentity SET content = :content WHERE scope = :scope AND assistant_id = :ownerId AND id = :id")
    suspend fun update(scope: ConfigurationScope, ownerId: String, id: Int, content: String): Int

    @Query("DELETE FROM memoryentity WHERE scope = :scope AND assistant_id = :ownerId AND id = :id")
    suspend fun delete(scope: ConfigurationScope, ownerId: String, id: Int): Int

    @Query("DELETE FROM memoryentity WHERE scope = :scope AND assistant_id = :ownerId")
    suspend fun deleteAll(scope: ConfigurationScope, ownerId: String)
}
