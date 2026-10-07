package net.weero.measix.pilot.ui.pages.setting

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Actual settings consumer; policy persistence and original-selection rejection use the application tests. */
@RunWith(AndroidJUnit4::class)
class SettingMcpPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toolbarAndPullGestureShareOneRefreshInPersonalAndEnterpriseViews() {
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "My MCP"), url = "https://example.test/mcp")
        val row = config.toPresentation(McpRuntimeCapability.EMPTY)
        val catalog = MutableStateFlow<McpCatalogUiModel?>(McpCatalogUiModel(
            RealmSelection(RealmAccess.Personal, 1), McpCatalogReadState.Available(listOf(row))))
        val query = mockk<McpQueryService>()
        every { query.userServers } returns MutableStateFlow(listOf(row))
        every { query.catalog } returns catalog
        val commands = mockk<McpApplicationService>()
        var calls = 0
        var accepted = CompletableDeferred<McpRefreshReceipt>()
        coEvery { commands.refreshAll() } coAnswers { calls++; accepted.await() }
        compose.setContent {
            McpTestTheme {
                CompositionLocalProvider(LocalToaster provides rememberToasterState(),
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup()))) {
                    SettingMcpPage(commands, query, mockk())
                }
            }
        }
        val refresh = compose.onNodeWithContentDescription(compose.activity.getString(R.string.mcp_refresh_personal))
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_refresh_personal)).assertDoesNotExist()
        refresh.assertIsEnabled().performClick()
        compose.waitUntil { calls == 1 }
        refresh.assertIsNotEnabled()
        // The list fills the scaffold behind the app bar; start inside its visible content.
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.85f) }
        compose.runOnIdle { org.junit.Assert.assertEquals(1, calls); accepted.complete(McpRefreshReceipt(1, 1)) }
        compose.waitForIdle()
        refresh.assertIsEnabled()
        compose.runOnIdle { accepted = CompletableDeferred() }
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.85f) }
        compose.waitUntil { calls == 2 }
        compose.runOnIdle { accepted.complete(McpRefreshReceipt(1, 1)) }
        compose.waitForIdle()
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(EnterpriseAuthority("refresh-test"), "user"), "session")
        compose.runOnIdle {
            accepted = CompletableDeferred()
            catalog.value = McpCatalogUiModel(RealmSelection(access, 2), McpCatalogReadState.Available(listOf(row.copy(access = access))))
        }
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.85f) }
        compose.waitUntil { calls == 3 }
        refresh.assertIsNotEnabled()
        compose.runOnIdle { accepted.complete(McpRefreshReceipt(1, 1)) }
        compose.waitForIdle()
        refresh.assertIsEnabled()
        coVerify(exactly = 3) { commands.refreshAll() }
    }

    /** Display evidence uses a controlled query projection; runtime/enterprise authorization is tested separately. */
    @Test
    fun enterpriseAndSharedPersonalDefinitionsRemainClearInDarkLargeText() {
        val authority = EnterpriseAuthority("mcp-ui-review")
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "reviewer"), "review-session")
        val diagnostic = "JsonDecodingException: Notification stream rejected malformed JSON\n" +
            "GET /mcp/events; previous confirmed directory revision=7 remains available\n" +
            (1..18).joinToString("\n") { "Caused by: fixture notification detail $it" }
        val managed = McpServerPresentation(
            ConfigurationReference.Enterprise(authority, "managed_research"), "Enterprise research tools", true,
            null, access, requiredEnabled = true, status = McpStatus.Ready(1, 7), sessionCallable = true,
            tools = listOf(McpToolPresentation("read_research", "Read the confirmed enterprise research directory",
                JsonObject(emptyMap()), true, false)),
            allowsAllTools = true, directoryConfirmed = true, connectionDiagnostic = diagnostic,
            notifications = McpNotificationHealth.Unavailable(McpStatus.Error("Notification JSON rejected", diagnostic), false),
        )
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "Personal research tools shared across spaces"),
            url = "https://example.test/mcp",
        )
        val user = config.toPresentation(McpRuntimeCapability.EMPTY).copy(
            directoryConfirmed = true,
            tools = listOf(McpToolPresentation("read_personal", "Read a personal document", JsonObject(emptyMap()), true, false)),
        )
        val query = mockk<McpQueryService>()
        every { query.userServers } returns MutableStateFlow(listOf(user))
        every { query.catalog } returns MutableStateFlow<McpCatalogUiModel?>(McpCatalogUiModel(
            RealmSelection(access, 1), McpCatalogReadState.Available(listOf(managed, user.copy(access = access))),
        ))
        compose.setContent {
            val originalDensity = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(originalDensity.density, fontScale = 1.4f)) {
                net.weero.measix.pilot.ui.theme.MeasixTheme(colorMode = net.weero.measix.pilot.ui.theme.ColorMode.DARK) {
                    CompositionLocalProvider(
                        LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                        LocalToaster provides rememberToasterState(),
                        LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                    ) { SettingMcpPage(mockk(), query, mockk()) }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_enterprise_read_only)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_notification_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        captureMcpProjectionEvidence("enterprise-readonly-notification-degraded-dark-large")
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_connection_details)).performScrollTo().performClick()
        compose.onNode(hasText(diagnostic) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        captureMcpProjectionEvidence("enterprise-notification-diagnostic-dark-large")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_shared_definition_notice)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(config.commonOptions.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.managed_configuration_source_local)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_catalog_saved, 1)).assertIsDisplayed()
        captureMcpProjectionEvidence("shared-personal-saved-directory-dark-large")
    }

    private fun captureMcpProjectionEvidence(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: requireNotNull(compose.activity.getExternalFilesDir(null))
        check(directory.isDirectory || directory.mkdirs())
        java.io.File(directory, "mcp-projection-$name.png").outputStream().use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    @Test
    fun connectionDiagnosticsUseTheSharedViewerAndCopyKeepsTheServerAndDetailsOpen() {
        val diagnostic = "ConnectException: /192.168.1.8:9100\n" + (1..60).joinToString("\n") { "Caused by: IOException: original MCP detail $it" }
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Personal MCP"), url = "https://example.test/mcp")
        val row = config.toPresentation(McpRuntimeCapability.EMPTY).copy(status = McpStatus.Error("Connection failed", diagnostic))
        val query = mockk<McpQueryService>()
        every { query.userServers } returns MutableStateFlow(listOf(row))
        every { query.catalog } returns MutableStateFlow<McpCatalogUiModel?>(
            McpCatalogUiModel(RealmSelection(RealmAccess.Personal, 1), McpCatalogReadState.Available(listOf(row))),
        )
        compose.setContent {
            McpTestTheme {
                CompositionLocalProvider(LocalToaster provides rememberToasterState(),
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup()))) {
                    SettingMcpPage(mockk<McpApplicationService>(), query, mockk<ConfigurationApplicationService>())
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_conversation_diagnostics)).performScrollTo().performClick()
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).assertCountEquals(1)
        compose.onNode(hasText(diagnostic) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        android.os.SystemClock.sleep(750)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: compose.activity.getExternalFilesDir(null)
        java.io.File(directory, "mcp-connection-diagnostics.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.chat_page_copy_error)) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            org.junit.Assert.assertEquals(diagnostic, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNode(hasText("Personal MCP") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
        compose.onNodeWithText("Personal MCP").assertIsDisplayed()
        (compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
    }

    @Test
    fun failedCatalogShowsFullDiagnosisAndRetriesOnlyTheOriginalRead() {
        val authority = EnterpriseAuthority("deployment")
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "user"), "original")
        val selection = RealmSelection(access, 1)
        val failure = java.io.IOException("catalog unavailable token=private-token", IllegalStateException("original cause"))
        val state = MutableStateFlow<McpCatalogUiModel?>(McpCatalogUiModel(selection, McpCatalogReadState.Failed(failure)))
        val query = mockk<McpQueryService>()
        every { query.userServers } returns MutableStateFlow(emptyList())
        every { query.catalog } returns state
        val recovered = McpServerPresentation(ConfigurationReference.Enterprise(authority, "mcp_profile"), "Recovered enterprise tools", true,
            null, access, requiredEnabled = true, status = McpStatus.Idle, sessionCallable = false, tools = emptyList())
        var attempts = 0
        coEvery { query.retryCatalog(selection) } coAnswers {
            attempts++
            if (attempts == 1) throw java.io.IOException("retry original detail", IllegalStateException("retry cause"))
            if (attempts == 2) {
                state.value = McpCatalogUiModel(selection, McpCatalogReadState.Failed(java.io.IOException("new read failure")))
                return@coAnswers Unit
            }
            state.value = McpCatalogUiModel(selection, McpCatalogReadState.Available(listOf(recovered)))
        }
        val commands = mockk<McpApplicationService>()
        compose.setContent {
            McpTestTheme {
                CompositionLocalProvider(
                    LocalToaster provides rememberToasterState(),
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                ) { SettingMcpPage(commands, query, mockk<ConfigurationApplicationService>()) }
            }
        }
        compose.onNodeWithText("original cause", substring = true).assertIsDisplayed()
        compose.onNodeWithText("private-token", substring = true).assertDoesNotExist()
        val retry = compose.activity.getString(R.string.application_recovery_retry)
        compose.onNodeWithText(retry).performClick()
        compose.onNodeWithText("retry original detail", substring = true).assertIsDisplayed()
        compose.onNodeWithText("retry cause", substring = true).assertIsDisplayed()
        compose.onNodeWithText(retry).performClick()
        compose.onNodeWithText("new read failure", substring = true).assertIsDisplayed()
        compose.onNodeWithText("retry original detail", substring = true).assertDoesNotExist()
        compose.onNodeWithText(retry).performClick()
        compose.onNodeWithText("Recovered enterprise tools").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("retry original detail", substring = true).assertDoesNotExist()
        coVerify(exactly = 3) { query.retryCatalog(selection) }
        coVerify(exactly = 0) { commands.refreshAll() }
    }

    @Test fun directToolRangesAndContractFailuresUseExistingReadOnlyCards() {
        val authority = EnterpriseAuthority("deployment")
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "user"), "original")
        val selection = RealmSelection(access, 1)
        val restricted = McpServerPresentation(ConfigurationReference.Enterprise(authority, "mcp_restricted"), "Reviewed service", true,
            null, access, requiredEnabled = true, status = McpStatus.Ready(3, 1), sessionCallable = true,
            tools = listOf(
                McpToolPresentation("read", "Read tool details", JsonObject(emptyMap()), true, true),
                McpToolPresentation("changed", null, JsonObject(emptyMap()), false, true, McpToolUnavailableReason.CONTRACT_CHANGED),
            ), allowsAllTools = false, directoryConfirmed = true)
        val all = restricted.copy(serverId = ConfigurationReference.Enterprise(authority, "mcp_all"), name = "Dynamic tools",
            tools = emptyList(), allowsAllTools = true)
        val query = mockk<McpQueryService>()
        every { query.userServers } returns MutableStateFlow(emptyList())
        every { query.catalog } returns MutableStateFlow<McpCatalogUiModel?>(McpCatalogUiModel(selection,
            McpCatalogReadState.Available(listOf(restricted, all))))
        compose.setContent {
            McpTestTheme {
                CompositionLocalProvider(LocalToaster provides rememberToasterState(),
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup()))) {
                    SettingMcpPage(mockk(), query, mockk())
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_managed_approved_tools)).performScrollTo().assertIsDisplayed()
        val header = compose.onNodeWithContentDescription(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 2))
        header.performScrollTo().assertHasClickAction()
        val minimumTouch = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("Compact server header retains a 48dp touch target", header.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTouch)
        header.performClick()
        val toolHeader = compose.onNodeWithContentDescription(compose.activity.getString(R.string.code_block_expand))
        toolHeader.performScrollTo().assertHasClickAction()
        assertTrue("Tool details header retains a 48dp touch target", toolHeader.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTouch)
        toolHeader.performClick()
        compose.onNodeWithText("Read tool details").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.code_block_collapse)).performClick()
        compose.onNodeWithText("Read tool details").assertDoesNotExist()
        compose.onNodeWithText("changed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_tool_contract_changed)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.setting_mcp_page_needs_approval)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("changed").onParent().onChildren()
            .filter(hasText(compose.activity.getString(R.string.setting_mcp_page_needs_approval))).assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_managed_all_tools)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_managed_empty_directory)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun managedToolsStayReadOnlyAndGatewayUsesTheRenderedSelectionBeforeLeavingTheRealm() {
        val authority = EnterpriseAuthority("deployment")
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "user"), "original")
        val selection = RealmSelection(access, 1)
        val user = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "Shared MCP"), url = "https://example.test/mcp")
            .toPresentation(McpRuntimeCapability.EMPTY)
        val direct = McpServerPresentation(ConfigurationReference.Enterprise(authority, "mcp_direct"), "Enterprise profile", true,
            null, access, requiredEnabled = true, status = McpStatus.Idle, sessionCallable = false,
            tools = listOf(McpToolPresentation("get_enterprise_profile", null, JsonObject(emptyMap()), true, false)))
        val gateway = McpServerPresentation(ConfigurationReference.Enterprise(authority, "twg_example"), "Enterprise gateway", true,
            null, access, gatewayEnablement = ResolvedGatewayEnablement(true, true), status = McpStatus.Idle, sessionCallable = false,
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
                McpTestTheme {
                    CompositionLocalProvider(
                        LocalToaster provides rememberToasterState(),
                        LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                    ) { SettingMcpPage(mcpCommands, query, commands) }
                }
            }
            compose.onNodeWithText("Enterprise profile").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.mcp_enabled_tools_count, 1, 1)).performScrollTo().performClick()
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

    @Test
    fun headerValidationAndSaveFailureKeepDraftAndOnlyNewEmptyRowsAreDiscarded() {
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "tools", headers = listOf("Authorization" to "Bearer test", "Bad Name" to "  preserve  ")),
            url = "https://example.test/mcp",
        )
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val saved = java.util.concurrent.atomic.AtomicReference<McpServerConfig>()
        compose.setContent {
            McpTestTheme {
                val state = net.weero.measix.pilot.ui.hooks.useEditState<McpServerConfig> { }
                androidx.compose.runtime.LaunchedEffect(Unit) { state.open(config) }
                McpServerConfigModal(state) { candidate ->
                    saved.set(candidate)
                    if (attempts.incrementAndGet() == 1) throw java.io.IOException("disk write detail", IllegalStateException("original cause"))
                }
            }
        }
        val save = compose.activity.getString(R.string.setting_mcp_page_save)
        fun waitForWindow() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        fun submit() {
            waitForWindow()
            compose.onNodeWithText(save).performClick()
        }
        submit()
        compose.onNodeWithText("mcp_header_invalid_name", substring = true).assertIsDisplayed()
        org.junit.Assert.assertEquals(0, attempts.get())
        compose.onNodeWithText("Bad Name").performScrollTo().performTextReplacement("X-Key")
        // Stabilize the IME insets before scrolling and injecting clicks into the form.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        val addHeader = compose.onNodeWithText(compose.activity.getString(R.string.setting_mcp_page_add_header))
        addHeader.performScrollTo()
        waitForWindow()
        addHeader.performClick()
        submit()
        compose.onNodeWithText("disk write detail", substring = true).assertIsDisplayed()
        compose.onNodeWithText("original cause", substring = true).assertIsDisplayed()
        compose.onNodeWithText("X-Key").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(listOf("Authorization" to "Bearer test", "X-Key" to "  preserve  "), saved.get().commonOptions.headers)
        submit()
        compose.waitUntil(5_000) { attempts.get() == 2 }
        compose.onNodeWithText(save).assertDoesNotExist()
    }

    @Test
    fun existingEmptyHeaderIsAnErrorRatherThanAnImplicitDeletion() {
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "tools", headers = listOf("" to "")), url = "https://example.test/mcp")
        compose.setContent {
            McpTestTheme {
                val state = net.weero.measix.pilot.ui.hooks.useEditState<McpServerConfig> { }
                androidx.compose.runtime.LaunchedEffect(Unit) { state.open(config) }
                McpServerConfigModal(state) { error("existing invalid row must not reach persistence") }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.setting_mcp_page_save)).performClick()
        compose.onNodeWithText("mcp_header_empty_name", substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.setting_mcp_page_save)).assertIsDisplayed()
    }

    @Test
    fun compactToolEditorKeepsChangesInTheDraftUntilSave() {
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "my_tools"), url = "https://example.test/mcp")
        val query = mockk<McpQueryService>()
        every { query.observeUserServer(config.id) } returns kotlinx.coroutines.flow.flowOf(
            McpServerPresentation(config.id, "Personal tools", true, config, status = McpStatus.Ready(1, 1),
                sessionCallable = true, directoryConfirmed = true,
                tools = listOf(McpToolPresentation("create_ticket", "Creates a ticket", JsonObject(emptyMap()), true, false)))
        )
        val isolated = koinApplication { modules(module { single { query } }) }
        val saved = java.util.concurrent.atomic.AtomicReference<McpServerConfig>()
        try {
            compose.setContent {
                KoinIsolatedContext(isolated) { McpTestTheme {
                    val state = net.weero.measix.pilot.ui.hooks.useEditState<McpServerConfig> {}
                    androidx.compose.runtime.LaunchedEffect(Unit) { state.open(config) }
                    McpServerConfigModal(state) { saved.set(it) }
                } }
            }
            compose.onNodeWithText(compose.activity.getString(R.string.setting_mcp_page_tools)).performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.setting_mcp_page_needs_approval))
                .assertIsOff().performClick().assertIsOn()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.setting_mcp_page_enable))
                .assertIsOn().performClick().assertIsOff()
            val touchBounds = compose.onNodeWithContentDescription(compose.activity.getString(R.string.setting_mcp_page_enable))
                .fetchSemanticsNode().touchBoundsInRoot
            val minimumTarget = 48 * compose.activity.resources.displayMetrics.density
            org.junit.Assert.assertTrue(touchBounds.width >= minimumTarget && touchBounds.height >= minimumTarget)
            org.junit.Assert.assertNull(saved.get())
            compose.onNodeWithText(compose.activity.getString(R.string.setting_mcp_page_save)).assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { saved.get() != null }
            org.junit.Assert.assertEquals(config.id, saved.get().id)
            org.junit.Assert.assertEquals(config.url, (saved.get() as McpServerConfig.StreamableHTTPServer).url)
            org.junit.Assert.assertEquals(listOf(McpToolPolicy(name = "create_ticket", enable = false, needsApproval = true)), saved.get().commonOptions.toolPolicies)
        } finally { isolated.close() }
    }

    @Test
    fun importKeepsInputAndDistinguishesJsonSyntaxFromHeaderValidation() {
        val service = mockk<McpApplicationService>()
        coEvery { service.importServers(any()) } coAnswers {
            firstArg<List<McpServerConfig>>().forEach { validateMcpHeaders(it.commonOptions.headers) }
            McpImportResult(emptyList(), emptyList())
        }
        compose.setContent {
            McpTestTheme {
                val toaster = rememberToasterState()
                McpImportModal(onDismiss = {}, onImport = { text ->
                    handleMcpImport(text, service, toaster, compose.activity) { }
                })
            }
        }
        val confirm = compose.activity.getString(R.string.setting_mcp_page_import_confirm)
        val parsePrefix = compose.activity.getString(R.string.setting_mcp_page_import_parse_error, "").trim()
        compose.onNode(hasSetTextAction()).performTextInput("{")
        compose.onNodeWithText(confirm).performClick()
        compose.onNodeWithText(parsePrefix, substring = true).assertIsDisplayed()
        coVerify(exactly = 0) { service.importServers(any()) }
        val input = """{"tools":{"url":"https://example.test/mcp","headers":{"":"private-value"}}}"""
        compose.onNode(hasSetTextAction()).performTextReplacement(input)
        compose.onNodeWithText(confirm).performClick()
        compose.onNodeWithText("mcp_header_empty_name", substring = true).assertIsDisplayed()
        compose.onNodeWithText(parsePrefix, substring = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextEquals(input)
        coVerify(exactly = 1) { service.importServers(any()) }
    }

    @Test
    fun personalServerCardEditsAndDeleteMenuRequiresConfirmation() {
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "Personal tools"), url = "https://example.test/mcp",
        )
        val row = config.toPresentation(McpRuntimeCapability.EMPTY)
        val query = mockk<McpQueryService>()
        every { query.userServers } returns MutableStateFlow(listOf(row))
        every { query.catalog } returns MutableStateFlow<McpCatalogUiModel?>(
            McpCatalogUiModel(RealmSelection(RealmAccess.Personal, 1), McpCatalogReadState.Available(listOf(row))),
        )
        val commands = mockk<McpApplicationService>()
        coEvery { commands.delete(config.id) } returns Unit
        val configuration = mockk<ConfigurationApplicationService>()
        compose.setContent {
            McpTestTheme {
                CompositionLocalProvider(
                    LocalToaster provides rememberToasterState(),
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                ) { SettingMcpPage(commands, query, configuration) }
            }
        }
        val more = compose.activity.getString(R.string.more_options)
        val delete = compose.activity.getString(R.string.delete)
        val cancel = compose.activity.getString(R.string.cancel)
        val save = compose.activity.getString(R.string.setting_mcp_page_save)
        compose.onNodeWithContentDescription(more).performScrollTo().performClick()
        compose.onNodeWithText(delete).performClick()
        compose.onNodeWithText(save).assertDoesNotExist()
        compose.onNodeWithText(cancel).performClick()
        coVerify(exactly = 0) { commands.delete(any()) }
        compose.onNodeWithContentDescription(more).performScrollTo().performClick()
        compose.onNodeWithText(delete).performClick()
        compose.onNodeWithText(delete).performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { commands.delete(config.id) }
        compose.onNodeWithText("Personal tools").performScrollTo().performClick()
        compose.onNodeWithText(save).assertIsDisplayed()
    }

}

@Composable
private fun McpTestTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
            content()
        }
    }
}
