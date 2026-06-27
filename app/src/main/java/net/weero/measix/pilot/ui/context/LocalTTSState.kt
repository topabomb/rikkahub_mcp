package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import net.weero.measix.pilot.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
