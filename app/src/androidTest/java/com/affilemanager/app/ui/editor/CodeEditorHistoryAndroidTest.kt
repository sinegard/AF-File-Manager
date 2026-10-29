package com.affilemanager.app.ui.editor

import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.R
import org.junit.Assert.*
import org.junit.Test

class CodeEditorHistoryAndroidTest {
    @Test fun keyboardShortcutsUseTheSameHistoryAsTheEditor() = fixture { view, input ->
        input.text.insert(0, "Q")
        assertEquals("Qseed", input.text.toString())
        assertTrue(input.onKeyShortcut(KeyEvent.KEYCODE_Z, shortcut(KeyEvent.KEYCODE_Z)))
        assertEquals("seed", input.text.toString())
        assertTrue(input.onKeyShortcut(KeyEvent.KEYCODE_Y, shortcut(KeyEvent.KEYCODE_Y)))
        assertEquals("Qseed", input.text.toString())
        view.undo()
        assertEquals("seed", input.text.toString())
        view.redo()
        assertEquals("Qseed", input.text.toString())
        assertTrue(input.onKeyPreIme(KeyEvent.KEYCODE_Z, shortcut(KeyEvent.KEYCODE_Z)))
        assertEquals("seed", input.text.toString())
        assertTrue(input.onKeyPreIme(KeyEvent.KEYCODE_Y, shortcut(KeyEvent.KEYCODE_Y)))
        assertEquals("Qseed", input.text.toString())
    }

    @Test fun systemContextActionsShareHistoryAndRespectReadOnly() = fixture { view, input ->
        input.text.insert(0, "Q")
        assertTrue(input.onTextContextMenuItem(android.R.id.undo))
        assertEquals("seed", input.text.toString())
        assertTrue(input.onTextContextMenuItem(android.R.id.redo))
        assertEquals("Qseed", input.text.toString())
        view.setReadOnly(true)
        view.undo()
        input.onKeyShortcut(KeyEvent.KEYCODE_Z, shortcut(KeyEvent.KEYCODE_Z))
        input.onTextContextMenuItem(android.R.id.undo)
        assertEquals("Qseed", input.text.toString())
        view.setReadOnly(false)
        view.undo()
        assertEquals("seed", input.text.toString())
        view.setReadOnly(true)
        view.redo()
        input.onKeyShortcut(KeyEvent.KEYCODE_Y, shortcut(KeyEvent.KEYCODE_Y))
        input.onTextContextMenuItem(android.R.id.redo)
        assertEquals("seed", input.text.toString())
    }

    @Test fun inboundReplacementResetsHistoryWithoutRewritingAnEqualEcho() = fixture { view, input ->
        input.text.insert(0, "Q")
        view.setTextFromModel("Qseed")
        view.undo()
        assertEquals("seed", input.text.toString())
        view.setTextFromModel("replacement")
        view.undo()
        view.redo()
        assertEquals("replacement", input.text.toString())
    }

    private fun shortcut(code: Int) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON)

    private fun fixture(check: (CodeEditorView, EditText) -> Unit) {
        val test = InstrumentationRegistry.getInstrumentation()
        test.runOnMainSync {
            val view = CodeEditorView(ContextThemeWrapper(test.targetContext, R.style.Theme_AFFileManager))
            try {
                view.setTextFromModel("seed")
                check(view, view.getChildAt(1) as EditText)
            } finally {
                view.dispose()
            }
        }
    }
}
