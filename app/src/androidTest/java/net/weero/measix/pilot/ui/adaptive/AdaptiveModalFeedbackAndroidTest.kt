package net.weero.measix.pilot.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.R
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.sonner.rememberToasterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun compactHandleRetainsDismissalAndSystemTouchTarget() {
        var dismissed = 0
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.modal_drag_handle)
        compose.setContent { MaterialTheme {
            val compact = rememberAdaptiveLayoutInfo().copy(windowSize = DpSize(360.dp, 800.dp),
                separatingVerticalHingeBounds = emptyList(), separatingHorizontalHingeBounds = emptyList())
            CompositionLocalProvider(LocalAdaptiveLayoutInfo provides compact) {
                AdaptiveModal(onDismissRequest = { dismissed++ }) { Text("Modal content") }
            }
        } }
        val handle = compose.onNodeWithContentDescription(label).assertHasClickAction()
        val touch = handle.fetchSemanticsNode().touchBoundsInRoot
        val minimumTouch = with(compose.density) { 48.dp.toPx() }
        assertTrue("Compact handle retains 48dp touch target", touch.width >= minimumTouch && touch.height >= minimumTouch)
        handle.performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitUntil(5_000) { dismissed == 1 }
    }

}
