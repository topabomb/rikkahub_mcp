package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import net.weero.measix.pilot.service.SpeechRecognition

val LocalASRState = compositionLocalOf<SpeechRecognition> { error("Not provided yet") }

