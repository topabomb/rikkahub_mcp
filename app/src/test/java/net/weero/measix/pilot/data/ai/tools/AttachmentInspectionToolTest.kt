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
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.util.HttpException
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentFailureReasons
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `empty refs are invalid`() = runTest {
        val result = executeInspection(
            args = args(emptyList()),
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = { ToolAttachmentResolution() },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `more than four refs are invalid`() = runTest {
        val result = executeInspection(
            args = args((1..5).map { "attachment:11111111-1111-1111-1111-111111111111" }),
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = { ToolAttachmentResolution() },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `non attachment refs are invalid`() = runTest {
        val result = executeInspection(
            args = args(listOf("https://example.com/a.png")),
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = { ToolAttachmentResolution() },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `missing inspection model fails with inspection_model_unavailable`() = runTest {
        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            settings = settingsFor(null),
            providerManager = providerManager,
            resolveAttachments = { ToolAttachmentResolution() },
        )
        assertEquals(AttachmentFailureReasons.INSPECTION_MODEL_UNAVAILABLE, resultReason(result))
    }

    @Test
    fun `non image inspection model fails with inspection_model_unavailable`() = runTest {
        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            settings = settingsFor(textModel),
            providerManager = providerManager,
            resolveAttachments = { ToolAttachmentResolution() },
        )
        assertEquals(AttachmentFailureReasons.INSPECTION_MODEL_UNAVAILABLE, resultReason(result))
    }

    @Test
    fun `runtime resolution failure reason is propagated as is`() = runTest {
        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = {
                ToolAttachmentResolution(failureReason = AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)
            },
        )
        assertEquals(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND, resultReason(result))
    }

    @Test
    fun `resolution without image parts is invalid`() = runTest {
        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = { ToolAttachmentResolution(parts = emptyList()) },
        )
        assertEquals(AttachmentFailureReasons.INVALID_ATTACHMENTS, resultReason(result))
    }

    @Test
    fun `success returns observation text with a single model call`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
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
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = { ToolAttachmentResolution(parts = listOf(image)) },
        )

        assertEquals(1, calls)
        assertEquals("a red square", (result.single() as UIMessagePart.Text).text)
        // 识别调用只含 fixed system + 标注的图片 + request
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
    fun `multiple images are inspected in one call with ordered labels`() = runTest {
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
            settings = settingsFor(visionModel),
            providerManager = providerManager,
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
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )

        assertEquals("ok", (result.single() as UIMessagePart.Text).text)
        // 内部识别调用必须用 AUTO（模型默认推理档）：
        // OFF 在 Gemini 3 系列上映射 minimal，Gemini 3.7 Flash 不支持会直接 400。
        assertEquals(ReasoningLevel.AUTO, sentParams?.reasoningLevel)
    }

    @Test
    fun `model call failure is classified as runtime_error with detail`() = runTest {
        every { providerManager.getProviderByType(any()) } returns provider
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } throws IllegalStateException("network down")

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )
        assertEquals(AttachmentFailureReasons.RUNTIME_ERROR, resultReason(result))
        assertFalse(resultDetail(result).isNullOrEmpty())
    }

    @Test
    fun `rate limited provider failure maps to rate_limited with sanitized detail`() = runTest {
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
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )
        assertEquals(AttachmentFailureReasons.RATE_LIMITED, resultReason(result))
        assertTrue(resultDetail(result).orEmpty().contains("retry after 1 second"))
    }

    @Test
    fun `empty model output counts as inspection failure`() = runTest {
        every { providerManager.getProviderByType(any()) } returns provider
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } returns successChunk("   ")

        val result = executeInspection(
            args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
            settings = settingsFor(visionModel),
            providerManager = providerManager,
            resolveAttachments = {
                ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
            },
        )
        assertEquals(AttachmentFailureReasons.INSPECTION_FAILED, resultReason(result))
    }

    @Test
    fun `cancellation is not swallowed as failure`() = runTest {
        every { providerManager.getProviderByType(any()) } returns provider
        coEvery {
            provider.generateText(any(), any(), any<TextGenerationParams>())
        } throws kotlinx.coroutines.CancellationException("cancelled")

        try {
            executeInspection(
                args = args(listOf("attachment:11111111-1111-1111-1111-111111111111")),
                settings = settingsFor(visionModel),
                providerManager = providerManager,
                resolveAttachments = {
                    ToolAttachmentResolution(parts = listOf(UIMessagePart.Image(url = "file:///tmp/a.png")))
                },
            )
            throw AssertionError("expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }
}
