package net.weero.measix.pilot.ui.pages.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.ConversationOpenRequest
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationRecoveryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun missingConversationKeepsSpaceNavigationWhenNewChatCannotNavigate() {
        val original = Screen.Chat(ConversationOpenRequest.NewDraft(
            Uuid.random(), RealmAccess.Personal, ConfigurationReference.random(),
        ))
        val stack = mutableListOf<NavKey>(original)
        var attempts = 0
        show(stack, message = text(R.string.chat_conversation_missing_message), onNewChat = { attempts++ })
        compose.onNodeWithText(text(R.string.chat_page_new_chat)).performClick()
        compose.runOnIdle {
            assertEquals(1, attempts)
            assertEquals(listOf(original), stack)
        }
        compose.onNodeWithTag("conversation-recovery-spaces").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(original, Screen.Enterprise), stack) }
    }

    @Test
    fun failedConversationKeepsCompleteCopyableDiagnosticAndRecoveryOnAShortScreen() {
        val failure = IOException("conversation read failed: " + "unreadable row detail; ".repeat(250),
            IllegalStateException("selected connection unavailable"))
        val diagnostic = failure.userVisibleDiagnostic()
        val stack = mutableListOf<NavKey>(Screen.Startup())
        var retries = 0
        show(stack, text(conversationLoadFailureMessage(failure)), diagnostic,
            onRetry = { retries++ }, shortScreen = true, dark = true)
        capture("conversation-recovery-dark.png")
        compose.onNodeWithText(text(R.string.application_recovery_retry)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        compose.onNodeWithTag("conversation-recovery-diagnostics").performScrollTo().performClick()
        compose.onNode(hasText(diagnostic) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.chat_page_copy_error)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(diagnostic, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithContentDescription(text(R.string.update_card_close)).performClick()
        compose.onNodeWithTag("conversation-recovery-spaces").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.Enterprise, stack.last()) }
    }

    @Test
    fun initialConversationFailureOffersCurrentSpaceAndPreservesOriginalReason() {
        val failure = EnterpriseConfigurationException("enterprise_data_access_unavailable")
        val stack = mutableListOf<NavKey>(Screen.Startup())
        show(stack, text(conversationLoadFailureMessage(failure)), failure.userVisibleDiagnostic())
        compose.onNodeWithText(text(R.string.chat_conversation_access_changed_message)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.chat_page_new_chat)).assertDoesNotExist()
        compose.onNodeWithTag("conversation-recovery-diagnostics").performClick()
        compose.onNode(hasText(failure.userVisibleDiagnostic()) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.update_card_close)).performClick()
        compose.onNodeWithTag("conversation-recovery-spaces").performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.Startup(), Screen.Enterprise), stack) }
    }

    private fun show(
        stack: MutableList<NavKey>,
        message: String,
        diagnostic: String? = null,
        onRetry: () -> Unit = {},
        onNewChat: (() -> Unit)? = null,
        shortScreen: Boolean = false,
        dark: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            val colors = if (dark) darkColorScheme() else MaterialTheme.colorScheme
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(
                    LocalNavController provides Navigator(stack),
                    LocalDensity provides Density(density.density, if (shortScreen) 1.5f else density.fontScale),
                    net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo(),
                ) {
                    Surface {
                        Box(if (shortScreen) Modifier.fillMaxWidth().height(240.dp) else Modifier) {
                            ConversationUnavailable(
                                title = text(R.string.chat_conversation_load_failed_title),
                                message = message,
                                diagnostic = diagnostic,
                                onRetry = onRetry,
                                onNewChat = onNewChat,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            java.io.File(context.cacheDir, name).outputStream().use {
                org.junit.Assert.assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun text(resource: Int): String = compose.activity.getString(resource)
}
