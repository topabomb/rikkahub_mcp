package net.weero.measix.pilot.ui.components.webview

import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class RenderedContentReadAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pendingAccessIsLoadingUntilItSucceeds() {
        val gate = CompletableDeferred<Unit>()
        val files = mockk<FileManagementApplicationService>()
        val queries = mockk<ConversationQueryService>()
        val document = RenderedContent(RenderedContentSource.Static, "<p>Preview</p>")
        coEvery { files.requireContentAccess(document.source) } coAnswers { gate.await() }
        compose.setContent {
            MaterialTheme { ReadStatus(rememberRenderedContentState(document, files, queries)) }
        }
        try {
            compose.onNodeWithText("Loading").assertIsDisplayed()
            compose.onNodeWithText("Unavailable").assertDoesNotExist()
            compose.runOnIdle { gate.complete(Unit) }
            awaitText("Ready")
        } finally { gate.complete(Unit) }
    }

    @Test fun failureKeepsCopyableCauseAndRetriesOnlyTheOriginalBorrowedSource() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val source = RenderedContentSource.Conversation(view)
        val document = RenderedContent(source, "<p>Preview</p>")
        val files = mockk<FileManagementApplicationService>()
        val queries = mockk<ConversationQueryService>()
        val failure = IOException("Preview authorization unreadable", IllegalStateException("Original cause")).apply {
            addSuppressed(IllegalArgumentException("Additional diagnostic"))
        }
        val attempts = CopyOnWriteArrayList<RenderedContentSource>()
        val allowed = MutableStateFlow(true)
        coEvery { files.requireContentAccess(any()) } coAnswers {
            attempts += firstArg<RenderedContentSource>()
            view.requireOpen()
            if (attempts.size == 1) throw failure
        }
        every { queries.observeViewAccess(view) } returns allowed.onEach { if (!it) view.close() }
        var revision by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme { ReadStatus(rememberRenderedContentState(document, files, queries, revision)) { revision++ } }
        }
        try {
            awaitText(failure.userVisibleDiagnostic())
            compose.runOnIdle { assertEquals(1, attempts.size); assertFalse(view.closed.value) }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_page_copy_error)).performClick()
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == failure.userVisibleDiagnostic() }
            compose.onNodeWithText(compose.activity.getString(R.string.application_recovery_retry)).performClick()
            awaitText("Ready")
            compose.runOnIdle {
                assertEquals(2, attempts.size)
                assertTrue(attempts.all { it === source })
                assertFalse(view.closed.value)
            }
            compose.runOnIdle { allowed.value = false }
            compose.onNodeWithText("Unavailable").assertIsDisplayed()
            compose.runOnIdle { revision++ }
            compose.onNodeWithText("Unavailable").assertIsDisplayed()
            compose.runOnIdle { assertTrue(view.closed.value) }
        } finally { view.close() }
    }

    @Test fun resourceFailureDoesNotFailANewAttemptFromALateOldWebViewCallback() {
        val files = mockk<FileManagementApplicationService>()
        val queries = mockk<ConversationQueryService>()
        val source = RenderedContentSource.UserConfiguration
        val document = RenderedContent(source, "<p>Preview</p>")
        val failure = IOException("Owned image unreadable", IllegalStateException("Payload detail"))
        coEvery { files.requireContentAccess(source) } returns Unit
        coEvery { files.readRenderedImage(source, any()) } throws failure
        var revision by mutableIntStateOf(0)
        var read: RenderedContentReadState = RenderedContentReadState.Loading
        compose.setContent {
            val current = rememberRenderedContentState(document, files, queries, revision)
            SideEffect { read = current }
            MaterialTheme { ReadStatus(current) { revision++ } }
        }
        awaitText("Ready")
        var old: WebViewState? = null
        compose.runOnIdle {
            old = (read as RenderedContentReadState.Ready).webView
            assertEquals(403, old?.interceptRequest?.invoke(Uri.parse("file:///owned.png"))?.statusCode)
        }
        awaitText(failure.userVisibleDiagnostic())
        compose.onNodeWithText(compose.activity.getString(R.string.application_recovery_retry)).performClick()
        awaitText("Ready")
        compose.runOnIdle {
            val current = (read as RenderedContentReadState.Ready).webView
            assertNotSame(old, current)
            assertEquals(403, old?.interceptRequest?.invoke(Uri.parse("file:///old.png"))?.statusCode)
        }
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText(failure.userVisibleDiagnostic()).assertDoesNotExist()
        coVerify(exactly = 2) { files.readRenderedImage(source, any()) }
    }

    @Test fun accessObserverFailureStaysDiagnosticAndRetriesWithoutReplacingTheLease() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val document = RenderedContent(RenderedContentSource.Conversation(view), "<p>Preview</p>")
        val files = mockk<FileManagementApplicationService>()
        val queries = mockk<ConversationQueryService>()
        val failure = IOException("Access observation unreadable", IllegalStateException("Original observer cause"))
        var observations = 0
        coEvery { files.requireContentAccess(document.source) } coAnswers { view.requireOpen() }
        every { queries.observeViewAccess(view) } answers {
            if (++observations == 1) flow { throw failure } else flowOf(true)
        }
        var revision by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme { ReadStatus(rememberRenderedContentState(document, files, queries, revision)) { revision++ } }
        }
        try {
            awaitText(failure.userVisibleDiagnostic())
            compose.runOnIdle { assertEquals(1, observations); assertFalse(view.closed.value) }
            compose.onNodeWithText(compose.activity.getString(R.string.application_recovery_retry)).performClick()
            awaitText("Ready")
            compose.runOnIdle { assertEquals(2, observations); assertFalse(view.closed.value) }
            coVerify(exactly = 2) { files.requireContentAccess(document.source) }
        } finally { view.close() }
    }

    @Test fun revokedSourceIsUnavailableWithoutManufacturingADiagnosticFailure() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val document = RenderedContent(RenderedContentSource.Conversation(view), "<p>Preview</p>")
        val files = mockk<FileManagementApplicationService>()
        val queries = mockk<ConversationQueryService>()
        view.close()
        coEvery { files.requireContentAccess(document.source) } coAnswers { view.requireOpen() }
        compose.setContent {
            MaterialTheme { ReadStatus(rememberRenderedContentState(document, files, queries)) }
        }
        awaitText("Unavailable")
        compose.onNodeWithText(compose.activity.getString(R.string.rendered_content_read_failed)).assertDoesNotExist()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Composable
    private fun ReadStatus(state: RenderedContentReadState, retry: () -> Unit = {}) {
        when (state) {
            RenderedContentReadState.Loading -> Text("Loading")
            RenderedContentReadState.Unavailable -> Text("Unavailable")
            is RenderedContentReadState.Ready -> Text("Ready")
            is RenderedContentReadState.Failed -> RenderedContentReadFailure(state.error, retry)
        }
    }
}
