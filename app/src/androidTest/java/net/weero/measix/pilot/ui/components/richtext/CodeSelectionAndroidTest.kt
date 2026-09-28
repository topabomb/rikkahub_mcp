package net.weero.measix.pilot.ui.components.richtext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodeSelectionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun wrappedCrossLineSelectionCopiesOnlyCodeAndToolbarCopyPreservesOriginalWhitespace() {
        val first = "alpha bravo charlie delta echo foxtrot golf hotel india juliet"
        val last = "  omega"
        val code = "$first\n$last"
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val settings = Settings.dummy().let { it.copy(displaySetting = it.displaySetting.copy(
            codeBlockAutoWrap = true, showLineNumbers = true, codeBlockAutoCollapse = false,
        )) }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides settings, LocalDarkMode provides false) {
                    HighlightCodeBlock(code, "text", Modifier.width(200.dp).testTag("code"))
                }
            }
        }
        fun layout(text: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val firstLayout = layout(first)
        val lastLayout = layout(last)
        assertTrue("fixture must actually wrap", firstLayout.lineCount > 1)
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        val rootBounds = compose.onNodeWithTag("code").fetchSemanticsNode().boundsInRoot
        val firstBounds = compose.onNodeWithText(first).fetchSemanticsNode().boundsInRoot
        val lastBounds = compose.onNodeWithText(last).fetchSemanticsNode().boundsInRoot
        val start = firstBounds.topLeft + firstLayout.getBoundingBox(0).center - rootBounds.topLeft
        val endBox = lastLayout.getBoundingBox(last.lastIndex)
        val end = lastBounds.topLeft + Offset(endBox.right + 8f, endBox.center.y) - rootBounds.topLeft
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("before", "sentinel")) }
        compose.onNodeWithTag("code").performTouchInput {
            down(start)
            moveTo(start, delayMillis = 800)
            moveTo(end, delayMillis = 500)
            up()
        }
        compose.onNodeWithTag("code").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.C)
            keyUp(Key.CtrlLeft)
        }
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() != "sentinel" }
        compose.runOnIdle { assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("before", "sentinel")) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.code_block_copy)).performClick()
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == code }
    }
}
