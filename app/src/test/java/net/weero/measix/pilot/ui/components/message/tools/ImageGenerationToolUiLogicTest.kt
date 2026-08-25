package net.weero.measix.pilot.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolMetadata
import net.weero.measix.pilot.service.runtime.ToolCallPhase
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
            phase = ToolCallPhase.COMPLETED,
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
            phase = ToolCallPhase.COMPLETED,
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
            phase = ToolCallPhase.COMPLETED,
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
            phase = ToolCallPhase.COMPLETED,
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
            phase = ToolCallPhase.COMPLETED,
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
            phase = ToolCallPhase.COMPLETED,
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
            phase = ToolCallPhase.COMPLETED,
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
            phase = ToolCallPhase.EXECUTING,
        )
        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(context))
    }

    @Test
    fun `setting background phase resolves to SettingBackground`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "setting_background"),
            phase = ToolCallPhase.EXECUTING,
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
            phase = ToolCallPhase.EXECUTING,
        )
        assertEquals(ImageGenerationUiState.Persisting, resolveImageGenerationUiState(context))
    }

    @Test
    fun `metadata completed phase resolves to Completed`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "completed"),
            phase = ToolCallPhase.COMPLETED,
        )
        assertEquals(ImageGenerationUiState.Completed, resolveImageGenerationUiState(context))
    }

    @Test
    fun `active execution without metadata resolves to Generating`() {
        val context = context(content = null, phase = ToolCallPhase.EXECUTING)
        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(context))
    }

    @Test
    fun `terminal image delta cannot outrun the committed execution phase`() {
        val failedOutput = context(
            content = buildJsonObject {
                put("status", "failed")
                put("reason", "provider_error")
            },
            phase = ToolCallPhase.EXECUTING,
            metadata = metadata(phase = "failed", status = "failed", reason = "provider_error"),
        )
        val completedMetadata = context(
            content = null,
            phase = ToolCallPhase.EXECUTING,
            metadata = metadata(phase = "completed", status = "completed"),
        )

        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(failedOutput))
        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(completedMetadata))
    }

    @Test
    fun `streamed metadata cannot advance a ready call into remote execution`() {
        val context = context(
            content = null,
            phase = ToolCallPhase.READY,
            metadata = metadata(phase = "generating", status = "completed"),
        )

        assertEquals(ImageGenerationUiState.Queued, resolveImageGenerationUiState(context))
    }

    @Test
    fun `committed execution failure wins over stale generating metadata`() {
        val context = context(
            content = null,
            phase = ToolCallPhase.FAILED,
            metadata = metadata(phase = "generating", reason = "provider_error"),
        )

        assertEquals(
            ImageGenerationUiState.Failed("provider_error"),
            resolveImageGenerationUiState(context),
        )
    }

    @Test
    fun `streaming call is distinct from remote image execution`() {
        val context = context(content = null, phase = ToolCallPhase.CALL_STREAMING)
        assertEquals(ImageGenerationUiState.CallStreaming, resolveImageGenerationUiState(context))
    }

    @Test
    fun `denied background call does not remain queued`() {
        val context = context(
            content = null,
            phase = ToolCallPhase.DENIED,
            approvalState = ToolApprovalState.Denied("not now"),
        )
        assertEquals(ImageGenerationUiState.Denied, resolveImageGenerationUiState(context))
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
        phase: ToolCallPhase = ToolCallPhase.READY,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
    ) = ToolUIContext(
        tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "generate_image",
            input = "{}",
            approvalState = approvalState,
            metadata = metadata,
        ),
        arguments = JsonObject(emptyMap()),
        argumentsValid = true,
        content = content,
        phase = phase,
    )
}
