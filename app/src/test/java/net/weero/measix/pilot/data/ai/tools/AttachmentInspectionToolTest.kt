package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.util.HttpException
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentFailureReasons
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.findProvider
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentInspectionToolTest {
    private val providerManager = mockk<ProviderManager>()
    private val provider = mockk<Provider<ProviderSetting>>()

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

    private val inspectionCapabilities = RequestMediaCapabilities(userImages = RequestImageSupport.STRUCTURED)

    @Before
    fun configureProviderCapabilities() {
        every {
            provider.requestMediaCapabilities(any(), any())
        } returns inspectionCapabilities
    }

    private fun args(
        refs: List<String>,
        request: String = "what is in the image",
    ): JsonObject = buildJsonObject {
        put("attachments", JsonArray(refs.map { JsonPrimitive(it) }))
        put("request", request)
    }

    private fun settingsFor(model: Model?): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(models = listOf(visionModel, textModel)),
        ),
        attachmentInspectionModelId = model?.id,
    )

    private fun successChunk(text: String) = MessageChunk(
        id = "1",
        model = "vision",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = null,
                message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
                finishReason = null,
            ),
        ),
    )

    private fun resultReason(parts: List<UIMessagePart>): String {
        val text = (parts.single() as UIMessagePart.Text).text
        val payload = JsonInstant.parseToJsonElement(text) as JsonObject
        return (payload["reason"] as JsonPrimitive).content
    }

    private fun resultDetail(parts: List<UIMessagePart>): String? {
        val text = (parts.single() as UIMessagePart.Text).text
        val payload = JsonInstant.parseToJsonElement(text) as JsonObject
        return (payload["detail"] as? JsonPrimitive)?.contentOrNull
    }

    /** Resolve the inspection contract the same way createAttachmentInspectionTool does. */
    private fun resolveInspectionContract(model: Model): Triple<Model, ProviderSetting, Provider<ProviderSetting>> {
        val settings = settingsFor(model)
        val inspectionModel = settings.findModelById(settings.attachmentInspectionModelId)!!
        val providerSetting = inspectionModel.findProvider(settings.providers)!!
        every { providerManager.getProviderByType(providerSetting) } returns provider
        return Triple(inspectionModel, providerSetting, provider)
    }

    @Test
    fun `empty refs are invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = executeInspection(
            args = args(emptyList()),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution() },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `more than four refs are invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = executeInspection(
            args = args((1..5).map { "attachment:11111111-1111-1111-1111-111111111111" }),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution() },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `non attachment refs are invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = executeInspection(
            args = args(listOf("https://example.com/a.png")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `mixed or blank attachment array elements are invalid instead of being dropped`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val malformed = buildJsonObject {
            put(
                "attachments",
                JsonArray(
                    listOf(
                        JsonPrimitive("attachment:11111111-1111-1111-1111-111111111111"),
                        JsonPrimitive(42),
                    ),
                ),
            )
            put("request", "describe")
        }
        val blank = buildJsonObject {
            put("attachments", JsonArray(listOf(JsonPrimitive(" "))))
            put("request", "describe")
        }

        val malformedResult = executeInspection(
            args = malformed,
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        )
        val blankResult = executeInspection(
            args = blank,
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        )

        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(malformedResult))
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(blankResult))
    }

    @Test
    fun `non string request is invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val objectRequest = buildJsonObject {
            put("attachments", JsonArray(listOf(JsonPrimitive("attachment:11111111-1111-1111-1111-111111111111"))))
            put("request", buildJsonObject { put("text", "describe") })
        }
        val booleanRequest = buildJsonObject {
            put("attachments", JsonArray(listOf(JsonPrimitive("attachment:11111111-1111-1111-1111-111111111111"))))
            put("request", true)
        }

        val objectResult = executeInspection(
            args = objectRequest,
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        )
        val booleanResult = executeInspection(
            args = booleanRequest,
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        )

        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(objectResult))
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(booleanResult))
    }

    @Test
    fun `construction fails when inspection model is not configured`() {
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(visionModel, textModel))),
            attachmentInspectionModelId = null,
        )
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            createAttachmentInspectionTool(settings, providerManager)
        }
        assertTrue(error.message!!.contains("not configured"))
    }

    @Test
    fun `construction fails when inspection model is not image capable`() {
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(visionModel, textModel))),
            attachmentInspectionModelId = textModel.id,
        )
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            createAttachmentInspectionTool(settings, providerManager)
        }
        assertTrue(error.message!!.contains("IMAGE"))
    }

    @Test
    fun `construction fails fast when provider breaks image encoding contract`() {
        val settings = settingsFor(visionModel)
        val providerSetting = visionModel.findProvider(settings.providers)!!
        every { providerManager.getProviderByType(providerSetting) } returns provider
        every { provider.requestMediaCapabilities(providerSetting, visionModel) } returns RequestMediaCapabilities.NONE

        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            createAttachmentInspectionTool(settings, providerManager)
        }

        assertTrue(error.message!!.contains("Provider contract violation"))
    }

    @Test
    fun `runtime resolution failure reason is propagated as is`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(failureReason = AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)
            },
        )
        assertEquals(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND, resultReason(result))
    }

    @Test
    fun `resolution without image parts is invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution(parts = emptyList()) },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `success returns observation text with a single model call`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        var calls = 0
        var sent: List<UIMessage>? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            calls++
            sent = secondArg()
            successChunk("a red square")
        }

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution(parts = listOf(image("a.png"))) },
        )

        assertEquals(1, calls)
        assertEquals("a red square", (result.single() as UIMessagePart.Text).text)
        val messages = sent!!
        assertEquals(2, messages.size)
        val user = messages[1]
        assertEquals(MessageRole.USER, user.role)
        val userTexts = user.parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertTrue(userTexts.any { it.startsWith("[Image 1") && it.contains("name=") })
        assertEquals("what is in the image", userTexts.last())
        assertTrue(user.parts.any { it is UIMessagePart.Image })
    }

    @Test
    fun `inspection preserves the resolved stable ref in the image fact label`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val ref = "attachment:11111111-1111-1111-1111-111111111111"
        val image = AttachmentRefs.withMetadata(
            UIMessagePart.Image("file:///upload/shared.png"),
            kotlinx.serialization.json.buildJsonObject {
                put(AttachmentRefs.METADATA_KEY, ref)
            },
        ) as UIMessagePart.Image
        every { providerManager.getProviderByType(any()) } returns provider
        var sent: List<UIMessage>? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            sent = secondArg()
            successChunk("same image")
        }

        executeInspection(
            args = args(listOf(ref)),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution(parts = listOf(image)) },
        )

        val label = sent!![1].parts.filterIsInstance<UIMessagePart.Text>().first()
        assertTrue(label.text.contains(ref))
    }

    @Test
    fun `inspection call negotiates native user image capability`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        var sentParams: TextGenerationParams? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            sentParams = thirdArg()
            successChunk("ok")
        }

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )

        assertEquals("ok", (result.single() as UIMessagePart.Text).text)
        assertEquals(RequestImageSupport.STRUCTURED, sentParams?.mediaCapabilities?.userImages)
    }

    @Test
    fun `inspection call sends native image into USER request`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        var sent: List<UIMessage>? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            sent = secondArg()
            successChunk("ok")
        }

        executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )

        val user = sent!![1]
        assertTrue(user.parts.any { it is UIMessagePart.Image })
    }

    @Test
    fun `real provider failure preserves classified error reason`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } throws IllegalStateException("network down")

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )
        assertEquals(AttachmentFailureReasons.RUNTIME_ERROR, resultReason(result))
        assertFalse(resultDetail(result).isNullOrEmpty())
    }

    @Test
    fun `rate limited provider failure maps to rate_limited with sanitized detail`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } throws HttpException(
            message = "Failed to get response: 429 rate_limit_exceeded Please retry after 1 second.",
            statusCode = 429,
            errorCode = "rate_limit_exceeded",
        )

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )
        assertEquals(AttachmentFailureReasons.RATE_LIMITED, resultReason(result))
        assertTrue(resultDetail(result).orEmpty().contains("retry after 1 second"))
    }

    @Test
    fun `empty model output counts as inspection failure`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } returns successChunk("   ")

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )
        assertEquals(AttachmentFailureReasons.INSPECTION_FAILED, resultReason(result))
    }

    @Test
    fun `cancellation is not swallowed as failure`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } throws kotlinx.coroutines.CancellationException("cancelled")

        try {
            executeInspection(
                args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
                inspectionModel = model,
                providerSetting = providerSetting,
                provider = provider,
                mediaCapabilities = inspectionCapabilities,
                resolveAttachments = {
                    ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
                },
            )
            throw AssertionError("expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun `multiple images are inspected in one call with ordered labels`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        var calls = 0
        var sent: List<UIMessage>? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            calls++
            sent = secondArg()
            successChunk("two squares")
        }

        val result = executeInspection(
            args = args(
                listOf(
                    "attachment:11111111-1111-1111-1111-111111111111",
                    "attachment:22222222-2222-2222-2222-222222222222",
                ),
            ),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(
                    parts = listOf(
                        UIMessagePart.Image(url = "file:///tmp/a.png"),
                        UIMessagePart.Image(url = "file:///tmp/b.png"),
                    ),
                )
            },
        )

        assertEquals(1, calls)
        assertEquals("two squares", (result.single() as UIMessagePart.Text).text)
        val user = sent!![1]
        val labelTexts = user.parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertTrue(labelTexts.any { it.startsWith("[Image 1") })
        assertTrue(labelTexts.any { it.startsWith("[Image 2") })
        assertEquals(2, user.parts.count { it is UIMessagePart.Image })
    }

    @Test
    fun `inspection call requests reasoningLevel auto`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        var sentParams: TextGenerationParams? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            sentParams = thirdArg()
            successChunk("ok")
        }

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            inspectionModel = model,
            providerSetting = providerSetting,
            provider = provider,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )

        assertEquals("ok", (result.single() as UIMessagePart.Text).text)
        assertEquals(ReasoningLevel.AUTO, sentParams?.reasoningLevel)
    }

    private fun image(url: String) = UIMessagePart.Image(url = url)
}
