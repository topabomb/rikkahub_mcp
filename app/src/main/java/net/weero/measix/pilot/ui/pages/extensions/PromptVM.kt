package net.weero.measix.pilot.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    private val _lockedChanges = MutableSharedFlow<SettingsLockedException>(extraBufferCapacity = 1)
    val lockedChanges = _lockedChanges.asSharedFlow()
    internal val effectiveSettings: StateFlow<EffectiveSettingsSnapshot> = settingsStore.effectiveSettings
    val settings = settingsStore.effectiveSettings.map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            try {
                settingsStore.updateLocal(transform = transform)
            } catch (error: SettingsLockedException) {
                _lockedChanges.emit(error)
            }
        }
    }
}
