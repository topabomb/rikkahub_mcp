package net.weero.measix.pilot.ui.components.ai

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.StarterContextUiModel
import net.weero.measix.pilot.service.StarterOpeningDetailUiModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarterOpeningDetailsAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val detail = StarterOpeningDetailUiModel("Task", "Editable starting prompt", "{{user}} original system",
        listOf(StarterContextUiModel("Background", "<data>{{literal}}</data>")), false, true)

    @Test fun contextLoadsOnDemandAndRetryClearsFailureWithoutReloadingSavedContent() {
        val reads = AtomicInteger()
        compose.setContent {
            MaterialTheme { Column { StarterOpeningContext("first") {
                if (reads.incrementAndGet() == 1) throw IllegalStateException("opening_read_failed")
                detail
            } } }
        }
        compose.onNodeWithText(detail.systemPrompt!!).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reads.get()) }
        compose.onNodeWithText(context.getString(R.string.opening_context)).performClick()
        compose.onNodeWithText("IllegalStateException: opening_read_failed", substring = true).assertIsDisplayed()
        compose.onNodeWithText(detail.systemPrompt).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.application_recovery_retry)).performClick()
        compose.onNodeWithText("opening_read_failed", substring = true).assertDoesNotExist()
        compose.onNodeWithText(detail.systemPrompt).assertIsDisplayed()
        compose.onNodeWithText(detail.contexts.single().content).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, reads.get()) }
        compose.onNodeWithText(context.getString(R.string.opening_context)).performClick()
        compose.onNodeWithText(detail.systemPrompt).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.opening_context)).performClick()
        compose.runOnIdle { assertEquals(2, reads.get()) }
    }
    @Test fun initialModalFailureCanRetryReadWithoutRefreshingOpening() {
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        compose.setContent { MaterialTheme { androidx.compose.runtime.CompositionLocalProvider(
            net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo()) {
            StarterOpeningDetails("modal", load = {
                if (reads.incrementAndGet() == 1) throw java.io.IOException("opening_read_failed")
                detail
            }, refresh = { writes.incrementAndGet() }, clear = { writes.incrementAndGet() }, onDismiss = {})
        } } }
        compose.onNodeWithText("IOException: opening_read_failed", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.application_recovery_retry)).performClick()
        compose.onNodeWithText(detail.prompt).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, reads.get()); assertEquals(0, writes.get()) }
    }

    @Test fun readRetryCannotOverlapPendingOpeningUpdate() {
        val reads = AtomicInteger()
        val updates = AtomicInteger()
        val removals = AtomicInteger()
        val finishUpdate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val draft = detail.copy(isDraft = true, canRefresh = true)
        compose.setContent { MaterialTheme { androidx.compose.runtime.CompositionLocalProvider(
            net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo()) {
            StarterOpeningDetails("original-opening", load = { reads.incrementAndGet(); draft }, refresh = {
                if (updates.incrementAndGet() == 1) throw java.io.IOException("opening_update_failed")
                finishUpdate.await()
            }, clear = { removals.incrementAndGet() }, onDismiss = {})
        } } }
        val update = compose.onNodeWithText(context.getString(R.string.opening_update))
        update.performScrollTo().performClick()
        val diagnostic = "IOException: opening_update_failed"
        compose.onNodeWithText(diagnostic).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.application_recovery_retry)).assertExists()
        update.performScrollTo().performClick()
        compose.waitUntil { updates.get() == 2 }
        compose.onNodeWithText(diagnostic).assertExists()
        compose.onNodeWithText(context.getString(R.string.application_recovery_retry)).assertDoesNotExist()
        update.assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.opening_remove)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, reads.get()); assertEquals(0, removals.get()); finishUpdate.complete(Unit) }
        compose.onNodeWithText(diagnostic).assertDoesNotExist()
        update.assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.opening_remove)).assertIsEnabled()
        compose.runOnIdle { assertEquals(2, reads.get()); assertEquals(2, updates.get()); assertEquals(0, removals.get()) }
    }

}
