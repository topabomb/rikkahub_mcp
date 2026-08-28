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
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.DisplaySetting
import net.weero.measix.pilot.service.CustomChatFontService
import net.weero.measix.pilot.service.ArtifactUseCase

class SettingVM(
    private val settingsStore: SettingsStore,
    private val customChatFontService: CustomChatFontService,
    private val artifactUseCase: ArtifactUseCase,
) :
    ViewModel() {
    internal val effectiveSettings: StateFlow<EffectiveSettingsSnapshot> = settingsStore.effectiveSettings
    val settings: StateFlow<Settings> = effectiveSettings.map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))
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
