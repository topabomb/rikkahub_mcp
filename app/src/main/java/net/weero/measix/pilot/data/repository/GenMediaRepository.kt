package net.weero.measix.pilot.data.repository

import net.weero.measix.pilot.data.configuration.ConfigurationScope
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.dao.GenMediaDAO
import net.weero.measix.pilot.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(scope: ConfigurationScope): PagingSource<Int, GenMediaEntity> = dao.getAll(scope)

    fun insertMedia(media: GenMediaEntity): Long = dao.insert(media)

    suspend fun getAllMediaList(): List<GenMediaEntity> = dao.getAllMedia()

    fun observeAllMedia(scope: ConfigurationScope): Flow<List<GenMediaEntity>> = dao.observeAll(scope)

    suspend fun getMediaById(id: Int): GenMediaEntity? = dao.getById(id)

    suspend fun existsByPath(path: String): Boolean = dao.existsByPath(path)

    suspend fun listCreatedBefore(scope: ConfigurationScope, cutoff: Long): List<GenMediaEntity> = dao.listCreatedBefore(scope, cutoff)

    fun deleteMedia(id: Int) = dao.delete(id)
}
