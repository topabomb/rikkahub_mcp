package net.weero.measix.pilot.ui.hooks

import me.rerere.common.configuration.ConfigurationReference
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.data.model.Assistant

@Composable
fun rememberAssistantState(
    settings: Settings,
    onSelectAssistant: (ConfigurationReference) -> Unit,
): AssistantState {
    return remember(settings, onSelectAssistant) {
        AssistantState(settings, onSelectAssistant)
    }
}

class AssistantState(
    private val settings: Settings,
    private val onSelectAssistant: (ConfigurationReference) -> Unit,
) {
    private var _currentAssistant by mutableStateOf(
        settings.getCurrentAssistant()
    )
    val currentAssistant get() = _currentAssistant

    fun setSelectAssistant(assistant: Assistant) {
        onSelectAssistant(assistant.id)
    }
}
