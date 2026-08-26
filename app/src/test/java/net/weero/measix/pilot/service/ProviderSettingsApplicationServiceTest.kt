package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderSettingsApplicationServiceTest {
    @Test
    fun `catalog is sorted and enriched behind application boundary`() = runTest {
        val (service, setting, provider) = fixture()
        coEvery { provider.listModels(setting) } returns listOf(
            Model(modelId = "z-model"),
            Model(modelId = "deepseek-v4-flash-vision-exp"),
        )

        val result = service.listModels(setting)

        assertEquals(listOf("deepseek-v4-flash-vision-exp", "z-model"), result.map { it.modelId })
        assertTrue(Modality.IMAGE in result.first().inputModalities)
    }

    @Test
    fun `streaming chunks are projected without exposing provider protocol`() = runTest {
        val (service, setting, provider) = fixture()
        val model = Model(modelId = "test")
        coEvery { provider.streamText(setting, any(), any()) } returns flowOf(
            chunk(delta = UIMessagePart.Text("one")),
            chunk(delta = UIMessagePart.Text(" two")),
        )
        val text = StringBuilder()

        service.testStreaming(setting, model) { text.append(it) }

        assertEquals("one two", text.toString())
    }

    @Test
    fun `provider cancellation propagates unchanged`() = runTest {
        val (service, setting, provider) = fixture()
        val cancellation = CancellationException("dismissed")
        coEvery { provider.generateText(setting, any(), any()) } throws cancellation

        val actual = try {
            service.testNonStreaming(setting, Model(modelId = "test"))
            error("Expected cancellation")
        } catch (error: CancellationException) {
            error
        }

        assertTrue(actual === cancellation)
    }

    @Test
    fun `configuration save preserves models from the latest atomic snapshot`() = runTest {
        val providerId = Uuid.random()
        val concurrentModel = Model(id = Uuid.random(), modelId = "concurrent")
        val latest = ProviderSetting.OpenAI(
            id = providerId,
            name = "Latest",
            apiKey = "old-key",
            models = listOf(concurrentModel),
        )
        val edited = latest.copy(name = "Edited", apiKey = "new-key", models = emptyList())
        val store = mockk<SettingsStore>()
        var persisted: Settings? = null
        coEvery { store.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(Settings(providers = listOf(latest))).also { persisted = it }
        }

        ProviderSettingsApplicationService(mockk(), store).saveConfiguration(providerId, edited)

        val saved = persisted!!.providers.single() as ProviderSetting.OpenAI
        assertEquals("Edited", saved.name)
        assertEquals("new-key", saved.apiKey)
        assertEquals(listOf(concurrentModel), saved.models)
    }

    @Test
    fun `model reorder resolves stable ids against latest list and missing ids are no-op`() = runTest {
        val first = Model(id = Uuid.random(), modelId = "a")
        val concurrent = Model(id = Uuid.random(), modelId = "b")
        val last = Model(id = Uuid.random(), modelId = "c")
        val latest = ProviderSetting.OpenAI(models = listOf(first, concurrent, last))
        val store = mockk<SettingsStore>()
        var current = Settings(providers = listOf(latest))
        coEvery { store.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(current).also { current = it }
        }
        val service = ProviderSettingsApplicationService(mockk(), store)

        service.moveModel(latest.id, first.id, last.id)
        assertEquals(listOf(concurrent, last, first), current.providers.single().models)

        val afterMove = current.providers.single()
        service.moveModel(latest.id, Uuid.random(), last.id)
        assertSame(afterMove, current.providers.single())
    }

    @Test
    fun `bulk model removal uses catalog model ids rather than transient uuids`() = runTest {
        val retained = Model(id = Uuid.random(), modelId = "retained")
        val removed = Model(id = Uuid.random(), modelId = "removed")
        val latest = ProviderSetting.OpenAI(models = listOf(retained, removed))
        val store = mockk<SettingsStore>()
        var current = Settings(providers = listOf(latest))
        coEvery { store.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(current).also { current = it }
        }
        val service = ProviderSettingsApplicationService(mockk(), store)

        service.removeModelsByModelIds(latest.id, setOf("removed"))

        assertEquals(listOf(retained), current.providers.single().models)
    }

    @Test
    fun `background connection probe leaves OpenRouter session identity absent`() = runTest {
        val (service, setting, provider) = fixture(
            ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1"),
        )
        var params: me.rerere.ai.provider.TextGenerationParams? = null
        coEvery { provider.generateText(setting, any(), any()) } coAnswers {
            params = thirdArg()
            chunk(delta = UIMessagePart.Text("ok"))
        }

        service.testNonStreaming(setting, Model(modelId = "test"))

        assertEquals(null, params?.providerSessionId)
    }

    @Test
    fun `balance cache identity includes the edited endpoint and credential`() = runTest {
        val providerId = Uuid.random()
        val first = ProviderSetting.OpenAI(
            id = providerId,
            baseUrl = "https://first.example/v1",
            apiKey = "first-key",
        )
        val edited = first.copy(
            baseUrl = "https://second.example/v1",
            apiKey = "second-key",
        )
        val manager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { manager.getProviderByType(first) } returns provider
        every { manager.getProviderByType(edited) } returns provider
        coEvery { provider.getBalance(first) } returns "first"
        coEvery { provider.getBalance(edited) } returns "second"
        val service = ProviderSettingsApplicationService(manager, mockk())

        assertEquals("first", service.getBalance(first))
        assertEquals("second", service.getBalance(edited))
        assertEquals("first", service.getBalance(first))

        coVerify(exactly = 1) { provider.getBalance(first) }
        coVerify(exactly = 1) { provider.getBalance(edited) }
    }

    private fun fixture(
        setting: ProviderSetting.OpenAI = ProviderSetting.OpenAI(),
    ): Triple<ProviderSettingsApplicationService, ProviderSetting.OpenAI, Provider<ProviderSetting.OpenAI>> {
        val manager = mockk<ProviderManager>()
        val provider = mockk<Provider<ProviderSetting.OpenAI>>()
        every { manager.getProviderByType(setting) } returns provider
        return Triple(ProviderSettingsApplicationService(manager, mockk<SettingsStore>()), setting, provider)
    }

    private fun chunk(delta: UIMessagePart) = MessageChunk(
        id = "id",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(delta)),
                message = null,
                finishReason = null,
            )
        ),
    )
}
