package com.affilemanager.app.data

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FileEntryOrderingTest {
    @Test
    fun directoriesAlwaysPrecedeFilesForEveryModeAndDirection() {
        val entries = listOf(
            entry("a-file.txt", EntryKind.DOCUMENT, size = 1, modified = 10),
            entry("z-folder", EntryKind.DIRECTORY, size = 0, modified = 1),
            entry("z-file.zip", EntryKind.ARCHIVE, size = 30, modified = 2),
            entry("a-folder", EntryKind.DIRECTORY, size = 0, modified = 20),
        )

        SortMode.entries.forEach { mode ->
            SortDirection.entries.forEach { direction ->
                val ordered = FileEntryOrdering.order(entries, mode, direction)
                assertEquals(
                    "$mode / $direction",
                    listOf(true, true, false, false),
                    ordered.map(FileEntry::isDirectory),
                )
            }
        }
    }

    @Test
    fun defaultOrderMatchesDesktopFileManagers() {
        val ordered = FileEntryOrdering.order(
            listOf(
                entry("zz-file.txt", EntryKind.DOCUMENT),
                entry("z-folder", EntryKind.DIRECTORY),
                entry("00-file.txt", EntryKind.DOCUMENT),
                entry("01-folder", EntryKind.DIRECTORY),
                entry("a-file.txt", EntryKind.DOCUMENT),
            ),
            SortMode.NAME,
            SortDirection.ASCENDING,
        )

        assertEquals(
            listOf("01-folder", "z-folder", "00-file.txt", "a-file.txt", "zz-file.txt"),
            ordered.map(FileEntry::name),
        )
    }

    @Test
    fun descendingDirectionReversesNamesInsideEachGroupOnly() {
        val ordered = FileEntryOrdering.order(
            listOf(
                entry("a-file", EntryKind.OTHER),
                entry("a-folder", EntryKind.DIRECTORY),
                entry("z-file", EntryKind.OTHER),
                entry("z-folder", EntryKind.DIRECTORY),
            ),
            SortMode.NAME,
            SortDirection.DESCENDING,
        )

        assertEquals(listOf("z-folder", "a-folder", "z-file", "a-file"), ordered.map(FileEntry::name))
    }

    private fun entry(
        name: String,
        kind: EntryKind,
        size: Long = 0,
        modified: Long = 0,
    ) = FileEntry(
        absolutePath = "/test/$name",
        name = name,
        kind = kind,
        sizeBytes = size,
        modifiedAtMillis = modified,
        isHidden = false,
        isReadable = true,
        isWritable = true,
    )
}
