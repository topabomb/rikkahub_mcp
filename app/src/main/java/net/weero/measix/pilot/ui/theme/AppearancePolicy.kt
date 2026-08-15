package net.weero.measix.pilot.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 明暗、动态色门槛和 AMOLED 纯黑改写的纯判定。
 * 预设/自定义主题查找仍由 [findThemeById] / [findPresetTheme] 负责。
 */
object AppearancePolicy {
    const val DYNAMIC_COLOR_MIN_SDK = 31

    val AmoledBackground: Color = Color(0xFF000000)

    fun parseColorMode(raw: String?): ColorMode {
        return ColorMode.entries.firstOrNull { it.name == raw } ?: ColorMode.SYSTEM
    }

    fun resolveDarkTheme(colorMode: ColorMode, systemInDarkTheme: Boolean): Boolean {
        return when (colorMode) {
            ColorMode.SYSTEM -> systemInDarkTheme
            ColorMode.LIGHT -> false
            ColorMode.DARK -> true
        }
    }

    fun isDynamicColorAvailable(sdkInt: Int): Boolean = sdkInt >= DYNAMIC_COLOR_MIN_SDK

    fun isDynamicColorEffective(dynamicColor: Boolean, sdkInt: Int): Boolean {
        return dynamicColor && isDynamicColorAvailable(sdkInt)
    }

    fun shouldApplyAmoled(darkTheme: Boolean, amoledDarkMode: Boolean): Boolean {
        return darkTheme && amoledDarkMode
    }

    fun applyAmoledDark(colorScheme: ColorScheme): ColorScheme {
        return colorScheme.copy(
            background = AmoledBackground,
            surface = AmoledBackground,
        )
    }
}
