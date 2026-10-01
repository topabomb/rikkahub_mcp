package net.weero.measix.pilot.ui.pages.subassistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
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
    private val readRevision = MutableStateFlow(0L)
    val uiState = readRevision.flatMapLatest {
        detailReader.observe(source, runId).onStart { emit(SubAssistantDetailUiState.Loading) }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubAssistantDetailUiState.Loading)
    val settings = settingsStore.userSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun retry() {
        // The reader revalidates the original borrowed capability; retry never acquires another parent.
        readRevision.update { it + 1 }
    }

    fun attachmentPreviews(): Map<String, net.weero.measix.pilot.service.AttachmentPreview> =
        (uiState.value as? SubAssistantDetailUiState.Ready)?.attachmentPreviews.orEmpty()
}
