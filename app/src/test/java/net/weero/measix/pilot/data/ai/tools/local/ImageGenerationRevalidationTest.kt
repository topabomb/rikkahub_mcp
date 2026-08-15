package net.weero.measix.pilot.data.ai.tools.local

import io.mockk.every
import io.mockk.mockk
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.imggen.ImageGenerationModelDescriptor
import net.weero.measix.pilot.data.imggen.ImageGenerationSelection
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ImageGenerationRevalidationTest {
    private val ownerId = Uuid.random()
    private val model = Model(modelId = "gpt-image-1", displayName = "GPT Image", type = ModelType.IMAGE)
    private val providerSetting = ProviderSetting.OpenAI(models = listOf(model))

    private fun available(): ImageGenerationSelection.Available {
        val provider = mockk<Provider<*>>()
        every { provider.supportsImageGeneration } returns true
        return ImageGenerationSelection.Available(
            model = model,
            sourceProvider = providerSetting,
            effectiveProvider = providerSetting,
            provider = provider,
            descriptor = ImageGenerationModelDescriptor.from(model, providerSetting),
        )
    }

    @Test
    fun `missing owner fails as assistant not found`() {
        val resolver = mockk<ImageGenerationSelectionResolver>()
        val reason = revalidateGenerateImage(
            settings = Settings(assistants = emptyList()),
            ownerAssistantId = ownerId,
            capturedSelection = available(),
            resolver = resolver,
        )
        assertEquals("assistant_not_found", reason?.reason)
    }

    @Test
    fun `revoked option fails without using a new model`() {
        val resolver = mockk<ImageGenerationSelectionResolver>()
        val reason = revalidateGenerateImage(
            settings = Settings(assistants = listOf(Assistant(id = ownerId))),
            ownerAssistantId = ownerId,
            capturedSelection = available(),
            resolver = resolver,
        )
        assertEquals("tool_revoked", reason?.reason)
    }

    @Test
    fun `changed default model is a stable failure`() {
        val latest = available().copy(
            model = model.copy(id = Uuid.random()),
        )
        val resolver = mockk<ImageGenerationSelectionResolver>()
        every { resolver.resolve(any()) } returns latest
        val reason = revalidateGenerateImage(
            settings = Settings(
                assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
            ),
            ownerAssistantId = ownerId,
            capturedSelection = available(),
            resolver = resolver,
        )
        assertEquals("image_model_changed", reason?.reason)
    }

    @Test
    fun `same captured selection remains valid`() {
        val captured = available()
        val resolver = mockk<ImageGenerationSelectionResolver>()
        every { resolver.resolve(any()) } returns captured
        val reason = revalidateGenerateImage(
            settings = Settings(
                assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
            ),
            ownerAssistantId = ownerId,
            capturedSelection = captured,
            resolver = resolver,
        )
        assertNull(reason)
    }
}
