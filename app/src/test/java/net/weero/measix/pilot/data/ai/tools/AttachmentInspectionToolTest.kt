package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerify
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
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.core.ToolExecutionFailure
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
import net.weero.measix.pilot.service.runtime.captureProviderCredentialOwner
import net.weero.measix.pilot.service.runtime.freezeProviderWireShape
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

    /** 领域失败必须以 typed 异常进入 Runtime 的 FAILED 终态，同时保留工具拥有的正文。 */
    private suspend fun failureResult(block: suspend () -> List<UIMessagePart>): List<UIMessagePart> = try {
        block()
        throw AssertionError("expected ToolExecutionFailure")
    } catch (failure: ToolExecutionFailure) {
        failure.output
    }

    private fun inspectionTransport(
        model: Model,
        providerSetting: ProviderSetting,
    ): AttachmentInspectionTransport {
        val liveSettings = Settings(providers = listOf(providerSetting))
        val locator = captureProviderCredentialOwner(liveSettings, model, providerSetting)
        return AttachmentInspectionTransport(
            frozenProviderShape = freezeProviderWireShape(providerSetting, model.copy(providerOverwrite = null)),
            credentialLease = net.weero.measix.pilot.service.runtime.ProviderTransportLease {
                net.weero.measix.pilot.service.runtime.resolveProviderTransportOwner(liveSettings, locator)
            },
            providerManager = providerManager,
        )
    }

    /** Resolve the inspection contract the same way createAttachmentInspectionTool does. */
    private fun resolveInspectionContract(model: Model): Triple<Model, ProviderSetting, Provider<ProviderSetting>> {
        val settings = settingsFor(model)
        val inspectionModel = settings.findModelById(settings.attachmentInspectionModelId)!!
        val providerSetting = inspectionModel.findProvider(settings.providers)!!
        every { providerManager.getProviderByType(any()) } returns provider
        return Triple(inspectionModel, providerSetting, provider)
    }

    @Test
    fun `empty refs are invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = failureResult { executeInspection(
            args = args(emptyList()),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution() },
        ) }
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `more than four refs are invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = failureResult { executeInspection(
            args = args((1..5).map { "/upload/a.png" }),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution() },
        ) }
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `non attachment refs are invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = failureResult { executeInspection(
            args = args(listOf("https://example.com/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        ) }
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `only safe upload paths are accepted and UUID handles are not an alternate protocol`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        listOf(
            "attachment:11111111-1111-1111-1111-111111111111",
            "a.png", "file:///upload/a.png", "/workspace/a.png",
            "/upload/../a.png", "/upload/%61.png", "/upload/sub/a.png",
        ).forEach { path ->
            val result = failureResult { executeInspection(
                args = args(listOf(path)), inspectionModel = model,
                transport = inspectionTransport(model, providerSetting), mediaCapabilities = inspectionCapabilities,
                resolveAttachments = { error("invalid paths must not reach resolver") },
            ) }
            assertEquals(path, AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
        }
        coVerify(exactly = 0) { provider.generateText(any(), any(), any()) }
    }

    @Test
    fun `schema describes file paths from every disclosure source without internal identifiers`() {
        val settings = settingsFor(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        val tool = createAttachmentInspectionTool(settings, providerManager) { settings }
        val parameters = tool.parameters().toString()
        assertTrue(parameters.contains("/upload/<file>"))
        assertTrue(parameters.contains("[Attachment path=...]"))
        assertTrue(parameters.contains("file.path"))
        assertTrue(parameters.contains("artifacts[].path"))
        assertTrue(parameters.contains("Does not require a workspace"))
        assertFalse(parameters.contains("attachment:<uuid>"))
        assertFalse(parameters.contains("Attachment ref="))
    }

    @Test
    fun `mixed or blank attachment array elements are invalid instead of being dropped`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val malformed = buildJsonObject {
            put(
                "attachments",
                JsonArray(
                    listOf(
                        JsonPrimitive("/upload/a.png"),
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

        val malformedResult = failureResult { executeInspection(
            args = malformed,
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        ) }
        val blankResult = failureResult { executeInspection(
            args = blank,
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        ) }

        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(malformedResult))
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(blankResult))
    }

    @Test
    fun `non string request is invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val objectRequest = buildJsonObject {
            put("attachments", JsonArray(listOf(JsonPrimitive("/upload/a.png"))))
            put("request", buildJsonObject { put("text", "describe") })
        }
        val booleanRequest = buildJsonObject {
            put("attachments", JsonArray(listOf(JsonPrimitive("/upload/a.png"))))
            put("request", true)
        }

        val objectResult = failureResult { executeInspection(
            args = objectRequest,
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        ) }
        val booleanResult = failureResult { executeInspection(
            args = booleanRequest,
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { error("resolver must not run") },
        ) }

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
            createAttachmentInspectionTool(settings, providerManager) { settings }
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
            createAttachmentInspectionTool(settings, providerManager) { settings }
        }
        assertTrue(error.message!!.contains("IMAGE"))
    }

    @Test
    fun `construction fails fast when provider breaks image encoding contract`() {
        val settings = settingsFor(visionModel)
        val providerSetting = visionModel.findProvider(settings.providers)!!
        every { providerManager.getProviderByType(any()) } returns provider
        every { provider.requestMediaCapabilities(providerSetting, visionModel) } returns RequestMediaCapabilities.NONE

        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            createAttachmentInspectionTool(settings, providerManager) { settings }
        }

        assertTrue(error.message!!.contains("Provider contract violation"))
    }

    @Test
    fun `runtime resolution failure reason is propagated as is`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = failureResult { executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(failureReason = AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)
            },
        ) }
        assertEquals(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND, resultReason(result))
    }

    @Test
    fun `resolution without image parts is invalid`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val result = failureResult { executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution(parts = emptyList()) },
        ) }
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `inspection rejects resolver cardinality mismatches before provider dispatch`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        for (resolvedCount in listOf(1, 3)) {
            val result = failureResult { executeInspection(
                args = args(listOf("/upload/u7km2n4p.png", "/upload/u7km2n4p.png")),
                inspectionModel = model, transport = inspectionTransport(model, providerSetting),
                mediaCapabilities = inspectionCapabilities,
                resolveAttachments = {
                    ToolAttachmentResolution(parts = List(resolvedCount) { image("file:///tmp/u7km2n4p.png") })
                },
            ) }
            assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
        }
        coVerify(exactly = 0) { provider.generateText(any(), any(), any()) }
    }

    @Test
    fun `short and historical file paths preserve exact paths in ordered image labels`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val refs = listOf("/upload/abc123.png", "/upload/11111111-1111-1111-1111-111111111111.png")
        var sent = emptyList<ModelRequestMessage>()
        coEvery { provider.generateText(any(), any(), any()) } answers {
            sent = secondArg()
            successChunk("comparison")
        }
        val result = executeInspection(
            args = args(refs), inspectionModel = model,
            transport = inspectionTransport(model, providerSetting), mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { requested ->
                assertEquals(refs, requested)
                ToolAttachmentResolution(parts = listOf(image("file:///private/a.png"), image("file:///private/b.png")))
            },
        )

        assertEquals("comparison", (result.single() as UIMessagePart.Text).text)
        assertEquals(
            refs.mapIndexed { index, ref -> "[Image ${index + 1} path=$ref]" } + "what is in the image",
            sent.last().parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
        coVerify(exactly = 1) { provider.generateText(any(), any(), any()) }
    }

    @Test
    fun `success returns observation text with a single model call`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        every { providerManager.getProviderByType(any()) } returns provider
        var calls = 0
        var sent: List<ModelRequestMessage>? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            calls++
            sent = secondArg()
            successChunk("a red square")
        }

        val result = executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
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
        assertEquals("[Image 1 path=/upload/a.png]", userTexts.first())
        assertEquals("what is in the image", userTexts.last())
        assertTrue(user.parts.any { it is UIMessagePart.Image })
    }

    @Test
    fun `inspection labels requested path while preserving resolved image UUID`() = runTest {
        val (model, providerSetting, provider) = resolveInspectionContract(visionModel)
        val ref = "attachment:11111111-1111-1111-1111-111111111111"
        val image = AttachmentRefs.withMetadata(
            UIMessagePart.Image("file:///upload/shared.png"),
            kotlinx.serialization.json.buildJsonObject {
                put(AttachmentRefs.METADATA_KEY, ref)
            },
        ) as UIMessagePart.Image
        every { providerManager.getProviderByType(any()) } returns provider
        var sent: List<ModelRequestMessage>? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            sent = secondArg()
            successChunk("same image")
        }

        executeInspection(
            args = args(listOf("/upload/shared.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = { ToolAttachmentResolution(parts = listOf(image)) },
        )

        val label = sent!![1].parts.filterIsInstance<UIMessagePart.Text>().first()
        assertEquals("[Image 1 path=/upload/shared.png]", label.text)
        assertEquals(ref, AttachmentRefs.getStableRef(sent!![1].parts.filterIsInstance<UIMessagePart.Image>().single()))
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
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
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
        var sent: List<ModelRequestMessage>? = null
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } answers {
            sent = secondArg()
            successChunk("ok")
        }

        executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
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

        val result = failureResult { executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        ) }
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

        val result = failureResult { executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        ) }
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

        val result = failureResult { executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        ) }
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
                args = args(listOf("/upload/a.png")),
                inspectionModel = model,
                transport = inspectionTransport(model, providerSetting),
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
        var sent: List<ModelRequestMessage>? = null
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
                    "/upload/a.png",
                    "/upload/b.png",
                ),
            ),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
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
    fun `inspection transport refreshes only the exact owner secret and fails closed after revoke`() = runTest {
        val startProvider = ProviderSetting.OpenAI(
            models = listOf(visionModel),
            apiKey = "start-secret",
            baseUrl = "https://start.example/v1",
        )
        var liveSettings = Settings(providers = listOf(startProvider.copy(
            apiKey = "rotated-secret",
            baseUrl = "https://live-change.example/v1",
        )))
        val locator = captureProviderCredentialOwner(
            Settings(providers = listOf(startProvider)),
            visionModel,
            startProvider,
        )
        val transport = AttachmentInspectionTransport(
            frozenProviderShape = freezeProviderWireShape(startProvider, visionModel.copy(providerOverwrite = null)),
            credentialLease = net.weero.measix.pilot.service.runtime.ProviderTransportLease {
                net.weero.measix.pilot.service.runtime.resolveProviderTransportOwner(liveSettings, locator)
            },
            providerManager = providerManager,
        )
        var leasedProvider: ProviderSetting.OpenAI? = null
        every { providerManager.getProviderByType(any()) } answers {
            leasedProvider = firstArg() as ProviderSetting.OpenAI
            provider
        }
        coEvery { provider.generateText(any(), any(), any()) } returns successChunk("ok")
        val resolution: suspend (List<String>) -> ToolAttachmentResolution = {
            ToolAttachmentResolution(parts = listOf(UIMessagePart.Image("file:///tmp/a.png")))
        }

        executeInspection(
            args = args(listOf("/upload/a.png")),
            inspectionModel = visionModel,
            transport = transport,
            mediaCapabilities = inspectionCapabilities,
            resolveAttachments = resolution,
        )
        assertEquals("rotated-secret", leasedProvider?.apiKey)
        assertEquals("https://start.example/v1", leasedProvider?.baseUrl)

        liveSettings = Settings(providers = emptyList())
        val failure = failureResult {
            executeInspection(
                args = args(listOf("/upload/a.png")),
                inspectionModel = visionModel,
                transport = transport,
                mediaCapabilities = inspectionCapabilities,
                resolveAttachments = resolution,
            )
        }
        assertEquals(AttachmentFailureReasons.RUNTIME_ERROR, resultReason(failure))
        coVerify(exactly = 1) { provider.generateText(any(), any(), any()) }
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
            args = args(listOf("/upload/a.png")),
            inspectionModel = model,
            transport = inspectionTransport(model, providerSetting),
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
