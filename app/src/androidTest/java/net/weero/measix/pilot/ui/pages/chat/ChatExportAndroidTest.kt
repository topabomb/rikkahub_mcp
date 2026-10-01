package net.weero.measix.pilot.ui.pages.chat

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.FileManagementApplicationService
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatExportAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rejectedMarkdownExportKeepsCopyableDiagnosisAndSelectionWithoutReplaying() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val queries = mockk<ConversationQueryService>()
        val attempts = AtomicInteger()
        val rootDetail = "Original source cause " + "x".repeat(600) + " final detail; token=private-token"
        val failure = IOException("Original export refused sk-abcdefghijklmnop", IllegalStateException(rootDetail))
        val diagnostic = compose.activity.getString(R.string.chat_page_export_failed,
            "IOException: Original export refused …\nCaused by: IllegalStateException: " +
                "Original source cause " + "x".repeat(600) + " final detail; token=<redacted>")
        coEvery { queries.requireViewAccess(view) } coAnswers {
            attempts.incrementAndGet()
            view.requireOpen()
            throw failure
        }
        val original = listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("Selected original"))))
        var selected by mutableStateOf(original)
        var visible by mutableStateOf(true)
        var dismissals = 0
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        compose.setContent {
            ExportHost {
                ChatExportSheet(source = view, visible = visible, onDismissRequest = {
                    dismissals++
                    selected = emptyList()
                    visible = false
                }, conversationTitle = "Original export selection", selectedMessages = selected, queries = queries)
            }
        }
        try {
            compose.onNodeWithText(compose.activity.getString(R.string.chat_page_export_markdown)).performClick()
            compose.onNode(hasText(diagnostic) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(hasContentDescription(compose.activity.getString(R.string.chat_page_copy_error)) and
                hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == diagnostic }
            compose.runOnIdle {
                assertEquals(1, attempts.get())
                assertEquals(0, dismissals)
                assertEquals(original, selected)
                assertFalse(view.closed.value)
            }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.chat_page_export_markdown)).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnIdle {
                assertEquals(1, attempts.get())
                assertEquals(original, selected)
                assertEquals(0, dismissals)
            }
            coVerify(exactly = 1) { queries.requireViewAccess(view) }
        } finally {
            clipboard.clearPrimaryClip()
            view.close()
        }
    }

    @Test fun cancelledMarkdownExportKeepsSelectionWithoutFailureOrReplay() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val queries = mockk<ConversationQueryService>()
        val attempts = AtomicInteger()
        coEvery { queries.requireViewAccess(view) } coAnswers {
            attempts.incrementAndGet()
            throw CancellationException("Original export cancelled")
        }
        val original = listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("Selected original"))))
        var selected by mutableStateOf(original)
        var dismissals = 0
        compose.setContent {
            ExportHost {
                ChatExportSheet(source = view, visible = true,
                    onDismissRequest = { dismissals++; selected = emptyList() },
                    conversationTitle = "Original export selection", selectedMessages = selected, queries = queries)
            }
        }
        try {
            compose.onNodeWithText(compose.activity.getString(R.string.chat_page_export_markdown)).performClick()
            compose.waitUntil(5_000) { attempts.get() == 1 }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_page_copy_error))
                .assertDoesNotExist()
            compose.onNodeWithText(compose.activity.getString(R.string.chat_page_export_markdown)).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, attempts.get())
                assertEquals(0, dismissals)
                assertEquals(original, selected)
                assertFalse(view.closed.value)
            }
            coVerify(exactly = 1) { queries.requireViewAccess(view) }
        } finally { view.close() }
    }

    @Composable
    private fun ExportHost(content: @Composable () -> Unit) {
        KoinApplication(application = { modules(module {
            single { mockk<FileManagementApplicationService>() }
            single { MediaExportService(get()) }
        }) }) {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalSettings provides Settings.dummy(),
                    LocalToaster provides rememberToasterState(),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                ) { content() }
            }
        }
    }
}
