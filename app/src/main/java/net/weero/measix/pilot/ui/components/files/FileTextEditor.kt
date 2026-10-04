package net.weero.measix.pilot.ui.components.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    internal var beforeReplace: ((Int, Int, CharSequence, Int, Int) -> Unit)? = null
    internal val editable = object : SpannableStringBuilder(initialText) {
        override fun replace(start: Int, end: Int, source: CharSequence, sourceStart: Int, sourceEnd: Int): SpannableStringBuilder {
            beforeReplace?.invoke(start, end, source, sourceStart, sourceEnd)
            return super.replace(start, end, source, sourceStart, sourceEnd)
        }
    }
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
    var lineNumbers by rememberSaveable(fileName) { mutableStateOf(false) }
    val unwrapped = remember(state, state.revision) { hasLongFileLine(state.editable) }
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

    Column(modifier) {
        if (showNavigation) FileTextNavigation(state, nativeView, enabled, lineNumbers) { lineNumbers = !lineNumbers }
        if (showNavigation && language != null && state.editable.length > MAX_HIGHLIGHT_CHARACTERS) {
            Text(stringResource(R.string.file_large_plain_text), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (unwrapped) {
            Text(stringResource(R.string.file_long_lines_no_wrap), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Column(
            Modifier.fillMaxWidth()
                .then(if (fillViewport) Modifier.weight(1f) else Modifier)
                .then(if (fillViewport) Modifier else Modifier.border(BorderStroke(1.dp, borderColor), MaterialTheme.shapes.extraSmall))
                .padding(if (fillViewport) PaddingValues(horizontal = 8.dp, vertical = 2.dp) else PaddingValues(12.dp)),
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
                        state.beforeReplace = null
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
                        view.updateWrapping(unwrapped)
                        view.updateLineNumbers(showNavigation && lineNumbers, colors.onSurfaceVariant.toArgb(), state.revision)
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
private fun FileTextNavigation(state: FileEditorState, view: FileEditText?, enabled: Boolean,
    lineNumbers: Boolean, onLineNumbers: () -> Unit) {
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { finding = !finding }, enabled = enabled) {
                Text(stringResource(R.string.file_find))
            }
            TextButton(onClick = { lineNumber = ""; invalidLine = false; lineDialog = true }, enabled = enabled) {
                Text(stringResource(R.string.file_go_to_line))
            }
            TextButton(onClick = onLineNumbers, enabled = enabled) {
                Checkbox(checked = lineNumbers, onCheckedChange = null)
                Text(stringResource(R.string.file_line_numbers))
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

// Avoid expensive native line breaking for minified files while retaining the complete draft.
internal fun hasLongFileLine(text: CharSequence): Boolean {
    var length = 0
    for (character in text) {
        if (character == '\n') length = 0 else if (++length > 4096) return true
    }
    return false
}

// Only affected logical lines can acquire a new overflow; ordinary edits do not re-layout twice.
internal fun replacementHasLongFileLine(text: CharSequence, start: Int, end: Int,
    source: CharSequence, sourceStart: Int, sourceEnd: Int): Boolean {
    var length = 0
    var index = start - 1
    while (index >= 0 && text[index--] != '\n') if (++length > 4096) return true
    for (i in sourceStart until sourceEnd) {
        if (source[i] == '\n') length = 0 else if (++length > 4096) return true
    }
    index = end
    while (index < text.length && text[index++] != '\n') if (++length > 4096) return true
    return false
}

/** Built only when a numbered document changes, never once per scrolling frame. */
internal fun fileLineStarts(text: CharSequence): IntArray {
    val starts = IntArray(1 + text.count { it == '\n' })
    var next = 1
    for (index in text.indices) if (text[index] == '\n') starts[next++] = index + 1
    return starts
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
    private var numberedRevision = -1
    private var numberedLines = intArrayOf(0)
    private var showLineNumbers = false
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    internal fun updateLineNumbers(show: Boolean, color: Int, revision: Int) {
        if (show && (numberedRevision != revision || !showLineNumbers)) {
            numberedLines = fileLineStarts(text)
            numberedRevision = revision
        }
        showLineNumbers = show
        numberPaint.color = color
        numberPaint.typeface = typeface
        numberPaint.textSize = textSize * .85f
        val gap = (8 * resources.displayMetrics.density).toInt()
        val gutter = if (show) numberPaint.measureText(numberedLines.size.toString()).toInt() + gap * 2 else 0
        if (paddingLeft != gutter) setPadding(gutter, paddingTop, paddingRight, paddingBottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showLineNumbers) return
        val textLayout = layout ?: return
        val firstRow = textLayout.getLineForVertical((scrollY - totalPaddingTop).coerceAtLeast(0))
        val lastRow = textLayout.getLineForVertical(scrollY + height - totalPaddingTop)
        val offset = textLayout.getLineStart(firstRow)
        val found = numberedLines.binarySearch(offset)
        val first = if (found >= 0) found else -found - 1
        val end = textLayout.getLineEnd(lastRow)
        val x = scrollX + paddingLeft - 8 * resources.displayMetrics.density
        val checkpoint = canvas.save()
        canvas.clipRect(scrollX, scrollY + totalPaddingTop, scrollX + width, scrollY + height - totalPaddingBottom)
        for (index in first until numberedLines.size) {
            if (numberedLines[index] > end) break
            val row = textLayout.getLineForOffset(numberedLines[index].coerceAtMost(length()))
            canvas.drawText((index + 1).toString(), x, (totalPaddingTop + textLayout.getLineBaseline(row)).toFloat(), numberPaint)
        }
        canvas.restoreToCount(checkpoint)
    }

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
        state.beforeReplace = { start, end, source, sourceStart, sourceEnd ->
            if (!horizontalLayout && replacementHasLongFileLine(text, start, end, source, sourceStart, sourceEnd)) {
                // Rebuild the old layout before Editable captures its change
                // watchers. Switching in Compose is too late for a large paste's reflow.
                updateWrapping(true)
                if (width > 0 && height > 0) {
                    measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                    layout(left, top, right, bottom)
                }
            }
        }
    }

    private var horizontalLayout = false

    internal fun updateWrapping(horizontal: Boolean) {
        if (horizontalLayout == horizontal) return
        horizontalLayout = horizontal
        setHorizontallyScrolling(horizontal)
    }

    fun updateReadOnly(readOnly: Boolean) {
        if (currentReadOnly == readOnly) return
        stopScrolling()
        val oldScroll = scrollY
        val oldScrollX = scrollX
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
        scrollTo(oldScrollX, oldScroll)
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
                flingScroller.fling(scrollX, scrollY, 0, velocity, scrollX, scrollX, 0, maximumScrollY())
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
            scrollTo(flingScroller.currX, flingScroller.currY.coerceIn(0, maximumScrollY()))
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
        val availableWidth = (width - totalPaddingLeft - totalPaddingRight).coerceAtLeast(1)
        val left = textLayout.getPrimaryHorizontal(start).toInt()
        val targetX = if (left < scrollX || left >= scrollX + availableWidth) {
            (left - availableWidth / 3).coerceAtLeast(0)
        } else scrollX
        scrollTo(targetX, (textLayout.getLineTop(line) - height / 3).coerceIn(0, maximumScrollY()))
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
