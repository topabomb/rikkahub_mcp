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
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
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
import net.weero.measix.pilot.R
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
    showNavigation: Boolean = false,
) {
    require(minLines >= 1 && maxLines >= minLines)
    var nativeView by remember(state) { mutableStateOf<FileEditText?>(null) }
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
        if (showNavigation) FileTextNavigation(state, nativeView, enabled)
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
                    factory = { context -> FileEditText(context, state).also { nativeView = it } },
                    onRelease = { view ->
                        view.stopScrolling()
                        view.clearSyntaxHighlight()
                        if (nativeView === view) nativeView = null
                    },
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

@Composable
private fun FileTextNavigation(state: FileEditorState, view: FileEditText?, enabled: Boolean) {
    var finding by remember(state) { mutableStateOf(false) }
    var query by remember(state) { mutableStateOf("") }
    var noMatch by remember(state) { mutableStateOf(false) }
    var lineDialog by remember(state) { mutableStateOf(false) }
    var lineNumber by remember(state) { mutableStateOf("") }
    var invalidLine by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state.revision, query) { noMatch = false }
    fun find(forward: Boolean) {
        val editor = view ?: return
        val offset = if (forward) editor.selectionEnd.coerceAtLeast(0)
            else editor.selectionStart.coerceAtLeast(0) - 1
        val match = findFileText(state.editable, query, offset, forward)
        noMatch = match < 0
        if (match >= 0) editor.revealRange(match, match + query.length)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { finding = !finding }, enabled = enabled) {
                Text(stringResource(R.string.file_find))
            }
            TextButton(onClick = { lineNumber = ""; invalidLine = false; lineDialog = true }, enabled = enabled) {
                Text(stringResource(R.string.file_go_to_line))
            }
        }
        if (finding) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.file_find)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                isError = noMatch,
                supportingText = if (noMatch) ({ Text(stringResource(R.string.file_find_no_match)) }) else null,
            )
            Row {
                TextButton(onClick = { find(false) }, enabled = enabled && query.isNotEmpty()) {
                    Text(stringResource(R.string.file_find_previous))
                }
                TextButton(onClick = { find(true) }, enabled = enabled && query.isNotEmpty()) {
                    Text(stringResource(R.string.file_find_next))
                }
                TextButton(onClick = { finding = false }) { Text(stringResource(R.string.update_card_close)) }
            }
        }
    }
    if (lineDialog) AlertDialog(
        onDismissRequest = { lineDialog = false },
        title = { Text(stringResource(R.string.file_go_to_line)) },
        text = {
            OutlinedTextField(
                value = lineNumber,
                onValueChange = { lineNumber = it; invalidLine = false },
                singleLine = true,
                label = { Text(stringResource(R.string.file_line_number)) },
                isError = invalidLine,
                supportingText = if (invalidLine) ({ Text(stringResource(R.string.file_line_invalid)) }) else null,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val offset = fileLineOffset(state.editable, lineNumber.toIntOrNull() ?: 0)
                invalidLine = offset < 0
                if (offset >= 0) { view?.revealRange(offset); lineDialog = false }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { lineDialog = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Literal, case-insensitive search without a second document copy or persistent index. */
internal fun findFileText(text: CharSequence, query: String, from: Int, forward: Boolean): Int {
    if (query.isEmpty()) return -1
    return if (forward) {
        text.indexOf(query, from.coerceAtLeast(0), ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf(query, ignoreCase = true)
    } else {
        (if (from >= 0) text.lastIndexOf(query, from, ignoreCase = true) else -1).takeIf { it >= 0 }
            ?: text.lastIndexOf(query, ignoreCase = true)
    }
}

/** Logical source lines, independent of wrapping width. A trailing newline has an empty last line. */
internal fun fileLineOffset(text: CharSequence, line: Int): Int {
    if (line < 1) return -1
    if (line == 1) return 0
    var current = 1
    for (index in text.indices) if (text[index] == '\n' && ++current == line) return index + 1
    return -1
}

// Compose owns styling; file editing keeps the platform buffer and input protocol without
// AppCompat's emoji/content adapters. FileEditorInputConnection bounds the platform IME queries.
@SuppressLint("AppCompatCustomView")
internal class FileEditText(context: Context, state: FileEditorState) : EditText(context) {
    private val editingKeyListener: android.text.method.KeyListener
    private val flingScroller = OverScroller(context)
    private val touchConfiguration = ViewConfiguration.get(context)
    private var velocityTracker: VelocityTracker? = null
    private var downY = 0f
    private var dragged = false
    private var selecting = false
    private var touchRevision = 0
    private val draft = state
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
        stopScrolling()
        val oldScroll = scrollY
        val oldStart = selectionStart
        val oldEnd = selectionEnd
        currentReadOnly = readOnly
        keyListener = if (readOnly) null else editingKeyListener
        setTextIsSelectable(readOnly)
        movementMethod = ArrowKeyMovementMethod.getInstance()
        isFocusableInTouchMode = true
        isCursorVisible = !readOnly
        showSoftInputOnFocus = !readOnly
        if (oldStart >= 0 && oldEnd >= 0) setSelection(oldStart.coerceAtMost(length()), oldEnd.coerceAtMost(length()))
        scrollTo(0, oldScroll)
    }

    internal fun stopScrolling() {
        flingScroller.forceFinished(true)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun performLongClick(): Boolean {
        selecting = true
        return super.performLongClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopScrolling()
                downY = event.y
                dragged = false
                selecting = hasSelection()
                touchRevision = draft.revision
                velocityTracker = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_POINTER_DOWN -> selecting = true
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.y - downY) > touchConfiguration.scaledTouchSlop) dragged = true
            }
        }
        velocityTracker?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && dragged && !selecting &&
            !hasSelection() && touchRevision == draft.revision) {
            val tracker = velocityTracker
            tracker?.computeCurrentVelocity(1000, touchConfiguration.scaledMaximumFlingVelocity.toFloat())
            val velocity = -(tracker?.yVelocity ?: 0f).toInt()
            // Native movement owns dragging. Cancel its UP so reading does not relocate the
            // cursor or open the keyboard; only the inertial continuation belongs here.
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
            stopScrolling()
            if (kotlin.math.abs(velocity) >= touchConfiguration.scaledMinimumFlingVelocity) {
                flingScroller.fling(0, scrollY, 0, velocity, 0, 0, 0, maximumScrollY())
                postInvalidateOnAnimation()
            }
            return true
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocityTracker?.recycle()
            velocityTracker = null
        }
        return handled
    }

    private fun maximumScrollY(): Int =
        ((layout?.height ?: 0) - (height - totalPaddingTop - totalPaddingBottom)).coerceAtLeast(0)

    override fun computeScroll() {
        super.computeScroll()
        if (!flingScroller.isFinished && draft.revision != touchRevision) flingScroller.forceFinished(true)
        if (flingScroller.computeScrollOffset()) {
            scrollTo(0, flingScroller.currY.coerceIn(0, maximumScrollY()))
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        stopScrolling()
        super.onDetachedFromWindow()
    }

    internal fun revealRange(start: Int, end: Int = start) {
        stopScrolling()
        setSelection(start, end)
        val textLayout = layout ?: return
        val line = textLayout.getLineForOffset(start)
        scrollTo(0, (textLayout.getLineTop(line) - height / 3).coerceIn(0, maximumScrollY()))
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
