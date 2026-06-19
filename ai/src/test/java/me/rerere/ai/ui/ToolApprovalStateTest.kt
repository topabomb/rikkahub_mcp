package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalStateTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `approved state can resume tool execution`() {
        assertTrue(ToolApprovalState.Approved.canResumeToolExecution())
    }

    @Test
    fun `denied state can resume tool execution`() {
        assertTrue(ToolApprovalState.Denied("no").canResumeToolExecution())
        assertTrue(ToolApprovalState.Denied("").canResumeToolExecution())
    }

    @Test
    fun `answered state can resume tool execution`() {
        assertTrue(ToolApprovalState.Answered("""{"answers":{"q1":"yes"}}""").canResumeToolExecution())
        assertTrue(ToolApprovalState.Answered("").canResumeToolExecution())
    }

    @Test
    fun `auto state cannot resume tool execution`() {
        assertFalse(ToolApprovalState.Auto.canResumeToolExecution())
    }

    @Test
    fun `pending state cannot resume tool execution`() {
        assertFalse(ToolApprovalState.Pending.canResumeToolExecution())
    }

    @Test
    fun `all states are distinct types`() {
        val states = listOf(
            ToolApprovalState.Auto,
            ToolApprovalState.Pending,
            ToolApprovalState.Approved,
            ToolApprovalState.Denied("reason"),
            ToolApprovalState.Answered("answer")
        )
        // Each state should be a different class
        val classes = states.map { it::class }.toSet()
        assertEquals(5, classes.size)
    }

    @Test
    fun `denied with blank reason should still resume`() {
        val denied = ToolApprovalState.Denied("")
        assertTrue(denied.canResumeToolExecution())
        assertEquals("", denied.reason)
    }

    @Test
    fun `answered preserves answer content`() {
        val answer = """{"q1":"option_a","q2":"some text"}"""
        val state = ToolApprovalState.Answered(answer)
        assertEquals(answer, state.answer)
        assertTrue(state.canResumeToolExecution())
    }

    @Test
    fun `denied preserves reason content`() {
        val reason = "Security concern: untrusted code execution"
        val state = ToolApprovalState.Denied(reason)
        assertEquals(reason, state.reason)
        assertTrue(state.canResumeToolExecution())
    }

    @Test
    fun `serialization round trip for Auto`() {
        val encoded = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Auto)
        val decoded = json.decodeFromString(ToolApprovalState.serializer(), encoded)
        assertEquals(ToolApprovalState.Auto, decoded)
    }

    @Test
    fun `serialization round trip for Pending`() {
        val encoded = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Pending)
        val decoded = json.decodeFromString(ToolApprovalState.serializer(), encoded)
        assertEquals(ToolApprovalState.Pending, decoded)
    }

    @Test
    fun `serialization round trip for Approved`() {
        val encoded = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Approved)
        val decoded = json.decodeFromString(ToolApprovalState.serializer(), encoded)
        assertEquals(ToolApprovalState.Approved, decoded)
    }

    @Test
    fun `serialization round trip for Denied`() {
        val state = ToolApprovalState.Denied("user rejected")
        val encoded = json.encodeToString(ToolApprovalState.serializer(), state)
        val decoded = json.decodeFromString(ToolApprovalState.serializer(), encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun `serialization round trip for Answered`() {
        val state = ToolApprovalState.Answered("""{"result":"ok"}""")
        val encoded = json.encodeToString(ToolApprovalState.serializer(), state)
        val decoded = json.decodeFromString(ToolApprovalState.serializer(), encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun `serialization produces correct discriminator`() {
        val auto = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Auto)
        assertTrue(auto.contains("\"auto\""))

        val pending = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Pending)
        assertTrue(pending.contains("\"pending\""))

        val approved = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Approved)
        assertTrue(approved.contains("\"approved\""))

        val denied = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Denied("r"))
        assertTrue(denied.contains("\"denied\""))

        val answered = json.encodeToString(ToolApprovalState.serializer(), ToolApprovalState.Answered("a"))
        assertTrue(answered.contains("\"answered\""))
    }
}
