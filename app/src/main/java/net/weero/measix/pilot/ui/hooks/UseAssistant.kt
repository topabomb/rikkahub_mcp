package net.weero.measix.pilot.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid

@Composable
fun rememberAssistantState(
    settings: Settings,
    onSelectAssistant: (Uuid) -> Unit,
): AssistantState {
    return remember(settings, onSelectAssistant) {
        AssistantState(settings, onSelectAssistant)
    }
}

class AssistantState(
    private val settings: Settings,
    private val onSelectAssistant: (Uuid) -> Unit,
) {
    private var _currentAssistant by mutableStateOf(
        settings.getCurrentAssistant()
    )
    val currentAssistant get() = _currentAssistant

    fun setSelectAssistant(assistant: Assistant) {
        onSelectAssistant(assistant.id)
    }
}
