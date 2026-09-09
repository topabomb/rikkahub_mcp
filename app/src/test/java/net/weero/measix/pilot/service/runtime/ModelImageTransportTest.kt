package net.weero.measix.pilot.service.runtime

import android.graphics.BitmapFactory
import android.util.Base64
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelImageTransportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `local image generation and editing produce complete decodable PNGs without network`() = runBlocking {
        val providers = mockk<ProviderManager>()
        val target = ModelRequestTarget.LocalExample
        val model = Model(modelId = "local-image", displayName = "Local image", type = ModelType.IMAGE)
        val generated = target.generateImage(providers, ImageGenerationParams(model, "fixture", numOfImages = 2, size = "256x256")).toList()
        assertEquals(2, generated.size)
        generated.forEach {
            assertEquals("image/png", it.mimeType)
            assertFalse(it.partial)
            val bytes = Base64.decode(it.data, Base64.DEFAULT)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try { assertEquals(256, bitmap.width); assertEquals(256, bitmap.height) } finally { bitmap.recycle() }
        }
        val input = temporary.newFile("reference.png").apply { writeBytes(Base64.decode(generated.first().data, Base64.DEFAULT)) }
        val edited = target.editImage(providers, ImageEditParams(model, "fixture edit", listOf(input.path), size = "512x512")).toList()
        assertEquals(1, edited.size)
        val bytes = Base64.decode(edited.single().data, Base64.DEFAULT)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try { assertEquals(512, bitmap.width); assertEquals(512, bitmap.height) } finally { bitmap.recycle() }
        verify { providers wasNot io.mockk.Called }
    }
}
