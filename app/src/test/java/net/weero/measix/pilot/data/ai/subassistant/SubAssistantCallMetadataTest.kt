package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantCallMetadataTest {

    private val json: Json = JsonInstant

    // ---- State transitions ----

    @Test
    fun `starting can transition to running`() {
        assertTrue(SubAssistantCallState.STARTING.canTransitionTo(SubAssistantCallState.RUNNING))
    }

    @Test
    fun `starting can transition to unavailable`() {
        assertTrue(SubAssistantCallState.STARTING.canTransitionTo(SubAssistantCallState.UNAVAILABLE))
    }

    @Test
    fun `starting can transition to failed`() {
        assertTrue(SubAssistantCallState.STARTING.canTransitionTo(SubAssistantCallState.FAILED))
    }

    @Test
    fun `starting can transition to stopped`() {
        assertTrue(SubAssistantCallState.STARTING.canTransitionTo(SubAssistantCallState.STOPPED))
    }

    @Test
    fun `starting cannot transition directly to completed`() {
        assertFalse(SubAssistantCallState.STARTING.canTransitionTo(SubAssistantCallState.COMPLETED))
    }

    @Test
    fun `running can transition to completed`() {
        assertTrue(SubAssistantCallState.RUNNING.canTransitionTo(SubAssistantCallState.COMPLETED))
    }

    @Test
    fun `running can transition to failed`() {
        assertTrue(SubAssistantCallState.RUNNING.canTransitionTo(SubAssistantCallState.FAILED))
    }

    @Test
    fun `running can transition to stopped`() {
        assertTrue(SubAssistantCallState.RUNNING.canTransitionTo(SubAssistantCallState.STOPPED))
    }

    @Test
    fun `terminal state cannot transition back to running`() {
        assertFalse(SubAssistantCallState.COMPLETED.canTransitionTo(SubAssistantCallState.RUNNING))
        assertFalse(SubAssistantCallState.FAILED.canTransitionTo(SubAssistantCallState.RUNNING))
        assertFalse(SubAssistantCallState.STOPPED.canTransitionTo(SubAssistantCallState.RUNNING))
        assertFalse(SubAssistantCallState.UNAVAILABLE.canTransitionTo(SubAssistantCallState.RUNNING))
    }

    @Test
    fun `same state is valid transition`() {
        for (state in SubAssistantCallState.entries) {
            assertTrue("$state should transition to itself", state.canTransitionTo(state))
        }
    }

    @Test
    fun `isTerminal returns true for terminal states`() {
        assertTrue(SubAssistantCallState.COMPLETED.isTerminal())
        assertTrue(SubAssistantCallState.FAILED.isTerminal())
        assertTrue(SubAssistantCallState.STOPPED.isTerminal())
        assertTrue(SubAssistantCallState.UNAVAILABLE.isTerminal())
    }

    @Test
    fun `isTerminal returns false for non-terminal states`() {
        assertFalse(SubAssistantCallState.STARTING.isTerminal())
        assertFalse(SubAssistantCallState.RUNNING.isTerminal())
    }

    // ---- Metadata merge ----

    @Test
    fun `merge preserves existing provider metadata`() {
        val existingMetadata = buildJsonObject {
            put("functionCallId", "call_abc123")
            put("thoughtSignature", "sig_xyz")
            put("sub_assistant_call", buildJsonObject {
                put("run_id", "old-run")
                put("state", "starting")
            })
        }

        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "assistant_call",
            input = "{}",
            metadata = existingMetadata,
        )

        val patch = SubAssistantCallMetadata(
            runId = "new-run",
            targetAssistantId = Uuid.random().toString(),
            targetNameSnapshot = "Test Assistant",
            state = SubAssistantCallState.RUNNING,
        )

        val merged = tool.mergeSubAssistantCallMetadata(json, patch)
        val mergedMeta = merged.metadata!!

        // Provider metadata preserved
        assertEquals("call_abc123", (mergedMeta["functionCallId"] as JsonPrimitive).content)
        assertEquals("sig_xyz", (mergedMeta["thoughtSignature"] as JsonPrimitive).content)

        // Sub-assistant call metadata updated
        val subMeta = merged.getSubAssistantCallMetadata(json)!!
        assertEquals("new-run", subMeta.runId)
        assertEquals(SubAssistantCallState.RUNNING, subMeta.state)
    }

    @Test
    fun `merge creates metadata when null`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "assistant_call",
            input = "{}",
            metadata = null,
        )

        val patch = SubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = Uuid.random().toString(),
            targetNameSnapshot = "Test",
            state = SubAssistantCallState.STARTING,
        )

        val merged = tool.mergeSubAssistantCallMetadata(json, patch)
        assertNotNull(merged.metadata)
        val subMeta = merged.getSubAssistantCallMetadata(json)!!
        assertEquals("run-1", subMeta.runId)
    }

    @Test
    fun `getSubAssistantCallMetadata returns null when not present`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "assistant_call",
            input = "{}",
            metadata = buildJsonObject { put("functionCallId", "abc") },
        )
        assertNull(tool.getSubAssistantCallMetadata(json))
    }

    @Test
    fun `getSubAssistantCallMetadata returns null for corrupted data`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "assistant_call",
            input = "{}",
            metadata = buildJsonObject {
                put("sub_assistant_call", JsonPrimitive("not-an-object"))
            },
        )
        assertNull(tool.getSubAssistantCallMetadata(json))
    }

    @Test
    fun `attachments parse empty and missing as none`() {
        assertEquals(emptyList<String>(), (parseAssistantCallAttachments(null) as AttachmentParseResult.Ok).paths)
        assertEquals(emptyList<String>(), (parseAssistantCallAttachments(JsonNull) as AttachmentParseResult.Ok).paths)
        assertEquals(emptyList<String>(), (parseAssistantCallAttachments(buildJsonArray {}) as AttachmentParseResult.Ok).paths)
    }

    @Test
    fun `attachments dedup then enforce max four`() {
        val ok = parseAssistantCallAttachments(
            buildJsonArray {
                add(JsonPrimitive("/upload/a.png"))
                add(JsonPrimitive(" /upload/a.png "))
                add(JsonPrimitive("/upload/b.png"))
            },
        ) as AttachmentParseResult.Ok
        assertEquals(listOf("/upload/a.png", "/upload/b.png"), ok.paths)
        assertTrue(parseAssistantCallAttachments(
            buildJsonArray {
                add(JsonPrimitive("/upload/1.png"))
                add(JsonPrimitive("/upload/2.png"))
                add(JsonPrimitive("/upload/3.png"))
                add(JsonPrimitive("/upload/4.png"))
                add(JsonPrimitive("/upload/5.png"))
            },
        ) is AttachmentParseResult.Invalid)
    }

    @Test
    fun `attachments reject non string json primitives`() {
        assertTrue(parseAssistantCallAttachments(
            buildJsonArray {
                add(JsonPrimitive("/upload/a.png"))
                add(JsonPrimitive(42))
            },
        ) is AttachmentParseResult.Invalid)
        assertTrue(parseAssistantCallAttachments(
            buildJsonArray {
                add(JsonPrimitive(true))
            },
        ) is AttachmentParseResult.Invalid)
    }

    // ---- buildSubAssistantCallResult ----

    @Test
    fun `attachments accept historical file paths but reject UUID and non upload inputs`() {
        val historical = "/upload/11111111-1111-1111-1111-111111111111.png"
        val accepted = parseAssistantCallAttachments(buildJsonArray { add(JsonPrimitive(historical)) })
        assertEquals(listOf(historical), (accepted as AttachmentParseResult.Ok).paths)
        listOf(
            "attachment:11111111-1111-1111-1111-111111111111",
            "https://example.test/a.png", "file:///upload/a.png", "a.png",
            "/workspace/a.png", "/upload/../a.png", "/upload/%61.png", "/upload/sub/a.png",
        ).forEach { path ->
            assertTrue(path, parseAssistantCallAttachments(buildJsonArray { add(JsonPrimitive(path)) }) is AttachmentParseResult.Invalid)
        }
    }

    @Test
    fun `result includes assistant_name not assistant_id`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Android Helper",
            content = "Here is the answer.",
        )
        assertTrue(result.contains("\"assistant_name\":\"Android Helper\""))
        assertFalse(result.contains("assistant_id"))
    }

    @Test
    fun `result includes reason when provided`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "failed",
            assistantName = "Helper",
            content = "",
            reason = "provider_error",
        )
        assertTrue(result.contains("\"reason\":\"provider_error\""))
    }

    @Test
    fun `result includes has_non_text_output when true`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "",
            hasNonTextOutput = true,
        )
        assertTrue(result.contains("\"has_non_text_output\":true"))
    }

    @Test
    fun `result omits has_non_text_output when false`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "Answer.",
            hasNonTextOutput = false,
        )
        assertFalse(result.contains("has_non_text_output"))
    }

    @Test
    fun `completed result includes lightweight artifacts and omitted count`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "Done.",
            hasNonTextOutput = true,
            artifacts = listOf(
                SubAssistantCallArtifact(
                    ref = "attachment:11111111-1111-1111-1111-111111111111",
                    type = ARTIFACT_TYPE_IMAGE,
                    mime = "image/png",
                    artifact = net.weero.measix.pilot.data.files.LocalArtifactRef(
                        relativePath = "upload/a.png",
                        mimeType = "image/png",
                    ),
                ),
            ),
            artifactsOmitted = 2,
        )
        assertTrue(result.contains("\"artifacts\""))
        assertFalse(result.contains("attachment:11111111-1111-1111-1111-111111111111"))
        assertFalse(result.contains("\"ref\""))
        assertTrue(result.contains("\"path\":\"/upload/a.png\""))
        assertTrue(result.contains("\"artifacts_omitted\":2"))
        assertFalse(result.contains("file:"))
        assertFalse(result.contains("artifact_delivery"))
    }

    @Test
    fun `model manifest omits resources without usable paths and preserves durable metadata`() {
        val stableRef = "attachment:11111111-1111-1111-1111-111111111111"
        val image = SubAssistantCallArtifact(
            ref = stableRef,
            type = ARTIFACT_TYPE_IMAGE,
            mime = "image/png",
            artifact = net.weero.measix.pilot.data.files.LocalArtifactRef(
                relativePath = "images/existing.png",
                mimeType = "image/png",
            ),
        )
        val manifest = buildSubAssistantArtifactManifest(listOf(image))

        assertTrue(manifest.isEmpty())
        assertEquals(stableRef, image.ref)
        assertFalse(manifest.toString().contains("images/"))
        assertFalse(manifest.toString().contains("\"path\""))
    }

    @Test
    fun `manifest exposes only readable file paths type and mime in delivery order`() {
        val source = listOf("upload/abc123.png", "images/hidden.png", "upload/11111111-1111-1111-1111-111111111111.png")
            .mapIndexed { index, path ->
                SubAssistantCallArtifact(
                    ref = "attachment:internal-$index", type = ARTIFACT_TYPE_IMAGE, mime = "image/png",
                    artifact = net.weero.measix.pilot.data.files.LocalArtifactRef(relativePath = path, mimeType = "image/png"),
                )
            }
        val manifest = buildSubAssistantArtifactManifest(source)
        assertEquals(
            listOf("/upload/abc123.png", "/upload/11111111-1111-1111-1111-111111111111.png"),
            manifest.map { (it as JsonObject)["path"]!!.jsonPrimitive.content },
        )
        manifest.forEach { assertEquals(setOf("path", "type", "mime"), (it as JsonObject).keys) }
        assertEquals("attachment:internal-0", source.first().ref)
    }

    @Test
    fun `failed result omits artifacts even when provided`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "failed",
            assistantName = "Helper",
            content = "",
            reason = "runtime_error",
            hasNonTextOutput = true,
            artifacts = listOf(
                SubAssistantCallArtifact(
                    ref = "attachment:11111111-1111-1111-1111-111111111111",
                    type = ARTIFACT_TYPE_IMAGE,
                    mime = "image/png",
                ),
            ),
        )
        assertFalse(result.contains("artifacts"))
        assertFalse(result.contains("has_non_text_output"))
    }

    @Test
    fun `result never includes artifact_delivery`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "Done.",
        )
        assertFalse(result.contains("artifact_delivery"))
    }

    @Test
    fun `result includes tool_calls table when counts exist`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "Answer.",
            toolCalls = listOf("search_web" to 2, "text_to_speech" to 1),
        )
        assertTrue(result.contains("\"tool_calls\""))
        assertTrue(result.contains("\"search_web\""))
        assertTrue(result.contains("\"text_to_speech\""))
    }

    @Test
    fun `result omits tool_calls and tts when empty`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "Answer.",
        )
        assertFalse(result.contains("tool_calls"))
        assertFalse(result.contains("\"tts\""))
    }

    @Test
    fun `result includes tts table when spoken texts exist`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "failed",
            assistantName = "Helper",
            content = "",
            reason = "runtime_error",
            ttsTexts = listOf("First spoken.", "Second."),
        )
        assertTrue(result.contains("\"tts\""))
        assertTrue(result.contains("First spoken."))
        assertTrue(result.contains("Second."))
        assertFalse(result.contains("assistant_name"))
    }

    @Test
    fun `result includes runtime_error detail`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "failed",
            assistantName = "Helper",
            content = "",
            reason = "runtime_error",
            detail = "HttpException: Failed to get response: 429",
        )
        assertTrue(result.contains("\"detail\""))
        assertTrue(result.contains("Failed to get response: 429"))
    }

    @Test
    fun `result includes provider_error detail`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "failed",
            assistantName = "Helper",
            content = "",
            reason = "provider_error",
            detail = "HttpException: Failed to get response: 429",
        )
        assertTrue(result.contains("\"detail\""))
        assertTrue(result.contains("Failed to get response: 429"))
    }

    @Test
    fun `result omits detail unless reason is a classified failure`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "failed",
            assistantName = "Helper",
            content = "",
            reason = "step_limit_reached",
            detail = "should not appear",
        )
        assertFalse(result.contains("detail"))
        assertFalse(result.contains("should not appear"))
    }

    @Test
    fun `result includes tts_stats when calls exist`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "Done.",
            ttsStats = SubAssistantTtsStats(calls = 2, chars = 40),
        )
        assertTrue(result.contains("\"tts_stats\""))
        assertTrue(result.contains("\"calls\":2"))
        assertTrue(result.contains("\"chars\":40"))
    }

    @Test
    fun `result omits tts_stats when there were no calls`() {
        val result = buildSubAssistantCallResult(
            json = json,
            status = "completed",
            assistantName = "Helper",
            content = "Done.",
            ttsStats = SubAssistantTtsStats(calls = 0, chars = 0),
        )
        assertFalse(result.contains("tts_stats"))
    }

    @Test
    fun `extras parser keeps known values and ignores unknown`() {
        val raw = buildJsonArray {
            add(JsonPrimitive("TTS"))
            add(JsonPrimitive("tool_calls"))
            add(JsonPrimitive("artifacts"))
            add(JsonPrimitive("preview"))
        }
        assertEquals(
            setOf(ASSISTANT_CALL_EXTRA_TTS, ASSISTANT_CALL_EXTRA_TOOL_CALLS, ASSISTANT_CALL_EXTRA_ARTIFACTS),
            parseAssistantCallExtras(raw),
        )
    }

    @Test
    fun `extras parser treats missing or invalid input as none`() {
        assertTrue(parseAssistantCallExtras(null).isEmpty())
        assertTrue(parseAssistantCallExtras(JsonPrimitive("tts")).isEmpty())
        assertTrue(parseAssistantCallExtrasFromInput("not-json").isEmpty())
        assertTrue(parseAssistantCallExtrasFromInput("""{"request":"hi"}""").isEmpty())
        assertEquals(
            setOf(ASSISTANT_CALL_EXTRA_TTS),
            parseAssistantCallExtrasFromInput("""{"extras":["tts"]}"""),
        )
    }

    // ---- buildInitialSubAssistantCallMetadata ----

    @Test
    fun `initial metadata has starting state`() {
        val meta = buildInitialSubAssistantCallMetadata(
            runId = "run-1",
            targetAssistantId = Uuid.random(),
            targetNameSnapshot = "Helper",
        )
        assertEquals(SubAssistantCallState.STARTING, meta.state)
        assertEquals("run-1", meta.runId)
        assertNull(meta.previousRunId)
        assertNull(meta.childConversationId)
    }

    @Test
    fun `initial metadata with previous run`() {
        val meta = buildInitialSubAssistantCallMetadata(
            runId = "run-2",
            targetAssistantId = Uuid.random(),
            targetNameSnapshot = "Helper",
            previousRunId = "run-1",
        )
        assertEquals("run-2", meta.runId)
        assertEquals("run-1", meta.previousRunId)
    }
}
