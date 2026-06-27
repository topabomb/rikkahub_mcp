package net.weero.measix.pilot.data.favorite

import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
