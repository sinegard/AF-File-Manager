package com.affilemanager.app.core

import com.affilemanager.app.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSystemRulesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validateFileNameRejectsTraversalAndSeparators() {
        assertTrue(FileSystemRules.validateFileName("normalus.txt").isSuccess)
        assertTrue(FileSystemRules.validateFileName("..").isFailure)
        assertTrue(FileSystemRules.validateFileName("a/b").isFailure)
        assertTrue(FileSystemRules.validateFileName("a\\b").isFailure)
    }

    @Test
    fun containmentUsesCanonicalPaths() {
        val root = temporaryFolder.newFolder("root")
        val inside = File(root, "a/../b.txt")
        val outside = File(root, "../outside.txt")
        assertTrue(FileSystemRules.isContained(root, inside))
        assertFalse(FileSystemRules.isContained(root, outside))
    }

    @Test
    fun keepBothPreservesExtension() {
        val root = temporaryFolder.root
        val original = File(root, "ataskaita.pdf")
        original.writeText("a")
        File(root, "ataskaita (1).pdf").writeText("b")
        assertEquals("ataskaita (2).pdf", FileSystemRules.keepBothTarget(original).name)
    }

    @Test
    fun knownExtensionsHaveUsefulKinds() {
        assertEquals(EntryKind.IMAGE, FileSystemRules.detectKind(File("foto.webp")))
        assertEquals(EntryKind.ARCHIVE, FileSystemRules.detectKind(File("archyvas.7z")))
        assertEquals(EntryKind.APK, FileSystemRules.detectKind(File("programa.apk")))
        assertEquals(EntryKind.DOCUMENT, FileSystemRules.detectKind(File("script.lua")))
        assertEquals(EntryKind.DOCUMENT, FileSystemRules.detectKind(File("screen.tsx")))
        assertEquals(EntryKind.DOCUMENT, FileSystemRules.detectKind(File("classes.smali")))
        assertEquals(EntryKind.DOCUMENT, FileSystemRules.detectKind(File("presentation.smil")))
    }

    @Test
    fun suppliedMimeTypeClassifiesContentUrisWithoutUsefulExtensions() {
        assertEquals(EntryKind.IMAGE, FileSystemRules.detectKind("provider-item", "image/png"))
        assertEquals(EntryKind.DOCUMENT, FileSystemRules.detectKind("provider-item", "application/pdf"))
        assertEquals(EntryKind.AUDIO, FileSystemRules.detectKind("provider-item", "audio/mpeg; charset=binary"))
        assertEquals(EntryKind.APK, FileSystemRules.detectKind("provider-item", "application/vnd.android.package-archive"))
    }
}
