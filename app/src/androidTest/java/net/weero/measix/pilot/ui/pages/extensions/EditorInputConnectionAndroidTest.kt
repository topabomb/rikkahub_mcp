package net.weero.measix.pilot.ui.pages.extensions

import android.os.Parcel
import android.os.Build
import android.graphics.RectF
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextBoundsInfoResult
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import net.weero.measix.pilot.ui.components.ui.FileEditorInputConnection
import net.weero.measix.pilot.ui.components.ui.FileEditorState
import net.weero.measix.pilot.ui.components.ui.FileTextEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorInputConnectionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun fullDocumentEditingAndImeQueriesUseOneBufferAndBoundedTransport() {
        val body = ("Saved state body ".repeat(100).take(1_499) + "\n").repeat(2_000)
        val state = FileEditorState(body)
        compose.setContent {
            MaterialTheme {
                FileTextEditor(state, Modifier.fillMaxSize(), minLines = 1, maxLines = Int.MAX_VALUE, fillViewport = true)
            }
        }
        onView(isAssignableFrom(EditText::class.java)).perform(click(), replaceText(body + "draft"))
        onView(isAssignableFrom(EditText::class.java)).check { view, failure ->
            if (failure != null) throw failure
            val editor = view as EditText
            assertTrue(editor.hasFocus())
            assertSame(state.editable, editor.text)
            assertEquals(body + "draft", state.snapshot())
            assertFalse(editor.isSaveEnabled)
            assertFalse(editor.isSaveFromParentEnabled)
            assertEquals(InputType.TYPE_CLASS_TEXT, editor.inputType and InputType.TYPE_MASK_CLASS)
            assertTrue("Full-page body must occupy the viewport", editor.height > compose.activity.resources.displayMetrics.heightPixels / 2)
            val info = EditorInfo()
            val connection = requireNotNull(editor.onCreateInputConnection(info))
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
            val limit = FileEditorInputConnection.MAX_QUERY_CHARACTERS
            val initial = requireNotNull(info.getInitialTextBeforeCursor(Int.MAX_VALUE, 0))
            assertTrue(initial.length <= limit)

            connection.setSelection(editor.length(), editor.length())
            val before = requireNotNull(connection.getTextBeforeCursor(Int.MAX_VALUE, 0))
            assertEquals(state.snapshot().takeLast(limit), before.toString())
            val parcel = Parcel.obtain()
            try {
                parcel.writeString(before.toString())
                assertTrue("IME reply must stay well below Binder limits", parcel.dataSize() < 16 * 1024)
            } finally {
                parcel.recycle()
            }
            connection.setSelection(0, 0)
            assertEquals(body.take(limit), connection.getTextAfterCursor(Int.MAX_VALUE, 0).toString())
            val surrounding = requireNotNull(connection.getSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE, 0))
            assertTrue(surrounding.text.length <= limit)
            assertEquals(0, surrounding.selectionStart)
            assertEquals(0, surrounding.selectionEnd)
            assertEquals(0, surrounding.offset)

            connection.setSelection(0, editor.length())
            assertNull(connection.getSelectedText(0))
            assertNull(connection.getSurroundingText(10, 10, 0))
            assertNull(connection.takeSnapshot())
            assertEquals(editor.length(), editor.selectionEnd)
            assertNull(connection.getExtractedText(ExtractedTextRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR))

            val updates = InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
            assertFalse(connection.requestCursorUpdates(updates))
            assertTrue(connection.setComposingRegion(0, editor.length()))
            assertFalse(connection.requestCursorUpdates(updates, InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS))
            if (Build.VERSION.SDK_INT >= 34) {
                var dispatched = false
                var result: TextBoundsInfoResult? = null
                connection.requestTextBoundsInfo(
                    RectF(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE),
                    { task -> dispatched = true; task.run() },
                    { result = it },
                )
                assertTrue(dispatched)
                assertEquals(TextBoundsInfoResult.CODE_UNSUPPORTED, requireNotNull(result).resultCode)
                assertNull(requireNotNull(result).textBoundsInfo)
            }
            assertTrue(connection.finishComposingText())

            connection.setSelection(editor.length(), editor.length())
            assertTrue(connection.setComposingText("composing", 1))
            assertTrue(connection.commitText("\uD83E\uDDEA", 1))
            assertTrue(connection.finishComposingText())
            assertEquals(body + "draft\uD83E\uDDEA", state.snapshot())
            assertSame(state.editable, editor.text)
            assertTrue(state.revision > 0)
        }
    }

    @Test
    fun readOnlyTransitionsPreserveDraftAndRestoreOrdinaryInput() {
        val state = FileEditorState("published")
        val readOnly = mutableStateOf(false)
        compose.setContent {
            MaterialTheme { FileTextEditor(state, Modifier.fillMaxSize(), readOnly = readOnly.value) }
        }
        onView(isAssignableFrom(EditText::class.java)).perform(click(), replaceText("retained draft"))
        compose.runOnIdle { readOnly.value = true }
        onView(isAssignableFrom(EditText::class.java)).check { view, failure ->
            if (failure != null) throw failure
            val editor = view as EditText
            assertSame(state.editable, editor.text)
            assertNull(editor.keyListener)
            assertNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(editor.isTextSelectable)
            assertEquals("retained draft", state.snapshot())
        }
        compose.runOnIdle { readOnly.value = false }
        onView(isAssignableFrom(EditText::class.java)).perform(click(), replaceText("editable again"))
        onView(isAssignableFrom(EditText::class.java)).check { view, failure ->
            if (failure != null) throw failure
            val editor = view as EditText
            assertNotNull(editor.keyListener)
            assertFalse(editor.isTextSelectable)
            assertSame(state.editable, editor.text)
            assertEquals("editable again", state.snapshot())
            val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            connection.setSelection(editor.length(), editor.length())
            assertTrue(connection.commitText("\ninput restored", 1))
            assertEquals("editable again\ninput restored", state.snapshot())
        }
    }
}
