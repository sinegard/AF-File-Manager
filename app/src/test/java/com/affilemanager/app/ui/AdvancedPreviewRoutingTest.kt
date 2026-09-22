package com.affilemanager.app.ui

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdvancedPreviewRoutingTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun directlyReadableFileBypassesThePrivilegedStagingRoute() {
        val file = temporary.newFile("alarm.ogg").apply { writeText("audio fixture") }
        assertEquals(file.canonicalFile, AdvancedPreviewRouting.directlyReadableFile(entry(file.absolutePath, EntryKind.AUDIO)))
    }

    @Test
    fun missingFilesAndDirectoriesStayOnThePrivilegedRoute() {
        val missing = temporary.root.resolve("missing.ogg")
        val directory = temporary.newFolder("music")
        assertNull(AdvancedPreviewRouting.directlyReadableFile(entry(missing.absolutePath, EntryKind.AUDIO)))
        assertNull(AdvancedPreviewRouting.directlyReadableFile(entry(directory.absolutePath, EntryKind.DIRECTORY)))
    }

    private fun entry(path: String, kind: EntryKind) = FileEntry(
        absolutePath = path,
        name = path.substringAfterLast(java.io.File.separatorChar),
        kind = kind,
        sizeBytes = 1,
        modifiedAtMillis = 1,
        isHidden = false,
        isReadable = true,
        isWritable = false,
    )
}
