package com.affilemanager.app.ui.screens

import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.transfer.NearbySourcePreparer

/** Selection outlives a visible page; never resolve selected paths against only the current page. */
internal object NearbyPickerSelection {
    fun toggle(current: Map<String, FileEntry>, entry: FileEntry): Map<String, FileEntry> = when {
        entry.absolutePath in current -> current - entry.absolutePath
        current.size >= NearbySourcePreparer.MAX_FILES -> current
        else -> current + (entry.absolutePath to entry)
    }

    fun togglePage(current: Map<String, FileEntry>, page: List<FileEntry>): Map<String, FileEntry> {
        if (page.isNotEmpty() && page.all { it.absolutePath in current }) return current - page.map { it.absolutePath }.toSet()
        val result = current.toMutableMap()
        page.forEach { entry -> if (result.size < NearbySourcePreparer.MAX_FILES) result[entry.absolutePath] = entry }
        return result
    }
}
