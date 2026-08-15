package com.affilemanager.app.ui.editor

import java.util.Locale

enum class EditorLanguage(val label: String) {
    PLAIN_TEXT("Plain text"),
    MARKDOWN("Markdown"),
    JSON("JSON"),
    XML("XML / HTML"),
    KOTLIN("Kotlin"),
    JAVA("Java"),
    JAVASCRIPT("JavaScript"),
    TYPESCRIPT("TypeScript"),
    PYTHON("Python"),
    SHELL("Shell"),
    SQL("SQL"),
    C_FAMILY("C / C++ / C#"),
    CSS("CSS / SCSS"),
    YAML("YAML"),
    TOML("TOML / INI"),
    ;

    companion object {
        fun detect(fileName: String): EditorLanguage = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "md", "markdown" -> MARKDOWN
            "json", "jsonl" -> JSON
            "xml", "html", "htm", "svg" -> XML
            "kt", "kts", "gradle" -> KOTLIN
            "java" -> JAVA
            "js", "jsx" -> JAVASCRIPT
            "ts", "tsx" -> TYPESCRIPT
            "py" -> PYTHON
            "sh", "bash", "zsh" -> SHELL
            "sql" -> SQL
            "c", "h", "cpp", "hpp", "cc", "cs" -> C_FAMILY
            "css", "scss" -> CSS
            "yaml", "yml" -> YAML
            "toml", "ini", "conf", "cfg", "properties" -> TOML
            else -> PLAIN_TEXT
        }
    }
}

enum class SyntaxTokenKind {
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    HEADING,
    TAG,
}

data class SyntaxSpan(
    val start: Int,
    val endExclusive: Int,
    val kind: SyntaxTokenKind,
)

object EditorSyntaxHighlighter {
    const val MAX_HIGHLIGHT_CHARS = 200_000

    private val commonKeywords = setOf(
        "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
        "data", "default", "do", "else", "enum", "export", "extends", "false", "final", "finally",
        "for", "from", "fun", "function", "if", "implements", "import", "in", "interface", "internal",
        "is", "let", "namespace", "new", "null", "object", "open", "override", "package", "private",
        "protected", "public", "return", "sealed", "static", "struct", "super", "switch", "this",
        "throw", "true", "try", "typealias", "typeof", "val", "var", "void", "when", "while", "yield",
    )
    private val pythonKeywords = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while",
        "with", "yield",
    )
    private val sqlKeywords = setOf(
        "add", "alter", "and", "as", "asc", "begin", "between", "by", "case", "commit", "create",
        "delete", "desc", "distinct", "drop", "else", "end", "exists", "from", "full", "group", "having",
        "in", "index", "inner", "insert", "into", "is", "join", "left", "like", "limit", "not", "null",
        "on", "or", "order", "outer", "primary", "references", "right", "rollback", "select", "set", "table",
        "then", "union", "unique", "update", "values", "when", "where", "with",
    )

    fun highlight(text: String, language: EditorLanguage): List<SyntaxSpan> {
        if (text.isEmpty() || text.length > MAX_HIGHLIGHT_CHARS || language == EditorLanguage.PLAIN_TEXT) return emptyList()
        val occupied = BooleanArray(text.length)
        val spans = ArrayList<SyntaxSpan>()

        fun addMatches(regex: Regex, kind: SyntaxTokenKind, group: Int = 0) {
            regex.findAll(text).forEach { match ->
                val range = match.groups[group]?.range ?: return@forEach
                val start = range.first
                val end = range.last + 1
                if (start >= end || end > occupied.size) return@forEach
                if ((start until end).any { occupied[it] }) return@forEach
                spans += SyntaxSpan(start, end, kind)
                for (index in start until end) occupied[index] = true
            }
        }

        when (language) {
            EditorLanguage.XML -> addMatches(Regex("<!--[\\s\\S]*?-->"), SyntaxTokenKind.COMMENT)
            EditorLanguage.PYTHON,
            EditorLanguage.SHELL,
            EditorLanguage.YAML,
            EditorLanguage.TOML,
            -> addMatches(Regex("(?m)#.*$"), SyntaxTokenKind.COMMENT)
            EditorLanguage.SQL -> {
                addMatches(Regex("/\\*[\\s\\S]*?\\*/"), SyntaxTokenKind.COMMENT)
                addMatches(Regex("(?m)--.*$"), SyntaxTokenKind.COMMENT)
            }
            EditorLanguage.MARKDOWN -> Unit
            else -> {
                addMatches(Regex("/\\*[\\s\\S]*?\\*/"), SyntaxTokenKind.COMMENT)
                addMatches(Regex("(?m)//.*$"), SyntaxTokenKind.COMMENT)
            }
        }

        addMatches(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`"), SyntaxTokenKind.STRING)
        if (language == EditorLanguage.MARKDOWN) {
            addMatches(Regex("(?m)^#{1,6}\\s+.*$"), SyntaxTokenKind.HEADING)
        }
        if (language == EditorLanguage.XML) {
            addMatches(Regex("</?[A-Za-z][A-Za-z0-9:_-]*"), SyntaxTokenKind.TAG)
        }
        addMatches(Regex("(?<![A-Za-z0-9_])(?:0[xX][0-9A-Fa-f]+|\\d+(?:\\.\\d+)?)(?![A-Za-z0-9_])"), SyntaxTokenKind.NUMBER)

        val keywords = when (language) {
            EditorLanguage.PYTHON -> pythonKeywords
            EditorLanguage.SQL -> sqlKeywords
            EditorLanguage.JSON -> setOf("true", "false", "null")
            else -> commonKeywords
        }
        if (keywords.isNotEmpty()) {
            val option = if (language == EditorLanguage.SQL) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val expression = keywords.joinToString(prefix = "\\b(?:", postfix = ")\\b", separator = "|") { Regex.escape(it) }
            addMatches(Regex(expression, option), SyntaxTokenKind.KEYWORD)
        }
        return spans.sortedBy(SyntaxSpan::start)
    }
}

data class EditorMatch(val start: Int, val endExclusive: Int)

data class EditorReplacement(val text: String, val replacements: Int)

object EditorSearch {
    const val MAX_MATCHES = 10_000

    fun findMatches(
        text: String,
        query: String,
        matchCase: Boolean,
        wholeWord: Boolean = false,
    ): List<EditorMatch> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val matches = ArrayList<EditorMatch>()
        var fromIndex = 0
        while (fromIndex <= text.length - query.length && matches.size < MAX_MATCHES) {
            val found = text.indexOf(query, fromIndex, ignoreCase = !matchCase)
            if (found < 0) break
            val end = found + query.length
            if (!wholeWord || isWholeWord(text, found, end)) matches += EditorMatch(found, end)
            fromIndex = (found + query.length.coerceAtLeast(1)).coerceAtMost(text.length)
        }
        return matches
    }

    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        matchCase: Boolean,
        wholeWord: Boolean = false,
        maximumChars: Int,
    ): EditorReplacement {
        val matches = findMatches(text, query, matchCase, wholeWord)
        if (matches.isEmpty()) return EditorReplacement(text, 0)
        val projected = text.length.toLong() + matches.size.toLong() * (replacement.length - query.length).toLong()
        require(projected in 0..maximumChars.toLong()) { "Replacement would exceed the editor size limit" }
        val output = StringBuilder(projected.toInt())
        var cursor = 0
        matches.forEach { match ->
            output.append(text, cursor, match.start)
            output.append(replacement)
            cursor = match.endExclusive
        }
        output.append(text, cursor, text.length)
        return EditorReplacement(output.toString(), matches.size)
    }

    private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
        val leftWord = start > 0 && text[start - 1].isWordCharacter()
        val rightWord = end < text.length && text[end].isWordCharacter()
        return !leftWord && !rightWord
    }

    private fun Char.isWordCharacter(): Boolean = isLetterOrDigit() || this == '_'
}

data class EditorDelta(
    val start: Int,
    val removed: String,
    val inserted: String,
)

data class EditorHistoryResult(
    val text: String,
    val selection: Int,
)

class EditorHistory(
    private val maximumSteps: Int = 200,
    private val maximumStoredBytes: Int = 8 * 1_024 * 1_024,
) {
    private val undo = ArrayDeque<EditorDelta>()
    private val redo = ArrayDeque<EditorDelta>()
    private var storedBytes = 0

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun clear() {
        undo.clear()
        redo.clear()
        storedBytes = 0
    }

    fun record(delta: EditorDelta) {
        if (delta.removed == delta.inserted) return
        redo.clear()
        val merged = mergeTyping(undo.lastOrNull(), delta)
        if (merged != null) {
            val previous = undo.removeLast()
            storedBytes -= cost(previous)
            undo.addLast(merged)
            storedBytes += cost(merged)
        } else {
            undo.addLast(delta)
            storedBytes += cost(delta)
        }
        trim()
    }

    fun undo(text: String): EditorHistoryResult? {
        val delta = undo.removeLastOrNull() ?: return null
        if (!text.regionMatches(delta.start, delta.inserted, 0, delta.inserted.length)) {
            clear()
            return null
        }
        storedBytes -= cost(delta)
        redo.addLast(delta)
        return EditorHistoryResult(
            text = text.replaceRange(delta.start, delta.start + delta.inserted.length, delta.removed),
            selection = delta.start + delta.removed.length,
        )
    }

    fun redo(text: String): EditorHistoryResult? {
        val delta = redo.removeLastOrNull() ?: return null
        if (!text.regionMatches(delta.start, delta.removed, 0, delta.removed.length)) {
            clear()
            return null
        }
        undo.addLast(delta)
        storedBytes += cost(delta)
        trim()
        return EditorHistoryResult(
            text = text.replaceRange(delta.start, delta.start + delta.removed.length, delta.inserted),
            selection = delta.start + delta.inserted.length,
        )
    }

    private fun mergeTyping(previous: EditorDelta?, current: EditorDelta): EditorDelta? {
        if (previous == null) return null
        if (previous.removed.isEmpty() && current.removed.isEmpty() &&
            current.start == previous.start + previous.inserted.length &&
            previous.inserted.length + current.inserted.length <= MAX_MERGED_TYPING_CHARS
        ) {
            return previous.copy(inserted = previous.inserted + current.inserted)
        }
        if (previous.inserted.isEmpty() && current.inserted.isEmpty() &&
            current.start + current.removed.length == previous.start &&
            previous.removed.length + current.removed.length <= MAX_MERGED_TYPING_CHARS
        ) {
            return EditorDelta(current.start, current.removed + previous.removed, "")
        }
        return null
    }

    private fun trim() {
        while (undo.size > maximumSteps || storedBytes > maximumStoredBytes) {
            val removed = undo.removeFirstOrNull() ?: break
            storedBytes -= cost(removed)
        }
    }

    private fun cost(delta: EditorDelta): Int = (delta.removed.length + delta.inserted.length) * 2

    private companion object {
        const val MAX_MERGED_TYPING_CHARS = 64
    }
}
