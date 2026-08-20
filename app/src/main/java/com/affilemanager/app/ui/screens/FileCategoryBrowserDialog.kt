package com.affilemanager.app.ui.screens

import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.FileEntryOrdering
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.FileCategoryUiState
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.ui.components.DirectoryQuickSearchField
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.SelectionActionBar
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FileCategoryBrowser(
    state: FileCategoryUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    if (!state.open || state.category == null) return
    var searchVisible by remember(state.category) { mutableStateOf(false) }
    var query by remember(state.category) { mutableStateOf("") }
    var selectedParent by remember(state.category) { mutableStateOf<String?>(null) }
    var menu by remember(state.category) { mutableStateOf(false) }
    var showDisplaySettings by remember(state.category) { mutableStateOf(false) }
    LaunchedEffect(state.category) {
        searchVisible = false
        query = ""
    }
    val parentPaths = remember(state.entries) {
        state.entries.mapNotNull { File(it.absolutePath).parent }.distinct().sorted().take(40)
    }
    var visible by remember(state.category) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var transforming by remember(state.category) { mutableStateOf(false) }
    LaunchedEffect(state.entries, query, selectedParent, state.sortMode, state.sortDirection) {
        val entries = state.entries
        val requestedQuery = query
        val requestedParent = selectedParent
        transforming = true
        visible = emptyList()
        visible = withContext(Dispatchers.Default) {
            FileEntryOrdering.order(entries, state.sortMode, state.sortDirection).filter { entry ->
                (requestedQuery.isBlank() || entry.name.contains(requestedQuery, ignoreCase = true)) &&
                    (requestedParent == null || File(entry.absolutePath).parent == requestedParent)
            }
        }
        transforming = false
    }
    val visiblePaths = remember(visible) { visible.mapTo(linkedSetOf(), FileEntry::absolutePath) }
    val allSelected = visiblePaths.isNotEmpty() && visiblePaths.all(state.selectedPaths::contains)

    BackHandler { viewModel.closeFileCategory() }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
                if (state.selectedPaths.isNotEmpty()) {
                    SelectionActionBar(
                        count = state.selectedPaths.size,
                        allSelected = allSelected,
                        onClose = viewModel::clearFileCategorySelection,
                        onToggleSelectAll = { viewModel.toggleAllFileCategoryEntries(visiblePaths) },
                    ) {
                        IconButton(onClick = { viewModel.copyFileCategorySelection(move = false) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti"))
                        }
                        IconButton(onClick = { viewModel.copyFileCategorySelection(move = false, append = true) }) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = uiText("Kopijuoti daugiau"))
                        }
                        IconButton(onClick = { viewModel.copyFileCategorySelection(move = true) }) {
                            Icon(Icons.Rounded.ContentCut, contentDescription = uiText("Perkelti"))
                        }
                    }
                } else {
                    DirectoryBrowserToolbar(
                        title = uiText(categoryTitle(state.category)),
                        path = uiText(
                            if (state.truncated) "Rodomi naujausi ${state.entries.size} failai"
                            else "${state.entries.size} failų visoje saugykloje",
                        ),
                        backEnabled = true,
                        forwardEnabled = false,
                        upEnabled = false,
                        searchActive = searchVisible,
                        grid = state.grid,
                        testTagPrefix = "category",
                        onBack = viewModel::closeFileCategory,
                        onForward = {},
                        onUp = {},
                        onToggleSearch = {
                            searchVisible = !searchVisible
                            if (!searchVisible) query = ""
                        },
                        onToggleLayout = viewModel::toggleFileCategoryLayout,
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
                                    displaySettingsTestTag = "category_display_settings",
                                    onToggleHidden = {},
                                    onToggleLayout = viewModel::toggleFileCategoryLayout,
                                    onToggleThumbnails = viewModel::toggleFileCategoryThumbnails,
                                    onOpenSettings = { showDisplaySettings = true },
                                    onSort = { mode -> viewModel.setFileCategorySort(mode, state.sortDirection) },
                                    onDismissMenu = { menu = false },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { LText("Atnaujinti") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                    enabled = !state.loading,
                                    onClick = { menu = false; viewModel.refreshFileCategory() },
                                )
                            }
                        }
                    }
                }
                if (searchVisible) {
                    DirectoryQuickSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = { searchVisible = false; query = "" },
                        modifier = Modifier.testTag("directory_search_field_category"),
                    )
                }
                if (parentPaths.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedParent == null,
                                onClick = { selectedParent = null },
                                label = { LText("Visi aplankai") },
                            )
                        }
                        items(parentPaths, key = { it }) { path ->
                            FilterChip(
                                selected = selectedParent == path,
                                onClick = { selectedParent = path },
                                label = { Text(File(path).name.ifBlank { path }, maxLines = 1) },
                            )
                        }
                    }
                }
                when {
                    state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    transforming && visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.error != null && state.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        LText(state.error, color = MaterialTheme.colorScheme.error)
                    }
                    visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LText("Atitinkančių failų nėra")
                    }
                    state.grid -> LazyVerticalGrid(
                        columns = GridCells.Fixed(state.gridColumns.coerceIn(1, 6)),
                        modifier = Modifier.fillMaxSize().testTag("category_grid"),
                        contentPadding = PaddingValues(10.dp, 6.dp, 10.dp, 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = FileEntry::absolutePath) { entry ->
                            CategoryGridItem(
                                entry,
                                entry.absolutePath in state.selectedPaths,
                                state.selectedPaths.isNotEmpty(),
                                state.showThumbnails,
                                state.iconScalePercent,
                                state.spacingScalePercent,
                                viewModel,
                            )
                        }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize().testTag("category_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(visible, key = FileEntry::absolutePath) { entry ->
                            CategoryListItem(
                                entry,
                                entry.absolutePath in state.selectedPaths,
                                state.selectedPaths.isNotEmpty(),
                                state.showThumbnails,
                                state.iconScalePercent,
                                state.spacingScalePercent,
                                viewModel,
                            )
                            HorizontalDivider()
                        }
                    }
                }
        }
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
                viewModel.setFileCategoryDisplaySettings(it)
                showDisplaySettings = false
            },
            onApplySort = viewModel::setFileCategorySort,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryListItem(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    showThumbnails: Boolean,
    iconScalePercent: Int,
    spacingScalePercent: Int,
    viewModel: MainViewModel,
) {
    val iconSize = (46f * iconScalePercent / 100f).dp
    val verticalPadding = (8f * spacingScalePercent / 100f).dp
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) viewModel.toggleFileCategorySelection(entry.absolutePath) else viewModel.open(entry) },
            onLongClick = { viewModel.toggleFileCategorySelection(entry.absolutePath) },
        ).padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalFileVisual(entry, iconSize, iconSize, showThumbnails = showThumbnails, modifier = Modifier.size(iconSize))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                "${File(entry.absolutePath).parent.orEmpty()} · ${FileSystemRules.humanBytes(entry.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionMode) Checkbox(selected, onCheckedChange = { viewModel.toggleFileCategorySelection(entry.absolutePath) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryGridItem(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    showThumbnails: Boolean,
    iconScalePercent: Int,
    spacingScalePercent: Int,
    viewModel: MainViewModel,
) {
    val height = (100f * iconScalePercent / 100f).dp
    val padding = (8f * spacingScalePercent / 100f).dp
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) viewModel.toggleFileCategorySelection(entry.absolutePath) else viewModel.open(entry) },
            onLongClick = { viewModel.toggleFileCategorySelection(entry.absolutePath) },
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(height).padding(padding)) {
            LocalFileVisual(entry, 112.dp, height, showThumbnails = showThumbnails, modifier = Modifier.fillMaxSize())
            if (selectionMode) Checkbox(
                checked = selected,
                onCheckedChange = { viewModel.toggleFileCategorySelection(entry.absolutePath) },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Text(entry.name, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun categoryTitle(category: FileCategory): String = when (category) {
    FileCategory.IMAGES -> "Vaizdai"
    FileCategory.VIDEOS -> "Vaizdo įrašai"
    FileCategory.AUDIO -> "Garso įrašai"
    FileCategory.DOCUMENTS -> "Dokumentai"
    FileCategory.ARCHIVES -> "Archyvai"
    FileCategory.APPS -> "Programos"
}
