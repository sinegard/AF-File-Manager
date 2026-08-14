package com.affilemanager.app.ui

import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.network.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBrowserRulesTest {
    @Test
    fun foldersStayFirstAndNamesUseTheRequestedDirection() {
        val entries = listOf(
            entry("b.txt", directory = false),
            entry("z-folder", directory = true),
            entry("a.txt", directory = false),
            entry("a-folder", directory = true),
        )

        val ascending = RemoteBrowserRules.displayEntries(
            entries,
            includeHidden = true,
            sortMode = SortMode.NAME,
            sortDirection = SortDirection.ASCENDING,
        )
        val descending = RemoteBrowserRules.displayEntries(
            entries,
            includeHidden = true,
            sortMode = SortMode.NAME,
            sortDirection = SortDirection.DESCENDING,
        )

        assertEquals(listOf("a-folder", "z-folder", "a.txt", "b.txt"), ascending.map(RemoteEntry::name))
        assertEquals(listOf("z-folder", "a-folder", "b.txt", "a.txt"), descending.map(RemoteEntry::name))
    }

    @Test
    fun hiddenEntriesAreExcludedUntilEnabled() {
        val entries = listOf(entry("visible.txt"), entry(".hidden.txt"), entry(".folder", directory = true))

        val hiddenOff = RemoteBrowserRules.displayEntries(
            entries,
            includeHidden = false,
            sortMode = SortMode.NAME,
            sortDirection = SortDirection.ASCENDING,
        )
        val hiddenOn = RemoteBrowserRules.displayEntries(
            entries,
            includeHidden = true,
            sortMode = SortMode.NAME,
            sortDirection = SortDirection.ASCENDING,
        )

        assertEquals(listOf("visible.txt"), hiddenOff.map(RemoteEntry::name))
        assertEquals(listOf(".folder", ".hidden.txt", "visible.txt"), hiddenOn.map(RemoteEntry::name))
    }

    @Test
    fun typeSortUsesTheSameFileKindsAsLocalStorage() {
        val entries = listOf(entry("photo.png"), entry("notes.txt"), entry("archive.zip"))

        val ordered = RemoteBrowserRules.displayEntries(
            entries,
            includeHidden = true,
            sortMode = SortMode.TYPE,
            sortDirection = SortDirection.ASCENDING,
        )

        assertEquals(listOf("photo.png", "notes.txt", "archive.zip"), ordered.map(RemoteEntry::name))
    }

    private fun entry(name: String, directory: Boolean = false) = RemoteEntry(
        name = name,
        path = "/$name",
        directory = directory,
        sizeBytes = if (directory) 0 else 12,
        modifiedAtMillis = 1_000,
    )
}
