package net.weero.measix.pilot.ui.pages.setting

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.DisplaySetting
import net.weero.measix.pilot.service.CustomChatFontService
import net.weero.measix.pilot.service.ArtifactUseCase

class SettingVM internal constructor(
    private val settingsStore: SettingsStore,
    private val customChatFontService: CustomChatFontService,
    private val artifactUseCase: ArtifactUseCase,
    configurationQueryService: net.weero.measix.pilot.service.ConfigurationQueryService,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.userSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))
    internal val modelCatalog = configurationQueryService.observeModelCatalog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), net.weero.measix.pilot.service.ModelCatalogReadState.Loading)
    private val _lockedChange = MutableStateFlow<SettingsLockedException?>(null)
    val lockedChange: StateFlow<SettingsLockedException?> = _lockedChange.asStateFlow()

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            try {
                artifactUseCase.updateSettingsReferences(transform)
                _lockedChange.value = null
            } catch (error: SettingsLockedException) {
                _lockedChange.value = error
            }
        }
    }

    fun clearLockedChange() {
        _lockedChange.value = null
    }

    suspend fun importCustomChatFont(uri: Uri): DisplaySetting = customChatFontService.import(uri)

    suspend fun removeCustomChatFont(expectedRelativePath: String): DisplaySetting =
        customChatFontService.remove(expectedRelativePath)

}
