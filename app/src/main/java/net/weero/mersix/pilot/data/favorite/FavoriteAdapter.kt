package net.weero.mersix.pilot.data.favorite

import net.weero.mersix.pilot.data.db.entity.FavoriteEntity
import net.weero.mersix.pilot.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
