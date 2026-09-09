package net.weero.measix.pilot.data.ai.tools

import me.rerere.common.configuration.ConfigurationReference

import io.mockk.every
import io.mockk.mockk
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.freeze
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
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageGenerationToolFactoryTest {
    private val ownerId = ConfigurationReference.random()
    private val model = Model(modelId = "gpt-image-1", displayName = "GPT Image", type = ModelType.IMAGE)
    private val providerSetting = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(model))

    private fun available() = net.weero.measix.pilot.service.ModelExecutionSnapshot(
        model, net.weero.measix.pilot.service.runtime.ModelExecutionLease { accept ->
            accept(net.weero.measix.pilot.service.runtime.ModelRequestTarget.Remote(providerSetting))
        }, "fixture", null,
    )

    private fun factory(): ImageGenerationToolFactory {
        return ImageGenerationToolFactory(
            filesDir = File("."),
            coordinator = mockk(relaxed = true),
            backgroundService = mockk(relaxed = true),
            artifactStore = mockk(relaxed = true),
            rewriter = mockk(relaxed = true),
        )
    }

    @Test
    fun `unavailable selection does not register the tool`() {
        val tool = factory().create(
            AssistantToolBuildContext(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, ownerId, Settings(), null),
        )
        assertNull(tool)
    }

    @Test
    fun `available selection registers generate_image with captured owner and prompt`() {
        val tool = factory().create(
            AssistantToolBuildContext(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
                ownerAssistantId = ownerId,
                imageModel = available(),
                settings = Settings(
                    providers = listOf(providerSetting),
                    assistants = listOf(Assistant(id = ownerId, localTools = listOf(LocalToolOption.TextToImage))),
                ),
            ),
        )
        assertNotNull(tool)
        assertEquals(GENERATE_IMAGE_TOOL_NAME, tool!!.name)
        val prompt = tool.freeze().systemPromptContribution
        assertTrue(prompt.contains("\"provider_type\":\"openai\""))
        assertTrue(prompt.contains("\"model_id\":\"gpt-image-1\""))
        assertFalse(
            prompt.contains("apiKey")
        )
        assertEquals(
            ToolInteractionRequirement.None,
            tool.interactionRequirement(
                kotlinx.serialization.json.buildJsonObject {
                    put("prompt", kotlinx.serialization.json.JsonPrimitive("cat"))
                }
            )
        )
        assertEquals(
            ToolInteractionRequirement.Approval,
            tool.interactionRequirement(
                kotlinx.serialization.json.buildJsonObject {
                    put("prompt", kotlinx.serialization.json.JsonPrimitive("cat"))
                    put("set_as_background", kotlinx.serialization.json.JsonPrimitive(true))
                }
            )
        )
    }
}
