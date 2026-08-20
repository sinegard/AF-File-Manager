package com.affilemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.advanced.AdvancedAccessState
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ClipboardMode
import com.affilemanager.app.model.ClipboardSource
import com.affilemanager.app.model.ClipboardState
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.AdvancedBrowserUiState
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryLayoutButton
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.SelectionActionBar
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

@Composable
fun AdvancedStorageBrowserDialog(
    state: AdvancedBrowserUiState,
    access: AdvancedAccessState,
    clipboard: ClipboardState?,
    viewModel: MainViewModel,
) {
    if (!state.open) return
    var menu by remember { mutableStateOf(false) }
    var createDirectory by remember { mutableStateOf(false) }
    var createFile by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDisplaySettings by remember { mutableStateOf(false) }
    val allSelected = state.entries.isNotEmpty() && state.entries.all { it.absolutePath in state.selectedPaths }

    BackHandler { viewModel.navigateAdvancedBack() }
    Dialog(
        onDismissRequest = viewModel::closeAdvancedBrowser,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.navigateAdvancedBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Grįžti"))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        LText(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = state.path.ifBlank { uiText("Jungiama") },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LText(advancedBackendLabel(access.activeBackend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    DirectoryLayoutButton(
                        grid = state.grid,
                        testTag = "advanced_layout_toggle",
                        onToggleLayout = viewModel::toggleAdvancedLayout,
                        onOpenSettings = { menu = true },
                    )
                    IconButton(onClick = viewModel::refreshAdvancedBrowser, enabled = !state.loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = uiText("Atnaujinti"))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Daugiau"))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { LText("Naujas aplankas") },
                                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                                onClick = { menu = false; createDirectory = true },
                            )
                            DropdownMenuItem(
                                text = { LText("Naujas failas") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null) },
                                onClick = { menu = false; createFile = true },
                            )
                            DropdownMenuItem(
                                text = { LText("Įklijuoti") },
                                leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                                enabled = clipboard != null && state.path.isNotBlank(),
                                onClick = { menu = false; viewModel.pasteIntoAdvanced() },
                            )
                            HorizontalDivider()
                            DirectoryDisplayMenuItems(
                                grid = state.grid,
                                includeHidden = state.includeHidden,
                                showThumbnails = false,
                                thumbnailsAvailable = false,
                                sortMode = state.sortMode,
                                sortDirection = state.sortDirection,
                                displaySettingsTestTag = "advanced_display_settings",
                                onToggleHidden = viewModel::toggleAdvancedHidden,
                                onToggleLayout = viewModel::toggleAdvancedLayout,
                                onToggleThumbnails = {},
                                onOpenSettings = { showDisplaySettings = true },
                                onSort = { mode ->
                                    val direction = if (state.sortMode == mode) {
                                        if (state.sortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
                                    } else SortDirection.ASCENDING
                                    viewModel.setAdvancedSort(mode, direction)
                                },
                                onDismissMenu = { menu = false },
                            )
                        }
                    }
                }

                if (state.selectedPaths.isNotEmpty()) {
                    SelectionActionBar(
                        count = state.selectedPaths.size,
                        allSelected = allSelected,
                        onClose = viewModel::clearAdvancedSelection,
                        onToggleSelectAll = viewModel::toggleSelectAllAdvanced,
                        modifier = Modifier.testTag("advanced_selection_bar"),
                    ) {
                        IconButton(onClick = { viewModel.copyAdvancedSelection(move = false) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti"))
                        }
                        if (clipboard?.source == ClipboardSource.PRIVILEGED && clipboard.mode == ClipboardMode.COPY) {
                            IconButton(onClick = { viewModel.copyAdvancedSelection(move = false, append = true) }) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = uiText("Kopijuoti daugiau"))
                            }
                        }
                        IconButton(onClick = { viewModel.copyAdvancedSelection(move = true) }) {
                            Icon(Icons.Rounded.ContentCut, contentDescription = uiText("Perkelti"))
                        }
                        if (state.selectedPaths.size == 1) {
                            IconButton(onClick = { renameTarget = state.entries.firstOrNull { it.absolutePath in state.selectedPaths } }) {
                                Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = uiText("Pervadinti"))
                            }
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Rounded.DeleteForever, contentDescription = uiText("Ištrinti visam laikui"), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                when {
                    state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.error != null && state.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LText("Aplanko atidaryti nepavyko", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            LText(state.error, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = viewModel::refreshAdvancedBrowser) { LText("Bandyti dar kartą") }
                        }
                    }
                    state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LText("Aplankas tuščias")
                    }
                    state.grid -> LazyVerticalGrid(
                        columns = GridCells.Fixed(state.gridColumns.coerceIn(2, 8)),
                        modifier = Modifier.fillMaxSize().testTag("advanced_grid"),
                        contentPadding = PaddingValues(10.dp, 8.dp, 10.dp, 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.entries, key = FileEntry::absolutePath) { entry ->
                            AdvancedGridEntry(entry, entry.absolutePath in state.selectedPaths, state.selectedPaths.isNotEmpty(), viewModel)
                        }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("advanced_list"),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.entries, key = FileEntry::absolutePath) { entry ->
                            AdvancedListEntry(entry, entry.absolutePath in state.selectedPaths, state.selectedPaths.isNotEmpty(), viewModel)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (createDirectory) NameDialog("Naujas aplankas", "Aplanko pavadinimas", { createDirectory = false }) { name ->
        createDirectory = false
        viewModel.createAdvancedDirectory(name)
    }
    if (createFile) NameDialog("Naujas failas", "Failo pavadinimas", { createFile = false }) { name ->
        createFile = false
        viewModel.createAdvancedFile(name)
    }
    renameTarget?.let { entry ->
        NameDialog("Pervadinti", "Naujas pavadinimas", { renameTarget = null }, initial = entry.name) { name ->
            renameTarget = null
            viewModel.renameAdvanced(entry.absolutePath, name)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { LText("Ištrinti visam laikui?") },
            text = {
                LText(
                    "Pasirinkta: ${state.selectedPaths.size}. Šių apsaugotų failų nebus galima atkurti iš AF File Manager šiukšlinės.",
                )
            },
            confirmButton = {
                Button(onClick = { confirmDelete = false; viewModel.deleteAdvancedSelectionPermanently() }) {
                    LText("Ištrinti visam laikui")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { LText("Atšaukti") } },
        )
    }
    if (showDisplaySettings) {
        DirectoryDisplaySettingsDialog(
            initialSettings = DirectoryDisplaySettings(
                layoutMode = if (state.grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
                iconScalePercent = state.iconScalePercent,
                spacingScalePercent = state.spacingScalePercent,
                gridColumns = state.gridColumns,
                showThumbnails = false,
            ),
            thumbnailsAvailable = false,
            onDismiss = { showDisplaySettings = false },
            onApply = {
                viewModel.setAdvancedDisplaySettings(it)
                showDisplaySettings = false
            },
            onApplySort = viewModel::setAdvancedSort,
            initialSortMode = state.sortMode,
            initialSortDirection = state.sortDirection,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdvancedListEntry(entry: FileEntry, selected: Boolean, selectionActive: Boolean, viewModel: MainViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionActive) viewModel.toggleAdvancedSelection(entry.absolutePath) else viewModel.openAdvancedEntry(entry) },
            onLongClick = { viewModel.toggleAdvancedSelection(entry.absolutePath) },
        ).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { viewModel.toggleAdvancedSelection(entry.absolutePath) })
        LocalFileVisual(entry, 48.dp, 48.dp, showThumbnails = false, modifier = Modifier.size(48.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LText(entryMeta(entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdvancedGridEntry(entry: FileEntry, selected: Boolean, selectionActive: Boolean, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionActive) viewModel.toggleAdvancedSelection(entry.absolutePath) else viewModel.openAdvancedEntry(entry) },
            onLongClick = { viewModel.toggleAdvancedSelection(entry.absolutePath) },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Checkbox(checked = selected, onCheckedChange = { viewModel.toggleAdvancedSelection(entry.absolutePath) }, modifier = Modifier.align(Alignment.End))
            LocalFileVisual(entry, 72.dp, 72.dp, showThumbnails = false, modifier = Modifier.size(72.dp))
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            LText(entryMeta(entry), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, initial: String = "", onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { LText(label) }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { LText("Patvirtinti") } },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

private fun entryMeta(entry: FileEntry): String = if (entry.isDirectory) "Aplankas" else FileSystemRules.humanBytes(entry.sizeBytes)

private fun advancedBackendLabel(backend: AdvancedAccessBackend): String = when (backend) {
    AdvancedAccessBackend.NONE -> "Prieiga neaktyvi"
    AdvancedAccessBackend.SHIZUKU_SHELL -> "Shizuku · shell prieiga"
    AdvancedAccessBackend.SHIZUKU_ROOT -> "Shizuku · root prieiga"
    AdvancedAccessBackend.ROOT -> "Root prieiga"
}
