package net.weero.mersix.pilot.data.repository

import androidx.paging.PagingSource
import net.weero.mersix.pilot.data.db.dao.GenMediaDAO
import net.weero.mersix.pilot.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun insertMedia(media: GenMediaEntity) = dao.insert(media)

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
