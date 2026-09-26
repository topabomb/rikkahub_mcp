package net.weero.measix.pilot.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveModalFeedbackAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyForegroundModalShowsSavedFeedbackWithoutRecreatingParentContent() {
        var parentCompositions = 0
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
            val feedback = rememberToasterState()
            var child by remember { mutableStateOf(false) }
            AdaptiveModal(onDismissRequest = {}, forceDialog = true, feedback = feedback, feedbackVisible = !child) {
                DisposableEffect(Unit) { parentCompositions++; onDispose {} }
                TextButton(onClick = { child = true }) { Text("Open configuration") }
                if (child) AdaptiveModal(onDismissRequest = { child = false }, forceDialog = true, feedback = feedback) {
                    TextButton(onClick = { feedback.show("Saved for next send") }) { Text("Save configuration") }
                }
            }
        } } }
        compose.onNodeWithText("Open configuration").performClick()
        compose.onNodeWithText("Save configuration").performClick()
        compose.onAllNodesWithText("Saved for next send").assertCountEquals(1)
        compose.onNodeWithText("Saved for next send").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, parentCompositions) }
    }
}
