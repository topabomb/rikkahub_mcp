package net.weero.measix.pilot.data.ai.tools

import io.mockk.every
import io.mockk.mockk
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.ai.tools.local.AssistantToolBuildContext
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolFactory
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.imggen.ImageGenerationModelDescriptor
import net.weero.measix.pilot.data.imggen.ImageGenerationSelection
import net.weero.measix.pilot.data.imggen.ImageGenerationSelectionResolver
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ImageGenerationToolFactoryTest {
    private val ownerId = Uuid.random()
    private val model = Model(modelId = "gpt-image-1", displayName = "GPT Image", type = ModelType.IMAGE)
    private val providerSetting = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(model))

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

    private fun factory(selection: ImageGenerationSelection): ImageGenerationToolFactory {
        val resolver = mockk<ImageGenerationSelectionResolver>()
        every { resolver.resolve(any()) } returns selection
        return ImageGenerationToolFactory(
            filesDir = File("."),
            settingsStore = mockk(relaxed = true),
            resolver = resolver,
            coordinator = mockk(relaxed = true),
            backgroundService = mockk(relaxed = true),
            artifactStore = mockk(relaxed = true),
            rewriter = mockk(relaxed = true),
        )
    }

    @Test
    fun `unavailable selection does not register the tool`() {
        val tool = factory(ImageGenerationSelection.Unavailable("image_model_unavailable")).create(
            AssistantToolBuildContext(ownerId, Settings()),
        )
        assertNull(tool)
    }

    @Test
    fun `available selection registers generate_image with captured owner and prompt`() {
        val tool = factory(available()).create(
            AssistantToolBuildContext(
                ownerAssistantId = ownerId,
                settings = Settings(
                    assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
                ),
            ),
        )
        assertNotNull(tool)
        assertEquals(GENERATE_IMAGE_TOOL_NAME, tool!!.name)
        val prompt = tool.systemPrompt(model, emptyList())
        assertTrue(prompt.contains("\"provider_type\":\"openai\""))
        assertTrue(prompt.contains("\"model_id\":\"gpt-image-1\""))
        assertFalse(prompt.contains("apiKey"))
        assertFalse(
            tool.needsApproval(
                kotlinx.serialization.json.buildJsonObject {
                    put("prompt", kotlinx.serialization.json.JsonPrimitive("cat"))
                }
            )
        )
        assertTrue(
            tool.needsApproval(
                kotlinx.serialization.json.buildJsonObject {
                    put("prompt", kotlinx.serialization.json.JsonPrimitive("cat"))
                    put("set_as_background", kotlinx.serialization.json.JsonPrimitive(true))
                }
            )
        )
    }
}
