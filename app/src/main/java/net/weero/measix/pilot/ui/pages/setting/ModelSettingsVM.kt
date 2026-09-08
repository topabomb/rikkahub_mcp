package net.weero.measix.pilot.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ResourceSelectionSlot
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ModelCatalogReadState

internal data class ModelSettingsError(val selection: RealmSelection, val detail: String)

class ModelSettingsVM internal constructor(
    queries: ConfigurationQueryService,
    private val commands: ConfigurationApplicationService,
) : ViewModel() {
    internal val catalog = queries.observeModelCatalog().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelCatalogReadState.Loading)
    private val _error = MutableStateFlow<ModelSettingsError?>(null)
    internal val error = _error.asStateFlow()

    internal suspend fun select(selection: RealmSelection, slot: ResourceSelectionSlot, reference: ConfigurationReference?) {
        commands.selectResource(selection, slot, reference)
    }

    internal fun reset(selection: RealmSelection, slot: ResourceSelectionSlot) = execute(selection) {
        commands.selectResource(selection, slot, null)
    }

    internal fun enableSuggestion(selection: RealmSelection, enabled: Boolean) = execute(selection) {
        commands.setSuggestionEnabled(selection, enabled)
    }

    private fun execute(selection: RealmSelection, action: suspend () -> Unit) = viewModelScope.launch {
        try {
            action()
            if (_error.value?.selection == selection) _error.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if ((catalog.value as? ModelCatalogReadState.Available)?.catalog?.selection == selection) {
                _error.value = ModelSettingsError(selection, error.message ?: "configuration_change_failed")
            }
        }
    }
}
