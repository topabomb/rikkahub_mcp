package net.weero.measix.pilot.service.runtime

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderTransportLeaseTest {
    @Test
    fun `frozen OpenAI shape excludes credentials and live lease cannot change wire options`() {
        val id = Uuid.random()
        val start = ProviderSetting.OpenAI(
            id = id,
            apiKey = "start-secret",
            baseUrl = "https://start.example/v1",
            chatCompletionsPath = "/chat/start",
            useResponseApi = false,
            includeHistoryReasoning = false,
        )
        val model = Model(modelId = "model", displayName = "Model")
        val frozen = freezeProviderWireShape(start, model) as FrozenProviderWireShape.OpenAI
        assertEquals(listOf(model), listOf(frozen.model))

        val merged = mergeProviderTransportCredentials(
            frozen = frozen,
            live = start.copy(
                apiKey = "rotated-secret",
                baseUrl = "https://changed.example/v1",
                chatCompletionsPath = "/chat/changed",
                useResponseApi = true,
                includeHistoryReasoning = true,
            ),
        ) as ProviderSetting.OpenAI

        assertEquals("rotated-secret", merged.apiKey)
        assertEquals("https://start.example/v1", merged.baseUrl)
        assertEquals("/chat/start", merged.chatCompletionsPath)
        assertEquals(false, merged.useResponseApi)
        assertEquals(false, merged.includeHistoryReasoning)
    }

    @Test
    fun `transport owner follows model provider overwrite`() {
        val overwrite = ProviderSetting.OpenAI(apiKey = "override-secret", baseUrl = "https://override.example/v1")
        val model = Model(modelId = "model", displayName = "Model", providerOverwrite = overwrite)
        val catalogProvider = ProviderSetting.OpenAI(models = listOf(model), apiKey = "catalog-secret")

        val settings = Settings(providers = listOf(catalogProvider))
        val locator = captureProviderCredentialOwner(settings, model, overwrite)
        val owner = resolveProviderTransportOwner(settings, locator) as ProviderSetting.OpenAI

        assertEquals(overwrite.id, owner.id)
        assertEquals("override-secret", owner.apiKey)
    }

    @Test
    fun `overwrite locator permits secret rotation only on the same exact owner`() {
        val overwriteId = Uuid.random()
        val startOverwrite = ProviderSetting.OpenAI(id = overwriteId, apiKey = "old")
        val model = Model(modelId = "model", displayName = "Model", providerOverwrite = startOverwrite)
        val catalog = ProviderSetting.OpenAI(models = listOf(model))
        val start = Settings(providers = listOf(catalog))
        val locator = captureProviderCredentialOwner(start, model, startOverwrite)
        val rotatedModel = model.copy(providerOverwrite = startOverwrite.copy(apiKey = "rotated"))

        val owner = resolveProviderTransportOwner(
            Settings(providers = listOf(catalog.copy(models = listOf(rotatedModel)))),
            locator,
        ) as ProviderSetting.OpenAI

        assertEquals("rotated", owner.apiKey)
    }

    @Test
    fun `overwrite locator rejects replacement owner and duplicate model identities`() {
        val startOverwrite = ProviderSetting.OpenAI(apiKey = "old")
        val model = Model(modelId = "model", displayName = "Model", providerOverwrite = startOverwrite)
        val catalog = ProviderSetting.OpenAI(models = listOf(model))
        val settings = Settings(providers = listOf(catalog))
        val locator = captureProviderCredentialOwner(settings, model, startOverwrite)
        val replacement = model.copy(providerOverwrite = ProviderSetting.OpenAI(apiKey = "replacement"))
        assertThrows(IllegalStateException::class.java) {
            resolveProviderTransportOwner(
                Settings(providers = listOf(catalog.copy(models = listOf(replacement)))),
                locator,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            resolveProviderTransportOwner(
                Settings(providers = listOf(catalog.copy(models = listOf(model, model)))),
                locator,
            )
        }
    }

    @Test
    fun `overwrite locator does not follow a moved model into another catalog provider`() {
        val overwrite = ProviderSetting.OpenAI(apiKey = "secret")
        val model = Model(modelId = "model", displayName = "Model", providerOverwrite = overwrite)
        val startContainer = ProviderSetting.OpenAI(models = listOf(model))
        val start = Settings(providers = listOf(startContainer))
        val locator = captureProviderCredentialOwner(start, model, overwrite)
        val movedContainer = ProviderSetting.OpenAI(models = listOf(model))

        assertThrows(IllegalStateException::class.java) {
            resolveProviderTransportOwner(Settings(providers = listOf(movedContainer)), locator)
        }
    }

    @Test
    fun `transport lease rejects provider identity drift`() {
        val frozen = freezeProviderWireShape(
            ProviderSetting.Claude(apiKey = "secret"),
            Model(modelId = "model", displayName = "Model"),
        )
        assertThrows(IllegalStateException::class.java) {
            mergeProviderTransportCredentials(
                frozen = frozen,
                live = ProviderSetting.Claude(id = Uuid.random(), apiKey = "other"),
            )
        }
    }
}
