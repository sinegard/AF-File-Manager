package com.affilemanager.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCoreTest {
    @Test
    fun languageDetectionAndHighlightingCoverCommonCode() {
        assertEquals(EditorLanguage.KOTLIN, EditorLanguage.detect("MainViewModel.kt"))
        val text = "// note\nval answer = 42\nval label = \"ok\""
        val spans = EditorSyntaxHighlighter.highlight(text, EditorLanguage.KOTLIN)

        assertTrue(spans.any { it.kind == SyntaxTokenKind.COMMENT })
        assertTrue(spans.any { it.kind == SyntaxTokenKind.KEYWORD && text.substring(it.start, it.endExclusive) == "val" })
        assertTrue(spans.any { it.kind == SyntaxTokenKind.NUMBER })
        assertTrue(spans.any { it.kind == SyntaxTokenKind.STRING })
    }

    @Test
    fun searchSupportsCaseWholeWordsAndBoundedReplacement() {
        val text = "cat catalog CAT cat"
        val matches = EditorSearch.findMatches(text, "cat", matchCase = false, wholeWord = true)
        assertEquals(listOf(0, 12, 16), matches.map(EditorMatch::start))

        val replaced = EditorSearch.replaceAll(text, "cat", "dog", false, true, maximumChars = 100)
        assertEquals("dog catalog dog dog", replaced.text)
        assertEquals(3, replaced.replacements)
    }

    @Test
    fun undoRedoStoresDeltasAndCoalescesTyping() {
        val history = EditorHistory()
        history.record(EditorDelta(0, "", "a"))
        history.record(EditorDelta(1, "", "b"))
        history.record(EditorDelta(2, "", "c"))

        val undone = requireNotNull(history.undo("abc"))
        assertEquals("", undone.text)
        assertEquals(0, undone.selection)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        val redone = requireNotNull(history.redo(undone.text))
        assertEquals("abc", redone.text)
        assertEquals(3, redone.selection)
    }
}
