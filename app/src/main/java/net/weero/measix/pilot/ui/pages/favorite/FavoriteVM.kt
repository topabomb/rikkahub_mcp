package net.weero.measix.pilot.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.service.FavoriteService
import net.weero.measix.pilot.service.NodeFavoriteItem
import net.weero.measix.pilot.service.FavoriteDirectoryState

class FavoriteVM(
    private val favoriteService: FavoriteService,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val directory = refresh.flatMapLatest {
        favoriteService.observeNodeFavorites()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FavoriteDirectoryState(loading = true))

    fun retry() { refresh.value += 1 }

    suspend fun openRequest(item: NodeFavoriteItem) = favoriteService.openRequest(item)

    suspend fun removeForUndo(item: NodeFavoriteItem): FavoriteService.RestoreToken? =
        favoriteService.removeForUndo(item)

    suspend fun restoreFavorite(token: FavoriteService.RestoreToken) = favoriteService.restore(token)
}
