package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
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
    fun `domain failure result still means the tool call returned normally`() {
        assertEquals(
            ToolCallPhase.COMPLETED,
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
