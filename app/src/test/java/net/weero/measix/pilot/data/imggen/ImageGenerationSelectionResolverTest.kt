package net.weero.measix.pilot.data.imggen

import io.mockk.every
import io.mockk.mockk
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ImageGenerationSelectionResolverTest {
    private val modelId = Uuid.random()
    private val imageModel = Model(
        id = modelId,
        modelId = "gpt-image-1",
        displayName = "GPT Image 1",
        type = ModelType.IMAGE,
    )

    @Test
    fun `missing model is unavailable`() {
        val resolver = ImageGenerationSelectionResolver(mockk(relaxed = true))
        val result = resolver.resolve(Settings(imageGenerationModelId = Uuid.random()))
        assertTrue(result is ImageGenerationSelection.Unavailable)
    }

    @Test
    fun `chat model type is unavailable`() {
        val chat = imageModel.copy(type = ModelType.CHAT)
        val resolver = ImageGenerationSelectionResolver(mockk(relaxed = true))
        val result = resolver.resolve(
            Settings(
                imageGenerationModelId = chat.id,
                providers = listOf(ProviderSetting.OpenAI(models = listOf(chat))),
            )
        )
        assertTrue(result is ImageGenerationSelection.Unavailable)
    }

    @Test
    fun `disabled source provider is unavailable`() {
        val resolver = ImageGenerationSelectionResolver(mockk(relaxed = true))
        val result = resolver.resolve(
            Settings(
                imageGenerationModelId = imageModel.id,
                providers = listOf(ProviderSetting.OpenAI(enabled = false, models = listOf(imageModel))),
            )
        )
        assertTrue(result is ImageGenerationSelection.Unavailable)
    }

    @Test
    fun `provider without capability is unavailable`() {
        val provider = mockk<Provider<ProviderSetting>>()
        every { provider.supportsImageGeneration } returns false
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(any<ProviderSetting>()) } returns provider
        val resolver = ImageGenerationSelectionResolver(manager)
        val result = resolver.resolve(
            Settings(
                imageGenerationModelId = imageModel.id,
                providers = listOf(ProviderSetting.OpenAI(models = listOf(imageModel))),
            )
        )
        assertTrue(result is ImageGenerationSelection.Unavailable)
    }

    @Test
    fun `valid openai image model is available and uses overwrite provider`() {
        val overwrite = ProviderSetting.OpenAI(name = "Override Host", enabled = true)
        val model = imageModel.copy(providerOverwrite = overwrite)
        val provider = mockk<Provider<ProviderSetting>>()
        every { provider.supportsImageGeneration } returns true
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(match { it.id == overwrite.id }) } returns provider
        val resolver = ImageGenerationSelectionResolver(manager)
        val result = resolver.resolve(
            Settings(
                imageGenerationModelId = model.id,
                providers = listOf(ProviderSetting.OpenAI(name = "Source", models = listOf(model))),
            )
        )
        val available = result as ImageGenerationSelection.Available
        assertEquals(overwrite.id, available.effectiveProvider.id)
        assertEquals("openai", available.descriptor.providerType)
        assertEquals("gpt-image-1", available.descriptor.modelId)
        assertEquals("GPT Image 1", available.descriptor.modelName)
    }

    @Test
    fun `supportsImageGeneration follows provider capability`() {
        val capable = mockk<Provider<ProviderSetting>>()
        every { capable.supportsImageGeneration } returns true
        val incapable = mockk<Provider<ProviderSetting>>()
        every { incapable.supportsImageGeneration } returns false
        val openai = ProviderSetting.OpenAI()
        val claude = ProviderSetting.Claude()
        val manager = mockk<ProviderManager>()
        every { manager.getProviderByType(match<ProviderSetting> { it.id == openai.id }) } returns capable
        every { manager.getProviderByType(match<ProviderSetting> { it.id == claude.id }) } returns incapable
        val resolver = ImageGenerationSelectionResolver(manager)
        assertTrue(resolver.supportsImageGeneration(openai))
        assertTrue(!resolver.supportsImageGeneration(claude))
    }
}
