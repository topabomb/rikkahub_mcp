package me.rerere.ai.provider

fun supportsBuiltInSearch(provider: ProviderSetting): Boolean = when (provider) {
    is ProviderSetting.Google -> true
    is ProviderSetting.OpenAI -> provider.useResponseApi
    is ProviderSetting.Claude -> false
}

/** Client transport support; compatible remote hosts may still reject their endpoint at runtime. */
fun supportsImageGeneration(provider: ProviderSetting): Boolean = when (provider) {
    is ProviderSetting.OpenAI -> true
    is ProviderSetting.Google, is ProviderSetting.Claude -> false
}
