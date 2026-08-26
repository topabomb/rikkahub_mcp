package net.weero.measix.pilot.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import org.koin.compose.koinInject

@Composable
fun rememberUserSettingsState(): State<Settings> {
    val store = koinInject<SettingsStore>()
    val settings = remember(store) {
        store.effectiveSettings.map { it.settings }
    }
    return settings
        .collectAsStateWithLifecycle(
            initialValue = Settings.dummy(),
        )
}
