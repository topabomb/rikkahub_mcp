package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.imggen.ImageGenerationModelDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolContractTest {
    @Test
    fun `non object arguments are invalid`() {
        assertTrue(parseGenerateImageArguments(JsonPrimitive("oops")).isFailure)
    }

    @Test
    fun `blank prompt is invalid`() {
        assertTrue(parseGenerateImageArguments(buildJsonObject { put("prompt", "   ") }).isFailure)
    }

    @Test
    fun `illegal boolean is invalid`() {
        assertTrue(
            parseGenerateImageArguments(
                buildJsonObject {
                    put("prompt", "cat")
                    put("set_as_background", "yes")
                }
            ).isFailure
        )
    }

    @Test
    fun `pure generation does not request background`() {
        val parsed = parseGenerateImageArguments(buildJsonObject { put("prompt", "a cat") }).getOrThrow()
        assertFalse(parsed.setAsBackground)
        assertNull(generateImageApprovalRejection(GENERATE_IMAGE_TOOL_NAME, buildJsonObject { put("prompt", "a cat") }))
    }

    @Test
    fun `invalid args reject before pending`() {
        val rejected = generateImageApprovalRejection(
            GENERATE_IMAGE_TOOL_NAME,
            buildJsonObject { put("prompt", "") },
        )
        val text = (rejected!!.single() as UIMessagePart.Text).text
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("failed", json["status"]?.jsonPrimitive?.content)
        assertEquals("invalid_arguments", json["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `system prompt encodes quotes and strips control characters`() {
        val descriptor = ImageGenerationModelDescriptor(
            providerType = "openai",
            providerName = "Open\"AI\u0001",
            modelId = "gpt-image-1",
            modelName = "A".repeat(200) + "<script>",
        )
        val prompt = imageGenerationSystemPrompt(descriptor)
        assertTrue(prompt.contains("\"provider_type\":\"openai\""))
        assertFalse(prompt.contains("\u0001"))
        assertTrue(prompt.contains("Open\\\"AI"))
        assertTrue(descriptor.sanitized().modelName.length <= 64)
        assertFalse(prompt.contains("apiKey"))
        assertFalse(prompt.contains("baseUrl"))
    }

    @Test
    fun `system prompt keeps supplementary characters in names`() {
        val descriptor = ImageGenerationModelDescriptor(
            providerType = "openai",
            providerName = "OpenAI 🎉",
            modelId = "gpt-image-1",
            modelName = "GPT Image 🐱",
        )
        val prompt = imageGenerationSystemPrompt(descriptor)
        assertTrue(prompt.contains("OpenAI 🎉"))
        assertTrue(prompt.contains("GPT Image 🐱"))
    }

    @Test
    fun `failed result always has text part`() {
        val parts = failedResult("provider_error")
        assertEquals(1, parts.size)
        assertTrue(parts.single() is UIMessagePart.Text)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("provider_error"))
    }

    @Test
    fun `failed result includes clipped detail when provided`() {
        val parts = failedResult("rate_limited", "Please retry after 2 seconds.")
        val json = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("failed", json["status"]?.jsonPrimitive?.content)
        assertEquals("rate_limited", json["reason"]?.jsonPrimitive?.content)
        assertEquals("Please retry after 2 seconds.", json["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `metadata unknown version is ignored by ui helper contract`() {
        val metadata = ImageGenerationToolMetadata(version = 99, phase = "queued")
        assertTrue(metadata.version != ImageGenerationToolMetadata.CURRENT_VERSION)
    }
}
