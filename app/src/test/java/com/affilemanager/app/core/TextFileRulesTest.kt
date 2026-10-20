package com.affilemanager.app.core

import com.affilemanager.app.editing.EditabilityRules
import com.affilemanager.app.model.EntryKind
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TextFileRulesTest {
    @Test fun lyricsSubtitlesAndShellConfigurationShareBrowserAndEditorDetection() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            listOf("song.lrc", "film.srt", "caption.VTT", ".bashrc", ".bash_profile", ".zshrc", "script.bash", "style.sass").forEach { name ->
                val kind = FileSystemRules.detectKind(name, null)
                assertEquals(name, EntryKind.DOCUMENT, kind)
                assertTrue(name, EditabilityRules.supportsInternalText(name, "application/octet-stream", kind))
            }
            assertTrue(EditabilityRules.supportsInternalText("data", "APPLICATION/JSON", EntryKind.DOCUMENT))
        } finally { Locale.setDefault(before) }
    }

    @Test fun binaryAndOfficeFormatsDoNotBecomeTextEditors() {
        listOf("photo.png", "archive.zip", "program.apk", "document.pdf", "book.docx", "a.bin").forEach { name ->
            assertFalse(name, TextFileRules.supportsName(name))
            assertFalse(name, EditabilityRules.supportsInternalText(name, "application/octet-stream", EntryKind.OTHER))
        }
    }
}
