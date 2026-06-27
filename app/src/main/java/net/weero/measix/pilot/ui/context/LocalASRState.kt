package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import net.weero.measix.pilot.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

