package net.weero.measix.pilot.ui.pages.extensions.workspace

import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceFileEditorAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useApplicationWindowPolicy() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun lineNumbersAreOptionalAndDoNotChangeLargeTextOrSelection() {
        val body = "{\"value\":\"" + "a".repeat(1_900_000) + "\"}\n"
        val query = mockk<WorkspaceQueryService>()
        val application = mockk<WorkspaceApplicationService>()
        coEvery { query.readTextForPreview("workspace", WorkspaceStorageArea.FILES, "large.json") } returns WorkspaceTextPreviewResult.Success(body)
        showPage("large.json", application, query)
        val label = compose.activity.getString(R.string.file_line_numbers)
        compose.onNodeWithText(compose.activity.getString(R.string.file_large_plain_text)).assertIsDisplayed()
        onView(isAssignableFrom(EditText::class.java)).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            assertEquals(0, editor.paddingLeft)
            editor.setSelection(12, 20)
        }
        compose.onNodeWithText(label).performClick()
        onView(isAssignableFrom(EditText::class.java)).check { view, error ->
            if (error != null) throw error
            val editor = view as EditText
            assertTrue(editor.paddingLeft > 0)
            assertEquals(body, editor.text.toString())
            assertEquals(12, editor.selectionStart)
            assertEquals(20, editor.selectionEnd)
            assertEquals(0, editor.text.getSpans(0, editor.length(), FileSyntaxColorSpan::class.java).size)
        }
        compose.onNodeWithText(label).performClick()
        onView(isAssignableFrom(EditText::class.java)).check { view, error ->
            if (error != null) throw error
            assertEquals(0, (view as EditText).paddingLeft)
            assertEquals(body, view.text.toString())
        }
    }

    @Test fun mountedEditorAcceptsLongLineReplacementWithoutLosingDraft() {
        val query = mockk<WorkspaceQueryService>()
        val application = mockk<WorkspaceApplicationService>()
        coEvery { query.readTextForPreview("workspace", WorkspaceStorageArea.FILES, "paste.txt") } returns
            WorkspaceTextPreviewResult.Success("short")
        showPage("paste.txt", application, query)
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        val body = "a".repeat(1_900_000)
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText(body), closeSoftKeyboard())
        onView(isAssignableFrom(EditText::class.java)).check { view, error ->
            if (error != null) throw error
            assertEquals(body, (view as EditText).text.toString())
        }
    }

    @Test fun savedMarkdownCanReturnToRenderedPreviewWithoutReopening() {
        val query = mockk<WorkspaceQueryService>()
        val application = mockk<WorkspaceApplicationService>()
        coEvery { query.readTextForPreview("workspace", WorkspaceStorageArea.FILES, "note.md") } returns
            WorkspaceTextPreviewResult.Success("# Original heading")
        coEvery { application.writeText("workspace", "note.md", "# Updated heading") } returns mockk<me.rerere.workspace.WorkspaceFileEntry>()
        showPage("note.md", application, query)
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText("# Updated heading"), closeSoftKeyboard())
        val preview = compose.activity.getString(R.string.file_preview_rendered)
        compose.onNodeWithText(preview).assertIsNotEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).performClick()
        compose.onNodeWithText(preview).assertIsEnabled().performClick()
        compose.onNodeWithText("Updated heading").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).assertIsDisplayed()
        coVerify(exactly = 1) { query.readTextForPreview(any(), any(), any()) }
    }

    @Test fun savedSvgRefreshFailurePreservesEditorAndRetryUsesNewSource() {
        val query = mockk<WorkspaceQueryService>()
        val application = mockk<WorkspaceApplicationService>()
        val original = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"40\"><rect width=\"80\" height=\"40\" fill=\"red\"/></svg>"
        val updated = original.replace("red", "blue")
        fun source(name: String, text: String) = net.weero.measix.pilot.service.ImageSource(
            name, net.weero.measix.pilot.service.ImageOrigin.LOCAL, displayName = name,
            verifyAccess = {}, readPayload = { text.toByteArray() }, mimeHint = "image/svg+xml")
        coEvery { query.readTextForPreview("workspace", WorkspaceStorageArea.FILES, "drawing.svg") } returns
            WorkspaceTextPreviewResult.Success(original)
        var reads = 0
        coEvery { application.previewImageSource("workspace", WorkspaceStorageArea.FILES, "drawing.svg") } coAnswers {
            when (++reads) {
                1 -> source("original.svg", original)
                2 -> throw java.io.IOException("preview_refresh_failed")
                else -> source("updated.svg", updated)
            }
        }
        coEvery { application.writeText("workspace", "drawing.svg", updated) } returns mockk<me.rerere.workspace.WorkspaceFileEntry>()
        showPage("drawing.svg", application, query)
        val imageClose = compose.activity.getString(R.string.image_viewer_close_content_description)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(imageClose).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(imageClose).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText(updated), closeSoftKeyboard())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_save)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.file_preview_rendered)).performClick()
        compose.onNodeWithText("preview_refresh_failed", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.update_card_close)).performClick()
        onView(isAssignableFrom(EditText::class.java)).check { view, error ->
            if (error != null) throw error
            assertEquals(updated, (view as EditText).text.toString())
            assertNotNull(view.keyListener)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.file_preview_rendered)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("updated.svg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("updated.svg").assertIsDisplayed()
        coVerify(exactly = 3) { application.previewImageSource("workspace", WorkspaceStorageArea.FILES, "drawing.svg") }
    }

    private fun showPage(path: String, application: WorkspaceApplicationService, query: WorkspaceQueryService) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides net.weero.measix.pilot.data.datastore.Settings.dummy(), LocalDarkMode provides false, LocalNavController provides Navigator(mutableListOf(Screen.Enterprise)),
                    LocalToaster provides rememberToasterState(),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                    WorkspaceFileEditorPage("workspace", WorkspaceStorageArea.FILES, path, application, query)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.edit)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.image_viewer_close_content_description)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun localSourceEditorKeepsExactDraftAndTargetAfterFailedSave() = withDockedKeyboard {
        val path = "src/page.html"
        val body = (1..100).joinToString("\r\n") { "<div data-id=\"$it\">中文 {{literal}}</div>" }
        val query = mockk<WorkspaceQueryService>()
        val application = mockk<WorkspaceApplicationService>()
        coEvery { query.readTextForPreview("workspace", WorkspaceStorageArea.FILES, path) } returns WorkspaceTextPreviewResult.Success(body)
        coEvery { application.writeText("workspace", path, body + "\nchanged") } throws java.io.IOException("source_save_failed")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides net.weero.measix.pilot.data.datastore.Settings.dummy(), LocalDarkMode provides false, LocalNavController provides Navigator(mutableListOf(Screen.Enterprise)),
                    LocalToaster provides rememberToasterState(),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                    WorkspaceFileEditorPage("workspace", WorkspaceStorageArea.FILES, path, application, query)
                }
            }
        }
        var editor: EditText? = null
        compose.waitUntil(5_000) { compose.onNodeWithText(compose.activity.getString(R.string.edit)).isDisplayed() }
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
        compose.onNodeWithText(compose.activity.getString(R.string.edit)).performClick()
        onView(isAssignableFrom(EditText::class.java)).perform(closeSoftKeyboard())
        compose.waitUntil(5_000) {
            var keyboardHidden = false
            compose.runOnUiThread {
                keyboardHidden = ViewCompat.getRootWindowInsets(requireNotNull(editor))?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            keyboardHidden
        }
        compose.waitForIdle()
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

    private fun withDockedKeyboard(block: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 34) return block()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val automation = instrumentation.uiAutomation
        val key = "stylus_handwriting_enabled"
        val original = android.provider.Settings.Secure.getString(resolver, key)
        fun setMode(value: String?) {
            try {
                automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                if (value == null) resolver.delete(android.provider.Settings.Secure.getUriFor(key), null, null)
                else assertTrue(android.provider.Settings.Secure.putString(resolver, key, value))
            } finally { automation.dropShellPermissionIdentity() }
        }
        try {
            // A floating handwriting IME can be visible without reducing the viewport.
            setMode("0")
            val inputMethod = requireNotNull(instrumentation.targetContext.getSystemService(android.view.inputmethod.InputMethodManager::class.java))
            compose.waitUntil(5_000) { !inputMethod.isStylusHandwritingAvailable }
            block()
        } finally {
            setMode(original)
            assertEquals(original, android.provider.Settings.Secure.getString(resolver, key))
        }
    }
}
