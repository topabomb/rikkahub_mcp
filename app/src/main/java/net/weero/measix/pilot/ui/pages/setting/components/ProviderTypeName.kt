package net.weero.measix.pilot.ui.pages.setting.components

import androidx.annotation.StringRes
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.R
import kotlin.reflect.KClass

/**
 * Returns the stable UI label for a provider protocol type.
 *
 * Never derive user-visible text from [KClass.simpleName]: release builds obfuscate class names,
 * so values such as `OpenAI` become short R8 names such as `y48`. Protocol types are a closed set;
 * an explicit resource mapping keeps their labels stable without disabling release obfuscation.
 */
@StringRes
internal fun providerTypeNameRes(type: KClass<out ProviderSetting>): Int = when (type) {
    ProviderSetting.OpenAI::class -> R.string.setting_provider_type_openai
    ProviderSetting.Google::class -> R.string.setting_provider_type_google
    ProviderSetting.Claude::class -> R.string.setting_provider_type_claude
    else -> error("Unsupported provider type: $type")
}

@StringRes
internal fun ProviderSetting.providerTypeNameRes(): Int = providerTypeNameRes(this::class)
