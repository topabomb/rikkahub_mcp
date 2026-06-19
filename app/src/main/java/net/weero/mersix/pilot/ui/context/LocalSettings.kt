package net.weero.mersix.pilot.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import net.weero.mersix.pilot.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
