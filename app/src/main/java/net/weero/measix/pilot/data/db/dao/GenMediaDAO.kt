package net.weero.measix.pilot.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.GenMediaEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun getAll(): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun observeAll(): Flow<List<GenMediaEntity>>

    @Query("SELECT * FROM genmediaentity WHERE id = :id")
    suspend fun getById(id: Int): GenMediaEntity?

    /** Caller dispatches to IO; the row commit and returned id must not be split by coroutine cancellation. */
    @Insert
    fun insert(media: GenMediaEntity): Long

    /** Caller dispatches to IO and pairs this commit with a recoverable payload tombstone. */
    @Query("DELETE FROM genmediaentity WHERE id = :id")
    fun delete(id: Int)
}
