package net.weero.measix.pilot.ui.components.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ChatErrorRetention
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatErrorCardAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun longDiagnosticsStayCompactAndCopyDoesNotDismiss() {
        val diagnostic = "IOException: " + (1..50).joinToString("\n") { "payload failure $it with original diagnostic" }
        val error = ChatError(detail = diagnostic, retention = ChatErrorRetention.UNTIL_DISMISSED)
        val second = error.copy(id = kotlin.uuid.Uuid.random())
        var activeErrors by mutableStateOf(listOf(error, second))
        var dismissals = 0
        compose.setContent {
            val density = LocalDensity.current
            MaterialTheme { CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f),
                net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo()) {
                Box(Modifier.width(320.dp).height(500.dp).testTag("chat-error-host")) {
                    ErrorCardsDisplay(activeErrors,
                        onDismissError = { dismissals++ }, onClearAllErrors = {}, modifier = Modifier.testTag("chat-errors"))
                }
            } }
        }
        val host = compose.onNodeWithTag("chat-error-host").fetchSemanticsNode().boundsInRoot
        val errors = compose.onNodeWithTag("chat-errors").fetchSemanticsNode().boundsInRoot
        assertTrue(errors.height <= host.height / 2 + 1)
        capture("chat-errors-narrow-large-font.png")
        compose.runOnIdle { activeErrors = listOf(error) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_conversation_diagnostics)).performClick()
        compose.onNode(hasText(diagnostic) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        capture("chat-error-full-diagnostic.png")
        val viewport = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).assertIsDisplayed()
        val scrolling = viewport.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scrolling.maxValue() > 0f)
        viewport.performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(scrolling.value() > 0f) }
        compose.onNode(hasContentDescription(context.getString(R.string.chat_page_copy_error)) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(diagnostic, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(0, dismissals)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.update_card_close)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_page_copy_error)).performClick()
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_page_dismiss_error)).performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
    }

    @Test fun readFailureOffersExplicitRetryWithoutDismissOrWriteReplay() {
        var retries = 0
        val error = ChatError(detail = "IOException: read failed", retention = ChatErrorRetention.UNTIL_DISMISSED)
        compose.setContent { MaterialTheme { ErrorCard(error, onRetry = { retries++ }) } }
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithText(context.getString(R.string.application_recovery_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_page_dismiss_error)).assertDoesNotExist()
    }
    @Test fun rejectedPresetUsesDialogAndPreservesInputAndIconSlot() {
        val input = net.weero.measix.pilot.ui.hooks.ChatInputState().apply { setMessageText("Unsent draft") }
        val diagnostic = "IllegalStateException: original input owner rejected insertion"
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    net.weero.measix.pilot.ui.context.LocalToaster provides com.dokar.sonner.rememberToasterState(),
                    net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo(),
                ) {
                    Row(Modifier.width(320.dp)) {
                        net.weero.measix.pilot.ui.components.ai.PromptPresetButton(
                            listOf(net.weero.measix.pilot.data.model.QuickMessage(title = "Insert preset", content = "Replacement")),
                            emptyList(), input,
                            requireInputOwner = { throw IllegalStateException("original input owner rejected insertion") },
                            opening = null, isDraft = true, onStarterClick = {}, onOpeningDetails = {},
                        )
                        androidx.compose.material3.Text("Input remains visible", Modifier.testTag("input-slot"))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_input_presets)).performClick()
        compose.onNodeWithText("Insert preset").performClick()
        compose.onNode(hasText(diagnostic) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        capture("chat-preset-rejected-diagnostic.png")
        compose.runOnIdle { assertEquals("Unsent draft", input.textContent.text.toString()) }
        compose.onNodeWithContentDescription(context.getString(R.string.update_card_close)).performClick()
        // Closing the retained menu reveals the original narrow input row unchanged.
        compose.onNodeWithText("Insert preset").assertExists()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithTag("input-slot").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_input_presets)).assertIsDisplayed()
    }

    private fun capture(name: String) {
        // Settle Compose-driven sheet and visibility animations before reading device pixels.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Native dialog windows and Compose semantics reach idle independently.
        android.os.SystemClock.sleep(750)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5_000)
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

}
