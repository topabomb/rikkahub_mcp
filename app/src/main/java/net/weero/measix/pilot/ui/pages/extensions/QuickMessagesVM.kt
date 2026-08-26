package net.weero.measix.pilot.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.QuickMessage
import kotlin.uuid.Uuid

class QuickMessagesVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    private val _lockedChanges = MutableSharedFlow<SettingsLockedException>(extraBufferCapacity = 1)
    val lockedChanges = _lockedChanges.asSharedFlow()
    internal val effectiveSettings: StateFlow<EffectiveSettingsSnapshot> = settingsStore.effectiveSettings
    val settings = settingsStore.effectiveSettings.map { it.settings }
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun addQuickMessage(title: String, content: String) {
        val quickMessage = QuickMessage(
            title = title,
            content = content,
        )
        updateSettings {
            settingsStore.updateLocal { current ->
                current.copy(quickMessages = current.quickMessages + quickMessage)
            }
        }
    }

    fun updateQuickMessage(updated: QuickMessage) {
        updateSettings {
            settingsStore.updateLocal { current ->
                current.copy(
                    quickMessages = current.quickMessages.map { quickMessage ->
                        if (quickMessage.id == updated.id) updated else quickMessage
                    }
                )
            }
        }
    }

    fun deleteQuickMessage(id: Uuid) {
        updateSettings {
            settingsStore.updateLocal { current ->
                current.copy(
                    quickMessages = current.quickMessages.filterNot { it.id == id },
                    assistants = current.assistants.map { assistant ->
                        if (id in assistant.quickMessageIds) {
                            assistant.copy(quickMessageIds = assistant.quickMessageIds - id)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
    }

    private fun updateSettings(update: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                update()
            } catch (error: SettingsLockedException) {
                _lockedChanges.emit(error)
            }
        }
    }
}
