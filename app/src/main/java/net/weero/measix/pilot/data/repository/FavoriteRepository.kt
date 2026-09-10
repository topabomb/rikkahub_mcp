package net.weero.measix.pilot.data.repository

import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.model.FavoriteType

class FavoriteRepository(private val dao: FavoriteDAO) {
    fun listByType(scope: ConfigurationScope, type: FavoriteType): Flow<List<FavoriteEntity>> = dao.listByType(scope, type.value)

    suspend fun getByRefKey(scope: ConfigurationScope, refKey: String): FavoriteEntity? = dao.getByRefKey(scope, refKey)

    suspend fun deleteByRefKey(scope: ConfigurationScope, refKey: String): Int = dao.deleteByRefKey(scope, refKey)

    suspend fun upsert(entity: FavoriteEntity) = dao.upsert(entity)
}
