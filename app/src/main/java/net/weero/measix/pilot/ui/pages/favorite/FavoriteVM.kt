package net.weero.measix.pilot.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.service.FavoriteService

class FavoriteVM(
    private val favoriteService: FavoriteService,
) : ViewModel() {
    val nodeFavorites = favoriteService.observeNodeFavorites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun removeForUndo(refKey: String): FavoriteService.RestoreToken? =
        favoriteService.removeForUndo(refKey)

    fun restoreFavorite(token: FavoriteService.RestoreToken) {
        viewModelScope.launch {
            favoriteService.restore(token)
        }
    }
}
