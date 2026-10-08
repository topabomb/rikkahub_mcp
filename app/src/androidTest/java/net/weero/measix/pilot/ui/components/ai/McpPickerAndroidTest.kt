package net.weero.measix.pilot.ui.components.ai

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

    @Test fun mandatoryAndOptionalServicesKeepIndependentSwitchesAndCommands() {
        val optionalId = serverId.copy(id = "mcp_optional")
        val optional = choice().copy(serverId = optionalId, name = "Optional enterprise tools", selected = false,
            canToggle = true, fixedByDefinition = false, selectsAllTools = null)
        val choices = mutableStateOf(listOf(choice(), optional))
        val commands = mutableListOf<Pair<ConfigurationReference, Boolean>>()
        compose.setContent { MaterialTheme { McpPicker(choices.value, onToggle = { id, enabled ->
            commands += id to enabled
            choices.value = choices.value.map { if (it.serverId == id) it.copy(selected = enabled) else it }
        }) } }
        compose.onNode(hasContentDescription("Enterprise tools") and isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_assistant_fixed_binding), substring = true).assertIsDisplayed()
        compose.onNode(hasContentDescription(optional.name) and isToggleable()).assertIsOff().assertIsEnabled().performClick()
        compose.onNode(hasContentDescription(optional.name) and isToggleable()).assertIsOn().performClick()
        compose.onNode(hasContentDescription(optional.name) and isToggleable()).assertIsOff()
        compose.onNode(hasContentDescription("Enterprise tools") and isToggleable()).assertIsOn().assertIsNotEnabled()
        assertEquals(listOf(optionalId to true, optionalId to false), commands)
    }

    @Test fun firstUseAndConfirmedEmptyRemainDistinctWithoutMisleadingCounts() {
        val state = mutableStateOf(choice().copy(status = McpStatus.Idle, sessionCallable = false,
            tools = listOf(missing.copy(unavailableReason = McpToolUnavailableReason.DIRECTORY_UNAVAILABLE)), directoryConfirmed = false))
        compose.setContent { MaterialTheme { McpPicker(listOf(state.value), onToggle = { _, _ -> error("fixed binding") }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_on_demand)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.mcp_status_on_demand)).assertExists()
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
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_catalog_saved, 1)).assertDoesNotExist()
        compose.onAllNodesWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1)).assertCountEquals(1)
    }

    @Test fun enterpriseMarkerIsIndependentOfMandatoryBindingAndIdleCountsAreShownOnce() {
        val mandatory = choice().copy(name = "Firecrawl 网页读取", status = McpStatus.Idle, sessionCallable = false)
        val optional = mandatory.copy(serverId = serverId.copy(id = "mcp_optional"), name = "远程工作区文件与执行工具",
            selected = false, canToggle = true, fixedByDefinition = false, selectsAllTools = null)
        val personal = optional.copy(serverId = ConfigurationReference.random(), name = "个人网页搜索")
        val commands = mutableListOf<Pair<ConfigurationReference, Boolean>>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    McpPicker(listOf(mandatory, optional, personal), onToggle = { id, selected -> commands += id to selected })
                }
            }
        }
        val source = compose.activity.getString(R.string.mcp_enterprise_definitions)
        val markers = compose.onAllNodes(hasContentDescription(source) and !isToggleable())
        markers.assertCountEquals(2)
        compose.onAllNodesWithText(source).assertCountEquals(listOf(mandatory, optional, personal).count { it.name == source })
        compose.onAllNodesWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1)).assertCountEquals(3)
        compose.onAllNodesWithText(compose.activity.getString(R.string.mcp_catalog_saved, 1)).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.mcp_status_on_demand)).assertCountEquals(0)
        val counts = compose.onAllNodesWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1))
            .fetchSemanticsNodes()
        listOf(mandatory, optional).forEachIndexed { index, server ->
            val markerBounds = markers[index].fetchSemanticsNode().boundsInRoot
            val nameBounds = compose.onNodeWithText(server.name).fetchSemanticsNode().boundsInRoot
            assertTrue("Source marker belongs below the name", markerBounds.top >= nameBounds.bottom)
            assertEquals("Supporting row starts at the name", nameBounds.left, markerBounds.left, 1f)
            assertEquals("Source marker shares the tool summary row", counts[index].boundsInRoot.center.y,
                markerBounds.center.y, 1f)
            markers[index].assertWidthIsEqualTo(14.dp).assertHeightIsEqualTo(14.dp)
        }
        val connectionIcons = compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.mcp_status_on_demand))
        connectionIcons.assertCountEquals(3)
        repeat(3) { connectionIcons[it].assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp) }
        compose.onNode(hasContentDescription(mandatory.name) and isToggleable()).assertIsOn().assertIsNotEnabled()
        compose.onNode(hasContentDescription(optional.name) and isToggleable()).assertIsOff().assertIsEnabled().performClick()
        compose.onNode(hasContentDescription(personal.name) and isToggleable()).assertIsOff().assertIsEnabled().performClick()
        assertEquals(listOf(optional.serverId to true, personal.serverId to true), commands)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        check(root.isDirectory || root.mkdirs())
        val screenshot = File(root, "mcp-picker-enterprise-source.png")
        screenshot.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun idlePresentationKeepsActionableAuthorizationAndNetworkStates() {
        val state = mutableStateOf(choice().copy(status = McpStatus.NeedsAuthorization, sessionCallable = false))
        compose.setContent { MaterialTheme { McpPicker(listOf(state.value), onToggle = { _, _ -> error("fixed binding") }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_needs_authorization)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(status = McpStatus.WaitingNetwork) }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_status_waiting_network)).assertIsDisplayed()
    }

    @Test fun longListKeepsTheLastServerAndItsOriginalCommandReachable() {
        val choices = (1..12).map { index -> choice().copy(serverId = ConfigurationReference.random(),
            name = "Server $index", canToggle = true, fixedByDefinition = false, selectsAllTools = null) }
        val commands = mutableListOf<Pair<ConfigurationReference, Boolean>>()
        compose.setContent { MaterialTheme { McpPicker(choices, onToggle = { id, enabled -> commands += id to enabled }) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Server 12"))
        compose.onNode(hasContentDescription("Server 12") and isToggleable()).assertIsOn().performClick()
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
        compose.onNode(hasContentDescription(server.name) and isToggleable()).assertIsOn().assertIsNotEnabled()
    }
}
