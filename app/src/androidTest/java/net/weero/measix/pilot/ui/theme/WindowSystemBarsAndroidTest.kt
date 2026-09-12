package net.weero.measix.pilot.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowSystemBarsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rootWindowFollowsVisibleThemeAndNestedStylingCannotOverwriteIt() {
        var dark by mutableStateOf(false)
        var nested by mutableStateOf(ColorMode.DARK)
        compose.setContent {
            WindowSystemBars(dark)
            MeasixTheme(colorMode = nested) { }
        }
        fun checkLight(expected: Boolean) = compose.runOnIdle {
            val window = compose.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals(expected, controller.isAppearanceLightStatusBars)
            assertEquals(expected, controller.isAppearanceLightNavigationBars)
        }
        checkLight(true)
        compose.runOnIdle { dark = true; nested = ColorMode.LIGHT }
        checkLight(false)
        compose.runOnIdle { dark = false; nested = ColorMode.DARK }
        checkLight(true)
    }
}
