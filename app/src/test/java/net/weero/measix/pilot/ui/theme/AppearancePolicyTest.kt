package net.weero.measix.pilot.ui.theme

import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePolicyTest {
    @Test
    fun `color mode maps to dark theme`() {
        assertTrue(AppearancePolicy.resolveDarkTheme(ColorMode.DARK, systemInDarkTheme = false))
        assertFalse(AppearancePolicy.resolveDarkTheme(ColorMode.LIGHT, systemInDarkTheme = true))
        assertTrue(AppearancePolicy.resolveDarkTheme(ColorMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(AppearancePolicy.resolveDarkTheme(ColorMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun `unknown color mode strings fall back to system`() {
        assertEquals(ColorMode.SYSTEM, AppearancePolicy.parseColorMode(null))
        assertEquals(ColorMode.SYSTEM, AppearancePolicy.parseColorMode("not-a-mode"))
        assertEquals(ColorMode.DARK, AppearancePolicy.parseColorMode("DARK"))
        assertEquals(ColorMode.LIGHT, AppearancePolicy.parseColorMode("LIGHT"))
    }

    @Test
    fun `dynamic color is only effective on Android 12 and above`() {
        assertFalse(AppearancePolicy.isDynamicColorAvailable(30))
        assertTrue(AppearancePolicy.isDynamicColorAvailable(31))
        assertFalse(AppearancePolicy.isDynamicColorEffective(dynamicColor = true, sdkInt = 30))
        assertTrue(AppearancePolicy.isDynamicColorEffective(dynamicColor = true, sdkInt = 31))
        assertFalse(AppearancePolicy.isDynamicColorEffective(dynamicColor = false, sdkInt = 34))
    }

    @Test
    fun `amoled only remaps when the resolved theme is dark`() {
        assertFalse(AppearancePolicy.shouldApplyAmoled(darkTheme = false, amoledDarkMode = true))
        assertFalse(AppearancePolicy.shouldApplyAmoled(darkTheme = true, amoledDarkMode = false))
        assertTrue(AppearancePolicy.shouldApplyAmoled(darkTheme = true, amoledDarkMode = true))
    }

    @Test
    fun `amoled remap only replaces background and surface`() {
        val scheme = lightColorScheme()
        val amoled = AppearancePolicy.applyAmoledDark(scheme)
        assertEquals(AppearancePolicy.AmoledBackground, amoled.background)
        assertEquals(AppearancePolicy.AmoledBackground, amoled.surface)
        assertEquals(scheme.surfaceContainerHighest, amoled.surfaceContainerHighest)
        assertEquals(scheme.primary, amoled.primary)
    }

    @Test
    fun `unknown theme id falls back to sakura`() {
        val theme = findThemeById("missing", customThemes = emptyList())
            ?: findPresetTheme("missing")
        assertEquals("sakura", theme.id)
    }
}
