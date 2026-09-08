package net.weero.measix.pilot.data.db.dao

import net.weero.measix.pilot.data.configuration.ConfigurationScope
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.GenMediaEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity WHERE scope = :scope ORDER BY create_at DESC")
    fun getAll(scope: ConfigurationScope): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    @Query("SELECT * FROM genmediaentity WHERE scope = :scope ORDER BY create_at DESC")
    fun observeAll(scope: ConfigurationScope): Flow<List<GenMediaEntity>>

    @Query("SELECT * FROM genmediaentity WHERE id = :id")
    suspend fun getById(id: Int): GenMediaEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM genmediaentity WHERE path = :path)")
    suspend fun existsByPath(path: String): Boolean

    /** 范围清理候选：只读 create_at 截止，由 Store 在 persist lock 内逐项复用删除协议。 */
    @Query("SELECT * FROM genmediaentity WHERE scope = :scope AND create_at <= :cutoff ORDER BY create_at DESC")
    suspend fun listCreatedBefore(scope: ConfigurationScope, cutoff: Long): List<GenMediaEntity>

    /** Caller dispatches to IO; the row commit and returned id must not be split by coroutine cancellation. */
    @Insert
    fun insert(media: GenMediaEntity): Long

    /** Caller dispatches to IO and pairs this commit with a recoverable payload tombstone. */
    @Query("DELETE FROM genmediaentity WHERE id = :id")
    fun delete(id: Int)
}
