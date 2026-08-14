package com.affilemanager.app.ui

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.network.RemoteEntry
import java.util.Locale

/** Display rules shared by the remote browser UI and its selection actions. */
internal object RemoteBrowserRules {
    fun displayEntries(
        entries: List<RemoteEntry>,
        includeHidden: Boolean,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): List<RemoteEntry> {
        val visible = if (includeHidden) entries else entries.filterNot(::isHidden)
        val ascending = when (sortMode) {
            SortMode.NAME -> compareBy<RemoteEntry> { it.name.lowercase(Locale.ROOT) }
            SortMode.SIZE -> compareBy<RemoteEntry> { it.sizeBytes }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            SortMode.MODIFIED -> compareBy<RemoteEntry> { it.modifiedAtMillis ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase(Locale.ROOT) }
            SortMode.TYPE -> compareBy<RemoteEntry> {
                FileSystemRules.detectKind(it.name, mimeType = null, isDirectory = it.directory)
            }.thenBy { it.name.lowercase(Locale.ROOT) }
        }
        val selected = if (sortDirection == SortDirection.ASCENDING) ascending else ascending.reversed()
        val directories = ArrayList<RemoteEntry>(visible.size)
        val files = ArrayList<RemoteEntry>(visible.size)
        visible.forEach { entry ->
            if (entry.directory) directories += entry else files += entry
        }
        directories.sortWith(selected)
        files.sortWith(selected)
        return ArrayList<RemoteEntry>(visible.size).apply {
            addAll(directories)
            addAll(files)
        }
    }

    fun isHidden(entry: RemoteEntry): Boolean = entry.name.startsWith('.')
}
