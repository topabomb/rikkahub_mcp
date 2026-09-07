package net.weero.measix.pilot.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.ImageGenerationToolMetadata
import net.weero.measix.pilot.service.runtime.ToolLivePhase
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
            phase = ToolLivePhase.COMPLETED,
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
            phase = ToolLivePhase.COMPLETED,
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
            phase = ToolLivePhase.COMPLETED,
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
            phase = ToolLivePhase.COMPLETED,
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
            phase = ToolLivePhase.COMPLETED,
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
            phase = ToolLivePhase.COMPLETED,
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
            phase = ToolLivePhase.COMPLETED,
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
            phase = ToolLivePhase.EXECUTING,
        )
        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(context))
    }

    @Test
    fun `setting background phase resolves to SettingBackground`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "setting_background"),
            phase = ToolLivePhase.EXECUTING,
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
            phase = ToolLivePhase.EXECUTING,
        )
        assertEquals(ImageGenerationUiState.Persisting, resolveImageGenerationUiState(context))
    }

    @Test
    fun `metadata completed phase resolves to Completed`() {
        val context = context(
            content = null,
            metadata = metadata(phase = "completed"),
            phase = ToolLivePhase.COMPLETED,
        )
        assertEquals(ImageGenerationUiState.Completed, resolveImageGenerationUiState(context))
    }

    @Test
    fun `active execution without metadata resolves to Generating`() {
        val context = context(content = null, phase = ToolLivePhase.EXECUTING)
        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(context))
    }

    @Test
    fun `terminal image delta cannot outrun the committed execution phase`() {
        val failedOutput = context(
            content = buildJsonObject {
                put("status", "failed")
                put("reason", "provider_error")
            },
            phase = ToolLivePhase.EXECUTING,
            metadata = metadata(phase = "failed", status = "failed", reason = "provider_error"),
        )
        val completedMetadata = context(
            content = null,
            phase = ToolLivePhase.EXECUTING,
            metadata = metadata(phase = "completed", status = "completed"),
        )

        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(failedOutput))
        assertEquals(ImageGenerationUiState.Generating, resolveImageGenerationUiState(completedMetadata))
    }

    @Test
    fun `streamed metadata cannot advance a ready call into remote execution`() {
        val context = context(
            content = null,
            phase = ToolLivePhase.READY,
            metadata = metadata(phase = "generating", status = "completed"),
        )

        assertEquals(ImageGenerationUiState.Queued, resolveImageGenerationUiState(context))
    }

    @Test
    fun `committed execution failure wins over stale generating metadata`() {
        val context = context(
            content = null,
            phase = ToolLivePhase.FAILED,
            metadata = metadata(phase = "generating", reason = "provider_error"),
        )

        assertEquals(
            ImageGenerationUiState.Failed("provider_error"),
            resolveImageGenerationUiState(context),
        )
    }

    @Test
    fun `streaming call is distinct from remote image execution`() {
        val context = context(content = null, phase = ToolLivePhase.CALL_STREAMING)
        assertEquals(ImageGenerationUiState.CallStreaming, resolveImageGenerationUiState(context))
    }

    @Test
    fun `denied background call does not remain queued`() {
        val context = context(
            content = null,
            phase = ToolLivePhase.DENIED,
            interactionState = ToolInteractionState.Denied("not now"),
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
        phase: ToolLivePhase = ToolLivePhase.READY,
        interactionState: ToolInteractionState = ToolInteractionState.NotRequired,
    ) = ToolUIContext(
        tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
            toolName = "generate_image",
            input = "{}",
            interactionState = interactionState,
            metadata = metadata,
        ),
        arguments = JsonObject(emptyMap()),
        argumentsValid = true,
        content = content,
        phase = phase,
    )
}
