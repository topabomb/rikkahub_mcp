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

    @Insert
    suspend fun insert(media: GenMediaEntity): Long

    @Query("DELETE FROM genmediaentity WHERE id = :id")
    suspend fun delete(id: Int)
}
