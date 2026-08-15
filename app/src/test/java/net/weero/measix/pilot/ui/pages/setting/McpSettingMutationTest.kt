package net.weero.measix.pilot.ui.pages.setting

import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpOAuthState
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpSettingMutationTest {
    @Test
    fun `editor save keeps latest oauth and newly synced tools`() {
        val id = Uuid.random()
        val latestOauth = McpOAuthState(enabled = true, accessToken = "fresh-token")
        val latest = McpServerConfig.StreamableHTTPServer(
            id = id,
            url = "https://old.example",
            commonOptions = McpCommonOptions(
                name = "Latest",
                tools = listOf(
                    McpTool(name = "search", enable = true, description = "new schema"),
                    McpTool(name = "added", enable = true, description = "from sync"),
                ),
                oauth = latestOauth,
            ),
        )
        val edited = latest.copy(
            url = "https://new.example",
            commonOptions = latest.commonOptions.copy(
                name = "Edited",
                tools = listOf(
                    McpTool(name = "search", enable = false, needsApproval = true, description = "stale schema"),
                ),
                oauth = McpOAuthState(enabled = true, accessToken = "stale-token"),
            ),
        )

        val saved = applyMcpEditorSave(latest, edited)

        assertEquals("https://new.example", (saved as McpServerConfig.StreamableHTTPServer).url)
        assertEquals("Edited", saved.commonOptions.name)
        assertEquals(latestOauth, saved.commonOptions.oauth)
        assertEquals(2, saved.commonOptions.tools.size)
        val search = saved.commonOptions.tools.single { it.name == "search" }
        assertFalse(search.enable)
        assertTrue(search.needsApproval)
        assertEquals("new schema", search.description)
        assertEquals("from sync", saved.commonOptions.tools.single { it.name == "added" }.description)
    }
}
