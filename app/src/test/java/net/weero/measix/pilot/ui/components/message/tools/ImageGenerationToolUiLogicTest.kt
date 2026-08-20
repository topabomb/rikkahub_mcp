package net.weero.measix.pilot.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolUiLogicTest {
    @Test
    fun `completed output with unavailable file is treated as missing artifact`() {
        val context = context(
            buildJsonObject {
                put("status", "completed")
                put(
                    "file",
                    buildJsonObject {
                        put("available", false)
                        put("reason", "artifact_missing")
                        put("mime_type", "image/jpeg")
                    },
                )
            },
        )
        assertTrue(context.fileIsUnavailable())
        assertEquals("artifact_missing", context.fileUnavailableReason())
        assertEquals("completed", context.resultStatus())
    }

    @Test
    fun `completed output with a path is available`() {
        val context = context(
            buildJsonObject {
                put("status", "completed")
                put(
                    "file",
                    buildJsonObject {
                        put("path", "/upload/x.jpg")
                        put("mime_type", "image/jpeg")
                    },
                )
            },
        )
        assertFalse(context.fileIsUnavailable())
        assertEquals("completed", context.resultStatus())
    }

    private fun context(content: JsonObject) = ToolUIContext(
        tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "generate_image",
            input = "{}",
        ),
        arguments = JsonObject(emptyMap()),
        content = content,
        loading = false,
    )
}
