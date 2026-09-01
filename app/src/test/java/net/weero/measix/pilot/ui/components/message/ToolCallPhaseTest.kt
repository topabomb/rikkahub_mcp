package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.ToolInteractionKind
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.service.runtime.ToolCallPhase
import net.weero.measix.pilot.service.runtime.resolveToolCallPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallPhaseTest {
    @Test
    fun `recovered execution envelopes retain interrupted and cancelled meaning`() {
        assertEquals(
            ToolCallPhase.INTERRUPTED,
            resolveToolCallPhase(tool("""{"status":"interrupted"}"""), null),
        )
        assertEquals(
            ToolCallPhase.CANCELLED,
            resolveToolCallPhase(tool("""{"status":"cancelled"}"""), null),
        )
    }

    @Test
    fun `explicit denial wins over its synthetic cancelled output`() {
        assertEquals(
            ToolCallPhase.DENIED,
            resolveToolCallPhase(
                tool(
                    """{"status":"cancelled"}""",
                    ToolApprovalState.Denied("generation stopped"),
                ),
                null,
            ),
        )
    }

    @Test
    fun `generic error envelopes are failures rather than completed calls`() {
        assertEquals(
            ToolCallPhase.FAILED,
            resolveToolCallPhase(tool("""{"type":"timeout","error":"timed out"}"""), null),
        )
    }

    @Test
    fun `standard domain failure envelope is a failed call`() {
        assertEquals(
            ToolCallPhase.FAILED,
            resolveToolCallPhase(tool("""{"status":"failed","reason":"provider_error"}"""), null),
        )
    }

    @Test
    fun `active committed phase wins over an uncommitted output delta`() {
        assertEquals(
            ToolCallPhase.EXECUTING,
            resolveToolCallPhase(
                tool("""{"status":"failed","reason":"provider_error"}"""),
                ToolCallPhase.EXECUTING,
            ),
        )
    }

    @Test
    fun `durable runtime terminal status survives archived plain text reload`() {
        val archived = "[Archived tool result: ref=7; status=completed; lines=300]"
        val cases = listOf(
            "completed" to ToolCallPhase.COMPLETED,
            "failed" to ToolCallPhase.FAILED,
            "denied" to ToolCallPhase.DENIED,
            "answered" to ToolCallPhase.ANSWERED,
        )
        cases.forEach { (status, expected) ->
            val tool = tool(archived).copy(
                metadata = ToolRuntimeMetadata.applyTo(
                    null,
                    ToolRuntimeMetadata.forResult(
                        ToolInteractionKind.NONE,
                        "ARCHIVABLE_TEXT",
                        terminalStatus = status,
                    ),
                ),
            )
            assertEquals(expected, resolveToolCallPhase(tool, null))
        }
    }

    private fun tool(
        output: String,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "tool",
        input = "{}",
        output = listOf(UIMessagePart.Text(output)),
        approvalState = approvalState,
    )
}
