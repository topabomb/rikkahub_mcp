package net.weero.measix.pilot.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.data.ai.mcp.McpCatalogSnapshot
import net.weero.measix.pilot.data.ai.mcp.McpCatalogTool
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class McpQueryServiceTest {
    @Test
    fun `catalog identity mismatch is an error rather than fabricated discovery`() {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "measurement-server"),
            url = "https://example.test/mcp",
        )
        val presentation = server.toPresentation(
            runtime = net.weero.measix.pilot.data.ai.mcp.McpRuntimeCapability(
                status = McpStatus.Ready(toolCount = 1, catalogRevision = 1L),
                catalog = McpCatalogSnapshot(
                serverId = server.id,
                revision = 1L,
                definitionDigest = "wrong-definition",
                catalogDigest = "catalog",
                tools = listOf(
                    McpCatalogTool(
                        name = "measure",
                        inputSchema = buildJsonObject { put("type", "object") },
                    )
                ),
                ),
            ),
        )

        assertTrue(presentation.status is McpStatus.Error)
        assertTrue(presentation.tools.isEmpty())
    }
}
