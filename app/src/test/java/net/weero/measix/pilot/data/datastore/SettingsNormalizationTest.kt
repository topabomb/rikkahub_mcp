package net.weero.measix.pilot.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.data.model.QuickMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsNormalizationTest {
    @Test
    fun `read materialization completes defaults and removes duplicate or dangling references`() {
        val providerId = Uuid.random()
        val modelId = Uuid.random()
        val staleId = Uuid.random()
        val assistantId = Uuid.random()
        val modeId = Uuid.random()
        val quickMessageId = Uuid.random()
        val asrId = Uuid.random()
        val model = Model(id = modelId, modelId = "model")
        val provider = ProviderSetting.OpenAI(
            id = providerId,
            models = listOf(model, model.copy()),
        )
        val assistant = Assistant(
            id = assistantId,
            modeInjectionIds = setOf(modeId, staleId),
            quickMessageIds = setOf(quickMessageId, staleId),
        )
        val asr = ASRProviderSetting.OpenAIRealtime(id = asrId)
        val stored = Settings(
            providers = listOf(provider, provider.copy()),
            assistants = listOf(assistant, assistant.copy()),
            favoriteModels = listOf(modelId, staleId),
            asrProviders = listOf(asr, asr.copy()),
            selectedASRProviderId = staleId,
            modeInjections = listOf(
                PromptInjection.ModeInjection(id = modeId),
                PromptInjection.ModeInjection(id = modeId),
            ),
            quickMessages = listOf(
                QuickMessage(id = quickMessageId),
                QuickMessage(id = quickMessageId),
            ),
        )

        val materialized = stored.materializeForRead()
        val materializedProvider = materialized.providers.single { it.id == providerId }
        val materializedAssistant = materialized.assistants.single { it.id == assistantId }

        assertEquals(1, materializedProvider.models.size)
        assertEquals(setOf(modeId), materializedAssistant.modeInjectionIds)
        assertEquals(setOf(quickMessageId), materializedAssistant.quickMessageIds)
        assertEquals(listOf(modelId), materialized.favoriteModels)
        assertEquals(listOf(asr), materialized.asrProviders)
        assertEquals(asrId, materialized.selectedASRProviderId)
        assertEquals(1, materialized.modeInjections.count { it.id == modeId })
        assertEquals(1, materialized.quickMessages.count { it.id == quickMessageId })
        assertTrue(DEFAULT_PROVIDERS.all { default -> materialized.providers.any { it.id == default.id } })
        assertTrue(DEFAULT_ASSISTANTS.all { default -> materialized.assistants.any { it.id == default.id } })

        // 读取模型的修复不能反向修改原始持久化快照。
        assertEquals(2, stored.providers.size)
        assertEquals(listOf(modelId, staleId), stored.favoriteModels)
    }

    @Test
    fun `read materialization restores transient metadata for a stored built-in provider`() {
        val defaultProvider = DEFAULT_PROVIDERS.first()
        val storedProvider = defaultProvider.copyProvider(
            name = "User-visible name",
            builtIn = false,
        )

        val materialized = Settings(providers = listOf(storedProvider)).materializeForRead()
        val restored = materialized.providers.single { it.id == defaultProvider.id }

        assertEquals("User-visible name", restored.name)
        assertTrue(restored.builtIn)
    }
}
