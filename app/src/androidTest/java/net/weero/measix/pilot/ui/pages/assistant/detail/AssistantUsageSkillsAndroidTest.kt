package net.weero.measix.pilot.ui.pages.assistant.detail

import android.content.ClipboardManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.SkillMetadata
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AssistantUsageSkillsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun directoryLoadsOnlyInItsTabAndFailureCanBeCopiedAndRetriedOnIo() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val manager = mockk<SkillManager>()
        val reads = AtomicInteger()
        val failure = IOException("Skill root unreadable", IllegalStateException("Directory detail"))
        every { manager.listSkills() } answers {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            if (reads.incrementAndGet() == 1) throw failure
            listOf(SkillMetadata("Available skill", "Published description"))
        }
        var showing by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column {
                    TextButton(onClick = { showing = !showing }) { Text("Switch tab") }
                    if (showing) AssistantUsageSkills(view, view::requireOpen, emptySet(), { _, _ -> }, {}, manager)
                    else Text("Other section")
                }
            }
        }
        try {
            compose.runOnIdle { verify(exactly = 0) { manager.listSkills() } }
            compose.onNodeWithText("Other section").assertIsDisplayed()
            compose.onNodeWithText("Switch tab").performClick()
            awaitText(failure.userVisibleDiagnostic())
            compose.runOnIdle { assertEquals(1, reads.get()); assertFalse(view.closed.value) }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_page_copy_error)).performClick()
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == failure.userVisibleDiagnostic() }
            compose.onNodeWithText(compose.activity.getString(R.string.application_recovery_retry)).performClick()
            awaitText("Available skill")
            compose.runOnIdle { assertEquals(2, reads.get()); assertFalse(view.closed.value) }
            compose.onNodeWithText("Switch tab").performClick()
            compose.onNodeWithText("Other section").assertIsDisplayed()
            compose.onNodeWithText(failure.userVisibleDiagnostic()).assertDoesNotExist()
        } finally { view.close() }
    }

    @Test fun leavingSkillsCancelsPublicationFromAnInFlightDirectoryRead() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val manager = mockk<SkillManager>()
        val reads = AtomicInteger()
        val release = CountDownLatch(1)
        every { manager.listSkills() } answers {
            if (reads.incrementAndGet() == 1) {
                release.await()
                throw IOException("Late old directory failure")
            }
            listOf(SkillMetadata("Current directory", "Published description"))
        }
        var showing by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column {
                    TextButton(onClick = { showing = !showing }) { Text("Switch tab") }
                    if (showing) AssistantUsageSkills(view, view::requireOpen, emptySet(), { _, _ -> }, {}, manager)
                    else Text("Other section")
                }
            }
        }
        try {
            compose.waitUntil { reads.get() == 1 }
            compose.onNodeWithText("Switch tab").performClick()
            compose.onNodeWithText("Other section").assertIsDisplayed()
            release.countDown()
            compose.onNodeWithText("Switch tab").performClick()
            awaitText("Current directory")
            compose.onNodeWithText("Late old directory failure", substring = true).assertDoesNotExist()
            compose.runOnIdle { assertEquals(2, reads.get()); assertFalse(view.closed.value) }
        } finally { release.countDown(); view.close() }
    }

    @Test fun closedPageIsUnavailableAndDoesNotReadDirectoryOrOfferReadRetry() {
        val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
        val manager = mockk<SkillManager>()
        view.close()
        compose.setContent {
            MaterialTheme { AssistantUsageSkills(view, view::requireOpen, emptySet(), { _, _ -> }, {}, manager) }
        }
        awaitText(compose.activity.getString(R.string.assistant_usage_skills_unavailable))
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_usage_skills_read_failed)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.application_recovery_retry)).assertDoesNotExist()
        verify(exactly = 0) { manager.listSkills() }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
