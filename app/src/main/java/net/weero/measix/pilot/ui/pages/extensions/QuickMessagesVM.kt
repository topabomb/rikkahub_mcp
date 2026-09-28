package net.weero.measix.pilot.ui.pages.extensions

import me.rerere.common.configuration.ConfigurationReference
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
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.QuickMessage

class QuickMessagesVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.userSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    suspend fun addQuickMessage(title: String, content: String) {
        val quickMessage = QuickMessage(
            title = title,
            content = content,
        )
        settingsStore.updateLocal { current ->
                current.copy(quickMessages = current.quickMessages + quickMessage)
        }
    }

    suspend fun updateQuickMessage(updated: QuickMessage) {
        settingsStore.updateLocal { current ->
                current.copy(
                    quickMessages = current.quickMessages.map { quickMessage ->
                        if (quickMessage.id == updated.id) updated else quickMessage
                    }
                )
        }
    }

    suspend fun deleteQuickMessage(id: ConfigurationReference) {
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
