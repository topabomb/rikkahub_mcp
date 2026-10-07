package net.weero.measix.pilot.ui.components.ai

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonObject
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.ai.mcp.McpToolUnavailableReason
import net.weero.measix.pilot.service.AssistantMcpChoice
import net.weero.measix.pilot.service.McpToolPresentation
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpPickerAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val serverId = ConfigurationReference.Enterprise(EnterpriseAuthority("picker-test"), "mcp_fixed")
    private val valid = McpToolPresentation("read", null, JsonObject(emptyMap()), true, false)
    private val changed = valid.copy(name = "changed", enabled = false, needsApproval = true,
        unavailableReason = McpToolUnavailableReason.CONTRACT_CHANGED)
    private val missing = valid.copy(name = "missing", inputSchema = null, enabled = false,
        unavailableReason = McpToolUnavailableReason.MISSING)

    private fun choice(tools: List<McpToolPresentation> = listOf(valid)) = AssistantMcpChoice(
        serverId, "Enterprise tools", true, false, null, McpStatus.Ready(tools.size, 1), true, tools,
        fixedByDefinition = true, allowsAllTools = true, selectsAllTools = true, directoryConfirmed = true,
    )

    @Test fun firstUseAndConfirmedEmptyRemainDistinctWithoutMisleadingCounts() {
        val state = mutableStateOf(choice().copy(status = McpStatus.Idle, sessionCallable = false,
            tools = listOf(missing.copy(unavailableReason = McpToolUnavailableReason.DIRECTORY_UNAVAILABLE)), directoryConfirmed = false))
        compose.setContent { MaterialTheme { McpPicker(listOf(state.value), onToggle = { _, _ -> error("fixed binding") }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_on_demand)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 0, 1)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_tools_partially_available, 0, 1)).assertDoesNotExist()
        compose.runOnIdle { state.value = choice(emptyList()) }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_managed_empty_directory)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_tool_directory_unavailable)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_ready, 0)).assertDoesNotExist()
    }

    @Test fun partialFailureSummaryDoesNotDependOnToolOrderOrUnlockFixedBinding() {
        val state = mutableStateOf(choice(listOf(valid, changed, missing)))
        compose.setContent { MaterialTheme { McpPicker(listOf(state.value), onToggle = { _, _ -> error("fixed binding") }) } }
        val summary = compose.activity.getString(R.string.mcp_tools_partially_available, 1, 2)
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(tools = state.value.tools.reversed()) }
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test fun disconnectedCatalogDoesNotClaimCurrentAvailabilityAndUsesOriginalToggle() {
        val state = mutableStateOf(choice().copy(status = McpStatus.Reconnecting(1, 3, maintenance = true),
            sessionCallable = false, fixedByDefinition = false, canToggle = true, selectsAllTools = null))
        val commands = mutableListOf<Pair<ConfigurationReference, Boolean>>()
        compose.setContent { MaterialTheme { McpPicker(listOf(state.value), onToggle = { id, enabled -> commands += id to enabled }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_maintenance_reconnecting)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_ready, 1)).assertDoesNotExist()
        val toggle = compose.onNode(isToggleable())
        val touchBounds = toggle.fetchSemanticsNode().touchBoundsInRoot
        val minimumTouch = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("Compact switch retains a 48dp touch target", touchBounds.height >= minimumTouch && touchBounds.width >= minimumTouch)
        toggle.performClick()
        assertEquals(listOf(serverId to false), commands)
        compose.runOnIdle { state.value = state.value.copy(status = McpStatus.Ready(1, 1)) }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_ready, 1)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(sessionCallable = true) }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_ready, 1)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connectionsPartiallyReady = true) }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_partially_connected)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_ready, 1)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(status = McpStatus.Idle, sessionCallable = false, connectionsPartiallyReady = false) }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_catalog_saved, 1)).assertIsDisplayed()
    }

    @Test fun longListKeepsTheLastServerAndItsOriginalCommandReachable() {
        val choices = (1..12).map { index -> choice().copy(serverId = ConfigurationReference.random(),
            name = "Server $index", canToggle = true, fixedByDefinition = false, selectsAllTools = null) }
        val commands = mutableListOf<Pair<ConfigurationReference, Boolean>>()
        compose.setContent { MaterialTheme { McpPicker(choices, onToggle = { id, enabled -> commands += id to enabled }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Server 12"))
        compose.onNodeWithContentDescription("Server 12").assertIsOn().performClick()
        assertEquals(listOf(choices.last().serverId to false), commands)
    }

    @Test fun compactConnectionErrorRetainsFullDetailsAndOriginalSelection() {
        val detail = (1..20).joinToString("\n") { "Original diagnostic line $it" }
        val server = choice().copy(connectionsPartiallyReady = true, connectionDiagnostic = detail)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                McpPicker(listOf(server), onToggle = { _, _ -> error("fixed binding") })
            }
        } }
        val disclosure = compose.onNodeWithText(compose.activity.getString(R.string.mcp_connection_details))
        val minimumTouch = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("Compact diagnostics retains a 48dp touch target", disclosure.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTouch)
        disclosure.performClick()
        compose.onNodeWithText(detail).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
        compose.onNodeWithContentDescription(server.name).assertIsOn().assertIsNotEnabled()
    }
}
