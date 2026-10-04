package net.weero.measix.pilot.ui.pages.extensions

import android.os.SystemClock
import android.view.MotionEvent
import net.weero.measix.pilot.ui.components.files.FileEditText
import net.weero.measix.pilot.ui.components.files.findFileText
import net.weero.measix.pilot.ui.components.files.fileLineOffset
import android.os.Parcel
import android.os.Build
import android.graphics.RectF
import android.content.ContentValues
import android.provider.MediaStore
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextBoundsInfoResult
import android.widget.EditText
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Selection
import android.view.inputmethod.BaseInputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import net.weero.measix.pilot.ui.components.files.FileEditorInputConnection
import net.weero.measix.pilot.ui.components.files.FileEditorState
import net.weero.measix.pilot.ui.components.files.FileTextEditor
import net.weero.measix.pilot.ui.components.files.FileSyntaxColorSpan
import net.weero.measix.pilot.ui.components.files.MAX_HIGHLIGHT_CHARACTERS
import net.weero.measix.pilot.ui.theme.AtomOneDarkPalette
import net.weero.measix.pilot.ui.theme.AtomOneLightPalette
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test fun readingFlingContinuesAfterReleaseAndStopsOnNewTouchWithoutEditing() {
        val body = (1..1000).joinToString("\n") { "line $it: source text" }
        val state = FileEditorState(body)
        compose.setContent {
            MaterialTheme { FileTextEditor(state, Modifier.fillMaxSize(), readOnly = true, fillViewport = true) }
        }
        val editor = nativeEditor()
        var releasedY = 0
        compose.runOnIdle {
            val now = SystemClock.uptimeMillis()
            val startY = editor.height * .8f
            fun dispatch(action: Int, time: Long, y: Float) {
                val event = MotionEvent.obtain(now - 80, time, action, editor.width / 2f, y, 0)
                try { editor.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN, now - 80, startY)
            dispatch(MotionEvent.ACTION_MOVE, now - 50, startY - 80)
            dispatch(MotionEvent.ACTION_MOVE, now - 20, startY - 240)
            dispatch(MotionEvent.ACTION_UP, now, startY - 280)
            releasedY = editor.scrollY
        }
        compose.waitUntil(3_000) {
            var moved = false
            compose.runOnUiThread { moved = editor.scrollY > releasedY + 10 }
            moved
        }
        var stoppedY = 0
        compose.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 40f, 40f, 0)
            try { editor.dispatchTouchEvent(down) } finally { down.recycle() }
            stoppedY = editor.scrollY
        }
        val observed = java.util.concurrent.CountDownLatch(1)
        compose.runOnUiThread { editor.postDelayed({ observed.countDown() }, 120) }
        assertTrue(observed.await(3, java.util.concurrent.TimeUnit.SECONDS))
        compose.runOnIdle {
            assertEquals(stoppedY, editor.scrollY)
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 40f, 40f, 0)
            try { editor.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            assertSame(state.editable, editor.text)
            assertEquals(body, state.snapshot())
            assertEquals(0, state.revision)
            assertNull(editor.onCreateInputConnection(EditorInfo()))
        }
    }

    @Test fun navigationUsesLogicalLinesAndSharedDraftWithoutCreatingEdits() {
        val state = FileEditorState("Alpha\r\nwrapped source\nalpha\n")
        compose.setContent {
            MaterialTheme { FileTextEditor(state, Modifier.fillMaxSize(), readOnly = true, fillViewport = true) }
        }
        val editor = nativeEditor() as FileEditText
        compose.runOnIdle {
            assertEquals(7, fileLineOffset(state.editable, 2))
            assertEquals(state.editable.length, fileLineOffset(state.editable, 4))
            assertEquals(-1, fileLineOffset(state.editable, 5))
            assertEquals(-1, fileLineOffset(state.editable, 0))
            val last = findFileText(state.editable, "ALPHA", -1, false)
            assertTrue(last > 7)
            editor.revealRange(last, last + 5)
            assertEquals("alpha", editor.text.subSequence(editor.selectionStart, editor.selectionEnd).toString())
            assertEquals(0, findFileText(state.editable, "alpha", editor.selectionEnd, true))
            assertEquals(0, state.revision)
            assertSame(state.editable, editor.text)
            state.replaceText("replacement")
            assertEquals(-1, findFileText(state.editable, "alpha", 0, true))
            assertEquals(-1, fileLineOffset(state.editable, 2))
        }
    }

    @Test fun viewportDrawsLongDocumentBelowItsMidpointAndScrollsInBothModes() {
        val body = (1..200).joinToString("\n") { "line $it: full source text" }
        val state = FileEditorState(body)
        val readOnly = mutableStateOf(true)
        compose.setContent {
            MaterialTheme { FileTextEditor(state, Modifier.fillMaxSize(), readOnly = readOnly.value, fillViewport = true) }
        }
        val editor = nativeEditor()
        fun assertDrawn() = compose.runOnIdle {
            assertTrue(editor.height > compose.activity.resources.displayMetrics.heightPixels / 2)
            val bitmap = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA)
                editor.draw(Canvas(bitmap))
                val drawn = (editor.height / 2 until editor.height - 16).sumOf { y ->
                    (0 until editor.width - 16).count { x -> bitmap.getPixel(x, y) != Color.MAGENTA }
                }
                assertTrue("Text must actually be painted in the bottom half, not just measured there", drawn > 100)
            } finally { bitmap.recycle() }
        }
        assertDrawn()
        capture("file-preview-viewport.png")
        onView(isAssignableFrom(EditText::class.java)).perform(swipeUp())
        compose.runOnIdle { assertTrue("Native preview must scroll", editor.scrollY > 0) }
        compose.runOnIdle { readOnly.value = false; editor.scrollTo(0, 0) }
        assertDrawn()
        capture("file-editor-viewport.png")
        onView(isAssignableFrom(EditText::class.java)).perform(click(), closeSoftKeyboard())
        compose.runOnIdle {
            assertTrue(editor.hasFocus())
            editor.setSelection(editor.length())
            editor.bringPointIntoView(editor.length())
        }
        // TextView animates bringPointIntoView after a swipe; Compose idleness does not wait
        // for its native Scroller. Wait for the actual final line to enter the viewport.
        compose.waitUntil(5_000) {
            var visible = false
            compose.runOnUiThread {
                visible = editor.layout.getLineBottom(editor.lineCount - 1) - editor.scrollY <= editor.height
            }
            visible
        }
        compose.runOnIdle { assertEquals(body, state.snapshot()) }
        capture("file-editor-end.png")
    }

    @Test fun syntaxColoursPreserveLiteralTextSelectionCompositionAndThemeWithoutCreatingEdits() {
        val body = "\"\"\"first\nimport inside string\nthird\"\"\"\nimport json\nvalue = 42\n"
        val state = FileEditorState(body)
        val dark = mutableStateOf(true)
        val name = mutableStateOf("server.py")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDarkMode provides dark.value) {
                    FileTextEditor(state, Modifier.fillMaxSize(), fillViewport = true, fileName = name.value)
                }
            }
        }
        val editor = nativeEditor()
        val position = body.indexOf("inside string")
        fun waitForColour(colour: Int) = compose.waitUntil(5_000) {
            var matched = false
            compose.runOnUiThread {
                matched = editor.text.getSpans(position, position + 1, FileSyntaxColorSpan::class.java)
                    .any { it.foregroundColor == colour }
            }
            matched
        }
        waitForColour(AtomOneDarkPalette.string.toArgb())
        compose.runOnIdle {
            assertEquals(0, state.revision)
            assertEquals(body, state.snapshot())
            assertSame(state.editable, editor.text)
            Selection.setSelection(editor.text, 2, 10)
            dark.value = false
        }
        waitForColour(AtomOneLightPalette.string.toArgb())
        compose.runOnIdle {
            assertEquals(2, editor.selectionStart)
            assertEquals(10, editor.selectionEnd)
            assertEquals(0, state.revision)
        }
        onView(isAssignableFrom(EditText::class.java)).perform(click(), closeSoftKeyboard())
        compose.runOnIdle {
            val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            connection.setSelection(editor.length(), editor.length())
            assertTrue(connection.setComposingText("中文", 1))
        }
        waitForColour(AtomOneLightPalette.string.toArgb())
        compose.runOnIdle {
            assertEquals(body + "中文", state.snapshot())
            assertEquals(body.length, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals(body.length + 2, BaseInputConnection.getComposingSpanEnd(editor.text))
            assertEquals(body.length + 2, editor.selectionEnd)
            name.value = "notes.txt"
        }
        compose.waitUntil(5_000) {
            var cleared = false
            compose.runOnUiThread { cleared = editor.text.getSpans(0, editor.length(), FileSyntaxColorSpan::class.java).isEmpty() }
            cleared
        }
        compose.runOnIdle {
            assertEquals(body + "中文", state.snapshot())
            assertEquals(body.length, BaseInputConnection.getComposingSpanStart(editor.text))
            editor.onCreateInputConnection(EditorInfo())!!.finishComposingText()
        }
    }

    private fun nativeEditor(): EditText {
        var result: EditText? = null
        onView(isAssignableFrom(EditText::class.java)).check { view, failure ->
            if (failure != null) throw failure
            result = view as EditText
        }
        return requireNotNull(result)
    }

    @Test fun changingSourceDiscardsOldRangesAndLargeDocumentsRetainCompletePlainText() {
        val state = FileEditorState(("{\"key\":\"中文\",\"value\":42}\n").repeat(600))
        compose.setContent { MaterialTheme { FileTextEditor(state, Modifier.fillMaxSize(), fillViewport = true, fileName = "data.json") } }
        val editor = nativeEditor()
        val current = "{\"final\": true}"
        compose.runOnIdle {
            state.replaceText("[" + "1234,".repeat(10_000) + "0]")
            state.replaceText(current)
        }
        compose.waitUntil(5_000) {
            var coloured = false
            compose.runOnUiThread { coloured = editor.text.getSpans(0, editor.length(), FileSyntaxColorSpan::class.java).isNotEmpty() }
            coloured
        }
        compose.runOnIdle {
            assertEquals(current, editor.text.toString())
            editor.text.getSpans(0, editor.length(), FileSyntaxColorSpan::class.java).forEach {
                assertTrue(editor.text.getSpanStart(it) >= 0)
                assertTrue(editor.text.getSpanEnd(it) <= current.length)
            }
        }
        val large = "\"" + "x".repeat(MAX_HIGHLIGHT_CHARACTERS + 1) + "\""
        compose.runOnIdle { state.replaceText(large) }
        compose.waitUntil(5_000) {
            var plain = false
            compose.runOnUiThread { plain = editor.text.getSpans(0, editor.length(), FileSyntaxColorSpan::class.java).isEmpty() }
            plain
        }
        compose.runOnIdle {
            assertEquals(large, state.snapshot())
            assertSame(state.editable, editor.text)
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${name.removeSuffix(".png")}-${java.util.UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CodexFileEditor")
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally { bitmap.recycle() }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun fullDocumentEditingAndImeQueriesUseOneBufferAndBoundedTransport() {
        val body = ("Saved state body ".repeat(100).take(1_499) + "\n").repeat(2_000)
        val state = FileEditorState(body)
        compose.setContent {
            MaterialTheme {
                FileTextEditor(state, Modifier.fillMaxSize(), fillViewport = true, fileName = "large.json")
            }
        }
        onView(isAssignableFrom(EditText::class.java)).perform(click(), replaceText(body + "draft"))
        onView(isAssignableFrom(EditText::class.java)).check { view, failure ->
            if (failure != null) throw failure
            val editor = view as EditText
            assertTrue(editor.hasFocus())
            assertSame(state.editable, editor.text)
            assertTrue(editor.text.getSpans(0, editor.length(), FileSyntaxColorSpan::class.java).isEmpty())
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
