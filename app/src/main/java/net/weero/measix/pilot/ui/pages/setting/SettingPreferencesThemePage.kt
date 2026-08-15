package net.weero.measix.pilot.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.ui.components.ui.Select
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.hooks.rememberAmoledDarkMode
import net.weero.measix.pilot.ui.hooks.rememberColorMode
import net.weero.measix.pilot.ui.theme.AppearancePolicy
import net.weero.measix.pilot.ui.theme.ColorMode
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var colorMode by rememberColorMode()
    var amoledDarkMode by rememberAmoledDarkMode()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val dynamicColorAvailable = AppearancePolicy.isDynamicColorAvailable(Build.VERSION.SDK_INT)
    val dynamicColorEffective = AppearancePolicy.isDynamicColorEffective(
        dynamicColor = settings.dynamicColor,
        sdkInt = Build.VERSION.SDK_INT,
    )
    val amoledEnabled = colorMode != ColorMode.LIGHT
    val amoledAppliesNow = AppearancePolicy.shouldApplyAmoled(
        darkTheme = AppearancePolicy.resolveDarkTheme(colorMode, isSystemInDarkTheme()),
        amoledDarkMode = amoledDarkMode,
    )

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_theme))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                        supportingContent = {
                            Text(
                                when (colorMode) {
                                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                }
                            )
                        },
                        trailingContent = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = { colorMode = it },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                                modifier = Modifier.width(150.dp),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                        supportingContent = {
                            Text(
                                if (dynamicColorAvailable) {
                                    stringResource(R.string.setting_page_dynamic_color_desc)
                                } else {
                                    stringResource(R.string.setting_page_dynamic_color_unavailable_desc)
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.dynamicColor && dynamicColorAvailable,
                                enabled = dynamicColorAvailable,
                                onCheckedChange = { value ->
                                    vm.updateSettings { it.copy(dynamicColor = value) }
                                },
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTheme) },
                        headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                        supportingContent = {
                            Text(
                                if (dynamicColorEffective) {
                                    stringResource(R.string.setting_page_theme_setting_dynamic_override_desc)
                                } else {
                                    stringResource(R.string.setting_page_theme_setting_desc)
                                }
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                        supportingContent = {
                            Text(
                                when {
                                    !amoledEnabled -> stringResource(R.string.setting_display_page_amoled_dark_mode_inactive_desc)
                                    colorMode == ColorMode.SYSTEM && !amoledAppliesNow ->
                                        stringResource(R.string.setting_display_page_amoled_dark_mode_inactive_desc)
                                    else -> stringResource(R.string.setting_display_page_amoled_dark_mode_desc)
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = amoledDarkMode,
                                enabled = amoledEnabled,
                                onCheckedChange = { amoledDarkMode = it }
                            )
                        },
                    )
                }
            }
        }
    }
}
