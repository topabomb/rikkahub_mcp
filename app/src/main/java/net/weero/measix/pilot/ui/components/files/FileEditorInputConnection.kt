package net.weero.measix.pilot.ui.components.files

import android.graphics.RectF
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextSnapshot
import android.view.inputmethod.TextBoundsInfoResult
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Bounds IME IPC, never the editor's document. Mutations retain the platform implementation. */
internal class FileEditorInputConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        super.getTextBeforeCursor(n.coerceAtMost(MAX_QUERY_CHARACTERS), flags)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        super.getTextAfterCursor(n.coerceAtMost(MAX_QUERY_CHARACTERS), flags)

    override fun getSelectedText(flags: Int): CharSequence? =
        super.getSelectedText(flags)?.takeIf { it.length <= MAX_QUERY_CHARACTERS }

    @RequiresApi(31)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
        super.getSurroundingText(
            beforeLength.coerceAtMost(MAX_QUERY_CHARACTERS / 2),
            afterLength.coerceAtMost(MAX_QUERY_CHARACTERS / 2),
            flags,
        )?.takeIf { it.text.length <= MAX_QUERY_CHARACTERS }

    @RequiresApi(33)
    override fun takeSnapshot(): TextSnapshot? =
        super.takeSnapshot()?.takeIf { it.surroundingText.text.length <= MAX_QUERY_CHARACTERS }

    // File editors do not offer full-document extraction. Returning null is the InputConnection
    // unsupported-query contract. Do not delegate MONITOR: TextView would push unbounded future
    // edits through updateExtractedText, bypassing these query bounds entirely.
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

    // Optional geometry can contain an entire composing span or per-character bounds. Never
    // register a monitor that could start sending unbounded data after the composition grows.
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    @RequiresApi(33)
    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean = false

    @RequiresApi(34)
    override fun requestTextBoundsInfo(bounds: RectF, executor: Executor, consumer: Consumer<TextBoundsInfoResult>) {
        executor.execute { consumer.accept(TextBoundsInfoResult(TextBoundsInfoResult.CODE_UNSUPPORTED)) }
    }

    companion object {
        internal const val MAX_QUERY_CHARACTERS = 4 * 1024
    }
}
