package com.affilemanager.app.ui.screens

import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.SafEntry
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.SafBrowserUiState
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.ui.components.SafFileVisual
import com.affilemanager.app.ui.components.DirectoryQuickSearchField
import com.affilemanager.app.ui.components.DirectorySearchButton
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SafBrowserDialog(
    state: SafBrowserUiState,
    selectedLocalPath: String?,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<SafEntry?>(null) }
    var delete by remember { mutableStateOf<SafEntry?>(null) }
    var info by remember { mutableStateOf<SafEntry?>(null) }
    var menu by remember(state.currentUri) { mutableStateOf(false) }
    var showDisplaySettings by remember(state.currentUri) { mutableStateOf(false) }
    var searchVisible by remember(state.location?.uri) { mutableStateOf(false) }
    var searchQuery by remember(state.location?.uri) { mutableStateOf("") }
    LaunchedEffect(state.currentUri) {
        searchVisible = false
        searchQuery = ""
    }
    var displayedEntries by remember(state.currentUri) { mutableStateOf<List<SafEntry>>(emptyList()) }
    var transforming by remember(state.currentUri) { mutableStateOf(false) }
    LaunchedEffect(state.entries, searchQuery, state.sortMode, state.sortDirection) {
        val entries = state.entries
        val query = searchQuery.trim()
        transforming = true
        displayedEntries = emptyList()
        displayedEntries = withContext(Dispatchers.Default) {
            val ordered = orderSafEntries(entries, state.sortMode, state.sortDirection)
            if (query.isEmpty()) ordered else ordered.filter { it.name.contains(query, ignoreCase = true) }
        }
        transforming = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
                DirectoryBrowserToolbar(
                    title = state.title,
                    path = safLocationLabel(state),
                    backEnabled = true,
                    forwardEnabled = false,
                    upEnabled = state.backStack.isNotEmpty(),
                    searchActive = searchVisible,
                    grid = state.grid,
                    testTagPrefix = "saf",
                    onBack = { if (!viewModel.navigateSafBack()) onDismiss() },
                    onForward = {},
                    onUp = { viewModel.navigateSafBack() },
                    onToggleSearch = {
                        searchVisible = !searchVisible
                        if (!searchVisible) searchQuery = ""
                    },
                    onToggleLayout = viewModel::toggleSafLayout,
                    onOpenSettings = { showDisplaySettings = true },
                ) {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Aplanko veiksmai"))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (selectedLocalPath != null) {
                                DropdownMenuItem(
                                    text = { LText("Kopijuoti pasirinktą vietinį elementą čia") },
                                    leadingIcon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
                                    onClick = { menu = false; viewModel.copyLocalToSaf(selectedLocalPath) },
                                )
                                HorizontalDivider()
                            }
                            DirectoryDisplayMenuItems(
                                grid = state.grid,
                                includeHidden = false,
                                hiddenFilesAvailable = false,
                                showThumbnails = state.showThumbnails,
                                thumbnailsAvailable = true,
                                sortMode = state.sortMode,
                                sortDirection = state.sortDirection,
                                displaySettingsTestTag = "saf_display_settings",
                                onToggleHidden = {},
                                onToggleLayout = viewModel::toggleSafLayout,
                                onToggleThumbnails = viewModel::toggleSafThumbnails,
                                onOpenSettings = { showDisplaySettings = true },
                                onSort = { mode -> viewModel.setSafSort(mode, state.sortDirection) },
                                onDismissMenu = { menu = false },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { LText("Atnaujinti") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                enabled = !state.loading,
                                onClick = { menu = false; viewModel.refreshSafBrowser() },
                            )
                        }
                    }
                }
                if (searchVisible) {
                    DirectoryQuickSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onClose = { searchVisible = false; searchQuery = "" },
                    )
                }
                HorizontalDivider()
                state.error?.let { error ->
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        LText(error, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.loading && state.entries.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (transforming && displayedEntries.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (state.entries.isEmpty() && state.error == null) {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(56.dp))
                            LText("Aplankas tuščias", style = MaterialTheme.typography.titleMedium)
                        }
                    } else if (displayedEntries.isEmpty()) {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(56.dp))
                            LText("Atitikmenų nerasta", style = MaterialTheme.typography.titleMedium)
                            LText("Pabandykite kitą pavadinimą", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (state.grid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(state.gridColumns.coerceIn(1, 6)),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp, 8.dp, 10.dp, 92.dp),
                            horizontalArrangement = Arrangement.spacedBy((8f * state.spacingScalePercent / 100f).dp),
                            verticalArrangement = Arrangement.spacedBy((8f * state.spacingScalePercent / 100f).dp),
                        ) {
                            gridItems(displayedEntries, key = SafEntry::uri) { entry ->
                                SafEntryGridItem(
                                    entry = entry,
                                    showThumbnails = state.showThumbnails,
                                    iconScalePercent = state.iconScalePercent,
                                    onOpen = { viewModel.openSafEntry(entry) },
                                    onRename = { rename = entry },
                                    onInfo = { info = entry },
                                    onDelete = { delete = entry },
                                    onDownload = { viewModel.copySafToLocal(entry) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 92.dp)) {
                            items(displayedEntries, key = SafEntry::uri) { entry ->
                                SafEntryRow(
                                    entry = entry,
                                    showThumbnails = state.showThumbnails,
                                    iconScalePercent = state.iconScalePercent,
                                    spacingScalePercent = state.spacingScalePercent,
                                    onOpen = { viewModel.openSafEntry(entry) },
                                    onRename = { rename = entry },
                                    onInfo = { info = entry },
                                    onDelete = { delete = entry },
                                    onDownload = { viewModel.copySafToLocal(entry) },
                                )
                            }
                        }
                    }
                    FloatingActionButton(onClick = { create = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = uiText("Sukurti"))
                    }
                }
            }
        }
    }

    if (create) {
        SafCreateDialog(
            onDismiss = { create = false },
            onFolder = { name -> viewModel.createSafDirectory(name); create = false },
            onFile = { name -> viewModel.createSafFile(name); create = false },
        )
    }
    rename?.let { entry ->
        SafNameDialog(
            title = "Pervadinti",
            initial = entry.name,
            confirm = "Pervadinti",
            onDismiss = { rename = null },
            onConfirm = { name -> viewModel.renameSafEntry(entry, name); rename = null },
        )
    }
    info?.let { entry ->
        SafInfoDialog(entry = entry, onDismiss = { info = null })
    }
    delete?.let { entry ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { LText("Ištrinti visam laikui?") },
            text = { LText("„${entry.name}“ bus trinamas per Android dokumentų teikėją ir nepateks į AF File Manager šiukšlinę.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteSafEntry(entry); delete = null }) { LText("Ištrinti") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { LText("Atšaukti") } },
        )
    }
    if (showDisplaySettings) {
        DirectoryDisplaySettingsDialog(
            initialSettings = DirectoryDisplaySettings(
                layoutMode = if (state.grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
                iconScalePercent = state.iconScalePercent,
                spacingScalePercent = state.spacingScalePercent,
                gridColumns = state.gridColumns,
                showThumbnails = state.showThumbnails,
            ),
            thumbnailsAvailable = true,
            initialSortMode = state.sortMode,
            initialSortDirection = state.sortDirection,
            onDismiss = { showDisplaySettings = false },
            onApply = {
                viewModel.setSafDisplaySettings(it)
                showDisplaySettings = false
            },
            onApplySort = viewModel::setSafSort,
        )
    }
}

@Composable
private fun SafEntryRow(
    entry: SafEntry,
    showThumbnails: Boolean,
    iconScalePercent: Int,
    spacingScalePercent: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val iconSize = (42f * iconScalePercent / 100f).dp
    val verticalPadding = (4f * spacingScalePercent / 100f).dp
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SafFileVisual(
                    entry = entry,
                    targetWidth = iconSize,
                    targetHeight = iconSize,
                    showThumbnails = showThumbnails,
                    modifier = Modifier.size(iconSize),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.name, fontWeight = if (entry.directory) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!entry.directory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Veiksmai")) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SafEntryMenuItems(
                    entry = entry,
                    dismiss = { menu = false },
                    onOpen = onOpen,
                    onRename = onRename,
                    onInfo = onInfo,
                    onDelete = onDelete,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
private fun SafEntryGridItem(
    entry: SafEntry,
    showThumbnails: Boolean,
    iconScalePercent: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val iconSize = (72f * iconScalePercent / 100f).dp
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                SafFileVisual(
                    entry = entry,
                    targetWidth = iconSize,
                    targetHeight = iconSize,
                    showThumbnails = showThumbnails,
                    modifier = Modifier.size(iconSize).align(Alignment.Center),
                )
                IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Veiksmai"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SafEntryMenuItems(
                        entry = entry,
                        dismiss = { menu = false },
                        onOpen = onOpen,
                        onRename = onRename,
                        onInfo = onInfo,
                        onDelete = onDelete,
                        onDownload = onDownload,
                    )
                }
            }
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!entry.directory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SafEntryMenuItems(
    entry: SafEntry,
    dismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    DropdownMenuItem(
        text = { LText(if (entry.directory) "Atidaryti aplanką" else "Peržiūrėti čia") },
        leadingIcon = { Icon(if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Visibility, contentDescription = null) },
        onClick = { dismiss(); onOpen() },
    )
    DropdownMenuItem(
        text = { LText("Kopijuoti į aktyvų langą") },
        leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
        onClick = { dismiss(); onDownload() },
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { LText("Pervadinti") },
        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        enabled = entry.canWrite,
        onClick = { dismiss(); onRename() },
    )
    DropdownMenuItem(
        text = { LText("Informacija") },
        leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
        onClick = { dismiss(); onInfo() },
    )
    DropdownMenuItem(
        text = { LText("Ištrinti") },
        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        enabled = entry.canWrite,
        onClick = { dismiss(); onDelete() },
    )
}

private fun safLocationLabel(state: SafBrowserUiState): String = buildString {
    append(state.location?.title ?: state.title)
    state.backStack.drop(1).forEach { (_, title) -> append(" / ").append(title) }
    if (state.backStack.isNotEmpty()) append(" / ").append(state.title)
}

private fun orderSafEntries(
    entries: List<SafEntry>,
    mode: SortMode,
    direction: SortDirection,
): List<SafEntry> {
    val ascending = when (mode) {
        SortMode.NAME -> compareBy<SafEntry> { it.name.lowercase(Locale.ROOT) }
        SortMode.SIZE -> compareBy<SafEntry> { it.sizeBytes }.thenBy { it.name.lowercase(Locale.ROOT) }
        SortMode.MODIFIED -> compareBy<SafEntry> { it.modifiedAtMillis }.thenBy { it.name.lowercase(Locale.ROOT) }
        SortMode.TYPE -> compareBy<SafEntry> { it.kind }.thenBy { it.name.lowercase(Locale.ROOT) }
    }
    val comparator = if (direction == SortDirection.ASCENDING) ascending else ascending.reversed()
    val (directories, files) = entries.partition(SafEntry::directory)
    return directories.sortedWith(comparator) + files.sortedWith(comparator)
}

@Composable
private fun SafInfoDialog(entry: SafEntry, onDismiss: () -> Unit) {
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LText(if (entry.directory) "Aplankas" else entry.mimeType ?: "Failas")
                if (!entry.directory) SafInfoLine("Dydis", FileSystemRules.humanBytes(entry.sizeBytes))
                entry.modifiedAtMillis.takeIf { it > 0L }?.let { SafInfoLine("Pakeista", dateFormat.format(Date(it))) }
                Text(entry.uri, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { LText("Uždaryti") } },
    )
}

@Composable
private fun SafInfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LText(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SafCreateDialog(onDismiss: () -> Unit, onFolder: (String) -> Unit, onFile: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
        title = { LText("Sukurti") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { LText("Pavadinimas") }, singleLine = true) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFile(name) }, enabled = name.isNotBlank()) { LText("Failą") }
                Button(onClick = { onFolder(name) }, enabled = name.isNotBlank()) { LText("Aplanką") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

@Composable
private fun SafNameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { LText("Pavadinimas") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { LText(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}
