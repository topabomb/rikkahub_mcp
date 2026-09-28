package net.weero.measix.pilot.ui.components.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.NoCopySpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.InputType
import android.text.method.ArrowKeyMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Composition-local draft; the native editor and this state share the same editable buffer. */
@Stable
class FileEditorState(initialText: String = "") {
    internal val editable = SpannableStringBuilder(initialText)
    var revision by mutableIntStateOf(0)
        private set

    init {
        val watcher = object : TextWatcher, NoCopySpan {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { revision++ }
        }
        editable.setSpan(watcher, 0, editable.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }

    fun snapshot(): String = editable.toString()

    fun replaceText(text: CharSequence) {
        if (text !== editable) editable.replace(0, editable.length, text)
    }
}

@Composable
fun FileTextEditor(
    state: FileEditorState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    minLines: Int = 5,
    maxLines: Int = 14,
    fillViewport: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
) {
    require(minLines >= 1 && maxLines >= minLines)
    val colors = MaterialTheme.colorScheme
    val borderColor = if (isError) colors.error else colors.outline
    val foreground = colors.onSurface.copy(alpha = if (enabled) 1f else .38f)
    val hintColor = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
    val textSizePx = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.fontSize.toPx() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (fillViewport) Modifier.weight(1f) else Modifier)
                .border(BorderStroke(1.dp, borderColor), MaterialTheme.shapes.extraSmall)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            label?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (isError) colors.error else hintColor)
            }
            key(state) {
                AndroidView(
                    factory = { context -> FileEditText(context, state) },
                    modifier = Modifier.fillMaxWidth().then(
                        if (fillViewport) Modifier.weight(1f).fillMaxSize() else Modifier
                    ),
                    update = { view ->
                        view.isEnabled = enabled
                        view.updateReadOnly(readOnly)
                        view.minLines = minLines
                        view.maxLines = maxLines
                        view.hint = placeholder
                        view.contentDescription = label
                        view.setTextColor(foreground.toArgb())
                        view.setHintTextColor(hintColor.toArgb())
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                    },
                )
            }
        }
        supportingText?.invoke()
    }
}

// Compose owns styling; plain file editing keeps the platform buffer and input protocol without
// AppCompat's emoji/content adapters. FileEditorInputConnection bounds the platform IME queries.
@SuppressLint("AppCompatCustomView")
internal class FileEditText(context: Context, state: FileEditorState) : EditText(context) {
    private val editingKeyListener: android.text.method.KeyListener
    private var currentReadOnly = false

    init {
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        typeface = Typeface.MONOSPACE
        background = null
        setPadding(0, 0, 0, 0)
        setHorizontallyScrolling(false)
        editingKeyListener = requireNotNull(keyListener)

        // setEditableFactory immediately rebinds the old (empty) View text. Only that first call
        // ignores its source; later native setText calls replace the same draft buffer.
        var binding = true
        setEditableFactory(object : Editable.Factory() {
            override fun newEditable(source: CharSequence): Editable {
                if (!binding) state.replaceText(source)
                return state.editable
            }
        })
        binding = false
    }

    fun updateReadOnly(readOnly: Boolean) {
        if (currentReadOnly == readOnly) return
        currentReadOnly = readOnly
        keyListener = if (readOnly) null else editingKeyListener
        setTextIsSelectable(readOnly)
        movementMethod = ArrowKeyMovementMethod.getInstance()
        isFocusableInTouchMode = true
        isCursorVisible = !readOnly
    }

    // Selectable/read-only mode normally changes the buffer type. Keep the same Editable owner.
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, BufferType.EDITABLE)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return FileEditorInputConnection(connection)
    }
}
