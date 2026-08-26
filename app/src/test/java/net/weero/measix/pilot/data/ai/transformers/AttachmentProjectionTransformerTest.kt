package net.weero.measix.pilot.data.ai.transformers

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.AttachmentProjectionTextMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentProjectionTransformerTest {
    private val context = mockk<android.content.Context>(relaxed = true)
    private val artifactStore = mockk<ArtifactStore>(relaxed = true)
    private val transformer = AttachmentProjectionTransformer(artifactStore)
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

    init {
        every { context.filesDir } returns java.io.File(requireNotNull(System.getProperty("java.io.tmpdir")))
    }

    private fun ctxFor(model: Model) = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        registerUnpublishedResource = { error("projection transformer must not create resources") },
    )

    private fun stampedImage(
        ref: String = AttachmentRefs.format(Uuid.random()),
        fileName: String = "shot.png",
    ) = UIMessagePart.Image(
        url = "file:///tmp/$fileName",
        metadata = buildJsonObject {
            put(AttachmentRefs.METADATA_KEY, ref)
        },
    )

    private fun UIMessagePart.Text.isProjectionText(): Boolean =
        metadataAs<AttachmentProjectionTextMetadata>()?.attachmentProjectionText == true

    @Test
    fun `native user image keeps image and records native input fact in user message`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = stampedImage(ref)
        val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"), image))

        val projected = transformer.transform(ctxFor(visionModel), listOf(message)).single()

        assertEquals(MessageRole.USER, projected.role)
        assertEquals("hi", (projected.parts[0] as UIMessagePart.Text).text)
        val marker = projected.parts[1] as UIMessagePart.Text
        assertEquals(
            "[Attachment ref=$ref type=image name=\"shot.png\" input=native]",
            marker.text,
        )
        assertTrue(marker.isProjectionText())
        assertTrue(projected.parts[2] is UIMessagePart.Image)
    }

    @Test
    fun `reference only user image becomes one factual marker in user message`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val message = UIMessage(role = MessageRole.USER, parts = listOf(stampedImage(ref)))

        val projected = transformer.transform(ctxFor(textModel), listOf(message)).single()

        assertEquals(MessageRole.USER, projected.role)
        assertEquals(1, projected.parts.size)
        val marker = projected.parts.single() as UIMessagePart.Text
        assertEquals(
            "[Attachment ref=$ref type=image name=\"shot.png\" input=reference_only]",
            marker.text,
        )
        assertTrue(marker.isProjectionText())
    }

    @Test
    fun `historical image fact stays with historical message and current user text is unchanged`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val historical = UIMessage(role = MessageRole.USER, parts = listOf(stampedImage(ref)))
        val current = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("more")))

        val projected = transformer.transform(ctxFor(textModel), listOf(historical, current))

        assertEquals(
            "[Attachment ref=$ref type=image name=\"shot.png\" input=reference_only]",
            (projected.first().parts.single() as UIMessagePart.Text).text,
        )
        assertEquals(listOf(UIMessagePart.Text("more")), projected.last().parts)
    }

    @Test
    fun `reference only image without stable ref becomes unavailable fact`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/legacy.png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val projected = transformer.transform(ctxFor(textModel), listOf(message)).single()

        val marker = projected.parts.single() as UIMessagePart.Text
        assertEquals("[Attachment ref=unavailable type=image input=unavailable]", marker.text)
        assertTrue(marker.isProjectionText())
    }

    @Test
    fun `native image without stable ref remains an image without invented reference`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/legacy.png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val projected = transformer.transform(ctxFor(visionModel), listOf(message)).single()

        assertEquals(1, projected.parts.size)
        assertTrue(projected.parts.single() is UIMessagePart.Image)
    }

    @Test
    fun `tool output image fact stays inside tool result`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val tool = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "generate_image",
            input = "{}",
            output = listOf(stampedImage(ref)),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))

        val projected = transformer.transform(ctxFor(textModel), listOf(message)).single()

        assertEquals(MessageRole.ASSISTANT, projected.role)
        assertEquals(1, projected.parts.size)
        val output = (projected.parts.single() as UIMessagePart.Tool).output
        val marker = output.single() as UIMessagePart.Text
        assertEquals(
            "[Attachment ref=$ref type=image name=\"shot.png\" input=reference_only]",
            marker.text,
        )
        assertTrue(marker.isProjectionText())
    }

    @Test
    fun `assistant image fact stays in assistant message`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(stampedImage(ref)))

        val projected = transformer.transform(ctxFor(textModel), listOf(message)).single()

        assertEquals(MessageRole.ASSISTANT, projected.role)
        assertEquals(
            "[Attachment ref=$ref type=image name=\"shot.png\" input=reference_only]",
            (projected.parts.single() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `mixed assistant content keeps source local ordering for direct and tool images`() = runTest {
        val toolRef = AttachmentRefs.format(Uuid.random())
        val directRef = AttachmentRefs.format(Uuid.random())
        val tool = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "generate_image",
            input = "{}",
            output = listOf(stampedImage(toolRef, "tool.png")),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("before"),
                tool,
                UIMessagePart.Text("after"),
                stampedImage(directRef, "direct.png"),
            ),
        )

        val projected = transformer.transform(ctxFor(textModel), listOf(message)).single()

        assertEquals("before", (projected.parts[0] as UIMessagePart.Text).text)
        val projectedTool = projected.parts[1] as UIMessagePart.Tool
        assertEquals(
            "[Attachment ref=$toolRef type=image name=\"tool.png\" input=reference_only]",
            (projectedTool.output.single() as UIMessagePart.Text).text,
        )
        assertEquals("after", (projected.parts[2] as UIMessagePart.Text).text)
        assertEquals(
            "[Attachment ref=$directRef type=image name=\"direct.png\" input=reference_only]",
            (projected.parts[3] as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `document audio video retain source parts and get projection markers`() = runTest {
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

        val projected = transformer.transform(ctxFor(visionModel), listOf(message)).single()

        assertEquals(6, projected.parts.size)
        assertTrue((projected.parts[0] as UIMessagePart.Text).text.contains("type=document"))
        assertTrue(projected.parts[1] is UIMessagePart.Document)
        assertTrue((projected.parts[2] as UIMessagePart.Text).text.contains("type=audio"))
        assertTrue(projected.parts[3] is UIMessagePart.Audio)
        assertTrue((projected.parts[4] as UIMessagePart.Text).text.contains("type=video"))
        assertTrue(projected.parts[5] is UIMessagePart.Video)
        projected.parts.filterIsInstance<UIMessagePart.Text>().forEach { assertTrue(it.isProjectionText()) }
    }

    @Test
    fun `same durable messages project per model without shared state`() = runTest {
        val image = stampedImage()
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val asNative = transformer.transform(ctxFor(visionModel), listOf(message))
        val asReference = transformer.transform(ctxFor(textModel), listOf(message))
        val asNativeAgain = transformer.transform(ctxFor(visionModel), listOf(message))

        assertTrue(asNative.single().parts.any { it is UIMessagePart.Image })
        assertTrue(asReference.single().parts.none { it is UIMessagePart.Image })
        assertTrue(asNativeAgain.single().parts.any { it is UIMessagePart.Image })
        assertEquals(
            (asNative.single().parts.first() as UIMessagePart.Text).text,
            (asNativeAgain.single().parts.first() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `projection never mutates durable messages`() = runTest {
        val image = stampedImage()
        val originalParts = listOf(UIMessagePart.Text("hi"), image)
        val message = UIMessage(role = MessageRole.USER, parts = originalParts)

        transformer.transform(ctxFor(textModel), listOf(message))
        transformer.transform(ctxFor(visionModel), listOf(message))

        assertEquals(2, message.parts.size)
        assertTrue(message.parts[0] === originalParts[0])
        assertTrue(message.parts[1] === originalParts[1])
        assertEquals(image.url, (message.parts[1] as UIMessagePart.Image).url)
        assertFalse((message.parts[0] as UIMessagePart.Text).isProjectionText())
    }
}
