package net.weero.measix.pilot.ui.hooks

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * 支持的应用语言。
 *
 * - [SYSTEM]：跟随系统区域设置
 * - 其余成员：用 BCP-47 tag 精确指定
 *
 * 持久化方式与 [ColorMode] 一致：SharedPreferences key [APP_LANGUAGE_KEY]
 * 存枚举名，解析时找不到则回退 [SYSTEM]。
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    CHINESE("zh"),
    JAPANESE("ja"),
    KOREAN("ko-KR"),
    RUSSIAN("ru");

    companion object {
        fun parse(raw: String?): AppLanguage {
            return entries.firstOrNull { it.name == raw } ?: SYSTEM
        }
    }
}

private const val APP_LANGUAGE_KEY = "appLanguage"

/**
 * 读取当前持久化的语言偏好。
 */
fun Context.getCurrentAppLanguage(): AppLanguage {
    val raw = readStringPreference(APP_LANGUAGE_KEY, AppLanguage.SYSTEM.name)
    return AppLanguage.parse(raw)
}

/**
 * 将 [Context] 的 Configuration 包裹为指定语言。
 *
 * 在 Activity.attachBaseContext 中调用，
 * 确保 Activity 从创建起就用正确的 locale，
 * ComponentActivity 也能生效（不依赖 AppCompatDelegate）。
 *
 * SYSTEM 不修改 Configuration，直接返回原 context。
 */
fun Context.wrapWithLocale(language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM || language.tag == null) return this
    val locale = Locale.forLanguageTag(language.tag)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

/**
 * Compose 侧的响应式读写 hook，模式与 [rememberColorMode] 完全一致。
 *
 * setter 写 SharedPreferences 后，调用方需要触发 Activity recreate
 * 才能让新 locale 生效（因为 RouteActivity 的 configChanges 拦截了配置变更）。
 */
@Composable
fun rememberAppLanguage(): MutableState<AppLanguage> {
    val context = LocalContext.current
    val state = rememberSharedPreferenceString(APP_LANGUAGE_KEY, AppLanguage.SYSTEM.name)
    return remember {
        object : MutableState<AppLanguage> {
            override var value: AppLanguage
                get() = AppLanguage.parse(state.value)
                set(value) {
                    state.value = value.name
                    // 找到 Activity 并 recreate，让 attachBaseContext 重新注入 locale
                    context.findActivity()?.recreate()
                }

            override fun component1(): AppLanguage = value
            override fun component2(): (AppLanguage) -> Unit = { value = it }
        }
    }
}

/** 从 Compose Context 链中找到 Activity */
private tailrec fun Context.findActivity(): android.app.Activity? {
    return when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
