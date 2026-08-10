package net.weero.measix.pilot.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks in the dialog container's outside-click contract that regressed in 4a73fbad:
 * taps on the scrim dismiss, taps inside the content surface never dismiss — including the
 * bottom action row, which previously rendered outside the measured content bounds on some
 * window sizes and was wrongly treated as a scrim tap (the "settings changes not saved on
 * the foldable emulator" bug).
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveDialogContainerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapOnScrim_dismisses() {
        var count = 0
        compose.setContent {
            // Mirrors the real call site (AdaptiveModal): the container fills the window.
            AdaptiveDialogContainer(
                onDismissRequest = { count++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                Surface(
                    modifier = Modifier
                        .size(200.dp)
                        .testTag("contentSurface"),
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        val density = compose.density
        val corner = with(density) { 5.dp.toPx() }
        compose.onRoot().performTouchInput {
            click(Offset(corner, corner))
        }
        compose.waitForIdle()
        assertEquals(1, count)
    }

    @Test
    fun tapInsideContent_doesNotDismiss() {
        var count = 0
        compose.setContent {
            AdaptiveDialogContainer(
                onDismissRequest = { count++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                Surface(
                    modifier = Modifier
                        .size(200.dp)
                        .testTag("contentSurface"),
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        // Clicks the exact center of the content surface (its onNodeWithTag center).
        compose.onNodeWithTag("contentSurface").performClick()
        compose.waitForIdle()
        assertEquals(0, count)
    }

    @Test
    fun tapOnBottomActionRow_doesNotDismiss_andTriggersAction() {
        var dismissed = 0
        var clicked = 0
        compose.setContent {
            AdaptiveDialogContainer(onDismissRequest = { dismissed++ }) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        Button(
                            onClick = { clicked++ },
                            modifier = Modifier.testTag("saveButton"),
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
        // The button sits at the very bottom of the container — exactly where the old
        // hit-test misclassified taps as "outside the content" on tall windows.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(0, dismissed)
        assertEquals(1, clicked)
    }
}
