package com.affilemanager.app.data

import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import java.util.Locale

internal object FileEntryOrdering {
    fun order(
        entries: List<FileEntry>,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): List<FileEntry> {
        val ascending = when (sortMode) {
            SortMode.NAME -> compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }
            SortMode.SIZE -> compareBy<FileEntry> { if (it.metadataComplete) it.sizeBytes else Long.MAX_VALUE }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            SortMode.MODIFIED -> compareBy<FileEntry> {
                if (it.metadataComplete) it.modifiedAtMillis else Long.MAX_VALUE
            }.thenBy { it.name.lowercase(Locale.ROOT) }
            SortMode.TYPE -> compareBy<FileEntry> { it.kind }.thenBy { it.name.lowercase(Locale.ROOT) }
        }
        val selected = if (sortDirection == SortDirection.ASCENDING) ascending else ascending.reversed()
        val directories = ArrayList<FileEntry>(entries.size)
        val files = ArrayList<FileEntry>(entries.size)
        entries.forEach { entry ->
            if (entry.isDirectory) directories += entry else files += entry
        }
        directories.sortWith(selected)
        files.sortWith(selected)
        return ArrayList<FileEntry>(entries.size).apply {
            addAll(directories)
            addAll(files)
        }
    }
}
