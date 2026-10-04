package net.weero.measix.pilot.ui.components.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.net.ConnectException
import me.rerere.ai.provider.ProviderSetting
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceTextPreviewResult
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import net.weero.measix.pilot.ui.pages.setting.ProviderConnectionTestState
import net.weero.measix.pilot.ui.pages.setting.ProviderSettingsUiState
import net.weero.measix.pilot.ui.pages.setting.components.ProviderConnectionTester
import net.weero.measix.pilot.utils.UiState
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class DiagnosticConsumersAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun providerProbeKeepsItsFullRedactedStackAndParentDialogAfterCopy() {
        val error = IOException("Connection failed. Authorization: Bearer fixture-secret", ConnectException("/192.168.1.8:9100 EHOSTUNREACH"))
        error.stackTrace = Array(60) { StackTraceElement("provider.Probe", "request$it", "Probe.kt", it + 1) }
        error.addSuppressed(IOException("original cleanup diagnostic"))
        var dismissals = 0
        compose.setContent {
            KoinApplication(application = { modules(module {
                single { mockk<ConfigurationApplicationService>() }
                single { mockk<ConfigurationQueryService>() }
            }) }) {
                DiagnosticTestTheme {
                    ProviderConnectionTester(ProviderSetting.OpenAI(), ProviderSettingsUiState(showConnectionTest = true,
                        connectionTest = ProviderConnectionTestState(nonStreaming = UiState.Error(error))),
                        onOpen = {}, onDismiss = { dismissals++ }, onSelectModel = {}, onRun = {})
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_conversation_diagnostics)).performClick()
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).assertCountEquals(1)
        capture("provider-probe-diagnostics.png")
        copyDetails()
        compose.runOnIdle {
            val copied = clipboard().primaryClip?.getItemAt(0)?.text.toString()
            assertFalse(copied.contains("fixture-secret"))
            assertTrue(copied.contains("Probe.kt:60"))
            assertTrue(copied.contains("Caused by: java.net.ConnectException: /192.168.1.8:9100 EHOSTUNREACH"))
            assertTrue(copied.contains("Suppressed: java.io.IOException: original cleanup diagnostic"))
            assertEquals(0, dismissals)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.setting_provider_page_test_connection)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_conversation_diagnostics)).assertIsDisplayed()
        clipboard().clearPrimaryClip()
    }

    @Test fun workspaceSaveFailureKeepsTheEditorAndDoesNotReplayTheWriteWhenCopyingOrClosing() {
        val error = IOException((1..40).joinToString("\n") { "original save diagnostic $it" }, IOException("filesystem cause"))
        val commands = mockk<WorkspaceApplicationService>()
        val queries = mockk<WorkspaceQueryService>()
        coEvery { queries.readTextForPreview("workspace", WorkspaceStorageArea.FILES, "draft.txt") } returns WorkspaceTextPreviewResult.Success("original draft")
        coEvery { commands.writeText("workspace", "draft.txt", "original draft") } throws error
        compose.setContent { DiagnosticTestTheme {
            WorkspaceFileEditorPage("workspace", WorkspaceStorageArea.FILES, "draft.txt", commands, queries)
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).performClick()
        compose.onNode(hasText(error.userVisibleDiagnostic()) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog())).assertCountEquals(1)
        capture("workspace-save-diagnostics.png")
        copyDetails()
        compose.runOnIdle { assertEquals(error.userVisibleDiagnostic(), clipboard().primaryClip?.getItemAt(0)?.text.toString()) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
        compose.onNodeWithText("draft.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).assertIsEnabled()
        coVerify(exactly = 1) { commands.writeText("workspace", "draft.txt", "original draft") }
        clipboard().clearPrimaryClip()
    }

    private fun copyDetails() = compose.onNode(hasContentDescription(compose.activity.getString(R.string.chat_page_copy_error))
        and hasAnyAncestor(isDialog())).performClick()

    private fun clipboard() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Wait for native dialog windows after Compose reaches idle.
        android.os.SystemClock.sleep(750)
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { java.io.File(it) } ?: compose.activity.getExternalFilesDir(null)
        java.io.File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

@Composable
private fun DiagnosticTestTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
            LocalToaster provides rememberToasterState(),
            LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup()))) { content() }
    }
}
