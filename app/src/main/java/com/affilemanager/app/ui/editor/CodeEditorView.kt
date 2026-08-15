package com.affilemanager.app.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import com.affilemanager.app.editing.EditLimits

data class EditorColors(
    val background: Int,
    val foreground: Int,
    val gutterBackground: Int,
    val gutterForeground: Int,
    val keyword: Int,
    val string: Int,
    val comment: Int,
    val number: Int,
    val heading: Int,
    val tag: Int,
    val searchMatch: Int,
    val currentSearchMatch: Int,
)

data class EditorCursor(
    val line: Int,
    val column: Int,
    val selectedCharacters: Int,
    val totalLines: Int,
)

data class EditorSearchStatus(
    val current: Int,
    val total: Int,
    val limited: Boolean,
)

enum class EditorShortcut {
    SAVE,
    SAVE_AS,
    FIND,
    REPLACE,
    GO_TO_LINE,
}

class CodeEditorController {
    private var view: CodeEditorView? = null

    internal fun attach(editorView: CodeEditorView) {
        view = editorView
    }

    internal fun detach(editorView: CodeEditorView) {
        if (view === editorView) view = null
    }

    internal fun dispose() {
        view?.dispose()
        view = null
    }

    fun undo() = view?.undo()
    fun redo() = view?.redo()
    fun findNext(forward: Boolean = true) = view?.findNext(forward)
    fun replaceCurrent(replacement: String): Boolean = view?.replaceCurrent(replacement) ?: false
    fun replaceAll(replacement: String): Int = view?.replaceAll(replacement) ?: 0
    fun goToLine(line: Int) = view?.goToLine(line)
    fun requestFocus() = view?.requestEditorFocus()
    fun replaceTextFromAccessibility(text: String): Boolean = view?.replaceTextFromAccessibility(text) ?: false
    internal fun setTextFromModel(text: String) = view?.setTextFromModel(text)
    internal fun setReadOnly(readOnly: Boolean) = view?.setReadOnly(readOnly)
    internal fun setWordWrap(enabled: Boolean) = view?.setWordWrap(enabled)
    internal fun setFontSizeSp(size: Float) = view?.setFontSizeSp(size)
    internal fun setLanguage(language: EditorLanguage) = view?.setLanguage(language)
    internal fun applyColors(colors: EditorColors) = view?.applyColors(colors)
    internal fun setSearch(query: String, matchCase: Boolean, wholeWord: Boolean) =
        view?.setSearch(query, matchCase, wholeWord)
    internal fun setEditorContentDescription(description: String) {
        view?.setEditorContentDescription(description)
    }
}

class CodeEditorView(context: Context) : LinearLayout(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val history = EditorHistory(maximumStoredBytes = 16 * 1_024 * 1_024)
    private val editor = AppCompatEditText(context)
    private val gutter = LineNumberView(context, editor)
    private var colors = defaultColors()
    private var language = EditorLanguage.PLAIN_TEXT
    private var wordWrapEnabled = true
    private var fontSizeSp = DEFAULT_FONT_SIZE_SP
    private var readOnlyEnabled = false
    private var lineStarts = intArrayOf(0)
    private var pendingStart = 0
    private var pendingRemoved = ""
    private var internalHistoryChange = false
    private var suppressModelCallback = false
    private var suppressUiCallbacks = false
    private var disposed = false
    private var textRevision = 0
    private var searchQuery = ""
    private var searchMatchCase = false
    private var searchWholeWord = false
    private var searchMatches: List<EditorMatch> = emptyList()
    private var currentSearchIndex = -1

    var onTextChanged: (String) -> Unit = {}
    var onCursorChanged: (EditorCursor) -> Unit = {}
    var onHistoryChanged: (Boolean, Boolean) -> Unit = { _, _ -> }
    var onSearchChanged: (EditorSearchStatus) -> Unit = {}
    var onShortcut: (EditorShortcut) -> Unit = {}

    private val highlightRunnable = Runnable {
        if (disposed) return@Runnable
        val revision = textRevision
        val snapshot = editor.text?.toString().orEmpty()
        val spans = EditorSyntaxHighlighter.highlight(snapshot, language)
        if (revision == textRevision) applySyntaxSpans(spans)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        setBackgroundColor(colors.background)

        gutter.layoutParams = LayoutParams(dp(48), LayoutParams.MATCH_PARENT)
        addView(gutter)

        editor.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        editor.gravity = Gravity.TOP or Gravity.START
        editor.typeface = Typeface.MONOSPACE
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, DEFAULT_FONT_SIZE_SP)
        editor.setLineSpacing(0f, 1.08f)
        editor.setPadding(dp(10), dp(8), dp(12), dp(24))
        editor.includeFontPadding = false
        editor.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        editor.setHorizontallyScrolling(false)
        editor.setTextIsSelectable(true)
        editor.setBackgroundColor(colors.background)
        editor.setTextColor(colors.foreground)
        editor.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN && handleEditorKey(keyCode, event)
        }
        editor.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                editor.post { if (!disposed) notifyCursor() }
            }
            false
        }
        editor.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> notifyCursor() }
        editor.setOnScrollChangeListener { _, _, _, _, _ -> gutter.invalidate() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {
                pendingStart = start
                pendingRemoved = text?.subSequence(start, (start + count).coerceAtMost(text.length))?.toString().orEmpty()
            }

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (!internalHistoryChange) {
                    val inserted = text?.subSequence(start, (start + count).coerceAtMost(text.length))?.toString().orEmpty()
                    history.record(EditorDelta(pendingStart, pendingRemoved, inserted))
                }
            }

            override fun afterTextChanged(text: Editable?) {
                textRevision += 1
                rebuildLineStarts(text)
                scheduleHighlight()
                refreshSearch()
                notifyHistory()
                notifyCursor()
                if (!suppressModelCallback) onTextChanged(text?.toString().orEmpty())
            }
        })
        addView(editor)
        applyColors(colors)
    }

    fun setTextFromModel(text: String) {
        if (editor.text?.toString() == text) return
        suppressModelCallback = true
        suppressUiCallbacks = true
        internalHistoryChange = true
        try {
            editor.setText(text)
            editor.setSelection(text.length.coerceAtMost(editor.length()))
            history.clear()
            notifyHistory()
        } finally {
            internalHistoryChange = false
            suppressUiCallbacks = false
            suppressModelCallback = false
        }
        mainHandler.post {
            if (!disposed) {
                notifyHistory()
                notifyCursor()
                notifySearch()
            }
        }
    }

    fun setReadOnly(readOnly: Boolean) {
        if (readOnlyEnabled == readOnly) return
        readOnlyEnabled = readOnly
        editor.isEnabled = !readOnly
        editor.isFocusable = !readOnly
        editor.isFocusableInTouchMode = !readOnly
        editor.alpha = if (readOnly) 0.72f else 1f
    }

    fun setEditorContentDescription(description: String) {
        editor.contentDescription = description
    }

    fun setWordWrap(enabled: Boolean) {
        if (wordWrapEnabled == enabled) return
        wordWrapEnabled = enabled
        editor.setHorizontallyScrolling(!enabled)
        editor.requestLayout()
        gutter.invalidate()
    }

    fun setFontSizeSp(size: Float) {
        val bounded = size.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        if (fontSizeSp == bounded) return
        fontSizeSp = bounded
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, bounded)
        gutter.textSizePx = editor.textSize * 0.72f
        gutter.invalidate()
    }

    fun setLanguage(value: EditorLanguage) {
        if (language == value) return
        language = value
        scheduleHighlight(immediate = true)
    }

    fun applyColors(value: EditorColors) {
        if (colors == value) return
        colors = value
        setBackgroundColor(value.background)
        editor.setBackgroundColor(value.background)
        editor.setTextColor(value.foreground)
        gutter.gutterBackgroundColor = value.gutterBackground
        gutter.foregroundColor = value.gutterForeground
        scheduleHighlight(immediate = true)
        applySearchSpans()
    }

    fun setSearch(query: String, matchCase: Boolean, wholeWord: Boolean) {
        if (searchQuery == query && searchMatchCase == matchCase && searchWholeWord == wholeWord) return
        searchQuery = query
        searchMatchCase = matchCase
        searchWholeWord = wholeWord
        suppressUiCallbacks = true
        try {
            refreshSearch()
        } finally {
            suppressUiCallbacks = false
        }
        mainHandler.post { if (!disposed) notifySearch() }
    }

    fun findNext(forward: Boolean = true) {
        if (searchMatches.isEmpty()) return
        val selectionStart = editor.selectionStart.coerceAtLeast(0)
        currentSearchIndex = if (forward) {
            searchMatches.indexOfFirst { it.start > selectionStart }
                .takeIf { it >= 0 } ?: 0
        } else {
            searchMatches.indexOfLast { it.endExclusive < selectionStart }
                .takeIf { it >= 0 } ?: searchMatches.lastIndex
        }
        selectCurrentMatch()
    }

    fun replaceCurrent(replacement: String): Boolean {
        if (searchQuery.isEmpty()) return false
        if (searchMatches.isEmpty()) refreshSearch()
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(0)
        val selected = if (start <= end) editor.text?.subSequence(start, end)?.toString().orEmpty() else ""
        val selectionMatches = selected.equals(searchQuery, ignoreCase = !searchMatchCase) &&
            (!searchWholeWord || EditorSearch.findMatches(selected, searchQuery, searchMatchCase, true).isNotEmpty())
        val target = if (selectionMatches) EditorMatch(start, end) else {
            findNext(forward = true)
            currentSearchMatch() ?: return false
        }
        editor.text?.replace(target.start, target.endExclusive, replacement)
        editor.setSelection((target.start + replacement.length).coerceAtMost(editor.length()))
        refreshSearch()
        return true
    }

    fun replaceAll(replacement: String): Int {
        if (searchQuery.isEmpty()) return 0
        val current = editor.text?.toString().orEmpty()
        val result = EditorSearch.replaceAll(
            text = current,
            query = searchQuery,
            replacement = replacement,
            matchCase = searchMatchCase,
            wholeWord = searchWholeWord,
            maximumChars = EditLimits.MAX_TEXT_CHARS,
        )
        if (result.replacements == 0) return 0
        editor.setText(result.text)
        editor.setSelection(editor.length())
        refreshSearch()
        return result.replacements
    }

    fun undo() {
        history.undo(editor.text?.toString().orEmpty())?.let(::applyHistoryResult)
    }

    fun redo() {
        history.redo(editor.text?.toString().orEmpty())?.let(::applyHistoryResult)
    }

    fun goToLine(requestedLine: Int) {
        if (lineStarts.isEmpty()) return
        val index = requestedLine.coerceIn(1, lineStarts.size) - 1
        val offset = lineStarts[index].coerceIn(0, editor.length())
        editor.requestFocus()
        editor.setSelection(offset)
        editor.bringPointIntoView(offset)
    }

    fun requestEditorFocus() {
        editor.requestFocus()
    }

    fun replaceTextFromAccessibility(text: String): Boolean {
        if (readOnlyEnabled || text.length > EditLimits.MAX_TEXT_CHARS) return false
        editor.setText(text)
        editor.setSelection(editor.length())
        return true
    }

    fun dispose() {
        disposed = true
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun handleEditorKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> if (event.isShiftPressed) redo() else undo()
                KeyEvent.KEYCODE_Y -> redo()
                KeyEvent.KEYCODE_S -> onShortcut(if (event.isShiftPressed) EditorShortcut.SAVE_AS else EditorShortcut.SAVE)
                KeyEvent.KEYCODE_F -> onShortcut(EditorShortcut.FIND)
                KeyEvent.KEYCODE_H -> onShortcut(EditorShortcut.REPLACE)
                KeyEvent.KEYCODE_G -> onShortcut(EditorShortcut.GO_TO_LINE)
                else -> return false
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            val start = editor.selectionStart.coerceAtLeast(0)
            val end = editor.selectionEnd.coerceAtLeast(0)
            editor.text?.replace(minOf(start, end), maxOf(start, end), "    ")
            return true
        }
        editor.post { if (!disposed) notifyCursor() }
        return false
    }

    private fun applyHistoryResult(result: EditorHistoryResult) {
        internalHistoryChange = true
        try {
            editor.setText(result.text)
            editor.setSelection(result.selection.coerceIn(0, editor.length()))
        } finally {
            internalHistoryChange = false
        }
        notifyHistory()
    }

    private fun rebuildLineStarts(text: CharSequence?) {
        val value = text ?: ""
        val starts = ArrayList<Int>((value.length / 32).coerceAtLeast(1))
        starts += 0
        value.forEachIndexed { index, character -> if (character == '\n' && index + 1 <= value.length) starts += index + 1 }
        lineStarts = starts.toIntArray()
        gutter.lineStarts = lineStarts
        val digits = lineStarts.size.toString().length.coerceAtLeast(2)
        val requiredWidth = (editor.textSize * 0.72f * (digits + 1.7f)).toInt().coerceAtLeast(dp(42))
        if (gutter.layoutParams.width != requiredWidth) {
            gutter.layoutParams = gutter.layoutParams.apply { width = requiredWidth }
        }
        gutter.invalidate()
    }

    private fun notifyCursor() {
        if (suppressUiCallbacks || disposed) return
        val position = editor.selectionStart.coerceIn(0, editor.length())
        val found = lineStarts.binarySearch(position)
        val lineIndex = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
        onCursorChanged(
            EditorCursor(
                line = lineIndex + 1,
                column = position - lineStarts[lineIndex] + 1,
                selectedCharacters = kotlin.math.abs(editor.selectionEnd - editor.selectionStart),
                totalLines = lineStarts.size,
            ),
        )
    }

    private fun notifyHistory() {
        if (suppressUiCallbacks || disposed) return
        onHistoryChanged(history.canUndo, history.canRedo)
    }

    private fun scheduleHighlight(immediate: Boolean = false) {
        mainHandler.removeCallbacks(highlightRunnable)
        if (disposed) return
        mainHandler.postDelayed(highlightRunnable, if (immediate) 0L else HIGHLIGHT_DELAY_MS)
    }

    private fun applySyntaxSpans(spans: List<SyntaxSpan>) {
        val editable = editor.text ?: return
        editable.getSpans(0, editable.length, EditorSyntaxSpan::class.java).forEach(editable::removeSpan)
        spans.forEach { span ->
            if (span.start < 0 || span.endExclusive > editable.length || span.start >= span.endExclusive) return@forEach
            editable.setSpan(
                EditorSyntaxSpan(colorFor(span.kind)),
                span.start,
                span.endExclusive,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun refreshSearch() {
        val text = editor.text?.toString().orEmpty()
        searchMatches = EditorSearch.findMatches(text, searchQuery, searchMatchCase, searchWholeWord)
        currentSearchIndex = searchMatches.indexOfFirst { match ->
            editor.selectionStart in match.start..match.endExclusive
        }
        applySearchSpans()
        notifySearch()
    }

    private fun applySearchSpans() {
        val editable = editor.text ?: return
        editable.getSpans(0, editable.length, EditorSearchSpan::class.java).forEach(editable::removeSpan)
        searchMatches.take(MAX_VISIBLE_SEARCH_HIGHLIGHTS).forEachIndexed { index, match ->
            if (match.endExclusive <= editable.length) {
                editable.setSpan(
                    EditorSearchSpan(if (index == currentSearchIndex) colors.currentSearchMatch else colors.searchMatch),
                    match.start,
                    match.endExclusive,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun selectCurrentMatch() {
        val match = currentSearchMatch() ?: return
        editor.requestFocus()
        editor.setSelection(match.start, match.endExclusive)
        editor.bringPointIntoView(match.start)
        applySearchSpans()
        notifySearch()
    }

    private fun currentSearchMatch(): EditorMatch? = searchMatches.getOrNull(currentSearchIndex)

    private fun notifySearch() {
        if (suppressUiCallbacks || disposed) return
        onSearchChanged(
            EditorSearchStatus(
                current = if (currentSearchIndex >= 0) currentSearchIndex + 1 else 0,
                total = searchMatches.size,
                limited = searchMatches.size >= EditorSearch.MAX_MATCHES,
            ),
        )
    }

    private fun colorFor(kind: SyntaxTokenKind): Int = when (kind) {
        SyntaxTokenKind.KEYWORD -> colors.keyword
        SyntaxTokenKind.STRING -> colors.string
        SyntaxTokenKind.COMMENT -> colors.comment
        SyntaxTokenKind.NUMBER -> colors.number
        SyntaxTokenKind.HEADING -> colors.heading
        SyntaxTokenKind.TAG -> colors.tag
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun defaultColors() = EditorColors(
        background = 0xFFFFFFFF.toInt(),
        foreground = 0xFF202124.toInt(),
        gutterBackground = 0xFFF2F3F5.toInt(),
        gutterForeground = 0xFF6B7280.toInt(),
        keyword = 0xFF7C3AED.toInt(),
        string = 0xFF0F7B49.toInt(),
        comment = 0xFF6B7280.toInt(),
        number = 0xFFB45309.toInt(),
        heading = 0xFF1D4ED8.toInt(),
        tag = 0xFFB91C1C.toInt(),
        searchMatch = 0x66FACC15,
        currentSearchMatch = 0x99FB923C.toInt(),
    )

    private class EditorSyntaxSpan(color: Int) : ForegroundColorSpan(color)
    private class EditorSearchSpan(color: Int) : BackgroundColorSpan(color)

    private companion object {
        const val DEFAULT_FONT_SIZE_SP = 14f
        const val MIN_FONT_SIZE_SP = 10f
        const val MAX_FONT_SIZE_SP = 28f
        const val HIGHLIGHT_DELAY_MS = 140L
        const val MAX_VISIBLE_SEARCH_HIGHLIGHTS = 2_000
    }
}

private class LineNumberView(
    context: Context,
    private val editor: AppCompatEditText,
) : View(context) {
    var lineStarts: IntArray = intArrayOf(0)
    var gutterBackgroundColor: Int = 0xFFF2F3F5.toInt()
    var foregroundColor: Int = 0xFF6B7280.toInt()
    var textSizePx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        12f,
        resources.displayMetrics,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(gutterBackgroundColor)
        val layout = editor.layout ?: return
        if (layout.lineCount <= 0 || lineStarts.isEmpty()) return
        paint.color = foregroundColor
        paint.textSize = textSizePx
        val firstVisualLine = layout.getLineForVertical((editor.scrollY - editor.totalPaddingTop).coerceAtLeast(0))
        val lastVisualLine = layout.getLineForVertical(editor.scrollY + editor.height)
        val text = editor.text ?: return
        for (visualLine in firstVisualLine..lastVisualLine.coerceAtMost(layout.lineCount - 1)) {
            val offset = layout.getLineStart(visualLine).coerceIn(0, text.length)
            val logicalLine = lineStarts.binarySearch(offset)
            if (logicalLine < 0) continue
            val baseline = editor.totalPaddingTop + layout.getLineBaseline(visualLine) - editor.scrollY
            canvas.drawText((logicalLine + 1).toString(), width - dp(8).toFloat(), baseline.toFloat(), paint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
