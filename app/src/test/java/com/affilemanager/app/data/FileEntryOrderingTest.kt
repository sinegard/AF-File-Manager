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

    @Test
    fun sizeAndModifiedSortingUseTheRequestedDirection() {
        val entries = listOf(
            entry("middle.txt", EntryKind.DOCUMENT, size = 50, modified = 500),
            entry("largest.txt", EntryKind.DOCUMENT, size = 900, modified = 100),
            entry("smallest.txt", EntryKind.DOCUMENT, size = 1, modified = 900),
        )

        assertEquals(
            listOf("smallest.txt", "middle.txt", "largest.txt"),
            FileEntryOrdering.order(entries, SortMode.SIZE, SortDirection.ASCENDING).map(FileEntry::name),
        )
        assertEquals(
            listOf("largest.txt", "middle.txt", "smallest.txt"),
            FileEntryOrdering.order(entries, SortMode.SIZE, SortDirection.DESCENDING).map(FileEntry::name),
        )
        assertEquals(
            listOf("largest.txt", "middle.txt", "smallest.txt"),
            FileEntryOrdering.order(entries, SortMode.MODIFIED, SortDirection.ASCENDING).map(FileEntry::name),
        )
        assertEquals(
            listOf("smallest.txt", "middle.txt", "largest.txt"),
            FileEntryOrdering.order(entries, SortMode.MODIFIED, SortDirection.DESCENDING).map(FileEntry::name),
        )
    }

    @Test
    fun typeSortingUsesTheFileExtensionInsideTheSameKind() {
        val entries = listOf(
            entry("page.pdf", EntryKind.DOCUMENT),
            entry("notes.txt", EntryKind.DOCUMENT),
            entry("data.csv", EntryKind.DOCUMENT),
        )

        assertEquals(
            listOf("data.csv", "page.pdf", "notes.txt"),
            FileEntryOrdering.order(entries, SortMode.TYPE, SortDirection.ASCENDING).map(FileEntry::name),
        )
        assertEquals(
            listOf("notes.txt", "page.pdf", "data.csv"),
            FileEntryOrdering.order(entries, SortMode.TYPE, SortDirection.DESCENDING).map(FileEntry::name),
        )
    }

    @Test
    fun unfinishedMetadataAlwaysStaysBehindStableResults() {
        val complete = entry("complete.txt", EntryKind.DOCUMENT, size = 10, modified = 10)
        val pending = entry("pending.txt", EntryKind.DOCUMENT, size = 999, modified = 999).copy(metadataComplete = false)

        SortDirection.entries.forEach { direction ->
            assertEquals(
                listOf("complete.txt", "pending.txt"),
                FileEntryOrdering.order(listOf(pending, complete), SortMode.SIZE, direction).map(FileEntry::name),
            )
            assertEquals(
                listOf("complete.txt", "pending.txt"),
                FileEntryOrdering.order(listOf(pending, complete), SortMode.MODIFIED, direction).map(FileEntry::name),
            )
        }
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
