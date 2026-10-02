package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
            ToolInteractionState.Denied(""),
            ToolInteractionState.Answered("""{"result":"ok"}"""),
            ToolInteractionState.Answered(""),
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
    fun `interaction gates depend on typed result status rather than output presence`() {
        val cases = listOf(
            Triple(ToolInteractionState.NotRequired, false, false),
            Triple(ToolInteractionState.AwaitingApproval, true, false),
            Triple(ToolInteractionState.AwaitingInput, true, false),
            Triple(ToolInteractionState.Approved, false, true),
            Triple(ToolInteractionState.Denied("x"), false, true),
            Triple(ToolInteractionState.Answered("x"), false, true),
        )
        for ((state, pending, resumable) in cases) {
            for (status in listOf(null) + ToolResultStatus.entries) {
                for (output in listOf(emptyList(), listOf(UIMessagePart.Text("result")))) {
                    val call = tool(state, output).copy(resultStatus = status)
                    val label = "$state status=$status output=$output"
                    assertEquals(label, status != null, call.hasReplayResult)
                    assertEquals(label, status == null && pending, call.isPending)
                    assertEquals(label, status == null && resumable, call.canResumeResultAssembly)
                }
            }
        }
    }
}
