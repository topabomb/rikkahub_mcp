package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import net.weero.measix.pilot.service.SpeechPlayback

val LocalTTSState = compositionLocalOf<SpeechPlayback> { error("Not provided yet") }
