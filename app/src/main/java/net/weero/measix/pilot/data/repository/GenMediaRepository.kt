package net.weero.measix.pilot.data.repository

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.dao.GenMediaDAO
import net.weero.measix.pilot.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun insertMedia(media: GenMediaEntity): Long = dao.insert(media)

    suspend fun getAllMediaList(): List<GenMediaEntity> = dao.getAllMedia()

    fun observeAllMedia(): Flow<List<GenMediaEntity>> = dao.observeAll()

    suspend fun getMediaById(id: Int): GenMediaEntity? = dao.getById(id)

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
