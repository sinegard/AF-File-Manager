package com.affilemanager.app.data

import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
internal object FileEntryOrdering {
    fun order(
        entries: List<FileEntry>,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): List<FileEntry> {
        val nameComparator = Comparator<FileEntry> { left, right ->
            left.name.compareTo(right.name, ignoreCase = true)
        }
        val baseComparator = when (sortMode) {
            SortMode.NAME -> nameComparator
            SortMode.SIZE -> Comparator<FileEntry> { left, right ->
                left.sizeBytes.compareTo(right.sizeBytes).takeIf { it != 0 }
                    ?: nameComparator.compare(left, right)
            }
            SortMode.MODIFIED -> Comparator<FileEntry> { left, right ->
                left.modifiedAtMillis.compareTo(right.modifiedAtMillis).takeIf { it != 0 }
                    ?: nameComparator.compare(left, right)
            }
            SortMode.TYPE -> compareBy<FileEntry> { it.kind }
                .thenBy { if (it.isDirectory) "" else it.extension }
                .then(nameComparator)
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
        val directoryCount = entries.count(FileEntry::isDirectory)
        val directories = ArrayList<FileEntry>(directoryCount)
        val files = ArrayList<FileEntry>(entries.size - directoryCount)
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
