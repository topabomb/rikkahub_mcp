package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import net.weero.measix.pilot.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
