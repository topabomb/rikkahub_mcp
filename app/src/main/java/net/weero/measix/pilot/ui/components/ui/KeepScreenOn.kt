package net.weero.measix.pilot.ui.components.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import net.weero.measix.pilot.utils.getActivity

@Composable
fun KeepScreenOn(enabled: Boolean = true) {
    val context = LocalContext.current
    val window = context.getActivity()?.window
    DisposableEffect(window, enabled) {
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}
