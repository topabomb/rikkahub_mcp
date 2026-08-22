package net.weero.measix.pilot.data.ai.transformers

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentProjectionTransformerTest {
    private val context = mockk<android.content.Context>(relaxed = true)
    private val visionModel = Model(
        id = Uuid.random(),
        modelId = "vision",
        displayName = "Vision",
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    )
    private val textModel = Model(
        id = Uuid.random(),
        modelId = "text",
        displayName = "Text",
        type = ModelType.CHAT,
        inputModalities = listOf(Modality.TEXT),
    )
    private val assistant = Assistant()
    private val settings = Settings()

    private fun ctxFor(model: Model) = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
    )

    private fun stampedImage(ref: String = AttachmentRefs.format(Uuid.random())) = UIMessagePart.Image(
        url = "file:///tmp/shot.png",
        metadata = buildJsonObject {
            put(AttachmentRefs.METADATA_KEY, ref)
        },
    )

    private fun texts(message: UIMessage) = message.parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    @Test
    fun `native keeps image part and prepends ref line`() = runTest {
        val image = stampedImage()
        val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"), image))
        val projected = AttachmentProjectionTransformer.transform(ctxFor(visionModel), listOf(message))

        val parts = projected.single().parts
        assertEquals("hi", (parts[0] as UIMessagePart.Text).text)
        val refLine = (parts[1] as UIMessagePart.Text).text
        assertTrue(refLine.startsWith("[Attachment ref="))
        assertTrue(refLine.contains("type=image"))
        assertTrue(refLine.contains("name=\"shot.png\""))
        assertTrue(parts[2] is UIMessagePart.Image)
        // 无 reference-only image：不注入 capability hint
        assertTrue(texts(projected.single()).none { it == AttachmentProjectionTransformer.CAPABILITY_HINT })
    }

    @Test
    fun `reference only replaces image with ref line and appends hint once`() = runTest {
        val image = stampedImage()
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))
        val projected = AttachmentProjectionTransformer.transform(ctxFor(textModel), listOf(message))

        val parts = projected.single().parts
        assertTrue(parts.none { it is UIMessagePart.Image })
        val refLine = (parts[0] as UIMessagePart.Text).text
        assertTrue(refLine.startsWith("[Attachment ref="))
        assertEquals(AttachmentProjectionTransformer.CAPABILITY_HINT, (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `hint is appended to the last message only`() = runTest {
        val first = UIMessage(role = MessageRole.USER, parts = listOf(stampedImage()))
        val second = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("more")))
        val projected = AttachmentProjectionTransformer.transform(ctxFor(textModel), listOf(first, second))

        assertEquals(1, projected.count { m -> texts(m).any { it == AttachmentProjectionTransformer.CAPABILITY_HINT } })
        assertEquals(
            AttachmentProjectionTransformer.CAPABILITY_HINT,
            texts(projected.last()).last(),
        )
    }

    @Test
    fun `image without ref degrades to placeholder in reference only mode`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/legacy.png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))
        val projected = AttachmentProjectionTransformer.transform(ctxFor(textModel), listOf(message))

        val parts = projected.single().parts
        assertTrue(parts.none { it is UIMessagePart.Image })
        assertEquals("[Image]", (parts[0] as UIMessagePart.Text).text)
        assertEquals(AttachmentProjectionTransformer.CAPABILITY_HINT, (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `image without ref is kept in native mode without ref line`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/legacy.png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))
        val projected = AttachmentProjectionTransformer.transform(ctxFor(visionModel), listOf(message))

        assertEquals(1, projected.single().parts.size)
        assertTrue(projected.single().parts.single() is UIMessagePart.Image)
    }

    @Test
    fun `tool output images are projected recursively`() = runTest {
        val image = stampedImage()
        val tool = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "generate_image",
            input = "{}",
            output = listOf(image),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val projected = AttachmentProjectionTransformer.transform(ctxFor(textModel), listOf(message))

        val parts = projected.single().parts
        val outTool = parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(outTool.output.none { it is UIMessagePart.Image })
        assertTrue((outTool.output[0] as UIMessagePart.Text).text.startsWith("[Attachment ref="))
        // capability hint 在消息尾部，不在 tool output 内
        val messageTexts = texts(projected.single())
        assertEquals(AttachmentProjectionTransformer.CAPABILITY_HINT, messageTexts.last())
    }

    @Test
    fun `document audio video keep parts but get ref lines`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val document = UIMessagePart.Document(
            url = "file:///tmp/report.pdf",
            fileName = "report.pdf",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )
        val audio = UIMessagePart.Audio(
            url = "file:///tmp/voice.wav",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )
        val video = UIMessagePart.Video(
            url = "file:///tmp/clip.mp4",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )
        val message = UIMessage(role = MessageRole.USER, parts = listOf(document, audio, video))
        val projected = AttachmentProjectionTransformer.transform(ctxFor(visionModel), listOf(message))

        val parts = projected.single().parts
        assertEquals(6, parts.size)
        assertTrue((parts[0] as UIMessagePart.Text).text.contains("type=document"))
        assertTrue(parts[1] is UIMessagePart.Document)
        assertTrue((parts[2] as UIMessagePart.Text).text.contains("type=audio"))
        assertTrue(parts[3] is UIMessagePart.Audio)
        assertTrue((parts[4] as UIMessagePart.Text).text.contains("type=video"))
        assertTrue(parts[5] is UIMessagePart.Video)
        // 非 image media 不触发 capability hint
        assertNull(texts(projected.single()).firstOrNull { it == AttachmentProjectionTransformer.CAPABILITY_HINT })
    }

    @Test
    fun `same durable messages project differently per model without shared state`() = runTest {
        val image = stampedImage()
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val asNative = AttachmentProjectionTransformer.transform(ctxFor(visionModel), listOf(message))
        val asReference = AttachmentProjectionTransformer.transform(ctxFor(textModel), listOf(message))
        val asNativeAgain = AttachmentProjectionTransformer.transform(ctxFor(visionModel), listOf(message))

        assertTrue(asNative.single().parts.any { it is UIMessagePart.Image })
        assertTrue(asReference.single().parts.none { it is UIMessagePart.Image })
        assertTrue(asNativeAgain.single().parts.any { it is UIMessagePart.Image })
        assertFalse(asNativeAgain.single().parts.last().let { it is UIMessagePart.Text && it.text == AttachmentProjectionTransformer.CAPABILITY_HINT })
    }

    @Test
    fun `projection never mutates durable messages`() = runTest {
        // A/B/C 切换只产生请求级副本，durable Conversation 原对象不受影响
        val image = stampedImage()
        val originalParts = listOf(UIMessagePart.Text("hi"), image)
        val message = UIMessage(role = MessageRole.USER, parts = originalParts)

        AttachmentProjectionTransformer.transform(ctxFor(textModel), listOf(message))
        AttachmentProjectionTransformer.transform(ctxFor(visionModel), listOf(message))

        assertEquals(2, message.parts.size)
        assertTrue(message.parts[0] === originalParts[0])
        assertTrue(message.parts[1] === originalParts[1])
        assertEquals(image.url, (message.parts[1] as UIMessagePart.Image).url)
    }
}
