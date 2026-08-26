package net.weero.measix.pilot.ui.pages.share.handler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import kotlin.uuid.Uuid

class ShareHandlerVM(
    text: String,
    private val settingsStore: SettingsStore
) : ViewModel() {
    val shareText = checkNotNull(text)
    val settings = settingsStore.effectiveSettings.map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    suspend fun updateAssistant(assistantId: Uuid) {
        settingsStore.updateLocal { settings -> settings.copy(assistantId = assistantId) }
    }
}
