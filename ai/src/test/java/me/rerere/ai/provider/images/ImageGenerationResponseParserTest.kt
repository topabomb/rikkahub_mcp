package me.rerere.ai.provider.images

import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationResponseParserTest {
    @Test
    fun `parses b64 items and default mime`() {
        val parsed = parseImageGenerationResponseBody(
            """{"data":[{"b64_json":"QUJD"}],"output_format":"webp"}""",
        )
        assertFalse(parsed.allBlockedByModeration)
        val item = parsed.items.single() as ParsedImageGenerationItem.Base64Json
        assertEquals("QUJD", item.data)
        assertEquals("image/webp", item.mimeType)
    }

    @Test
    fun `parses url items`() {
        val parsed = parseImageGenerationResponseBody(
            """{"data":[{"url":"https://cdn.example/a.png"}]}""",
        )
        val item = parsed.items.single() as ParsedImageGenerationItem.RemoteUrl
        assertEquals("https://cdn.example/a.png", item.url)
    }

    @Test
    fun `xAI respect_moderation false is treated as blocked`() {
        val parsed = parseImageGenerationResponseBody(
            """{"data":[{"url":"https://cdn.example/a.png","respect_moderation":false}]}""",
        )
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.allBlockedByModeration)
    }

    @Test
    fun `keeps unmoderated items when some are filtered`() {
        val parsed = parseImageGenerationResponseBody(
            """{"data":[{"b64_json":"QUE=","respect_moderation":true},{"b64_json":"QkI=","respect_moderation":false}]}""",
        )
        assertFalse(parsed.allBlockedByModeration)
        assertEquals(1, parsed.items.size)
    }

    @Test
    fun `error envelope without images throws HttpException`() {
        try {
            parseImageGenerationResponseBody(
                """{"error":{"message":"Your request was rejected as a result of our safety system.","code":"moderation_blocked"}}""",
            )
            throw AssertionError("expected HttpException")
        } catch (error: HttpException) {
            assertEquals(400, error.statusCode)
            assertEquals("moderation_blocked", error.errorCode)
        }
    }

    @Test
    fun `empty data is not automatically moderation`() {
        val parsed = parseImageGenerationResponseBody("""{"data":[]}""")
        assertTrue(parsed.items.isEmpty())
        assertFalse(parsed.allBlockedByModeration)
    }
}
