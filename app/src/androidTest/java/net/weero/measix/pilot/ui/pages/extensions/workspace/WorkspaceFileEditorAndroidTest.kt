package net.weero.measix.pilot.ui.pages.extensions.workspace

import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dokar.sonner.rememberToasterState
import io.mockk.*
import me.rerere.workspace.WorkspaceStorageArea
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.workspace.*
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.files.FileSyntaxColorSpan
import net.weero.measix.pilot.ui.context.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceFileEditorAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun localSourceEditorKeepsExactDraftAndTargetAfterFailedSave() {
        val path = "src/page.html"
        val body = (1..100).joinToString("\r\n") { "<div data-id=\"$it\">中文 {{literal}}</div>" }
        val query = mockk<WorkspaceQueryService>()
        val application = mockk<WorkspaceApplicationService>()
        coEvery { query.readTextForPreview("workspace", WorkspaceStorageArea.FILES, path) } returns WorkspaceTextPreviewResult.Success(body)
        coEvery { application.writeText("workspace", path, body + "\nchanged") } throws java.io.IOException("source_save_failed")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf(Screen.Enterprise)),
                    LocalToaster provides rememberToasterState(),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                    WorkspaceFileEditorPage("workspace", WorkspaceStorageArea.FILES, path, application, query)
                }
            }
        }
        var editor: EditText? = null
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).isDisplayed() }
        onView(isAssignableFrom(EditText::class.java)).check { view, failure ->
            if (failure != null) throw failure
            editor = view as EditText
            assertEquals(body, editor!!.text.toString())
        }
        compose.waitUntil(5_000) {
            var coloured = false
            compose.runOnUiThread {
                val view = requireNotNull(editor)
                coloured = view.text.getSpans(0, view.length(), FileSyntaxColorSpan::class.java).isNotEmpty()
            }
            coloured
        }
        var initialHeight = 0
        compose.runOnIdle { initialHeight = requireNotNull(editor).height }
        onView(isAssignableFrom(EditText::class.java)).perform(click())
        compose.waitUntil(5_000) {
            var keyboardShown = false
            compose.runOnUiThread {
                keyboardShown = ViewCompat.getRootWindowInsets(requireNotNull(editor))?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            keyboardShown
        }
        compose.waitUntil(5_000) {
            var resized = false
            compose.runOnUiThread { resized = requireNotNull(editor).height < initialHeight }
            resized
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).assertIsDisplayed()
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText(body + "\nchanged"), closeSoftKeyboard())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).performClick()
        compose.onNodeWithText("source_save_failed", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
        onView(isAssignableFrom(EditText::class.java)).check { view, failure ->
            if (failure != null) throw failure
            assertEquals(body + "\nchanged", (view as EditText).text.toString())
        }
        coVerify(exactly = 1) { application.writeText("workspace", path, body + "\nchanged") }
        coVerify(exactly = 1) { query.readTextForPreview("workspace", WorkspaceStorageArea.FILES, path) }
    }
}
