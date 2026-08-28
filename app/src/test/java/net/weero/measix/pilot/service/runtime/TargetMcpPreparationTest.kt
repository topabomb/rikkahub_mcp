package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.data.ai.mcp.McpServerCapabilityOutcome
import net.weero.measix.pilot.data.ai.mcp.McpServerCapabilityState
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TargetMcpPreparationTest {
    @Test
    fun `target fails closed when a selected MCP server is unavailable`() {
        val failure = targetMcpPreparationFailure(
            TurnMcpCapabilitySnapshot(
                tools = emptyList(),
                serverOutcomes = listOf(
                    McpServerCapabilityOutcome(
                        serverId = Uuid.random(),
                        serverName = "measurement-server",
                        state = McpServerCapabilityState.TIMEOUT,
                        toolCount = 0,
                    )
                ),
            )
        )

        assertTrue(failure.orEmpty().contains("measurement-server=TIMEOUT"))
    }

    @Test
    fun `target accepts a fully ready MCP preparation`() {
        assertNull(
            targetMcpPreparationFailure(
                TurnMcpCapabilitySnapshot(
                    tools = emptyList(),
                    serverOutcomes = listOf(
                        McpServerCapabilityOutcome(
                            serverId = Uuid.random(),
                            serverName = "measurement-server",
                            state = McpServerCapabilityState.READY,
                            toolCount = 20,
                        )
                    ),
                )
            )
        )
    }
}
