package net.weero.measix.pilot.ui.pages.setting

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.ai.mcp.*
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.configuration.ResolvedGatewayEnablement
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual settings consumer; policy persistence and original-selection rejection use the application tests. */
@RunWith(AndroidJUnit4::class)
class SettingMcpPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun managedToolsStayReadOnlyAndGatewayUsesTheRenderedSelectionBeforeLeavingTheRealm() {
        val authority = EnterpriseAuthority("local:example", "deployment")
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "user"), "original")
        val selection = RealmSelection(access, 1)
        val user = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Shared MCP"), url = "https://example.test/mcp")
            .toPresentation(McpRuntimeCapability.EMPTY)
        val direct = McpServerPresentation(ConfigurationReference.Enterprise(authority, "mcp_direct"), "Enterprise profile", true,
            null, access, requiredEnabled = true, status = McpStatus.Idle,
            tools = listOf(McpToolPresentation("get_enterprise_profile", null, JsonObject(emptyMap()), true, false)))
        val gateway = McpServerPresentation(ConfigurationReference.Enterprise(authority, "twg_example"), "Enterprise gateway", true,
            null, access, gatewayEnablement = ResolvedGatewayEnablement(true, true), status = McpStatus.Idle,
            tools = listOf("discover_tools", "invoke_tool").map { McpToolPresentation(it, null, JsonObject(emptyMap()), true, false) })
        fun catalog(gatewayRow: McpServerPresentation) = McpCatalogUiModel(selection, McpCatalogReadState.Available(listOf(direct, gatewayRow,
            user.copy(access = access, unavailableReason = ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED))))
        val state = MutableStateFlow<McpCatalogUiModel?>(catalog(gateway))
        val query = mockk<McpQueryService>()
        every { query.userServers } returns MutableStateFlow(listOf(user))
        every { query.catalog } returns state
        val commands = mockk<ConfigurationApplicationService>()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { commands.setGatewayEnabled(selection, gateway.serverId as ConfigurationReference.Enterprise, false) } coAnswers {
            started.complete(Unit)
            finish.await()
            state.value = catalog(gateway.copy(enabled = false, gatewayEnablement = ResolvedGatewayEnablement(false, true)))
        }
        val mcpCommands = mockk<McpApplicationService>()
        try {
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalToaster provides rememberToasterState(),
                        LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                    ) { SettingMcpPage(mcpCommands, query, commands) }
                }
            }
            compose.onNodeWithText("Enterprise profile").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1)).performScrollTo().performClick()
            compose.onNodeWithText("get_enterprise_profile").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.setting_mcp_page_needs_approval)).assertDoesNotExist()
            compose.onAllNodes(isToggleable()).assertCountEquals(1)
            compose.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
            compose.waitUntil(5_000) { started.isCompleted }
            compose.onNode(isToggleable()).assertIsNotEnabled()
            coVerify(exactly = 1) { commands.setGatewayEnabled(selection, gateway.serverId as ConfigurationReference.Enterprise, false) }
            finish.complete(Unit)
            compose.waitUntil(5_000) { state.value?.servers?.any { it.gatewayEnablement == ResolvedGatewayEnablement(false, true) } == true }
            compose.onNode(isToggleable()).assertIsOff()
            state.value = catalog(gateway.copy(gatewayEnablement = ResolvedGatewayEnablement(true, false), requiredEnabled = true))
            compose.onNode(isToggleable()).assertIsOn().assertIsNotEnabled()
            state.value = McpCatalogUiModel(RealmSelection(RealmAccess.Personal, 2), McpCatalogReadState.Available(listOf(user)))
            compose.onNodeWithText("Shared MCP").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Enterprise profile").assertDoesNotExist()
            compose.onNodeWithText("Enterprise gateway").assertDoesNotExist()
            compose.onAllNodes(isToggleable()).assertCountEquals(0)
        } finally { finish.complete(Unit) }
    }
}
