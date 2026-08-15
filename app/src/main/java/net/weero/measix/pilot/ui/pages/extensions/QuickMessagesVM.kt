package net.weero.measix.pilot.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.QuickMessage
import kotlin.uuid.Uuid

class QuickMessagesVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun addQuickMessage(title: String, content: String) {
        val quickMessage = QuickMessage(
            title = title,
            content = content,
        )
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(quickMessages = current.quickMessages + quickMessage)
            }
        }
    }

    fun updateQuickMessage(updated: QuickMessage) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    quickMessages = current.quickMessages.map { quickMessage ->
                        if (quickMessage.id == updated.id) updated else quickMessage
                    }
                )
            }
        }
    }

    fun deleteQuickMessage(id: Uuid) {
        viewModelScope.launch {
            settingsStore.update { current ->
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
}
