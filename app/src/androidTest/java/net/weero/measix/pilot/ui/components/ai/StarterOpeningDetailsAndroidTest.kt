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

    @Test fun contextIsCollapsedAndQueriedOnlyOnDemand() {
        val reads = AtomicInteger()
        compose.setContent {
            MaterialTheme { Column { StarterOpeningContext("first") { reads.incrementAndGet(); detail } } }
        }
        compose.onNodeWithText(detail.systemPrompt!!).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reads.get()) }
        compose.onNodeWithText(context.getString(R.string.opening_context)).performClick()
        compose.onNodeWithText(detail.systemPrompt).assertIsDisplayed()
        compose.onNodeWithText(detail.contexts.single().content).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reads.get()) }
        compose.onNodeWithText(context.getString(R.string.opening_context)).performClick()
        compose.onNodeWithText(detail.systemPrompt).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.opening_context)).performClick()
        compose.runOnIdle { assertEquals(1, reads.get()) }
    }
}
