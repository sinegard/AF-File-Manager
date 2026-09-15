package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import com.affilemanager.app.ui.theme.AfFilterChip as FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.localization.LText

enum class DirectoryEntryFilter {
    FOLDERS,
    FILES,
    DOCUMENTS,
    ARCHIVES,
    IMAGES,
    VIDEOS,
    MUSIC,
    APKS,
}

object DirectoryEntryFilterRules {
    fun visibleEntries(
        entries: List<FileEntry>,
        query: String,
        selected: Set<DirectoryEntryFilter>,
    ): List<FileEntry> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() && selected.isEmpty()) return entries
        return entries.filter { entry ->
            (normalizedQuery.isEmpty() || entry.name.contains(normalizedQuery, ignoreCase = true)) &&
                matches(entry.kind, selected)
        }
    }

    fun matches(kind: EntryKind, selected: Set<DirectoryEntryFilter>): Boolean {
        if (selected.isEmpty()) return true
        return when {
            kind == EntryKind.DIRECTORY -> DirectoryEntryFilter.FOLDERS in selected
            DirectoryEntryFilter.FILES in selected -> true
            kind == EntryKind.DOCUMENT -> DirectoryEntryFilter.DOCUMENTS in selected
            kind == EntryKind.ARCHIVE -> DirectoryEntryFilter.ARCHIVES in selected
            kind == EntryKind.IMAGE -> DirectoryEntryFilter.IMAGES in selected
            kind == EntryKind.VIDEO -> DirectoryEntryFilter.VIDEOS in selected
            kind == EntryKind.AUDIO -> DirectoryEntryFilter.MUSIC in selected
            kind == EntryKind.APK -> DirectoryEntryFilter.APKS in selected
            else -> false
        }
    }
}

@Composable
fun DirectoryEntryFilterDialog(
    selected: Set<DirectoryEntryFilter>,
    onSelectedChange: (Set<DirectoryEntryFilter>) -> Unit,
    onDismiss: () -> Unit,
) {
    AfModalDialog(
        title = "Filtras",
        subtitle = "Nepasirinkus nė vieno tipo rodomi visi elementai.",
        icon = Icons.Rounded.FilterAlt,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = { onSelectedChange(emptySet()) }) { LText("Išvalyti") }
            TextButton(onClick = onDismiss) { LText("Atlikta") }
        },
    ) {
        DirectoryEntryFilterChoices(selected, onSelectedChange)
    }
}

/** Shared wrapping surface; tests inject already translated labels without cloning the layout. */
@Composable
internal fun DirectoryEntryFilterChoices(
    selected: Set<DirectoryEntryFilter>,
    onSelectedChange: (Set<DirectoryEntryFilter>) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (DirectoryEntryFilter) -> Unit = { option -> LText(option.label) },
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DirectoryEntryFilter.entries.forEach { option ->
            FilterChip(
                modifier = Modifier.testTag("directory_filter_${option.name.lowercase()}"),
                selected = option in selected,
                onClick = {
                    onSelectedChange(
                        if (option in selected) selected - option else selected + option,
                    )
                },
                label = { label(option) },
            )
        }
    }
}

internal val DirectoryEntryFilter.label: String
    get() = when (this) {
        DirectoryEntryFilter.FOLDERS -> "Aplankai"
        DirectoryEntryFilter.FILES -> "Visi failai"
        DirectoryEntryFilter.DOCUMENTS -> "Dokumentai"
        DirectoryEntryFilter.ARCHIVES -> "Archyvai"
        DirectoryEntryFilter.IMAGES -> "Nuotraukos"
        DirectoryEntryFilter.VIDEOS -> "Vaizdo įrašai"
        DirectoryEntryFilter.MUSIC -> "Muzika"
        DirectoryEntryFilter.APKS -> "APK"
    }
