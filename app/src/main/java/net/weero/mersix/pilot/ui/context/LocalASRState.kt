package net.weero.mersix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import net.weero.mersix.pilot.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

