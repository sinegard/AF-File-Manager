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
        val baseComparator = when (sortMode) {
            SortMode.NAME -> compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }
            SortMode.SIZE -> compareBy<FileEntry> { it.sizeBytes }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            SortMode.MODIFIED -> compareBy<FileEntry> { it.modifiedAtMillis }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            SortMode.TYPE -> compareBy<FileEntry> { it.kind }
                .thenBy { if (it.isDirectory) "" else it.extension }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        }
        val metadataAware = sortMode == SortMode.SIZE || sortMode == SortMode.MODIFIED
        val selected = Comparator<FileEntry> { left, right ->
            if (metadataAware && left.metadataComplete != right.metadataComplete) {
                if (left.metadataComplete) -1 else 1
            } else if (sortDirection == SortDirection.ASCENDING) {
                baseComparator.compare(left, right)
            } else {
                baseComparator.compare(right, left)
            }
        }
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
