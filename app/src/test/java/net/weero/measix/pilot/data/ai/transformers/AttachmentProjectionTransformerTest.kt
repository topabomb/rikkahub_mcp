package net.weero.measix.pilot.data.ai.transformers

import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot

import net.weero.measix.pilot.test.testPromptInputs

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.ui.AttachmentProjectionTextMetadata
import me.rerere.ai.ui.OpenAIResponseMetadata
import me.rerere.ai.ui.OpenAIResponseSourceProfile
import me.rerere.ai.ui.OpenAIResponseWireFormat
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

class AttachmentProjectionTransformerTest {
    private val context = mockk<android.content.Context>()
    private val artifactStore = mockk<ArtifactStore>()
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
    private val assistant = resolveTurnAssistantSnapshot(Assistant())
    private val settings = Settings()

    init {
        every { context.filesDir } returns java.io.File(requireNotNull(System.getProperty("java.io.tmpdir")))
        coEvery { artifactStore.resolveManagedReference(any()) } answers {
            LocalArtifactRef(relativePath = "upload/${firstArg<java.io.File>().name}", mimeType = "image/png")
        }
    }

    private fun capabilitiesFor(model: Model) = if (Modality.IMAGE in model.inputModalities) {
        RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            assistantImages = RequestImageSupport.STRUCTURED,
            toolOutputImages = RequestImageSupport.STRUCTURED,
        )
    } else {
        RequestMediaCapabilities.NONE
    }

    private fun ctxFor(model: Model, capabilities: RequestMediaCapabilities = capabilitiesFor(model)) = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        promptInputs = testPromptInputs(),
        requestOrigins = RequestMessageOriginTracker(),
        mediaCapabilities = capabilities,
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
    fun `managed upload exposes one actual file path without changing durable identity`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = stampedImage(ref, "u7km2n4p.png")
        coEvery { artifactStore.resolveManagedReference(any()) } returns
            LocalArtifactRef(relativePath = "upload/u7km2n4p.png", mimeType = "image/png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val output = transformer.transform(ctxFor(visionModel), listOf(message)).single().parts

        assertEquals(
            "[Attachment path=/upload/u7km2n4p.png type=image input=native]",
            (output.first() as UIMessagePart.Text).text,
        )
        assertEquals(image, output.last())
        assertEquals(ref, AttachmentRefs.getStableRef(image))
        assertEquals(listOf(image), message.parts)
    }

    @Test
    fun `unavailable managed image never advertises a usable path or UUID`() = runTest {
        val image = stampedImage()
        coEvery { artifactStore.resolveManagedReference(any()) } returns null

        listOf(textModel, visionModel).forEach { model ->
            val parts = transformer.transform(
                ctxFor(model), listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))),
            ).single().parts
            val mode = if (model == visionModel) "native" else "unavailable"
            assertEquals(
                "[Attachment type=image input=$mode]",
                (parts.first() as UIMessagePart.Text).text,
            )
            assertEquals(model == visionModel, parts.any { it is UIMessagePart.Image })
        }
    }

    @Test
    fun `remote image does not disclose UUID or fabricated local path`() = runTest {
        val image = stampedImage().copy(url = "https://example.test/remote.png")
        listOf(textModel, visionModel).forEach { model ->
            val parts = transformer.transform(
                ctxFor(model), listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))),
            ).single().parts
            val mode = if (model == visionModel) "native" else "unavailable"
            assertEquals("[Attachment type=image input=$mode]", (parts.first() as UIMessagePart.Text).text)
            assertEquals(model == visionModel, parts.any { it is UIMessagePart.Image })
        }
        coVerify(exactly = 0) { artifactStore.resolveManagedReference(any()) }
    }

    @Test
    fun `document audio and video disclose actual managed names instead of document display names`() = runTest {
        val source = listOf(
            UIMessagePart.Document(url = "file:///tmp/u7km2n4p.pdf", fileName = "original report.pdf"),
            UIMessagePart.Audio(url = "file:///tmp/u4nz8q2a.wav"),
            UIMessagePart.Video(url = "file:///tmp/u9rv3c6t.mp4"),
        ).map(AttachmentRefs::ensureAttachmentRef)
        coEvery { artifactStore.resolveManagedReference(any()) } answers {
            LocalArtifactRef(relativePath = "upload/${firstArg<java.io.File>().name}", mimeType = "application/octet-stream")
        }

        val projected = transformer.transform(
            ctxFor(textModel), listOf(UIMessage(role = MessageRole.USER, parts = source)),
        ).single().parts

        val names = listOf("u7km2n4p.pdf", "u4nz8q2a.wav", "u9rv3c6t.mp4")
        val types = listOf("document", "audio", "video")
        names.forEachIndexed { index, name ->
            assertEquals(
                "[Attachment path=/upload/$name type=${types[index]}]",
                (projected[index * 2] as UIMessagePart.Text).text,
            )
            assertEquals(source[index], projected[index * 2 + 1])
        }
    }

    @Test
    fun `managed reference lookup cancellation propagates from request projection`() = runTest {
        val cancelled = kotlinx.coroutines.CancellationException("turn cancelled")
        coEvery { artifactStore.resolveManagedReference(any()) } throws cancelled

        try {
            transformer.transform(
                ctxFor(textModel), listOf(UIMessage(role = MessageRole.USER, parts = listOf(stampedImage()))),
            )
            org.junit.Assert.fail("cancellation must propagate")
        } catch (actual: kotlinx.coroutines.CancellationException) {
            assertEquals(cancelled, actual)
        }
    }

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
            "[Attachment path=/upload/shot.png type=image input=native]",
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
            "[Attachment path=/upload/shot.png type=image input=reference_only]",
            marker.text,
        )
        assertTrue(marker.isProjectionText())
    }

    @Test
    fun `managed path disclosure is independent of malformed internal UUID metadata`() = runTest {
        val image = stampedImage(ref = "attachment:not-a-uuid")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val projected = transformer.transform(ctxFor(textModel), listOf(message)).single()

        assertEquals(
            "[Attachment path=/upload/shot.png type=image input=reference_only]",
            (projected.parts.single() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `non upload managed image does not expose its internal handle`() = runTest {
        coEvery { artifactStore.resolveManagedReference(any()) } returns
            LocalArtifactRef(relativePath = "images/existing.png", mimeType = "image/png")
        val image = stampedImage()
        listOf(textModel, visionModel).forEach { model ->
            val projected = transformer.transform(
                ctxFor(model), listOf(UIMessage(role = MessageRole.USER, parts = listOf(image))),
            ).single()
            val mode = if (model == visionModel) "native" else "unavailable"
            assertEquals("[Attachment type=image input=$mode]", (projected.parts.first() as UIMessagePart.Text).text)
            assertEquals(model == visionModel, projected.parts.any { it is UIMessagePart.Image })
        }
    }

    @Test
    fun `historical image fact stays with historical message and current user text is unchanged`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val historical = UIMessage(role = MessageRole.USER, parts = listOf(stampedImage(ref)))
        val current = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("more")))

        val projected = transformer.transform(ctxFor(textModel), listOf(historical, current))

        assertEquals(
            "[Attachment path=/upload/shot.png type=image input=reference_only]",
            (projected.first().parts.single() as UIMessagePart.Text).text,
        )
        assertEquals(listOf(UIMessagePart.Text("more")), projected.last().parts)
    }

    @Test
    fun `managed image without stable ref discloses its actual path`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/legacy.png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val projected = transformer.transform(ctxFor(textModel), listOf(message)).single()

        val marker = projected.parts.single() as UIMessagePart.Text
        assertEquals("[Attachment path=/upload/legacy.png type=image input=reference_only]", marker.text)
        assertTrue(marker.isProjectionText())
    }

    @Test
    fun `native managed image without stable ref keeps its actual path and image`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/legacy.png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))

        val projected = transformer.transform(ctxFor(visionModel), listOf(message)).single()

        assertEquals(2, projected.parts.size)
        val marker = projected.parts.first() as UIMessagePart.Text
        assertEquals("[Attachment path=/upload/legacy.png type=image input=native]", marker.text)
        assertTrue(marker.isProjectionText())
        assertTrue(projected.parts.last() is UIMessagePart.Image)
    }

    @Test
    fun `tool output image fact stays inside tool result`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
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
            "[Attachment path=/upload/shot.png type=image input=reference_only]",
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
            "[Attachment path=/upload/shot.png type=image input=reference_only]",
            (projected.parts.single() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `opaque replay eligibility does not mark a rebuilt assistant image native`() = runTest {
        val ref = AttachmentRefs.format(Uuid.random())
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(stampedImage(ref)),
            providerMetadata = OpenAIResponseMetadata(
                wireFormat = OpenAIResponseWireFormat.OPENAI,
                outputItemGroups = listOf(listOf(buildJsonObject { put("type", "message") })),
                sourceProfile = OpenAIResponseSourceProfile.OPENAI,
            ).toMetadata(),
        )
        val ctx = TransformerContext(
            context = context,
            model = visionModel,
            assistant = assistant,
            promptInputs = testPromptInputs(),
            requestOrigins = RequestMessageOriginTracker(),
            mediaCapabilities = RequestMediaCapabilities(
                userImages = RequestImageSupport.STRUCTURED,
                assistantImages = RequestImageSupport.OPAQUE_REPLAY_ONLY,
                toolOutputImages = RequestImageSupport.NONE,
            ),
            registerUnpublishedResource = { error("projection transformer must not create resources") },
        )

        val projected = transformer.transform(ctx, listOf(message)).single()

        assertEquals(
            "[Attachment path=/upload/shot.png type=image input=reference_only]",
            (projected.parts.single() as UIMessagePart.Text).text,
        )
        assertFalse(projected.parts.any { it is UIMessagePart.Image })
    }

    @Test
    fun `mixed assistant content keeps source local ordering for direct and tool images`() = runTest {
        val toolRef = AttachmentRefs.format(Uuid.random())
        val directRef = AttachmentRefs.format(Uuid.random())
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
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
            "[Attachment path=/upload/tool.png type=image input=reference_only]",
            (projectedTool.output.single() as UIMessagePart.Text).text,
        )
        assertEquals("after", (projected.parts[2] as UIMessagePart.Text).text)
        assertEquals(
            "[Attachment path=/upload/direct.png type=image input=reference_only]",
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

    @Test
    fun `three image origins retain their source container across four wire protocols`() = runTest {
        val client = OkHttpClient()
        val openAI = OpenAIProvider(client)
        val claude = ClaudeProvider(client)
        val google = GoogleProvider(client)
        val userImage = stampedImage(fileName = "user.png")
        val toolImage = stampedImage(fileName = "tool.png")
        val assistantImage = stampedImage(fileName = "assistant.png")
        val messages = listOf(
            UIMessage.system("unchanged"),
            UIMessage(role = MessageRole.USER, parts = listOf(userImage)),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "image-call", toolName = "generate_image", input = "{}",
                        output = listOf(toolImage),
                    ),
                    assistantImage,
                ),
            ),
        )
        listOf(textModel, visionModel).forEach { model ->
            val adapters = listOf(
                "chat" to openAI.requestMediaCapabilities(ProviderSetting.OpenAI(), model),
                "responses" to openAI.requestMediaCapabilities(ProviderSetting.OpenAI(useResponseApi = true), model),
                "claude" to claude.requestMediaCapabilities(ProviderSetting.Claude(), model),
                "google" to google.requestMediaCapabilities(ProviderSetting.Google(), model),
            )
            adapters.forEach { (wire, capabilities) ->
                val context = ctxFor(model, capabilities)
                val projected = transformer.transform(context, messages)
                assertEquals(messages.map { it.role }, projected.map { it.role })
                assertEquals(messages.first(), projected.first())
                val tool = projected.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
                val origins = listOf(
                    Triple("user.png", capabilities.userImages, projected[1].parts),
                    Triple("tool.png", capabilities.toolOutputImages, tool.output),
                    Triple("assistant.png", capabilities.assistantImages, projected.last().parts.drop(1)),
                )
                origins.forEach { (name, support, parts) ->
                    val native = support == RequestImageSupport.STRUCTURED
                    val mode = if (native) "native" else "reference_only"
                    assertEquals(
                        "$wire ${model.modelId} $name",
                        "[Attachment path=/upload/$name type=image input=$mode]",
                        (parts.first() as UIMessagePart.Text).text,
                    )
                    assertEquals(native, parts.any { it is UIMessagePart.Image })
                }
            }
        }
        assertEquals(listOf(userImage), messages[1].parts)
    }
}
