package net.weero.measix.pilot.data.ai.transformers

import java.io.File
import kotlin.io.path.createTempDirectory
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.attachments.TINY_PNG
import net.weero.measix.pilot.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ImageInputAdapterTest {
    private val ocrModelId = Uuid.random()
    private val ocrModel = Model(
        id = ocrModelId,
        modelId = "ocr",
        displayName = "OCR",
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    )
    private val visionModel = Model(
        modelId = "vision",
        displayName = "Vision",
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    )
    private val textModel = Model(
        modelId = "text",
        displayName = "Text",
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT),
    )

    @Test
    fun `vision model is native`() {
        assertEquals(
            ImageAdaptCapability.NATIVE,
            ImageInputAdapter.preflight(visionModel, Settings()),
        )
    }

    @Test
    fun `text model with ocr provider is derived`() {
        val settings = Settings(
            ocrModelId = ocrModelId,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(ocrModel))),
        )
        assertEquals(
            ImageAdaptCapability.DERIVED,
            ImageInputAdapter.preflight(textModel, settings),
        )
    }

    @Test
    fun `text model without ocr is unavailable`() {
        assertEquals(
            ImageAdaptCapability.UNAVAILABLE,
            ImageInputAdapter.preflight(textModel, Settings()),
        )
    }

    @Test
    fun `text-only ocr model is unavailable not derived`() {
        val textOnlyOcr = ocrModel.copy(
            id = Uuid.random(),
            modelId = "ocr-text-only",
            inputModalities = listOf(Modality.TEXT),
        )
        val settings = Settings(
            ocrModelId = textOnlyOcr.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(textOnlyOcr))),
        )
        assertEquals(
            ImageAdaptCapability.UNAVAILABLE,
            ImageInputAdapter.preflight(textModel, settings),
        )
    }

    @Test
    fun `derived wraps observation with ref and does not include request`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = UIMessagePart.Image(
            url = "file:///tmp/a.png",
            metadata = kotlinx.serialization.json.buildJsonObject {
                put(AttachmentRefs.METADATA_KEY, kotlinx.serialization.json.JsonPrimitive(ref))
            },
        )
        val view = ImageInputAdapter.adaptPartForView(
            part = image,
            capability = ImageAdaptCapability.DERIVED,
            mode = ImageAdaptMode.SUB_ASSISTANT,
            isCurrentTask = true,
            observationText = "a red square",
        ) as UIMessagePart.Text
        assertTrue(view.text.contains("<attachment_observation ref=\"$ref\">"))
        assertTrue(view.text.contains("a red square"))
        assertTrue(!view.text.contains("request"))
    }

    @Test
    fun `unavailable current task does not use chat placeholder in sub-assistant mode`() {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val current = ImageInputAdapter.adaptPartForView(
            part = image,
            capability = ImageAdaptCapability.UNAVAILABLE,
            mode = ImageAdaptMode.SUB_ASSISTANT,
            isCurrentTask = true,
            observationText = null,
        ) as UIMessagePart.Text
        val historical = ImageInputAdapter.adaptPartForView(
            part = image,
            capability = ImageAdaptCapability.UNAVAILABLE,
            mode = ImageAdaptMode.SUB_ASSISTANT,
            isCurrentTask = false,
            observationText = null,
        ) as UIMessagePart.Text
        val chat = ImageInputAdapter.adaptPartForView(
            part = image,
            capability = ImageAdaptCapability.UNAVAILABLE,
            mode = ImageAdaptMode.CHAT_COMPAT,
            isCurrentTask = true,
            observationText = null,
        ) as UIMessagePart.Text
        assertTrue(current.text.contains("attachment_observation"))
        assertEquals(ImageInputAdapter.historicalUnavailableText(null), historical.text)
        assertEquals(ImageInputAdapter.CHAT_PLACEHOLDER, chat.text)
    }

    @Test
    fun `cache key follows content hash not path`() {
        val dir = createTempDirectory("obs-cache").toFile()
        val a = File(dir, "a.png").apply { writeBytes(TINY_PNG) }
        val b = File(dir, "copy/b.png").apply {
            parentFile!!.mkdirs()
            writeBytes(TINY_PNG)
        }
        val modelId = Uuid.random()
        val keyA = ImageInputAdapter.cacheKey(ImageInputAdapter.contentHash(a), modelId, "prompt")
        val keyB = ImageInputAdapter.cacheKey(ImageInputAdapter.contentHash(b), modelId, "prompt")
        assertEquals(keyA, keyB)
        val keyPrompt = ImageInputAdapter.cacheKey(ImageInputAdapter.contentHash(a), modelId, "other")
        assertTrue(keyA != keyPrompt)
        dir.deleteRecursively()
    }
}
