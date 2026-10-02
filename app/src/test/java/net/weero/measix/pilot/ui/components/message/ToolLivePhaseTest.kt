package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.ToolInteractionState
import kotlin.uuid.Uuid
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.service.runtime.resolveToolLivePhase
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolLivePhaseTest {
    @Test
    fun `explicit denial wins over its synthetic cancelled output`() {
        assertEquals(
            ToolLivePhase.DENIED,
            resolveToolLivePhase(
                tool(
                    """{"status":"cancelled"}""",
                    ToolInteractionState.Denied("generation stopped"),
                ),
                null,
            ),
        )
    }

    @Test
    fun `active committed phase wins over an uncommitted output delta`() {
        assertEquals(
            ToolLivePhase.EXECUTING,
            resolveToolLivePhase(
                tool("""{"status":"failed","reason":"provider_error"}"""),
                ToolLivePhase.EXECUTING,
            ),
        )
    }

    @Test
    fun `durable runtime terminal status survives archived plain text reload`() {
        val archived = "[Archived tool result: ref=7; status=completed; lines=300]"
        val cases = listOf(
            ToolResultStatus.COMPLETED to ToolLivePhase.COMPLETED,
            ToolResultStatus.FAILED to ToolLivePhase.FAILED,
            ToolResultStatus.DENIED to ToolLivePhase.DENIED,
            ToolResultStatus.ANSWERED to ToolLivePhase.ANSWERED,
            ToolResultStatus.INTERRUPTED to ToolLivePhase.INTERRUPTED,
            ToolResultStatus.CANCELLED to ToolLivePhase.CANCELLED,
        )
        cases.forEach { (status, expected) ->
            val tool = tool(archived).copy(resultStatus = status)
            assertEquals(expected, resolveToolLivePhase(tool, null))
        }
    }

    private fun tool(
        output: String,
        interactionState: ToolInteractionState = ToolInteractionState.NotRequired,
        resultStatus: ToolResultStatus? = null,
    ) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
        toolName = "tool",
        input = "{}",
        output = listOf(UIMessagePart.Text(output)),
        interactionState = interactionState,
        resultStatus = resultStatus,
    )
}
