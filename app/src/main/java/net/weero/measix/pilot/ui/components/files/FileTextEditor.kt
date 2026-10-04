package net.weero.measix.pilot.ui.components.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.NoCopySpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.InputType
import android.text.style.ForegroundColorSpan
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.HighlightToken
import me.rerere.highlight.CodeHighlighter
import me.rerere.highlight.highlightTokenStyle
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import net.weero.measix.pilot.ui.theme.AtomOneDarkPalette
import net.weero.measix.pilot.ui.theme.AtomOneLightPalette

/** In-memory draft; the native editor and this state share the same editable buffer. */
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
    fileName: String? = null,
) {
    require(minLines >= 1 && maxLines >= minLines)
    val colors = MaterialTheme.colorScheme
    val borderColor = if (isError) colors.error else colors.outline
    val foreground = colors.onSurface.copy(alpha = if (enabled) 1f else .38f)
    val hintColor = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
    val textSizePx = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.fontSize.toPx() }
    val language = remember(fileName) { fileName?.let(::fileTextFormat)?.language }
    // The parser caches mutable matchers. Each source/language owns its worker instance so
    // cancelled work cannot race a new language or the synchronous message renderer.
    val highlighter = remember(state, language) { language?.let { CodeHighlighter() } }
    val palette = if (LocalDarkMode.current) AtomOneDarkPalette else AtomOneLightPalette
    var highlight by remember(state, language) { mutableStateOf<FileSyntaxHighlight?>(null) }
    LaunchedEffect(state, language, highlighter) {
        snapshotFlow { state.revision }.collectLatest { revision ->
            highlight = null
            if (language != null && highlighter != null && state.editable.length <= MAX_HIGHLIGHT_CHARACTERS) {
                // One serial calculation: quick input cancels the delay or waits for the bounded
                // previous parse to exit. Native text is only read on Main, never on the worker.
                delay(120)
                if (state.revision != revision || state.editable.length > MAX_HIGHLIGHT_CHARACTERS) {
                    return@collectLatest
                }
                val source = state.snapshot()
                val tokens = withContext(Dispatchers.Default) { highlighter.highlight(source, language) }
                if (state.revision == revision &&
                    tokens.count { it is HighlightToken.Styled } <= MAX_HIGHLIGHT_SPANS) {
                    highlight = FileSyntaxHighlight(revision, tokens)
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (fillViewport) Modifier.weight(1f) else Modifier)
                .then(if (fillViewport) Modifier else Modifier.border(BorderStroke(1.dp, borderColor), MaterialTheme.shapes.extraSmall))
                .padding(if (fillViewport) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            label?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (isError) colors.error else hintColor)
            }
            key(state) {
                AndroidView(
                    factory = { context -> FileEditText(context, state) },
                    onRelease = { view -> view.clearSyntaxHighlight() },
                    modifier = Modifier.fillMaxWidth().then(
                        if (fillViewport) Modifier.weight(1f).fillMaxSize() else Modifier
                    ),
                    update = { view ->
                        view.isEnabled = enabled
                        view.updateReadOnly(readOnly)
                        view.minLines = if (fillViewport) 1 else minLines
                        view.maxLines = if (fillViewport) Int.MAX_VALUE else maxLines
                        view.hint = placeholder
                        view.contentDescription = label
                        view.setTextColor(foreground.toArgb())
                        view.setHintTextColor(hintColor.toArgb())
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                        view.updateSyntaxHighlight(
                            highlight?.takeIf { enabled && it.revision == state.revision }, palette)
                    },
                )
            }
        }
        supportingText?.invoke()
    }
}

// Compose owns styling; file editing keeps the platform buffer and input protocol without
// AppCompat's emoji/content adapters. FileEditorInputConnection bounds the platform IME queries.
@SuppressLint("AppCompatCustomView")
internal class FileEditText(context: Context, state: FileEditorState) : EditText(context) {
    private val editingKeyListener: android.text.method.KeyListener
    private var currentReadOnly = false
    private var syntaxHighlight: FileSyntaxHighlight? = null
    private var syntaxPalette: HighlightTextColorPalette? = null

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

    internal fun updateSyntaxHighlight(highlight: FileSyntaxHighlight?, palette: HighlightTextColorPalette) {
        if (syntaxHighlight === highlight && syntaxPalette == palette) return
        clearSyntaxHighlight()
        syntaxHighlight = highlight
        syntaxPalette = palette
        var offset = 0
        highlight?.tokens?.forEach { token ->
            val end = offset + token.content.length
            if (token is HighlightToken.Styled && end > offset) {
                text.setSpan(
                    FileSyntaxColorSpan(highlightTokenStyle(token.type, palette).color.toArgb()),
                    offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            offset = end
        }
    }

    internal fun clearSyntaxHighlight() {
        // Only our draw spans are removed. Selection, IME composing spans and the buffer watcher
        // retain their native ownership; colours never create a text revision or affect saving.
        text.getSpans(0, text.length, FileSyntaxColorSpan::class.java).forEach(text::removeSpan)
        syntaxHighlight = null
        syntaxPalette = null
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

internal data class FileSyntaxHighlight(val revision: Int, val tokens: List<HighlightToken>)
internal class FileSyntaxColorSpan(color: Int) : ForegroundColorSpan(color), NoCopySpan

// Parsing and applying spans are bounded independently of the full editable document.
internal const val MAX_HIGHLIGHT_CHARACTERS = 128 * 1024
private const val MAX_HIGHLIGHT_SPANS = 10_000
