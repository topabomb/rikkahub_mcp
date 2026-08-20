package net.weero.measix.pilot.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolMetadata
import net.weero.measix.pilot.utils.JsonInstant
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

    @Test
    fun `completed with available file resolves to Completed`() {
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
        assertEquals(ImageGenerationUiState.Completed, resolveImageGenerationUiState(context))
    }

    @Test
    fun `completed with missing artifact never resolves to Failed`() {
        val context = context(
            buildJsonObject {
                put("status", "completed")
                put("media_id", 76)
                put(
                    "file",
                    buildJsonObject {
                        put("available", false)
                        put("reason", "artifact_missing")
                        put("mime_type", "image/jpeg")
                    },
                )
                put(
                    "background",
                    buildJsonObject {
                        put("requested", true)
                        put("updated", true)
                    },
                )
            },
        )
        val state = resolveImageGenerationUiState(context)
        assertEquals(
            ImageGenerationUiState.CompletedArtifactUnavailable("artifact_missing"),
            state,
        )
    }

    @Test
    fun `failed execution resolves to Failed`() {
        val context = context(
            buildJsonObject {
                put("status", "failed")
                put("reason", "provider_error")
            },
        )
        assertEquals(
            ImageGenerationUiState.Failed("provider_error"),
            resolveImageGenerationUiState(context),
        )
    }

    @Test
    fun `execution failure reason is not masked by file unavailability`() {
        val context = context(
            buildJsonObject {
                put("status", "failed")
                put("reason", "provider_error")
                put(
                    "file",
                    buildJsonObject {
                        put("available", false)
                        put("reason", "artifact_missing")
                    },
                )
            },
        )
        assertEquals(
            ImageGenerationUiState.Failed("provider_error"),
            resolveImageGenerationUiState(context),
        )
    }

    @Test
    fun `metadata failure status resolves to Failed`() {
        val context = context(
            content = null,
            metadata = metadata(status = "failed", reason = "tool_revoked"),
        )
        assertEquals(
            ImageGenerationUiState.Failed("tool_revoked"),
            resolveImageGenerationUiState(context),
        )
    }

    @Test
    fun `generating phase resolves to Generating`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "generating"),
        )
        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(context))
    }

    @Test
    fun `setting background phase resolves to SettingBackground`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "setting_background"),
        )
        assertEquals(
            ImageGenerationUiState.SettingBackground,
            resolveImageGenerationUiState(context),
        )
    }

    @Test
    fun `persisting phase resolves to Persisting`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "persisting"),
        )
        assertEquals(ImageGenerationUiState.Persisting, resolveImageGenerationUiState(context))
    }

    @Test
    fun `metadata completed phase resolves to Completed`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "completed"),
        )
        assertEquals(ImageGenerationUiState.Completed, resolveImageGenerationUiState(context))
    }

    @Test
    fun `loading without output resolves to Queued`() {
        val context = context(content = null, loading = true)
        assertEquals(ImageGenerationUiState.Queued, resolveImageGenerationUiState(context))
    }

    @Test
    fun `unknown state resolves to Queued`() {
        val context = context(content = null)
        assertEquals(ImageGenerationUiState.Queued, resolveImageGenerationUiState(context))
    }

    private fun metadata(
        phase: String = "queued",
        status: String? = null,
        reason: String? = null,
    ): JsonObject = JsonInstant.encodeToJsonElement(
        ImageGenerationToolMetadata.serializer(),
        ImageGenerationToolMetadata(phase = phase, status = status, reason = reason),
    ) as JsonObject

    private fun context(
        content: JsonObject?,
        metadata: JsonObject? = null,
        loading: Boolean = false,
    ) = ToolUIContext(
        tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "generate_image",
            input = "{}",
            metadata = metadata,
        ),
        arguments = JsonObject(emptyMap()),
        content = content,
        loading = loading,
    )
}
