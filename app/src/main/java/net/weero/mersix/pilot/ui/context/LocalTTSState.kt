package net.weero.mersix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import net.weero.mersix.pilot.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
