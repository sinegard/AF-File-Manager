package com.affilemanager.app.editing

import com.affilemanager.app.model.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class EditSessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun editingUsesPrivateCopyAndDoesNotTouchOriginalBeforeSave() {
        val cache = temporaryFolder.newFolder("cache")
        val source = temporaryFolder.newFile("notes.txt").apply { writeText("original") }
        val store = EditSessionStore(cache)

        val session = store.prepareFromFile(
            sourceKey = "local|${source.absolutePath}",
            displayName = source.name,
            mimeType = "text/plain",
            sourceFile = source,
            origin = EditOrigin.Local(source.absolutePath, canWrite = true),
            modifiedAtMillis = source.lastModified(),
            internalTextEditor = true,
        )
        val staged = store.stageText(session, "edited")

        assertEquals("original", source.readText())
        assertEquals("edited", staged.workingFile.readText())
        assertTrue(staged.hasOriginChanges)
        assertTrue(staged.hasUnsavedChanges)
    }

    @Test
    fun detectsOriginalContentConflictBeforeOverwrite() {
        val source = temporaryFolder.newFile("conflict.txt").apply { writeText("version one") }
        val store = EditSessionStore(temporaryFolder.newFolder("cache-conflict"))
        val session = store.prepareFromFile(
            sourceKey = "source",
            displayName = source.name,
            mimeType = "text/plain",
            sourceFile = source,
            origin = EditOrigin.Local(source.absolutePath, canWrite = true),
            modifiedAtMillis = source.lastModified(),
            internalTextEditor = true,
        )
        val staged = store.stageText(session, "my edit")
        source.writeText("changed elsewhere")

        val result = store.saveLocal(staged, force = false)

        assertTrue(result is EditSaveResult.Conflict)
        assertEquals("changed elsewhere", source.readText())
        val conflict = (result as EditSaveResult.Conflict).details
        assertFalse(conflict.expected.hasSameContent(conflict.current))
    }

    @Test
    fun explicitForceOverwriteIsVerifiedAndCanBeMarkedSaved() {
        val source = temporaryFolder.newFile("overwrite.txt").apply { writeText("version one") }
        val store = EditSessionStore(temporaryFolder.newFolder("cache-overwrite"))
        val session = store.prepareFromFile(
            sourceKey = "source",
            displayName = source.name,
            mimeType = "text/plain",
            sourceFile = source,
            origin = EditOrigin.Local(source.absolutePath, canWrite = true),
            modifiedAtMillis = source.lastModified(),
            internalTextEditor = true,
        )
        val staged = store.stageText(session, "my edit")
        source.writeText("changed elsewhere")

        val result = store.saveLocal(staged, force = true) as EditSaveResult.Saved
        val savedSession = store.markOriginSaved(staged, result.revision)

        assertEquals("my edit", source.readText())
        assertFalse(savedSession.hasOriginChanges)
        assertFalse(savedSession.hasUnsavedChanges)
    }

    @Test
    fun hostileDisplayNameCannotEscapeEditCache() {
        val source = temporaryFolder.newFile("safe.txt").apply { writeText("content") }
        val cache = temporaryFolder.newFolder("cache-path")
        val store = EditSessionStore(cache)

        val session = store.prepareFromFile(
            sourceKey = "source",
            displayName = "../../escaped.txt",
            mimeType = "text/plain",
            sourceFile = source,
            origin = EditOrigin.Local(source.absolutePath, canWrite = true),
            modifiedAtMillis = null,
            internalTextEditor = true,
        )

        assertTrue(session.workingFile.canonicalPath.startsWith(File(cache, "edit-sessions").canonicalPath + File.separator))
        assertFalse(session.displayName.contains('/'))
        assertFalse(File(cache.parentFile, "escaped.txt").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesFilesAboveTheBoundBeforeCopying() {
        val source = temporaryFolder.newFile("large.bin")
        RandomAccessFile(source, "rw").use { it.setLength(EditLimits.MAX_FILE_BYTES + 1) }
        val store = EditSessionStore(temporaryFolder.newFolder("cache-large"))

        store.prepareFromFile(
            sourceKey = "large",
            displayName = source.name,
            mimeType = "application/octet-stream",
            sourceFile = source,
            origin = EditOrigin.Local(source.absolutePath, canWrite = true),
            modifiedAtMillis = null,
            internalTextEditor = false,
        )
    }

    @Test
    fun editabilityRulesCoverTextButExcludeUnsafeContainerTypes() {
        assertTrue(EditabilityRules.supportsInternalText("config.toml", "application/octet-stream", EntryKind.OTHER))
        assertTrue(EditabilityRules.supportsInternalText("README", "text/plain", EntryKind.DOCUMENT))
        assertFalse(EditabilityRules.supportsInternalText("photo.png", "image/png", EntryKind.IMAGE))
        assertFalse(EditabilityRules.mayUseExternalEditor(EntryKind.ARCHIVE, "zip"))
        assertFalse(EditabilityRules.mayUseExternalEditor(EntryKind.APK, "apk"))
        assertTrue(EditabilityRules.mayUseExternalEditor(EntryKind.IMAGE, "png"))
    }

    @Test
    fun nonUtf8TextUsesAReversibleSingleByteFallback() {
        val source = temporaryFolder.newFile("invalid.txt").apply {
            writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        }
        val store = EditSessionStore(temporaryFolder.newFolder("cache-invalid-utf8"))
        val session = store.prepareFromFile(
            sourceKey = "invalid",
            displayName = source.name,
            mimeType = "text/plain",
            sourceFile = source,
            origin = EditOrigin.Local(source.absolutePath, canWrite = true),
            modifiedAtMillis = source.lastModified(),
            internalTextEditor = true,
        )

        val document = store.readTextDocument(session)

        assertTrue(document.encoding == TextEncoding.WINDOWS_1252 || document.encoding == TextEncoding.ISO_8859_1)
        val staged = store.stageText(session, document.text, document.encoding, document.lineEnding)
        assertEquals(source.readBytes().toList(), staged.workingFile.readBytes().toList())
    }

    @Test
    fun saveAsAsksBeforeReplacingAndCanKeepBoth() {
        val directory = temporaryFolder.newFolder("save-as")
        val source = File(directory, "notes.txt").apply { writeText("original") }
        val occupied = File(directory, "copy.txt").apply { writeText("occupied") }
        val store = EditSessionStore(temporaryFolder.newFolder("cache-save-as"))
        val session = store.prepareFromFile(
            sourceKey = "source",
            displayName = source.name,
            mimeType = "text/plain",
            sourceFile = source,
            origin = EditOrigin.Local(source.absolutePath, canWrite = true),
            modifiedAtMillis = source.lastModified(),
            internalTextEditor = true,
        )
        val staged = store.stageText(session, "edited")

        val conflict = store.saveLocalAs(staged, directory.absolutePath, occupied.name, EditExistingPolicy.ASK)
        assertTrue(conflict is EditSaveAsResult.Conflict)
        assertEquals("occupied", occupied.readText())

        val kept = store.saveLocalAs(staged, directory.absolutePath, occupied.name, EditExistingPolicy.KEEP_BOTH)
            as EditSaveAsResult.Saved
        val savedFile = File((kept.destination as EditDestination.Local).path)
        assertEquals("copy (1).txt", savedFile.name)
        assertEquals("edited", savedFile.readText())

        val rebased = store.rebaseOrigin(staged, kept.destination, kept.revision)
        assertEquals(savedFile.absolutePath, (rebased.origin as EditOrigin.Local).path)
        assertFalse(rebased.hasUnsavedChanges)
    }
}
