package me.rerere.ai.provider

/** Implemented transport features, independent of endpoints and credentials. */
data class ChatTransportCapabilities(val audioInput: Boolean, val videoInput: Boolean, val builtInSearch: Boolean) {
    companion object {
        val BASIC = ChatTransportCapabilities(false, false, false)
        val GOOGLE = ChatTransportCapabilities(true, true, true)
        val RESPONSES = ChatTransportCapabilities(false, false, true)
    }
}

fun chatTransportCapabilities(provider: ProviderSetting): ChatTransportCapabilities = when (provider) {
    is ProviderSetting.Google -> ChatTransportCapabilities.GOOGLE
    is ProviderSetting.OpenAI -> if (provider.useResponseApi) ChatTransportCapabilities.RESPONSES else ChatTransportCapabilities.BASIC
    is ProviderSetting.Claude -> ChatTransportCapabilities.BASIC
}

fun supportsBuiltInSearch(provider: ProviderSetting): Boolean = chatTransportCapabilities(provider).builtInSearch

/** Client transport support; compatible remote hosts may still reject their endpoint at runtime. */
fun supportsImageGeneration(provider: ProviderSetting): Boolean = when (provider) {
    is ProviderSetting.OpenAI -> true
    is ProviderSetting.Google, is ProviderSetting.Claude -> false
}
