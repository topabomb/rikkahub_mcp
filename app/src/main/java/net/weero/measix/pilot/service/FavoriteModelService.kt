package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.datastore.SettingsStore

/** 模型收藏的记录级命令入口，避免通用 Compose 组件直接改写 Settings 快照。 */
class FavoriteModelService(
    private val settingsStore: SettingsStore,
) {
    val favoriteModelIds = settingsStore.effectiveSettings.map { it.settings }
        .map { it.favoriteModels }
        .distinctUntilChanged()

    suspend fun setFavorite(modelId: ConfigurationReference, favorite: Boolean) {
        settingsStore.updateLocal { current ->
            val updated = if (favorite) {
                if (modelId in current.favoriteModels) current.favoriteModels else current.favoriteModels + modelId
            } else {
                current.favoriteModels.filterNot { it == modelId }
            }
            current.copy(favoriteModels = updated)
        }
    }

    suspend fun move(fromModelId: ConfigurationReference, toModelId: ConfigurationReference) {
        settingsStore.updateLocal { current ->
            current.copy(
                favoriteModels = moveFavoriteModel(
                    current = current.favoriteModels,
                    fromModelId = fromModelId,
                    toModelId = toModelId,
                )
            )
        }
    }
}

internal fun moveFavoriteModel(
    current: List<ConfigurationReference>,
    fromModelId: ConfigurationReference,
    toModelId: ConfigurationReference,
): List<ConfigurationReference> {
    val fromIndex = current.indexOf(fromModelId)
    val toIndex = current.indexOf(toModelId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return current
    return current.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
