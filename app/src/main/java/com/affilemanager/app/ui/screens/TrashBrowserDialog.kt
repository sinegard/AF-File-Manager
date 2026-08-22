package com.affilemanager.app.ui.screens

import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.TrashBrowserEntry
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.TrashBrowserUiState
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.ui.components.DirectoryQuickSearchField
import com.affilemanager.app.ui.components.LocalFileVisual
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TrashBrowserDialog(
    state: TrashBrowserUiState,
    itemCount: Int,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TrashBrowserEntry?>(null) }
    var searchVisible by remember(state.itemId, state.relativePath) { mutableStateOf(false) }
    var searchQuery by remember(state.itemId, state.relativePath) { mutableStateOf("") }
    var menu by remember(state.itemId, state.relativePath) { mutableStateOf(false) }
    var showDisplaySettings by remember(state.itemId, state.relativePath) { mutableStateOf(false) }
    LaunchedEffect(state.itemId, state.relativePath) {
        searchVisible = false
        searchQuery = ""
    }
    var displayedEntries by remember(state.itemId, state.relativePath) { mutableStateOf<List<TrashBrowserEntry>>(emptyList()) }
    var transforming by remember(state.itemId, state.relativePath) { mutableStateOf(false) }
    LaunchedEffect(state.entries, searchQuery, state.sortMode, state.sortDirection) {
        val entries = state.entries
        val query = searchQuery.trim()
        transforming = true
        displayedEntries = emptyList()
        displayedEntries = withContext(Dispatchers.Default) {
            val ordered = orderTrashEntries(entries, state.sortMode, state.sortDirection)
            if (query.isEmpty()) ordered else ordered.filter { it.name.contains(query, ignoreCase = true) }
        }
        transforming = false
    }

    BackHandler(enabled = preview == null) { viewModel.navigateTrashBack() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("trash-browser-dialog"),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                DirectoryBrowserToolbar(
                    title = state.rootName ?: uiText("Šiukšliadėžė"),
                    path = trashLocationLabel(state),
                    backEnabled = true,
                    forwardEnabled = false,
                    upEnabled = state.itemId != null,
                    searchActive = searchVisible,
                    grid = state.grid,
                    testTagPrefix = "trash",
                    onBack = { viewModel.navigateTrashBack() },
                    onForward = {},
                    onUp = { viewModel.navigateTrashBack() },
                    onToggleSearch = {
                        searchVisible = !searchVisible
                        if (!searchVisible) searchQuery = ""
                    },
                    onToggleLayout = viewModel::toggleTrashLayout,
                    onOpenSettings = { showDisplaySettings = true },
                ) {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Aplanko veiksmai"))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DirectoryDisplayMenuItems(
                                grid = state.grid,
                                includeHidden = false,
                                hiddenFilesAvailable = false,
                                showThumbnails = state.showThumbnails,
                                thumbnailsAvailable = true,
                                sortMode = state.sortMode,
                                sortDirection = state.sortDirection,
                                displaySettingsTestTag = "trash_display_settings",
                                onToggleHidden = {},
                                onToggleLayout = viewModel::toggleTrashLayout,
                                onToggleThumbnails = viewModel::toggleTrashThumbnails,
                                onOpenSettings = { showDisplaySettings = true },
                                onSort = { mode -> viewModel.setTrashSort(mode, state.sortDirection) },
                                onDismissMenu = { menu = false },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { LText("Atnaujinti") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                enabled = !state.loading && !state.emptying,
                                onClick = { menu = false; viewModel.refreshTrashBrowser() },
                            )
                            DropdownMenuItem(
                                text = { LText("Išvalyti visą šiukšliadėžę") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
                                enabled = itemCount > 0 && !state.emptying,
                                onClick = { menu = false; confirmEmpty = true },
                            )
                        }
                    }
                }
                if (searchVisible) {
                    DirectoryQuickSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onClose = { searchVisible = false; searchQuery = "" },
                        modifier = Modifier.testTag("directory_search_field_trash"),
                    )
                }
                HorizontalDivider()
                if (state.loading || state.emptying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.error != null -> TrashEmptyState("Katalogo atidaryti nepavyko", state.error)
                        state.loading && state.entries.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        transforming && displayedEntries.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        state.entries.isEmpty() -> TrashEmptyState(
                            if (state.itemId == null) "Šiukšliadėžė tuščia" else "Katalogas tuščias",
                            if (state.itemId == null) "Ištrinti elementai bus rodomi čia." else "Šiame kataloge nėra failų.",
                        )
                        displayedEntries.isEmpty() -> TrashEmptyState("Atitikmenų nerasta", "Pabandykite kitą pavadinimą")
                        state.grid -> LazyVerticalGrid(
                            columns = GridCells.Fixed(state.gridColumns.coerceIn(1, 6)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy((8f * state.spacingScalePercent / 100f).dp),
                            verticalArrangement = Arrangement.spacedBy((8f * state.spacingScalePercent / 100f).dp),
                        ) {
                            gridItems(displayedEntries, key = { "${it.itemId}|${it.relativePath}|${it.storedPath}" }) { entry ->
                                TrashBrowserGridItem(
                                    entry = entry,
                                    showThumbnails = state.showThumbnails,
                                    iconScalePercent = state.iconScalePercent,
                                    onOpen = { viewModel.openTrashEntry(entry) },
                                    onRestore = { viewModel.restoreTrash(entry.itemId) },
                                    onDelete = { deleteTarget = entry },
                                )
                            }
                        }
                        else -> LazyColumn(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            items(displayedEntries, key = { "${it.itemId}|${it.relativePath}|${it.storedPath}" }) { entry ->
                                TrashBrowserRow(
                                    entry = entry,
                                    showThumbnails = state.showThumbnails,
                                    iconScalePercent = state.iconScalePercent,
                                    spacingScalePercent = state.spacingScalePercent,
                                    onOpen = { viewModel.openTrashEntry(entry) },
                                    onRestore = { viewModel.restoreTrash(entry.itemId) },
                                    onDelete = { deleteTarget = entry },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { LText("Išvalyti visą šiukšliadėžę?") },
            text = { LText("Visi $itemCount šiukšliadėžėje esantys elementai bus ištrinti visam laikui ir jų atkurti nebebus galima.") },
            confirmButton = {
                Button(onClick = { confirmEmpty = false; viewModel.emptyTrash() }) { LText("Išvalyti viską") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { LText("Atšaukti") } },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { LText("Ištrinti visam laikui?") },
            text = { LText("„${entry.name}“ nebebus galima atkurti iš programos šiukšliadėžės.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteTrashForever(entry.itemId); deleteTarget = null }) {
                    LText("Ištrinti visam laikui")
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { LText("Atšaukti") } },
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
                viewModel.setTrashDisplaySettings(it)
                showDisplaySettings = false
            },
            onApplySort = viewModel::setTrashSort,
        )
    }
}

@Composable
private fun TrashBrowserRow(
    entry: TrashBrowserEntry,
    showThumbnails: Boolean,
    iconScalePercent: Int,
    spacingScalePercent: Int,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val fileEntry = remember(entry) { entry.toFileEntry() }
    val dateFormat = rememberLocalizedDateTimeFormat()
    val iconSize = (52f * iconScalePercent / 100f).dp
    val verticalPadding = (10f * spacingScalePercent / 100f).dp
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalFileVisual(
                entry = fileEntry,
                targetWidth = iconSize,
                targetHeight = iconSize,
                showThumbnails = showThumbnails,
                modifier = Modifier.size(iconSize),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LText(trashEntryMeta(entry, dateFormat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.originalPath?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (entry.topLevel) {
                IconButton(onClick = onRestore) { Icon(Icons.Rounded.Restore, contentDescription = uiText("Atkurti ${entry.name}")) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteForever, contentDescription = uiText("Ištrinti ${entry.name} visam laikui")) }
            }
        }
    }
}

@Composable
private fun TrashBrowserGridItem(
    entry: TrashBrowserEntry,
    showThumbnails: Boolean,
    iconScalePercent: Int,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val fileEntry = remember(entry) { entry.toFileEntry() }
    val iconSize = (72f * iconScalePercent / 100f).dp
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LocalFileVisual(
                entry = fileEntry,
                targetWidth = iconSize,
                targetHeight = iconSize,
                showThumbnails = showThumbnails,
                modifier = Modifier.size(iconSize),
            )
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.topLevel) {
                Row {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Rounded.Restore, contentDescription = uiText("Atkurti ${entry.name}"))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = uiText("Ištrinti ${entry.name} visam laikui"))
                    }
                }
            }
        }
    }
}

private fun orderTrashEntries(
    entries: List<TrashBrowserEntry>,
    mode: SortMode,
    direction: SortDirection,
): List<TrashBrowserEntry> {
    val ascending = when (mode) {
        SortMode.NAME -> compareBy<TrashBrowserEntry> { it.name.lowercase(Locale.ROOT) }
        SortMode.SIZE -> compareBy<TrashBrowserEntry> { it.sizeBytes }.thenBy { it.name.lowercase(Locale.ROOT) }
        SortMode.MODIFIED -> compareBy<TrashBrowserEntry> { it.modifiedAtMillis }.thenBy { it.name.lowercase(Locale.ROOT) }
        SortMode.TYPE -> compareBy<TrashBrowserEntry> { it.kind }.thenBy { it.name.lowercase(Locale.ROOT) }
    }
    val comparator = if (direction == SortDirection.ASCENDING) ascending else ascending.reversed()
    val (directories, files) = entries.partition(TrashBrowserEntry::isDirectory)
    return directories.sortedWith(comparator) + files.sortedWith(comparator)
}

@Composable
private fun trashLocationLabel(state: TrashBrowserUiState): String = buildString {
    append(uiText("Šiukšliadėžė"))
    state.rootName?.let { append(" / ").append(it) }
    if (state.relativePath.isNotEmpty()) append(" / ").append(state.relativePath)
}

private fun trashEntryMeta(entry: TrashBrowserEntry, dateFormat: DateFormat): String = when {
    entry.deletedAtMillis != null -> buildString {
        append(if (entry.isDirectory) "Katalogas" else FileSystemRules.humanBytes(entry.sizeBytes))
        append(" · ")
        append(dateFormat.format(Date(entry.deletedAtMillis)))
    }
    entry.isDirectory -> "Katalogas"
    else -> FileSystemRules.humanBytes(entry.sizeBytes)
}

@Composable
private fun TrashEmptyState(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        LText(title, style = MaterialTheme.typography.titleMedium)
        LText(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
