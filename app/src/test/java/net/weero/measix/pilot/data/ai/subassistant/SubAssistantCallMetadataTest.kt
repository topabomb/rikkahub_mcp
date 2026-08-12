package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    // ---- buildSubAssistantCallResult ----

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
