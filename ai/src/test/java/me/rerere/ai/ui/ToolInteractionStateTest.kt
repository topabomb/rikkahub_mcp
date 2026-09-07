package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * V3 typed interaction contract: the six [ToolInteractionState] variants, their wire discriminators,
 * and the pending / result-assembly predicates that drive the approval and user-input gates.
 */
class ToolInteractionStateTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `serialization round trip preserves every state`() {
        val states = listOf(
            ToolInteractionState.NotRequired,
            ToolInteractionState.AwaitingApproval,
            ToolInteractionState.AwaitingInput,
            ToolInteractionState.Approved,
            ToolInteractionState.Denied("user rejected"),
            ToolInteractionState.Answered("""{"result":"ok"}"""),
        )
        for (state in states) {
            val encoded = json.encodeToString(ToolInteractionState.serializer(), state)
            val decoded = json.decodeFromString(ToolInteractionState.serializer(), encoded)
            assertEquals(state, decoded)
        }
    }

    @Test
    fun `wire discriminators are stable lowercase tokens`() {
        assertTrue(json.encodeToString(ToolInteractionState.serializer(), ToolInteractionState.NotRequired).contains("not_required"))
        assertTrue(json.encodeToString(ToolInteractionState.serializer(), ToolInteractionState.AwaitingApproval).contains("awaiting_approval"))
        assertTrue(json.encodeToString(ToolInteractionState.serializer(), ToolInteractionState.AwaitingInput).contains("awaiting_input"))
        assertTrue(json.encodeToString(ToolInteractionState.serializer(), ToolInteractionState.Approved).contains("approved"))
        assertTrue(json.encodeToString(ToolInteractionState.serializer(), ToolInteractionState.Denied("r")).contains("denied"))
        assertTrue(json.encodeToString(ToolInteractionState.serializer(), ToolInteractionState.Answered("a")).contains("answered"))
    }

    @Test
    fun `denied and answered preserve their payloads`() {
        assertEquals("Security concern", ToolInteractionState.Denied("Security concern").reason)
        assertEquals("", ToolInteractionState.Denied("").reason)
        assertEquals("""{"q":"a"}""", ToolInteractionState.Answered("""{"q":"a"}""").answer)
    }

    private fun tool(interaction: ToolInteractionState, output: List<UIMessagePart> = emptyList()) = UIMessagePart.Tool(
        localCallId = Uuid.random(),
        stepId = Uuid.random(),
        providerCallId = "call",
        toolName = "t",
        input = "{}",
        output = output,
        interactionState = interaction,
    )

    @Test
    fun `only awaiting states are pending`() {
        assertTrue(tool(ToolInteractionState.AwaitingApproval).isPending)
        assertTrue(tool(ToolInteractionState.AwaitingInput).isPending)
        assertFalse(tool(ToolInteractionState.NotRequired).isPending)
        assertFalse(tool(ToolInteractionState.Approved).isPending)
        assertFalse(tool(ToolInteractionState.Denied("x")).isPending)
        assertFalse(tool(ToolInteractionState.Answered("x")).isPending)
    }

    @Test
    fun `resolved gates without a replay result can resume assembly`() {
        assertTrue(tool(ToolInteractionState.Approved).canResumeResultAssembly)
        assertTrue(tool(ToolInteractionState.Denied("x")).canResumeResultAssembly)
        assertTrue(tool(ToolInteractionState.Answered("x")).canResumeResultAssembly)
        assertFalse(tool(ToolInteractionState.AwaitingApproval).canResumeResultAssembly)
        assertFalse(tool(ToolInteractionState.AwaitingInput).canResumeResultAssembly)
        assertFalse(tool(ToolInteractionState.NotRequired).canResumeResultAssembly)
    }

    @Test
    fun `a replay result always blocks re-assembly`() {
        val withResult = tool(ToolInteractionState.Approved, output = listOf(UIMessagePart.Text("done")))
        assertTrue(withResult.hasReplayResult)
        assertFalse(withResult.canResumeResultAssembly)
    }
}
