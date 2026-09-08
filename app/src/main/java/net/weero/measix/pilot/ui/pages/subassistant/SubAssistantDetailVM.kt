package net.weero.measix.pilot.ui.pages.subassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.SubAssistantDetailReader
import net.weero.measix.pilot.service.SubAssistantDetailUiState

class SubAssistantDetailVM(
    source: ConversationViewLease?,
    runId: String,
    detailReader: SubAssistantDetailReader,
    settingsStore: SettingsStore,
) : ViewModel() {
    val uiState = detailReader.observe(source, runId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubAssistantDetailUiState.Loading)
    val settings = settingsStore.effectiveSettings.map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun attachmentPreviews(): Map<String, String> =
        (uiState.value as? SubAssistantDetailUiState.Ready)?.attachmentPreviews.orEmpty()
}
