package com.affilemanager.app.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryGridStyle
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.FileCategoryUiState
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.ui.components.DirectoryQuickSearchField
import com.affilemanager.app.ui.components.FileInfoDialog
import com.affilemanager.app.ui.components.FileSizeBar
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.SelectionActionBar
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext

private const val CATEGORY_PREFETCH_DISTANCE = 24

private data class CategoryTransform(
    val sourceEntryCount: Int = 0,
    val entries: List<FileEntry> = emptyList(),
    val parentPaths: List<String> = emptyList(),
)

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
    var infoTarget by remember(state.category) { mutableStateOf<FileEntry?>(null) }
    var confirmTrash by remember(state.category) { mutableStateOf(false) }
    val exportAppsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.exportSelectedInstalledApps(uri)
    }
    LaunchedEffect(state.category) {
        searchVisible = false
        query = ""
    }
    var transformed by remember(state.category) { mutableStateOf(CategoryTransform()) }
    var transforming by remember(state.category) { mutableStateOf(false) }
    LaunchedEffect(state.entries, query, selectedParent, state.sortMode, state.sortDirection) {
        val entries = state.entries
        val requestedQuery = query
        val requestedParent = selectedParent
        transforming = true
        transformed = withContext(Dispatchers.Default) {
            CategoryTransform(
                sourceEntryCount = entries.size,
                entries = entries.filter { entry ->
                    (requestedQuery.isBlank() || entry.name.contains(requestedQuery, ignoreCase = true)) &&
                        (requestedParent == null || File(entry.absolutePath).parent == requestedParent)
                },
                parentPaths = entries.asSequence()
                    .mapNotNull { File(it.absolutePath).parent }
                    .distinct()
                    .sorted()
                    .take(40)
                    .toList(),
            )
        }
        transforming = false
    }
    val visible = transformed.entries
    val largestSizeBytes = remember(visible, state.sortMode) {
        visible.takeIf { state.sortMode == SortMode.SIZE }
            ?.asSequence()
            ?.filter(FileEntry::metadataComplete)
            ?.maxOfOrNull(FileEntry::sizeBytes)
    }
    val parentPaths = transformed.parentPaths
    val visiblePaths = remember(visible) { visible.mapTo(linkedSetOf(), FileEntry::absolutePath) }
    val allSelected = visiblePaths.isNotEmpty() && visiblePaths.all(state.selectedPaths::contains)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(state.category, state.grid, state.nextOffset, state.loadingMore, visible.size) {
        if (transformed.sourceEntryCount != state.entries.size ||
            state.nextOffset == null || state.loading || state.loadingMore || visible.isEmpty()
        ) return@LaunchedEffect
        snapshotFlow {
            if (state.grid) gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            else listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .filter { lastVisible -> lastVisible >= visible.lastIndex - CATEGORY_PREFETCH_DISTANCE }
            .take(1)
            .collect { viewModel.loadMoreFileCategory() }
    }

    BackHandler {
        if (state.selectedPaths.isNotEmpty()) viewModel.clearFileCategorySelection()
        else viewModel.closeFileCategory()
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
                if (state.selectedPaths.isNotEmpty()) {
                    SelectionActionBar(
                        count = state.selectedPaths.size,
                        allSelected = allSelected,
                        onClose = viewModel::clearFileCategorySelection,
                        onToggleSelectAll = { viewModel.toggleAllFileCategoryEntries(visiblePaths) },
                    ) {
                        if (state.category == FileCategory.INSTALLED_APPS) {
                            IconButton(
                                enabled = state.selectedPaths.size == 1,
                                onClick = { infoTarget = state.entries.firstOrNull { it.absolutePath in state.selectedPaths } },
                                modifier = Modifier.testTag("category_info"),
                            ) {
                                Icon(Icons.Rounded.Info, contentDescription = uiText("Informacija"))
                            }
                            IconButton(
                                onClick = { exportAppsLauncher.launch(null) },
                                modifier = Modifier.testTag("installed_apps_export"),
                            ) {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = uiText("Išsaugoti APK"))
                            }
                            IconButton(
                                enabled = state.selectedPaths.size == 1,
                                onClick = viewModel::uninstallSelectedInstalledApp,
                                modifier = Modifier.testTag("installed_apps_uninstall"),
                            ) {
                                Icon(Icons.Rounded.DeleteForever, contentDescription = uiText("Pašalinti programą"), tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(onClick = { viewModel.copyFileCategorySelection(move = false) }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti"))
                            }
                            IconButton(onClick = { viewModel.copyFileCategorySelection(move = false, append = true) }) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = uiText("Kopijuoti daugiau"))
                            }
                            IconButton(
                                enabled = state.selectedPaths.size == 1,
                                onClick = { infoTarget = state.entries.firstOrNull { it.absolutePath in state.selectedPaths } },
                                modifier = Modifier.testTag("category_info"),
                            ) {
                                Icon(Icons.Rounded.Info, contentDescription = uiText("Informacija"))
                            }
                            IconButton(onClick = { viewModel.copyFileCategorySelection(move = true) }) {
                                Icon(Icons.Rounded.ContentCut, contentDescription = uiText("Perkelti"))
                            }
                            IconButton(
                                onClick = { confirmTrash = true },
                                modifier = Modifier.testTag("category_delete"),
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = uiText("Į šiukšlinę"), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                } else {
                    DirectoryBrowserToolbar(
                        title = uiText(categoryTitle(state.category)),
                        path = uiText(
                            when {
                                state.loadingMore -> "Rodoma ${state.entries.size} failų · kraunama daugiau"
                                state.nextOffset != null -> "Rodoma ${state.entries.size} failų · daugiau slenkant žemyn"
                                state.truncated -> "Rodomi pirmi ${state.entries.size} failai pagal pasirinktą tvarką"
                                else -> "${state.entries.size} failų visoje saugykloje"
                            },
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
                            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("category_more")) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Aplanko veiksmai"))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (state.category == FileCategory.INSTALLED_APPS) {
                                    DropdownMenuItem(
                                        text = { LText("Rodyti sistemines programas") },
                                        leadingIcon = {
                                            Checkbox(
                                                checked = state.showSystemApps,
                                                onCheckedChange = null,
                                            )
                                        },
                                        modifier = Modifier.testTag("installed_apps_show_system"),
                                        onClick = { menu = false; viewModel.toggleInstalledSystemApps() },
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
                                    displaySettingsTestTag = "category_display_settings",
                                    onToggleHidden = {},
                                    onToggleLayout = viewModel::toggleFileCategoryLayout,
                                    onToggleThumbnails = viewModel::toggleFileCategoryThumbnails,
                                    onOpenSettings = { showDisplaySettings = true },
                                    onSort = { mode ->
                                        val direction = if (mode == state.sortMode) {
                                            if (state.sortDirection == com.affilemanager.app.model.SortDirection.ASCENDING) {
                                                com.affilemanager.app.model.SortDirection.DESCENDING
                                            } else {
                                                com.affilemanager.app.model.SortDirection.ASCENDING
                                            }
                                        } else {
                                            com.affilemanager.app.model.SortDirection.ASCENDING
                                        }
                                        viewModel.setFileCategorySort(mode, direction)
                                    },
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
                if (state.error != null && state.entries.isNotEmpty()) {
                    LText(
                        state.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                        state = gridState,
                        modifier = Modifier.fillMaxSize().testTag("category_grid"),
                        contentPadding = PaddingValues(10.dp, 6.dp, 10.dp, 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = FileEntry::absolutePath, contentType = { it.kind }) { entry ->
                            CategoryGridItem(
                                entry,
                                entry.absolutePath in state.selectedPaths,
                                state.selectedPaths.isNotEmpty(),
                                state.showThumbnails,
                                state.iconScalePercent,
                                state.spacingScalePercent,
                                state.gridStyle,
                                largestSizeBytes,
                                viewModel,
                            )
                        }
                        if (state.loadingMore) {
                            item(key = "category_loading_more", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("category_list"),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(visible, key = FileEntry::absolutePath, contentType = { it.kind }) { entry ->
                            CategoryListItem(
                                entry,
                                entry.absolutePath in state.selectedPaths,
                                state.selectedPaths.isNotEmpty(),
                                state.showThumbnails,
                                state.iconScalePercent,
                                state.spacingScalePercent,
                                largestSizeBytes,
                                viewModel,
                            )
                            HorizontalDivider()
                        }
                        if (state.loadingMore) {
                            item(key = "category_loading_more", contentType = "loading") {
                                Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            }
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
                gridStyle = state.gridStyle,
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
            onApplyToAll = { settings, mode, direction ->
                viewModel.applyDirectoryDisplaySettingsToAll(settings, mode, direction)
                showDisplaySettings = false
            },
        )
    }
    infoTarget?.let { entry -> FileInfoDialog(entry = entry, onDismiss = { infoTarget = null }) }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { LText("Perkelti į šiukšlinę?") },
            text = { LText("Pasirinkti failai bus perkelti į AF File Manager šiukšlinę ir juos bus galima atkurti.") },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { LText("Atšaukti") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTrash = false
                        viewModel.trashFileCategorySelection()
                    },
                    modifier = Modifier.testTag("category_delete_confirm"),
                ) { LText("Perkelti") }
            },
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
    largestSizeBytes: Long?,
    viewModel: MainViewModel,
) {
    val iconSize = (46f * iconScalePercent / 100f).dp
    val verticalPadding = (8f * spacingScalePercent / 100f).dp
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) viewModel.toggleFileCategorySelection(entry.absolutePath) else viewModel.openFileCategoryEntry(entry) },
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
            if (largestSizeBytes != null && entry.metadataComplete) {
                FileSizeBar(
                    sizeBytes = entry.sizeBytes,
                    largestSizeBytes = largestSizeBytes,
                    identity = entry.absolutePath,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
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
    gridStyle: DirectoryGridStyle,
    largestSizeBytes: Long?,
    viewModel: MainViewModel,
) {
    val height = (100f * iconScalePercent / 100f).dp
    val padding = (8f * spacingScalePercent / 100f).dp
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) viewModel.toggleFileCategorySelection(entry.absolutePath) else viewModel.openFileCategoryEntry(entry) },
            onLongClick = { viewModel.toggleFileCategorySelection(entry.absolutePath) },
        ),
        shape = if (gridStyle == DirectoryGridStyle.CLASSIC) androidx.compose.foundation.shape.RoundedCornerShape(4.dp) else androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else if (gridStyle == DirectoryGridStyle.CLASSIC) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else MaterialTheme.colorScheme.surfaceContainer,
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
        if (largestSizeBytes != null && entry.metadataComplete) {
            FileSizeBar(
                sizeBytes = entry.sizeBytes,
                largestSizeBytes = largestSizeBytes,
                identity = entry.absolutePath,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}

private fun categoryTitle(category: FileCategory): String = when (category) {
    FileCategory.IMAGES -> "Vaizdai"
    FileCategory.VIDEOS -> "Vaizdo įrašai"
    FileCategory.AUDIO -> "Garso įrašai"
    FileCategory.DOCUMENTS -> "Dokumentai"
    FileCategory.ARCHIVES -> "Archyvai"
    FileCategory.APPS -> "Programos"
    FileCategory.INSTALLED_APPS -> "Įdiegtos programos"
}
