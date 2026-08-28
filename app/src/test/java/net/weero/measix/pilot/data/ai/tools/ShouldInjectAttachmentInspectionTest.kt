package net.weero.measix.pilot.data.ai.tools

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.providers.OpenAIProvider
import net.weero.measix.pilot.data.datastore.Settings
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ShouldInjectAttachmentInspectionTest {
    private val fullCoverage = RequestMediaCapabilities(
        userImages = RequestImageSupport.STRUCTURED,
        assistantImages = RequestImageSupport.STRUCTURED,
        toolOutputImages = RequestImageSupport.STRUCTURED,
    )

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

    private fun settingsWithResolved(inspection: Model?, resolved: Model): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(models = listOfNotNull(inspection, resolved)),
        ),
        attachmentInspectionModelId = inspection?.id,
    )

    @Test
    fun `not injected when resolved model covers all attachment image sources`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(shouldInjectAttachmentInspection(vision, fullCoverage, settingsWith(inspection, vision)))
    }

    @Test
    fun `not injected when resolved model is missing`() {
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(shouldInjectAttachmentInspection(null, fullCoverage, settingsWith(inspection)))
    }

    @Test
    fun `not injected when inspection model is not configured`() {
        val text = model(listOf(Modality.TEXT))
        assertFalse(shouldInjectAttachmentInspection(text, RequestMediaCapabilities.NONE, settingsWith(null)))
    }

    @Test
    fun `not injected when inspection model is not image capable`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT))
        assertFalse(
            shouldInjectAttachmentInspection(
                text,
                RequestMediaCapabilities.NONE,
                settingsWithResolved(inspection, text),
            )
        )
    }

    @Test
    fun `not injected when inspection model is absent from providers`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val settings = Settings(
            providers = emptyList(),
            attachmentInspectionModelId = inspection.id,
        )
        assertFalse(shouldInjectAttachmentInspection(text, RequestMediaCapabilities.NONE, settings))
    }

    @Test
    fun `injected for text model with valid vision inspection model`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertTrue(
            shouldInjectAttachmentInspection(
                text,
                RequestMediaCapabilities.NONE,
                settingsWithResolved(inspection, text),
            )
        )
    }

    @Test
    fun `injected when resolved model has user images but opaque assistant images`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val capabilities = RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            assistantImages = RequestImageSupport.OPAQUE_REPLAY_ONLY,
            toolOutputImages = RequestImageSupport.STRUCTURED,
        )
        assertTrue(shouldInjectAttachmentInspection(vision, capabilities, settingsWith(inspection, vision)))
    }

    @Test
    fun `injected when resolved model has user images but opaque tool output images`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val capabilities = RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            assistantImages = RequestImageSupport.STRUCTURED,
            toolOutputImages = RequestImageSupport.OPAQUE_REPLAY_ONLY,
        )
        assertTrue(shouldInjectAttachmentInspection(vision, capabilities, settingsWith(inspection, vision)))
    }

    @Test
    fun `injected when resolved model user images are not structured`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertTrue(
            shouldInjectAttachmentInspection(
                vision,
                RequestMediaCapabilities.NONE,
                settingsWith(inspection, vision),
            )
        )
    }

    @Test
    fun `injected when assistant images only support opaque replay`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        // OPAQUE_REPLAY_ONLY is not full coverage because ordinary assistant images may not carry
        // replayable provider metadata.
        val capabilities = RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            assistantImages = RequestImageSupport.OPAQUE_REPLAY_ONLY,
            toolOutputImages = RequestImageSupport.STRUCTURED,
        )
        assertTrue(shouldInjectAttachmentInspection(vision, capabilities, settingsWith(inspection, vision)))
    }

    @Test
    fun `custom openai-compatible host does not veto injection`() {
        val current = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val compatibleProvider = ProviderSetting.OpenAI(
            baseUrl = "https://proxy.example.com/v1",
            models = listOf(current, inspection),
        )
        val compatibleAdapter = OpenAIProvider(OkHttpClient())
        val capabilities = compatibleAdapter.requestMediaCapabilities(compatibleProvider, current)
        assertTrue(
            shouldInjectAttachmentInspection(
                current,
                capabilities,
                Settings(
                    providers = listOf(compatibleProvider),
                    attachmentInspectionModelId = inspection.id,
                ),
            )
        )
    }

    @Test
    fun `not injected when inspection provider is disabled`() {
        val current = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val activeProvider = ProviderSetting.OpenAI(models = listOf(current))
        val disabledProvider = ProviderSetting.OpenAI(enabled = false, models = listOf(inspection))
        assertFalse(
            shouldInjectAttachmentInspection(
                current,
                RequestMediaCapabilities.NONE,
                Settings(
                    providers = listOf(activeProvider, disabledProvider),
                    attachmentInspectionModelId = inspection.id,
                ),
            )
        )
    }
}
