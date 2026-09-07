package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk

interface OpenAIImpl {
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>
}
