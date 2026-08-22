package net.weero.measix.pilot.data.ai.tools

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ShouldInjectAttachmentInspectionTest {
    private fun model(modalities: List<Modality>) = Model(
        id = Uuid.random(),
        modelId = "m",
        displayName = "M",
        type = ModelType.CHAT,
        inputModalities = modalities,
    )

    private fun settingsWith(inspection: Model?): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(models = listOfNotNull(inspection)),
        ),
        attachmentInspectionModelId = inspection?.id,
    )

    @Test
    fun `not injected when resolved model already sees images`() {
        val vision = model(listOf(Modality.TEXT, Modality.IMAGE))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(shouldInjectAttachmentInspection(vision, settingsWith(inspection)))
    }

    @Test
    fun `not injected when inspection model is not configured`() {
        val text = model(listOf(Modality.TEXT))
        assertFalse(shouldInjectAttachmentInspection(text, settingsWith(null)))
    }

    @Test
    fun `not injected when inspection model is not image capable`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT))
        assertFalse(shouldInjectAttachmentInspection(text, settingsWith(inspection)))
    }

    @Test
    fun `not injected when resolved model is missing`() {
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertFalse(shouldInjectAttachmentInspection(null, settingsWith(inspection)))
    }

    @Test
    fun `injected for text model with valid vision inspection model`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        assertTrue(shouldInjectAttachmentInspection(text, settingsWith(inspection)))
    }

    @Test
    fun `not injected when inspection model is absent from providers`() {
        val text = model(listOf(Modality.TEXT))
        val inspection = model(listOf(Modality.TEXT, Modality.IMAGE))
        val settings = Settings(
            providers = emptyList(),
            attachmentInspectionModelId = inspection.id,
        )
        assertFalse(shouldInjectAttachmentInspection(text, settings))
    }
}
