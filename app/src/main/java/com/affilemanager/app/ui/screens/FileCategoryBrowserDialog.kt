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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.FileCategoryUiState
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.DirectoryLayoutButton
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.SelectionActionBar
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import java.io.File

@Composable
fun FileCategoryBrowserDialog(state: FileCategoryUiState, viewModel: MainViewModel) {
    if (!state.open || state.category == null) return
    var query by remember(state.category) { mutableStateOf("") }
    var selectedParent by remember(state.category) { mutableStateOf<String?>(null) }
    var grid by remember(state.category) { mutableStateOf(true) }
    val parentPaths = remember(state.entries) {
        state.entries.mapNotNull { File(it.absolutePath).parent }.distinct().sorted().take(40)
    }
    val visible = remember(state.entries, query, selectedParent) {
        state.entries.filter { entry ->
            (query.isBlank() || entry.name.contains(query, ignoreCase = true)) &&
                (selectedParent == null || File(entry.absolutePath).parent == selectedParent)
        }
    }
    val visiblePaths = remember(visible) { visible.mapTo(linkedSetOf(), FileEntry::absolutePath) }
    val allSelected = visiblePaths.isNotEmpty() && visiblePaths.all(state.selectedPaths::contains)

    BackHandler { viewModel.closeFileCategory() }
    Dialog(
        onDismissRequest = viewModel::closeFileCategory,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = viewModel::closeFileCategory) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Grįžti"))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        LText(categoryTitle(state.category), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        LText(
                            if (state.truncated) "Rodomi naujausi ${state.entries.size} failai" else "${state.entries.size} failų visoje saugykloje",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DirectoryLayoutButton(
                        grid = grid,
                        testTag = "category_layout_toggle",
                        onToggleLayout = { grid = !grid },
                        onOpenSettings = { grid = !grid },
                    )
                    IconButton(onClick = viewModel::refreshFileCategory, enabled = !state.loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = uiText("Atnaujinti"))
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { LText("Ieškoti šioje kategorijoje") },
                    singleLine = true,
                )
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
                }
                when {
                    state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.error != null && state.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        LText(state.error, color = MaterialTheme.colorScheme.error)
                    }
                    visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LText("Atitinkančių failų nėra")
                    }
                    grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(128.dp),
                        modifier = Modifier.fillMaxSize().testTag("category_grid"),
                        contentPadding = PaddingValues(10.dp, 6.dp, 10.dp, 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = FileEntry::absolutePath) { entry ->
                            CategoryGridItem(entry, entry.absolutePath in state.selectedPaths, state.selectedPaths.isNotEmpty(), viewModel)
                        }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize().testTag("category_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(visible, key = FileEntry::absolutePath) { entry ->
                            CategoryListItem(entry, entry.absolutePath in state.selectedPaths, state.selectedPaths.isNotEmpty(), viewModel)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryListItem(entry: FileEntry, selected: Boolean, selectionMode: Boolean, viewModel: MainViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) viewModel.toggleFileCategorySelection(entry.absolutePath) else viewModel.open(entry) },
            onLongClick = { viewModel.toggleFileCategorySelection(entry.absolutePath) },
        ).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalFileVisual(entry, 46.dp, 46.dp, showThumbnails = true, modifier = Modifier.size(46.dp))
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
private fun CategoryGridItem(entry: FileEntry, selected: Boolean, selectionMode: Boolean, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionMode) viewModel.toggleFileCategorySelection(entry.absolutePath) else viewModel.open(entry) },
            onLongClick = { viewModel.toggleFileCategorySelection(entry.absolutePath) },
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(100.dp).padding(8.dp)) {
            LocalFileVisual(entry, 112.dp, 84.dp, showThumbnails = true, modifier = Modifier.fillMaxSize())
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
