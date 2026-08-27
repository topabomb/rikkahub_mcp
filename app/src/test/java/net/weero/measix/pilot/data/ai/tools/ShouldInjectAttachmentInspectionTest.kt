package net.weero.measix.pilot.data.ai.tools

import io.mockk.every
import io.mockk.mockk
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import net.weero.measix.pilot.data.datastore.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class ShouldInjectAttachmentInspectionTest {
    private val providerManager = mockk<ProviderManager>()
    private val provider = mockk<Provider<ProviderSetting.OpenAI>>()

    @Before
    fun setUp() {
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns provider
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            toolOutputImages = RequestImageSupport.STRUCTURED,
        )
    }

    private fun model(modalities: List<Modality>) = Model(
        id = Uuid.random(),
        modelId = "m",
        displayName = "M",
        type = ModelType.CHAT,
        inputModalities = modalities,
    )

    private fun settingsWith(inspection: Model?, resolved: Model? = null): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(models = listOfNotNull(inspection, resolved)),
        ),
        attachmentInspectionModelId = inspection?.id,
    )

    @Test
    fun `not injected when resolved model already sees images`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(shouldInjectAttachmentInspection(vision, settingsWith(inspection, vision), providerManager))
    }

    @Test
    fun `not injected when inspection model is not configured`() {
        val text = model(listOf(Modality.TEXT))
        assertFalse(shouldInjectAttachmentInspection(text, settingsWith(null), providerManager))
    }

    @Test
    fun `not injected when inspection model is not image capable`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT))
        assertFalse(shouldInjectAttachmentInspection(text, settingsWith(inspection), providerManager))
    }

    @Test
    fun `not injected when resolved model is missing`() {
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(shouldInjectAttachmentInspection(null, settingsWith(inspection), providerManager))
    }

    @Test
    fun `injected for text model with valid vision inspection model`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertTrue(shouldInjectAttachmentInspection(text, settingsWith(inspection), providerManager))
    }

    @Test
    fun `not injected when inspection model is absent from providers`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val settings = Settings(
            providers = emptyList(),
            attachmentInspectionModelId = inspection.id,
        )
        assertFalse(shouldInjectAttachmentInspection(text, settings, providerManager))
    }

    @Test
    fun `not injected when provider does not expose structured user images`() {
        every { provider.requestMediaCapabilities(any(), any()) } returns RequestMediaCapabilities.NONE
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(shouldInjectAttachmentInspection(text, settingsWith(inspection), providerManager))
    }

    @Test
    fun `image model with provider none falls back to structured inspection model`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        every { provider.requestMediaCapabilities(any(), any()) } answers {
            if (secondArg<Model>().id == vision.id) RequestMediaCapabilities.NONE
            else RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED)
        }

        assertTrue(shouldInjectAttachmentInspection(vision, settingsWith(inspection, vision), providerManager))
    }

    @Test
    fun `image model with native user images but opaque tool output still injects inspection`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        every { provider.requestMediaCapabilities(any(), any()) } answers {
            if (secondArg<Model>().id == vision.id) {
                RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED)
            } else {
                RequestMediaCapabilities(
                    userImages = RequestImageSupport.STRUCTURED,
                    toolOutputImages = RequestImageSupport.STRUCTURED,
                )
            }
        }

        assertTrue(shouldInjectAttachmentInspection(vision, settingsWith(inspection, vision), providerManager))
    }
}
