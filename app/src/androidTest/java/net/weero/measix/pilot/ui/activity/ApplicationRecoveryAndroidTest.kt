package net.weero.measix.pilot.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.service.ApplicationRecoveryCoordinator
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ApplicationRecoveryState
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.IOException

/** UI adapter coverage: durable Settings read/migration failures are exercised in SettingsStartupTest. */
@RunWith(AndroidJUnit4::class)
class ApplicationRecoveryAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun recoveryFailureKeepsTypeAndCauseAndRetryReopensRealRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gate = GlobalContext.get().get<ApplicationRecoveryGate>()
        val recovery = GlobalContext.get().get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { gate.awaitReady() } }
        val failure = IOException("settings file unavailable", IllegalStateException("original settings cause"))
        gate.failed(failure)
        var activity: ActivityScenario<RouteActivity>? = null
        try {
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            var uiContext: Context = context
            activity.onActivity { uiContext = it }
            compose.onNodeWithText(uiContext.getString(R.string.application_recovery_failed)).assertIsDisplayed()
            compose.onNodeWithText("IOException", substring = true).assertIsDisplayed()
            compose.onNodeWithText("settings file unavailable", substring = true).assertIsDisplayed()
            compose.onNodeWithText("original settings cause", substring = true).assertIsDisplayed()
            assertSame(failure, (gate.state.value as ApplicationRecoveryState.Failed).error)
            compose.onNodeWithText(uiContext.getString(R.string.application_recovery_retry)).performClick()
            compose.waitUntil(30_000) { gate.state.value is ApplicationRecoveryState.Ready }
            compose.onNodeWithText(uiContext.getString(R.string.application_recovery_failed)).assertDoesNotExist()
        } finally {
            activity?.close()
            if (gate.state.value !is ApplicationRecoveryState.Ready) {
                recovery.retry()
                runBlocking { withTimeout(30_000) { gate.state.first { it is ApplicationRecoveryState.Ready } } }
            }
        }
    }
}
