package net.weero.measix.pilot.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.service.FavoriteService
import net.weero.measix.pilot.service.NodeFavoriteItem

class FavoriteVM(
    private val favoriteService: FavoriteService,
) : ViewModel() {
    val nodeFavorites = favoriteService.observeNodeFavorites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun openRequest(item: NodeFavoriteItem) = favoriteService.openRequest(item)

    suspend fun removeForUndo(item: NodeFavoriteItem): FavoriteService.RestoreToken? =
        favoriteService.removeForUndo(item)

    suspend fun restoreFavorite(token: FavoriteService.RestoreToken) = favoriteService.restore(token)
}
