package com.affilemanager.app.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.IndeterminateCheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.archive.ArchiveFormat
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.model.ClipboardMode
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.data.PanelWorkspace
import com.affilemanager.app.data.FileTagDefinition
import com.affilemanager.app.data.RecentItem
import com.affilemanager.app.data.TaggedFileRecord
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.PanelUiState
import com.affilemanager.app.ui.PanelComparisonStatus
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.preview.PreviewSource
import com.affilemanager.app.ui.preview.openWith
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesScreen(
    viewModel: MainViewModel,
    contentPadding: PaddingValues,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
) {
    val left by viewModel.leftPanel.collectAsStateWithLifecycle()
    val right by viewModel.rightPanel.collectAsStateWithLifecycle()
    val leftTabs by viewModel.leftTabs.collectAsStateWithLifecycle()
    val rightTabs by viewModel.rightTabs.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val filesHomeVisible by viewModel.filesHomeVisible.collectAsStateWithLifecycle()
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val renameUndo by viewModel.renameUndo.collectAsStateWithLifecycle()
    val tagSnapshot by viewModel.tagSnapshot.collectAsStateWithLifecycle()
    val panelComparison by viewModel.panelComparison.collectAsStateWithLifecycle()

    var createFor by remember { mutableStateOf<PanelId?>(null) }
    var renameTarget by remember { mutableStateOf<Pair<PanelId, FileEntry>?>(null) }
    var trashPanel by remember { mutableStateOf<PanelId?>(null) }
    var archivePanel by remember { mutableStateOf<PanelId?>(null) }
    var pastePanel by remember { mutableStateOf<PanelId?>(null) }
    var tagPanel by remember { mutableStateOf<PanelId?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val state = if (activePanel == PanelId.LEFT) left else right
                when {
                    event.isCtrlPressed && event.isShiftPressed && event.key == Key.T -> {
                        viewModel.restoreClosedTab(activePanel); true
                    }
                    event.isCtrlPressed && event.key == Key.T -> {
                        viewModel.newTab(activePanel); true
                    }
                    event.isCtrlPressed && event.key == Key.W -> {
                        viewModel.closeActiveTab(activePanel); true
                    }
                    event.isCtrlPressed && event.key == Key.C -> {
                        viewModel.copySelection(activePanel, move = false); true
                    }
                    event.isCtrlPressed && event.key == Key.X -> {
                        viewModel.copySelection(activePanel, move = true); true
                    }
                    event.isCtrlPressed && event.key == Key.V && clipboard != null -> {
                        pastePanel = activePanel; true
                    }
                    event.isCtrlPressed && event.key == Key.A -> {
                        viewModel.selectAll(activePanel); true
                    }
                    event.isAltPressed && event.key == Key.DirectionLeft -> viewModel.navigateBack(activePanel)
                    event.isAltPressed && event.key == Key.DirectionRight -> viewModel.navigateForward(activePanel)
                    event.key == Key.Backspace -> viewModel.navigateUp(activePanel)
                    event.key == Key.F5 -> {
                        viewModel.refreshPanel(activePanel); true
                    }
                    event.key == Key.Delete && state.selectedPaths.isNotEmpty() -> {
                        trashPanel = activePanel; true
                    }
                    event.key == Key.F2 && state.selectedPaths.isNotEmpty() -> {
                        if (state.selectedPaths.size == 1) {
                            state.entries.firstOrNull { it.absolutePath in state.selectedPaths }?.let { renameTarget = activePanel to it }
                        } else {
                            viewModel.beginBatchRename(state.entries.filter { it.absolutePath in state.selectedPaths }.map(FileEntry::absolutePath))
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        val dualPane = maxWidth >= 780.dp
        val compactToolbar = (if (dualPane) maxWidth / 2 else maxWidth) < 600.dp
        Column(modifier = Modifier.fillMaxSize()) {
            if (!hasAllFilesAccess) {
                PermissionBanner(onRequestAllFilesAccess)
            }
            if (filesHomeVisible) {
                FilesHome(
                    roots = roots,
                    onOpen = { path -> viewModel.navigate(activePanel, path) },
                )
            } else if (dualPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    FilePanel(
                        panelId = PanelId.LEFT,
                        state = left,
                        tabs = leftTabs,
                        active = activePanel == PanelId.LEFT,
                        clipboardAvailable = clipboard != null,
                        batchRenameUndoAvailable = renameUndo != null,
                        compactToolbar = compactToolbar,
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                        onCreate = { createFor = PanelId.LEFT },
                        onRename = { renameTarget = PanelId.LEFT to it },
                        onTrash = { trashPanel = PanelId.LEFT },
                        onArchive = { archivePanel = PanelId.LEFT },
                        onTag = { tagPanel = PanelId.LEFT },
                        onCopyToOther = { viewModel.copySelection(PanelId.LEFT, move = false); pastePanel = PanelId.RIGHT },
                        onPaste = { pastePanel = PanelId.LEFT },
                        onUndoBatchRename = viewModel::undoBatchRename,
                    )
                    VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    FilePanel(
                        panelId = PanelId.RIGHT,
                        state = right,
                        tabs = rightTabs,
                        active = activePanel == PanelId.RIGHT,
                        clipboardAvailable = clipboard != null,
                        batchRenameUndoAvailable = renameUndo != null,
                        compactToolbar = compactToolbar,
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                        onCreate = { createFor = PanelId.RIGHT },
                        onRename = { renameTarget = PanelId.RIGHT to it },
                        onTrash = { trashPanel = PanelId.RIGHT },
                        onArchive = { archivePanel = PanelId.RIGHT },
                        onTag = { tagPanel = PanelId.RIGHT },
                        onCopyToOther = { viewModel.copySelection(PanelId.RIGHT, move = false); pastePanel = PanelId.LEFT },
                        onPaste = { pastePanel = PanelId.RIGHT },
                        onUndoBatchRename = viewModel::undoBatchRename,
                    )
                }
            } else {
                val panelState = if (activePanel == PanelId.LEFT) left else right
                FilePanel(
                    panelId = activePanel,
                    state = panelState,
                    tabs = if (activePanel == PanelId.LEFT) leftTabs else rightTabs,
                    active = true,
                    clipboardAvailable = clipboard != null,
                    batchRenameUndoAvailable = renameUndo != null,
                    compactToolbar = compactToolbar,
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    onCreate = { createFor = activePanel },
                    onRename = { renameTarget = activePanel to it },
                    onTrash = { trashPanel = activePanel },
                    onArchive = { archivePanel = activePanel },
                    onTag = { tagPanel = activePanel },
                    onCopyToOther = {
                        viewModel.copySelection(activePanel, move = false)
                        pastePanel = if (activePanel == PanelId.LEFT) PanelId.RIGHT else PanelId.LEFT
                    },
                    onPaste = { pastePanel = activePanel },
                    onUndoBatchRename = viewModel::undoBatchRename,
                )
            }
        }
    }

    createFor?.let { panel ->
        CreateItemDialog(
            onDismiss = { createFor = null },
            onCreateFolder = { name -> viewModel.createDirectory(panel, name); createFor = null },
            onCreateFile = { name -> viewModel.createFile(panel, name); createFor = null },
        )
    }
    renameTarget?.let { (panel, entry) ->
        TextInputDialog(
            title = "Pervadinti",
            initial = entry.name,
            confirmLabel = "Pervadinti",
            onDismiss = { renameTarget = null },
            onConfirm = { name -> viewModel.rename(panel, entry.absolutePath, name); renameTarget = null },
        )
    }
    trashPanel?.let { panel ->
        val count = if (panel == PanelId.LEFT) left.selectedPaths.size else right.selectedPaths.size
        AlertDialog(
            onDismissRequest = { trashPanel = null },
            title = { Text("Perkelti į šiukšlinę?") },
            text = { Text("Pasirinkta: $count. Failus bus galima atkurti skiltyje „Daugiau“.") },
            confirmButton = {
                Button(onClick = { viewModel.moveSelectionToTrash(panel); trashPanel = null }) { Text("Perkelti") }
            },
            dismissButton = { TextButton(onClick = { trashPanel = null }) { Text("Atšaukti") } },
        )
    }
    archivePanel?.let { panel ->
        ArchiveDialog(
            onDismiss = { archivePanel = null },
            onCreate = { name, format, password ->
                viewModel.createArchive(panel, name, format, password)
                archivePanel = null
            },
        )
    }
    pastePanel?.let { panel ->
        TransferOptionsDialog(
            moving = clipboard?.mode == ClipboardMode.MOVE,
            onDismiss = { pastePanel = null },
            onConfirm = { policy, verification, failurePolicy ->
                viewModel.paste(panel, policy, verification, failurePolicy)
                pastePanel = null
            },
        )
    }
    tagPanel?.let { panel ->
        val selectedPaths = if (panel == PanelId.LEFT) left.selectedPaths.toList() else right.selectedPaths.toList()
        TagDialog(
            paths = selectedPaths,
            definitions = tagSnapshot.definitions,
            records = tagSnapshot.records,
            onDismiss = { tagPanel = null },
            onApply = { tags, rating, color ->
                viewModel.applyTags(selectedPaths, tags, rating, color)
                tagPanel = null
            },
            onClear = {
                viewModel.clearTags(selectedPaths)
                tagPanel = null
            },
            onExport = { viewModel.exportTags(panel) },
            onImport = selectedPaths.singleOrNull()?.takeIf { it.endsWith(".json", ignoreCase = true) }?.let { path ->
                { viewModel.importTags(path); tagPanel = null }
            },
        )
    }
    if (panelComparison.open) {
        var onlyDifferences by remember(panelComparison.leftPath, panelComparison.rightPath) { mutableStateOf(true) }
        val visibleEntries = if (onlyDifferences) {
            panelComparison.entries.filter { it.status != PanelComparisonStatus.SAME }
        } else {
            panelComparison.entries
        }
        AlertDialog(
            onDismissRequest = viewModel::closePanelComparison,
            title = { Text("Skydelių aplankų palyginimas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Greitas palyginimas pagal pavadinimą, tipą, dydį ir keitimo datą. Failai nekeičiami.", style = MaterialTheme.typography.bodySmall)
                    Text("Kairė: ${panelComparison.leftPath}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Dešinė: ${panelComparison.rightPath}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FilterChip(
                        selected = onlyDifferences,
                        onClick = { onlyDifferences = !onlyDifferences },
                        label = { Text("Tik skirtumai · ${panelComparison.entries.count { it.status != PanelComparisonStatus.SAME }}") },
                    )
                    when {
                        panelComparison.running -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        panelComparison.error != null -> Text(panelComparison.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        visibleEntries.isEmpty() -> Text("Skirtumų nerasta")
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            items(visibleEntries, key = { "compare:${it.status}:${it.name}" }) { entry ->
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                    Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        entry.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.status == PanelComparisonStatus.SAME) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closePanelComparison) { Text("Uždaryti") } },
        )
    }
}

@Composable
private fun PermissionBanner(onRequest: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Storage, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text("Reikia prieigos prie bendrų failų", fontWeight = FontWeight.SemiBold)
                Text(
                    "Be jos programa matys tik sistemos leistas vietas. Android/data vis tiek lieka sistemos ribojamas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onRequest) { Text("Suteikti") }
        }
    }
}

@Composable
private fun FilesHome(
    roots: List<StorageRoot>,
    onOpen: (String) -> Unit,
) {
    val quickLocations = remember {
        listOf(
            QuickLocation("Atsisiuntimai", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath, Icons.Rounded.Download),
            QuickLocation("Dokumentai", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath, Icons.Rounded.Description),
            QuickLocation("Nuotraukos", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath, Icons.Rounded.PhotoLibrary),
        ).filter { File(it.path).isDirectory }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Failų vietos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Pasirinkite saugyklą arba dažną vietą. Ji bus atverta aktyviame failų skydelyje.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Saugyklos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            roots.forEach { root ->
                StorageLocationCard(
                    title = if (root.removable) root.title.ifBlank { "Išimama saugykla" } else "Vidinė atmintis",
                    description = "${FileSystemRules.humanBytes(root.freeBytes)} laisva iš ${FileSystemRules.humanBytes(root.totalBytes)}",
                    icon = if (root.removable) Icons.Rounded.SdStorage else Icons.Rounded.Storage,
                    onClick = { onOpen(root.path) },
                )
            }

            if (quickLocations.isNotEmpty()) {
                Text("Greitos vietos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                quickLocations.forEach { location ->
                    StorageLocationCard(
                        title = location.title,
                        description = location.path,
                        icon = location.icon,
                        onClick = { onOpen(location.path) },
                    )
                }
            }
            Spacer(Modifier.height(76.dp))
        }
    }
}

private data class QuickLocation(
    val title: String,
    val path: String,
    val icon: ImageVector,
)

@Composable
private fun StorageLocationCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FilePanel(
    panelId: PanelId,
    state: PanelUiState,
    tabs: PanelWorkspace,
    active: Boolean,
    clipboardAvailable: Boolean,
    batchRenameUndoAvailable: Boolean,
    compactToolbar: Boolean,
    modifier: Modifier,
    viewModel: MainViewModel,
    onCreate: () -> Unit,
    onRename: (FileEntry) -> Unit,
    onTrash: () -> Unit,
    onArchive: () -> Unit,
    onTag: () -> Unit,
    onCopyToOther: () -> Unit,
    onPaste: () -> Unit,
    onUndoBatchRename: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var quickMenu by remember { mutableStateOf(false) }
    var compactMenu by remember { mutableStateOf(false) }
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val tagSnapshot by viewModel.tagSnapshot.collectAsStateWithLifecycle()
    val tagsByPath = remember(tagSnapshot.records) { tagSnapshot.records.associateBy(TaggedFileRecord::path) }
    val allEntriesSelected = state.entries.isNotEmpty() && state.entries.all { it.absolutePath in state.selectedPaths }
    val scrollKey = remember(tabs.activeTabId, state.path, state.grid) {
        FileScrollKey(tabId = tabs.activeTabId, path = state.path, grid = state.grid)
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        PanelTabsBar(panelId, tabs, viewModel)
        if (state.selectedPaths.isNotEmpty()) {
            SelectionToolbar(
                count = state.selectedPaths.size,
                allSelected = allEntriesSelected,
                onClose = { viewModel.clearSelection(panelId) },
                onToggleSelectAll = {
                    if (allEntriesSelected) viewModel.clearSelection(panelId) else viewModel.selectAll(panelId)
                },
                onCopy = { viewModel.copySelection(panelId, move = false) },
                onMove = { viewModel.copySelection(panelId, move = true) },
                onRename = {
                    val selectedEntries = state.entries.filter { it.absolutePath in state.selectedPaths }
                    if (selectedEntries.size == 1) selectedEntries.firstOrNull()?.let(onRename)
                    else viewModel.beginBatchRename(selectedEntries.map(FileEntry::absolutePath))
                },
                onArchive = onArchive,
                onTag = onTag,
                onCopyToOther = onCopyToOther,
                onTrash = onTrash,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.navigateBack(panelId) }, enabled = state.backHistory.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atgal")
                }
                IconButton(onClick = { viewModel.navigateForward(panelId) }, enabled = state.forwardHistory.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Pirmyn")
                }
                IconButton(onClick = { viewModel.navigateUp(panelId) }) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Aukštyn")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        File(state.path).name.ifBlank { state.path },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(state.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (compactToolbar) {
                    CompactPanelActions(
                        expanded = compactMenu,
                        onExpandedChange = { compactMenu = it },
                        panelId = panelId,
                        state = state,
                        favorites = favorites,
                        recents = recents,
                        clipboardAvailable = clipboardAvailable,
                        batchRenameUndoAvailable = batchRenameUndoAvailable,
                        viewModel = viewModel,
                        onPaste = onPaste,
                        onUndoBatchRename = onUndoBatchRename,
                    )
                } else {
                if (clipboardAvailable) {
                    IconButton(onClick = onPaste) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Įklijuoti") }
                }
                if (batchRenameUndoAvailable) {
                    IconButton(onClick = onUndoBatchRename) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Atšaukti paskutinį masinį pervadinimą")
                    }
                }
                IconButton(onClick = { viewModel.toggleFavorite(state.path) }) {
                    Icon(
                        if (state.path in favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (state.path in favorites) "Pašalinti žymą" else "Pridėti žymą",
                    )
                }
                Box {
                    IconButton(onClick = { quickMenu = true }) { Icon(Icons.Rounded.History, contentDescription = "Žymos ir istorija") }
                    DropdownMenu(expanded = quickMenu, onDismissRequest = { quickMenu = false }) {
                        if (favorites.isEmpty() && recents.isEmpty()) {
                            DropdownMenuItem(text = { Text("Žymų ir istorijos dar nėra") }, onClick = { quickMenu = false })
                        }
                        favorites.take(12).forEach { path ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("★ ${File(path).name.ifBlank { path }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                onClick = { viewModel.openQuickPath(path, panelId); quickMenu = false },
                            )
                        }
                        recents.take(12).forEach { recent ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(File(recent.path).name.ifBlank { recent.path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Neseniai · ${recent.path}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                onClick = { viewModel.openQuickPath(recent.path, panelId); quickMenu = false },
                            )
                        }
                    }
                }
                IconButton(onClick = { viewModel.toggleHidden(panelId) }) {
                    Icon(
                        if (state.includeHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = "Paslėpti failai",
                    )
                }
                IconButton(onClick = { viewModel.toggleGrid(panelId) }) {
                    Icon(if (state.grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView, contentDescription = "Rodinys")
                }
                IconButton(onClick = { viewModel.toggleThumbnails(panelId) }) {
                    Icon(
                        if (state.showThumbnails) Icons.AutoMirrored.Rounded.InsertDriveFile else Icons.Rounded.PhotoLibrary,
                        contentDescription = if (state.showThumbnails) "Rodyti piktogramas" else "Rodyti miniatiūras",
                    )
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Rikiuoti") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(mode)) },
                                onClick = { viewModel.setSort(panelId, mode); sortMenu = false },
                            )
                        }
                    }
                }
                IconButton(onClick = { viewModel.refreshPanel(panelId) }) { Icon(Icons.Rounded.Refresh, contentDescription = "Atnaujinti") }
                }
            }
        }

        Breadcrumbs(state.path) { path -> viewModel.navigate(panelId, path) }
        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Rasta ${state.entries.size} · metaduomenys ${state.listingMetadataEntries}/${state.entries.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.listingTruncated) {
            Text(
                "Rodomi pirmi ${state.entries.size} elementų. Sąrašas sutrumpintas, failai nepakeisti.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.error != null -> EmptyPanel("Aplankas nepasiekiamas", state.error)
                state.loading && state.entries.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.entries.isEmpty() -> EmptyPanel("Aplankas tuščias", "Čia dar nėra failų")
                state.grid -> FileGrid(panelId, state, tagsByPath, scrollKey, viewModel)
                else -> FileList(panelId, state, tagsByPath, scrollKey, viewModel)
            }
            FloatingActionButton(
                onClick = { viewModel.activatePanel(panelId); onCreate() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Sukurti")
            }
        }
    }
}

@Composable
private fun CompactPanelActions(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    panelId: PanelId,
    state: PanelUiState,
    favorites: List<String>,
    recents: List<RecentItem>,
    clipboardAvailable: Boolean,
    batchRenameUndoAvailable: Boolean,
    viewModel: MainViewModel,
    onPaste: () -> Unit,
    onUndoBatchRename: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Aplanko veiksmai")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (clipboardAvailable) {
                DropdownMenuItem(
                    text = { Text("Įklijuoti") },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                    onClick = { onExpandedChange(false); onPaste() },
                )
            }
            if (batchRenameUndoAvailable) {
                DropdownMenuItem(
                    text = { Text("Atšaukti paskutinį masinį pervadinimą") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null) },
                    onClick = { onExpandedChange(false); onUndoBatchRename() },
                )
            }
            DropdownMenuItem(
                text = { Text(if (state.path in favorites) "Pašalinti iš mėgstamų" else "Pridėti prie mėgstamų") },
                leadingIcon = {
                    Icon(if (state.path in favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = null)
                },
                onClick = { viewModel.toggleFavorite(state.path); onExpandedChange(false) },
            )
            favorites.take(4).forEach { path ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("★ ${File(path).name.ifBlank { path }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { viewModel.openQuickPath(path, panelId); onExpandedChange(false) },
                )
            }
            recents.take(4).forEach { recent ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(File(recent.path).name.ifBlank { recent.path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Neseniai · ${recent.path}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { viewModel.openQuickPath(recent.path, panelId); onExpandedChange(false) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (state.includeHidden) "Slėpti paslėptus failus" else "Rodyti paslėptus failus") },
                leadingIcon = { Icon(if (state.includeHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null) },
                onClick = { viewModel.toggleHidden(panelId); onExpandedChange(false) },
            )
            DropdownMenuItem(
                text = { Text(if (state.grid) "Rodyti sąrašą" else "Rodyti tinklelį") },
                leadingIcon = { Icon(if (state.grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView, contentDescription = null) },
                onClick = { viewModel.toggleGrid(panelId); onExpandedChange(false) },
            )
            DropdownMenuItem(
                text = { Text(if (state.showThumbnails) "Rodyti piktogramas" else "Rodyti miniatiūras") },
                leadingIcon = {
                    Icon(if (state.showThumbnails) Icons.AutoMirrored.Rounded.InsertDriveFile else Icons.Rounded.PhotoLibrary, contentDescription = null)
                },
                onClick = { viewModel.toggleThumbnails(panelId); onExpandedChange(false) },
            )
            SortMode.entries.forEach { mode ->
                val currentSuffix = if (state.sortMode == mode) {
                    if (state.sortDirection == SortDirection.ASCENDING) " ↑" else " ↓"
                } else ""
                DropdownMenuItem(
                    text = { Text("${sortLabel(mode)}$currentSuffix") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null) },
                    onClick = { viewModel.setSort(panelId, mode); onExpandedChange(false) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Atnaujinti") },
                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                onClick = { viewModel.refreshPanel(panelId); onExpandedChange(false) },
            )
        }
    }
}

@Composable
private fun PanelTabsBar(panel: PanelId, workspace: PanelWorkspace, viewModel: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        workspace.tabs.forEach { tab ->
            FilterChip(
                selected = tab.id == workspace.activeTabId,
                onClick = { viewModel.activateTab(panel, tab.id) },
                label = { Text(File(tab.path).name.ifBlank { tab.path }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (tab.locked) {
                    { Icon(Icons.Rounded.Lock, contentDescription = "Užrakinta", modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
        IconButton(onClick = { viewModel.newTab(panel) }) {
            Icon(Icons.Rounded.Add, contentDescription = "Nauja kortelė")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Kortelės veiksmai") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Dubliuoti kortelę") },
                    onClick = { viewModel.duplicateTab(panel); menu = false },
                )
                DropdownMenuItem(
                    text = { Text(if (workspace.activeTab.locked) "Atrakinti kortelę" else "Užrakinti kortelę") },
                    leadingIcon = {
                        Icon(if (workspace.activeTab.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, contentDescription = null)
                    },
                    onClick = { viewModel.toggleTabLock(panel); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Uždaryti kortelę") },
                    enabled = workspace.tabs.size > 1 && !workspace.activeTab.locked,
                    onClick = { viewModel.closeActiveTab(panel); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Atkurti uždarytą kortelę") },
                    enabled = workspace.closedTabs.isNotEmpty(),
                    onClick = { viewModel.restoreClosedTab(panel); menu = false },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Sukeisti skydelius") },
                    leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                    onClick = { viewModel.swapPanels(); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Palyginti skydelių aplankus") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null) },
                    onClick = { viewModel.comparePanels(); menu = false },
                )
            }
        }
    }
}

@Composable
private fun SelectionToolbar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onTag: () -> Unit,
    onCopyToOther: () -> Unit,
    onTrash: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Uždaryti") }
            Text("Pasirinkta: $count", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onToggleSelectAll) {
                Icon(
                    if (allSelected) Icons.Rounded.IndeterminateCheckBox else Icons.Rounded.CheckBox,
                    contentDescription = if (allSelected) "Atžymėti visus" else "Pasirinkti visus",
                )
            }
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Kopijuoti") }
            IconButton(onClick = onMove) { Icon(Icons.Rounded.ContentCut, contentDescription = "Perkelti") }
            IconButton(onClick = onCopyToOther) { Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = "Kopijuoti į kitą skydelį") }
            IconButton(onClick = onRename) {
                Icon(
                    Icons.AutoMirrored.Rounded.DriveFileMove,
                    contentDescription = if (count == 1) "Pervadinti" else "Masinis pervadinimas",
                )
            }
            IconButton(onClick = onArchive) { Icon(Icons.Rounded.Archive, contentDescription = "Archyvuoti") }
            IconButton(onClick = onTag) { Icon(Icons.AutoMirrored.Rounded.Label, contentDescription = "Žymos ir įvertinimas") }
            IconButton(onClick = onTrash) { Icon(Icons.Rounded.Delete, contentDescription = "Į šiukšlinę", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val file = remember(path) { runCatching { File(path).canonicalFile }.getOrElse { File(path).absoluteFile } }
    val chain = remember(path) {
        generateSequence(file) { it.parentFile }.toList().asReversed()
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        chain.forEach { part ->
            AssistChip(
                onClick = { onNavigate(part.absolutePath) },
                label = { Text(part.name.ifBlank { part.absolutePath }, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun FileList(
    panel: PanelId,
    state: PanelUiState,
    tagsByPath: Map<String, TaggedFileRecord>,
    scrollKey: FileScrollKey,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val initialPosition = remember(scrollKey) { viewModel.fileScrollPosition(scrollKey) }
    var restoringPosition by remember(scrollKey) {
        mutableStateOf(initialPosition.firstVisibleItemIndex > 0 || initialPosition.firstVisibleItemScrollOffset > 0)
    }
    val listState = key(scrollKey) {
        rememberLazyListState()
    }
    LaunchedEffect(scrollKey, state.entries.size, state.loading) {
        if (restoringPosition && state.entries.isNotEmpty() &&
            (state.entries.size > initialPosition.firstVisibleItemIndex || !state.loading)
        ) {
            val index = initialPosition.firstVisibleItemIndex.coerceAtMost(state.entries.lastIndex)
            val offset = initialPosition.firstVisibleItemScrollOffset.takeIf {
                index == initialPosition.firstVisibleItemIndex
            } ?: 0
            listState.scrollToItem(index, offset)
            restoringPosition = false
        }
    }
    LaunchedEffect(scrollKey, listState) {
        snapshotFlow {
            Triple(restoringPosition, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (restoring, index, offset) ->
            if (!restoring) viewModel.saveFileScrollPosition(scrollKey, index, offset)
        }
    }
    DisposableEffect(scrollKey, listState) {
        onDispose {
            if (!restoringPosition) {
                viewModel.saveFileScrollPosition(
                    scrollKey,
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                )
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.testTag("file-list-$panel"),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        items(state.entries, key = FileEntry::absolutePath, contentType = FileEntry::kind) { entry ->
            val tagRecord = tagsByPath[entry.absolutePath]
            val metadata = remember(entry) { entryMeta(entry, dateFormat) }
            val tagText = remember(entry, tagRecord) { tagSummary(entry, tagRecord) }
            FileRow(
                entry = entry,
                metadata = metadata,
                tagText = tagText,
                selected = entry.absolutePath in state.selectedPaths,
                showThumbnails = state.showThumbnails,
                onClick = { handleEntryClick(panel, state, entry, viewModel) },
                onLongClick = { viewModel.toggleSelection(panel, entry.absolutePath) },
                onPreview = { viewModel.activatePanel(panel); viewModel.open(entry) },
                onOpenWith = {
                    runCatching { openWith(context, PreviewSource.Local(entry)) }
                        .onFailure { Toast.makeText(context, it.message ?: "Programų pasirinkiklio atidaryti nepavyko", Toast.LENGTH_LONG).show() }
                },
                onSelect = { viewModel.toggleSelection(panel, entry.absolutePath) },
            )
        }
    }
}

@Composable
private fun FileGrid(
    panel: PanelId,
    state: PanelUiState,
    tagsByPath: Map<String, TaggedFileRecord>,
    scrollKey: FileScrollKey,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val initialPosition = remember(scrollKey) { viewModel.fileScrollPosition(scrollKey) }
    var restoringPosition by remember(scrollKey) {
        mutableStateOf(initialPosition.firstVisibleItemIndex > 0 || initialPosition.firstVisibleItemScrollOffset > 0)
    }
    val gridState = key(scrollKey) {
        rememberLazyGridState()
    }
    LaunchedEffect(scrollKey, state.entries.size, state.loading) {
        if (restoringPosition && state.entries.isNotEmpty() &&
            (state.entries.size > initialPosition.firstVisibleItemIndex || !state.loading)
        ) {
            val index = initialPosition.firstVisibleItemIndex.coerceAtMost(state.entries.lastIndex)
            val offset = initialPosition.firstVisibleItemScrollOffset.takeIf {
                index == initialPosition.firstVisibleItemIndex
            } ?: 0
            gridState.scrollToItem(index, offset)
            restoringPosition = false
        }
    }
    LaunchedEffect(scrollKey, gridState) {
        snapshotFlow {
            Triple(restoringPosition, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }.collect { (restoring, index, offset) ->
            if (!restoring) viewModel.saveFileScrollPosition(scrollKey, index, offset)
        }
    }
    DisposableEffect(scrollKey, gridState) {
        onDispose {
            if (!restoringPosition) {
                viewModel.saveFileScrollPosition(
                    scrollKey,
                    gridState.firstVisibleItemIndex,
                    gridState.firstVisibleItemScrollOffset,
                )
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(124.dp),
        state = gridState,
        modifier = Modifier.testTag("file-grid-$panel"),
        contentPadding = PaddingValues(8.dp, 6.dp, 8.dp, 88.dp),
    ) {
        items(state.entries, key = FileEntry::absolutePath, contentType = FileEntry::kind) { entry ->
            val tagRecord = tagsByPath[entry.absolutePath]
            val tagText = remember(entry, tagRecord) { tagSummary(entry, tagRecord) }
            FileTile(
                entry = entry,
                tagText = tagText,
                selected = entry.absolutePath in state.selectedPaths,
                showThumbnails = state.showThumbnails,
                onClick = { handleEntryClick(panel, state, entry, viewModel) },
                onLongClick = { viewModel.toggleSelection(panel, entry.absolutePath) },
                onPreview = { viewModel.activatePanel(panel); viewModel.open(entry) },
                onOpenWith = {
                    runCatching { openWith(context, PreviewSource.Local(entry)) }
                        .onFailure { Toast.makeText(context, it.message ?: "Programų pasirinkiklio atidaryti nepavyko", Toast.LENGTH_LONG).show() }
                },
                onSelect = { viewModel.toggleSelection(panel, entry.absolutePath) },
            )
        }
    }
}

private fun handleEntryClick(panel: PanelId, state: PanelUiState, entry: FileEntry, viewModel: MainViewModel) {
    viewModel.activatePanel(panel)
    if (state.selectedPaths.isNotEmpty()) viewModel.toggleSelection(panel, entry.absolutePath) else viewModel.open(entry)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    metadata: String,
    tagText: String?,
    selected: Boolean,
    showThumbnails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: () -> Unit,
    onOpenWith: () -> Unit,
    onSelect: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, selectionShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, selectionShape)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LocalFileVisual(
            entry = entry,
            targetWidth = 42.dp,
            targetHeight = 42.dp,
            showThumbnails = showThumbnails,
            modifier = Modifier.size(42.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal)
            Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tagText?.let { summary ->
                Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!entry.isReadable) Text("Neprieinama", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        EntryActionsButton(entry, onPreview, onOpenWith, onSelect)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    entry: FileEntry,
    tagText: String?,
    selected: Boolean,
    showThumbnails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: () -> Unit,
    onOpenWith: () -> Unit,
    onSelect: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(12.dp)
    Card(
        modifier = Modifier
            .padding(5.dp)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, selectionShape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = selectionShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(158.dp)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LocalFileVisual(
                    entry = entry,
                    targetWidth = 96.dp,
                    targetHeight = 76.dp,
                    showThumbnails = showThumbnails,
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (!entry.isDirectory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                tagText?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                EntryActionsButton(entry, onPreview, onOpenWith, onSelect)
            }
        }
    }
}

private fun tagSummary(entry: FileEntry, record: TaggedFileRecord?): String? {
    record ?: return null
    val current = entry.isDirectory || (entry.sizeBytes == record.sizeBytes && entry.modifiedAtMillis == record.modifiedAtMillis)
    val parts = buildList {
        if (record.rating > 0) add("★".repeat(record.rating))
        if (record.tags.isNotEmpty()) add(record.tags.sorted().joinToString(" · "))
        if (!current) add("pasenęs įrašas")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
}

@Composable
private fun TagDialog(
    paths: List<String>,
    definitions: List<FileTagDefinition>,
    records: List<TaggedFileRecord>,
    onDismiss: () -> Unit,
    onApply: (Set<String>, Int?, Int) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onImport: (() -> Unit)?,
) {
    val currentRecords = remember(paths, records) { records.filter { it.path in paths.toSet() } }
    val currentTags = remember(currentRecords) { currentRecords.flatMap(TaggedFileRecord::tags).toSortedSet() }
    var selectedTags by remember(paths) { mutableStateOf(emptySet<String>()) }
    var typedTags by remember(paths) { mutableStateOf("") }
    var rating by remember(paths) { mutableStateOf<Int?>(null) }
    val colors = remember { listOf(0xff1976d2, 0xff2e7d32, 0xffed6c02, 0xff9c27b0, 0xffc62828).map(Long::toInt) }
    var selectedColor by remember(paths) { mutableStateOf(colors.first()) }
    val parsedTags = remember(typedTags, selectedTags) {
        selectedTags + typedTags.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Žymos ir įvertinimas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pasirinkta: ${paths.size}. Naujos žymos pridedamos prie esamų; viską pašalina atskiras mygtukas.", style = MaterialTheme.typography.bodySmall)
                if (currentTags.isNotEmpty()) {
                    Text("Dabartinės: ${currentTags.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (definitions.isNotEmpty()) {
                    Text("Esamos žymos", style = MaterialTheme.typography.labelLarge)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        definitions.take(40).forEach { definition ->
                            FilterChip(
                                selected = definition.name in selectedTags,
                                onClick = {
                                    selectedTags = if (definition.name in selectedTags) selectedTags - definition.name else selectedTags + definition.name
                                },
                                label = { Text(definition.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = typedTags,
                    onValueChange = { typedTags = it.take(500) },
                    label = { Text("Naujos žymos, atskirtos kableliais") },
                    supportingText = { Text("Hierarchija: Projektas/Dokumentai") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Naujų žymų spalva", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colors.forEachIndexed { index, color ->
                        FilterChip(
                            selected = color == selectedColor,
                            onClick = { selectedColor = color },
                            label = { Text("●", color = Color(color)) },
                        )
                    }
                }
                Text("Įvertinimas (nepasirinkus nekeičiamas)", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf<Int?>(null, 1, 2, 3, 4, 5).forEach { value ->
                        FilterChip(
                            selected = rating == value,
                            onClick = { rating = value },
                            label = { Text(value?.let { "★".repeat(it) } ?: "Nekeisti") },
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onClear, enabled = currentRecords.isNotEmpty()) { Text("Pašalinti visas") }
                    OutlinedButton(onClick = onExport) { Text("Eksportuoti") }
                    onImport?.let { importAction -> OutlinedButton(onClick = importAction) { Text("Importuoti JSON") } }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(parsedTags, rating, selectedColor) }, enabled = parsedTags.isNotEmpty() || rating != null) { Text("Taikyti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
}

@Composable
private fun EntryActionsButton(
    entry: FileEntry,
    onPreview: () -> Unit,
    onOpenWith: () -> Unit,
    onSelect: () -> Unit,
) {
    var expanded by remember(entry.absolutePath) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Failo veiksmai: ${entry.name}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (entry.isDirectory) "Atidaryti aplanką" else "Peržiūrėti čia") },
                onClick = { expanded = false; onPreview() },
                enabled = entry.isReadable,
            )
            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text("Atidaryti su kita programa") },
                    onClick = { expanded = false; onOpenWith() },
                    enabled = entry.isReadable,
                )
            }
            DropdownMenuItem(
                text = { Text("Pasirinkti") },
                onClick = { expanded = false; onSelect() },
            )
        }
    }
}

@Composable
private fun EmptyPanel(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreateItemDialog(onDismiss: () -> Unit, onCreateFolder: (String) -> Unit, onCreateFile: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (folder) "Naujas aplankas" else "Naujas failas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { folder = true }) { Text("Aplankas") }
                    OutlinedButton(onClick = { folder = false }) { Text("Failas") }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Pavadinimas") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (folder) onCreateFolder(name) else onCreateFile(name) },
                enabled = name.isNotBlank(),
            ) { Text("Sukurti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
}

@Composable
private fun ArchiveDialog(onDismiss: () -> Unit, onCreate: (String, ArchiveFormat, CharArray?) -> Unit) {
    var name by remember { mutableStateOf("archyvas") }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sukurti archyvą") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Pavadinimas") }, singleLine = true)
                Text("Formatas", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ).forEach { candidate ->
                        AssistChip(onClick = { format = candidate }, label = { Text(candidate.name) })
                    }
                }
                if (format == ArchiveFormat.ZIP) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Slaptažodis (nebūtinas, AES-256)") },
                        singleLine = true,
                    )
                }
                Text("Pasirinkta: ${format.name}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, format, password.takeIf(String::isNotBlank)?.toCharArray()) }, enabled = name.isNotBlank()) {
                Text("Kurti")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
}

@Composable
private fun TransferOptionsDialog(
    moving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ConflictPolicy, TransferVerification, TransferFailurePolicy) -> Unit,
) {
    var policy by remember { mutableStateOf(ConflictPolicy.KEEP_BOTH) }
    var verifySha256 by remember { mutableStateOf(moving) }
    var skipErrors by remember { mutableStateOf(false) }
    val choices = listOf(
        ConflictPolicy.KEEP_BOTH to "Palikti abu",
        ConflictPolicy.REPLACE to "Pakeisti esamą",
        ConflictPolicy.SKIP to "Praleisti sutampančius",
        ConflictPolicy.MERGE to "Sujungti aplankus",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (moving) "Patikimai perkelti" else "Patikimai kopijuoti") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Planas bus išsaugotas prieš vykdymą. Ši taisyklė bus taikoma visiems sutampantiems vardams.")
                choices.forEach { (candidate, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { policy = candidate }, onLongClick = {}),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = policy == candidate, onClick = { policy = candidate })
                        Text(label)
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Patikrinti SHA-256", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (moving) "Rekomenduojama: šaltinis šalinamas tik patvirtinus kopiją" else "Lėčiau, bet patvirtina failo turinį",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = verifySha256, onCheckedChange = { verifySha256 = it })
                }
                if (!moving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Praleisti klaidingą failą ir tęsti", fontWeight = FontWeight.SemiBold)
                            Text("Iki 100 klaidų bus palikta galutinėje ataskaitoje", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = skipErrors, onCheckedChange = { skipErrors = it })
                    }
                } else {
                    Text("Perkėlimas klaidos atveju visada sustoja; nepatikrintas šaltinis nešalinamas.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        policy,
                        if (verifySha256) TransferVerification.SHA256 else TransferVerification.SIZE,
                        if (!moving && skipErrors) TransferFailurePolicy.SKIP_AND_CONTINUE else TransferFailurePolicy.STOP,
                    )
                },
            ) { Text("Išsaugoti planą ir pradėti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
}

private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "Pagal pavadinimą"
    SortMode.SIZE -> "Pagal dydį"
    SortMode.MODIFIED -> "Pagal datą"
    SortMode.TYPE -> "Pagal tipą"
}

private fun entryMeta(entry: FileEntry, dateFormat: DateFormat): String {
    if (!entry.metadataComplete) return if (entry.isDirectory) "Aplankas · kraunami duomenys…" else "Kraunami duomenys…"
    val date = entry.modifiedAtMillis.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) }
    return listOfNotNull(if (entry.isDirectory) "Aplankas" else FileSystemRules.humanBytes(entry.sizeBytes), date).joinToString(" · ")
}
