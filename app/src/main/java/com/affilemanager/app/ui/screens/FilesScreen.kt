package com.affilemanager.app.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Android
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.model.StorageRootKind
import com.affilemanager.app.data.PanelWorkspace
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryGridStyle
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.data.FileTagDefinition
import com.affilemanager.app.data.FileTagSnapshot
import com.affilemanager.app.data.RecentItem
import com.affilemanager.app.data.RecentFileItem
import com.affilemanager.app.data.TaggedFileRecord
import com.affilemanager.app.data.HomeCustomization
import com.affilemanager.app.data.HomeSection
import com.affilemanager.app.data.HomeShortcut
import com.affilemanager.app.data.HomeShortcutNavigationRules
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.PanelUiState
import com.affilemanager.app.ui.PanelComparisonStatus
import com.affilemanager.app.ui.ProgressiveScrollRules
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.DirectoryLayoutButton
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.ui.components.DirectoryQuickSearchField
import com.affilemanager.app.ui.components.DirectorySearchButton
import com.affilemanager.app.ui.preview.PreviewSource
import com.affilemanager.app.ui.components.SelectionActionDock
import com.affilemanager.app.ui.components.SelectionHeader
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat
import com.affilemanager.app.ui.preview.openWith
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

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
    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
    val filesHomeDisplaySettings by viewModel.filesHomeDisplaySettings.collectAsStateWithLifecycle()
    val homeCustomization by viewModel.homeCustomization.collectAsStateWithLifecycle()
    val filesHomeVisible by viewModel.filesHomeVisible.collectAsStateWithLifecycle()
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val remoteClipboard by viewModel.remoteClipboard.collectAsStateWithLifecycle()
    val afClipboard by viewModel.afClipboard.collectAsStateWithLifecycle()
    val renameUndo by viewModel.renameUndo.collectAsStateWithLifecycle()
    val tagSnapshot by viewModel.tagSnapshot.collectAsStateWithLifecycle()
    val panelComparison by viewModel.panelComparison.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val trashItems by viewModel.trashItems.collectAsStateWithLifecycle()
    val advancedAccess by viewModel.advancedAccess.collectAsStateWithLifecycle()
    val fileCategory by viewModel.fileCategory.collectAsStateWithLifecycle()

    var createFor by remember { mutableStateOf<PanelId?>(null) }
    var renameTarget by remember { mutableStateOf<Pair<PanelId, FileEntry>?>(null) }
    var trashPanel by remember { mutableStateOf<PanelId?>(null) }
    var archivePanel by remember { mutableStateOf<PanelId?>(null) }
    var pastePanel by remember { mutableStateOf<PanelId?>(null) }
    var tagPanel by remember { mutableStateOf<PanelId?>(null) }
    var displayPanel by remember { mutableStateOf<PanelId?>(null) }
    var showHomeDisplaySettings by remember { mutableStateOf(false) }
    var showHomeCustomization by remember { mutableStateOf(false) }
    var showRootAccessInfo by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<FileEntry?>(null) }
    val clipboardAvailable = clipboard != null || remoteClipboard != null || afClipboard != null

    LaunchedEffect(clipboardAvailable) {
        if (!clipboardAvailable) pastePanel = null
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (fileCategory.open) {
                    return@onPreviewKeyEvent when {
                        event.key == Key.Escape -> {
                            viewModel.closeFileCategory(); true
                        }
                        event.key == Key.F5 -> {
                            viewModel.refreshFileCategory(); true
                        }
                        event.isCtrlPressed && event.isShiftPressed && event.key == Key.C -> {
                            viewModel.copyFileCategorySelection(move = false, append = true); true
                        }
                        event.isCtrlPressed && event.key == Key.C -> {
                            viewModel.copyFileCategorySelection(move = false); true
                        }
                        event.isCtrlPressed && event.key == Key.X -> {
                            viewModel.copyFileCategorySelection(move = true); true
                        }
                        event.isCtrlPressed && event.key == Key.A -> {
                            viewModel.toggleAllFileCategoryEntries(fileCategory.entries.mapTo(linkedSetOf(), FileEntry::absolutePath)); true
                        }
                        else -> false
                    }
                }
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
                    event.isCtrlPressed && event.isShiftPressed && event.key == Key.C -> {
                        viewModel.addSelectionToClipboard(activePanel); true
                    }
                    event.isCtrlPressed && event.key == Key.C -> {
                        viewModel.copySelection(activePanel, move = false); true
                    }
                    event.isCtrlPressed && event.key == Key.X -> {
                        viewModel.copySelection(activePanel, move = true); true
                    }
                    event.isCtrlPressed && event.key == Key.V && clipboardAvailable -> {
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
        Column(modifier = Modifier.fillMaxSize()) {
            if (!hasAllFilesAccess) {
                PermissionBanner(onRequestAllFilesAccess)
            }
            if (fileCategory.open) {
                PanelTabsBar(
                    panel = activePanel,
                    workspace = if (activePanel == PanelId.LEFT) leftTabs else rightTabs,
                    viewModel = viewModel,
                    onBeforeTabAction = viewModel::closeFileCategory,
                )
                FileCategoryBrowser(
                    state = fileCategory,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f),
                )
            } else if (filesHomeVisible) {
                FilesHome(
                    roots = roots,
                    recentFiles = recentFiles.items,
                    recentFilesLoading = recentFiles.loading,
                    recentFilesError = recentFiles.error,
                    displaySettings = filesHomeDisplaySettings,
                    customization = homeCustomization,
                    favorites = favorites,
                    tagSnapshot = tagSnapshot,
                    trashCount = trashItems.size,
                    rootStorageAvailable = advancedAccess.activeBackend in setOf(
                        AdvancedAccessBackend.ROOT,
                        AdvancedAccessBackend.SHIZUKU_ROOT,
                    ),
                    onOpen = { location -> viewModel.openHomeShortcut(location.id, location.path, activePanel) },
                    onOpenStorage = { root -> viewModel.openStorageRoot(root, activePanel) },
                    onOpenRoot = {
                        if (advancedAccess.activeBackend in setOf(
                                AdvancedAccessBackend.ROOT,
                                AdvancedAccessBackend.SHIZUKU_ROOT,
                            )
                        ) {
                            viewModel.openRootFromHome()
                        } else {
                            showRootAccessInfo = true
                        }
                    },
                    onOpenRecent = { entry -> viewModel.activatePanel(activePanel); viewModel.open(entry) },
                    onOpenFavorite = { path -> viewModel.openQuickPath(path, activePanel) },
                    onOpenTrash = viewModel::openTrashFromHome,
                    onOpenTag = viewModel::openTagFromHome,
                    onOpenCleanup = viewModel::openCleanupFromHome,
                    onRefreshRecent = viewModel::refreshRecentFiles,
                    onToggleLayout = viewModel::toggleFilesHomeLayout,
                    onConfigureLayout = { showHomeDisplaySettings = true },
                    onConfigureHome = { showHomeCustomization = true },
                )
            } else if (dualPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    FilePanel(
                        panelId = PanelId.LEFT,
                        state = left,
                        tabs = leftTabs,
                        active = activePanel == PanelId.LEFT,
                        clipboardAvailable = clipboardAvailable,
                        batchRenameUndoAvailable = renameUndo != null,
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                        onCreate = { createFor = PanelId.LEFT },
                        onRename = { renameTarget = PanelId.LEFT to it },
                        onTrash = { trashPanel = PanelId.LEFT },
                        onTrashEntry = { entry -> viewModel.selectOnly(PanelId.LEFT, entry.absolutePath); trashPanel = PanelId.LEFT },
                        onInfo = { infoTarget = it },
                        onArchive = { archivePanel = PanelId.LEFT },
                        onTag = { tagPanel = PanelId.LEFT },
                        onCopyToOther = { viewModel.copySelection(PanelId.LEFT, move = false); pastePanel = PanelId.RIGHT },
                        onPaste = { pastePanel = PanelId.LEFT },
                        onUndoBatchRename = viewModel::undoBatchRename,
                        onDisplaySettings = { displayPanel = PanelId.LEFT },
                    )
                    VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    FilePanel(
                        panelId = PanelId.RIGHT,
                        state = right,
                        tabs = rightTabs,
                        active = activePanel == PanelId.RIGHT,
                        clipboardAvailable = clipboardAvailable,
                        batchRenameUndoAvailable = renameUndo != null,
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                        onCreate = { createFor = PanelId.RIGHT },
                        onRename = { renameTarget = PanelId.RIGHT to it },
                        onTrash = { trashPanel = PanelId.RIGHT },
                        onTrashEntry = { entry -> viewModel.selectOnly(PanelId.RIGHT, entry.absolutePath); trashPanel = PanelId.RIGHT },
                        onInfo = { infoTarget = it },
                        onArchive = { archivePanel = PanelId.RIGHT },
                        onTag = { tagPanel = PanelId.RIGHT },
                        onCopyToOther = { viewModel.copySelection(PanelId.RIGHT, move = false); pastePanel = PanelId.LEFT },
                        onPaste = { pastePanel = PanelId.RIGHT },
                        onUndoBatchRename = viewModel::undoBatchRename,
                        onDisplaySettings = { displayPanel = PanelId.RIGHT },
                    )
                }
            } else {
                val panelState = if (activePanel == PanelId.LEFT) left else right
                FilePanel(
                    panelId = activePanel,
                    state = panelState,
                    tabs = if (activePanel == PanelId.LEFT) leftTabs else rightTabs,
                    active = true,
                    clipboardAvailable = clipboardAvailable,
                    batchRenameUndoAvailable = renameUndo != null,
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    onCreate = { createFor = activePanel },
                    onRename = { renameTarget = activePanel to it },
                    onTrash = { trashPanel = activePanel },
                    onTrashEntry = { entry -> viewModel.selectOnly(activePanel, entry.absolutePath); trashPanel = activePanel },
                    onInfo = { infoTarget = it },
                    onArchive = { archivePanel = activePanel },
                    onTag = { tagPanel = activePanel },
                    onCopyToOther = {
                        viewModel.copySelection(activePanel, move = false)
                        pastePanel = if (activePanel == PanelId.LEFT) PanelId.RIGHT else PanelId.LEFT
                    },
                    onPaste = { pastePanel = activePanel },
                    onUndoBatchRename = viewModel::undoBatchRename,
                    onDisplaySettings = { displayPanel = activePanel },
                )
            }
        }
    }

    if (showRootAccessInfo) {
        val shizukuShellConnected = advancedAccess.activeBackend == AdvancedAccessBackend.SHIZUKU_SHELL
        AlertDialog(
            onDismissRequest = { showRootAccessInfo = false },
            title = { LText("Root prieiga neaktyvi") },
            text = {
                LText(
                    if (shizukuShellConnected) {
                        "Įprasta Shizuku prieiga veikia kaip Android shell ir negali atverti sistemos šaknies /. Root saugyklai reikia rootinto įrenginio arba Shizuku tarnybos, veikiančios su root teisėmis."
                    } else {
                        "Sistemos šaknis / atveriama tik su Root arba Shizuku root prieiga. Tai nėra įprastas Android leidimas ir AF File Manager jo neapeina."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRootAccessInfo = false
                        viewModel.setSection(AppSection.TOOLS)
                    },
                    modifier = Modifier.testTag("root_access_settings"),
                ) { LText("Išplėstiniai nustatymai") }
            },
            dismissButton = {
                Row {
                    if (shizukuShellConnected) {
                        TextButton(
                            onClick = {
                                showRootAccessInfo = false
                                viewModel.openAdvancedBrowser()
                            },
                            modifier = Modifier.testTag("root_open_protected_android"),
                        ) { LText("Apsaugoti Android failai") }
                    }
                    TextButton(onClick = { showRootAccessInfo = false }) { LText("Atšaukti") }
                }
            },
            modifier = Modifier.testTag("root_access_explanation"),
        )
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
            title = { LText("Perkelti į šiukšlinę?") },
            text = { LText("Pasirinkta: $count. Failus bus galima atkurti skiltyje „Daugiau“.") },
            confirmButton = {
                Button(onClick = { viewModel.moveSelectionToTrash(panel); trashPanel = null }) { LText("Perkelti") }
            },
            dismissButton = { TextButton(onClick = { trashPanel = null }) { LText("Atšaukti") } },
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
        val remote = remoteClipboard
        if (remote != null) {
            val destination = if (panel == PanelId.LEFT) left.path else right.path
            AlertDialog(
                onDismissRequest = { pastePanel = null },
                title = { LText("Įklijuoti iš serverio") },
                text = {
                    LText(
                        "Iš serverio bus nukopijuota ${remote.entries.size} elementų į $destination. Esami vardai nebus perrašyti.",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (viewModel.pasteRemoteClipboard(panel)) pastePanel = null
                    }) { LText("Įklijuoti") }
                },
                dismissButton = { TextButton(onClick = { pastePanel = null }) { LText("Atšaukti") } },
            )
        } else if (clipboard != null) {
            TransferOptionsDialog(
                moving = clipboard?.mode == ClipboardMode.MOVE,
                onDismiss = { pastePanel = null },
                onConfirm = { policy, verification, failurePolicy ->
                    viewModel.paste(panel, policy, verification, failurePolicy)
                    pastePanel = null
                },
            )
        } else if (afClipboard != null) {
            AlertDialog(
                onDismissRequest = { pastePanel = null },
                title = { LText("Mišrus kopijavimo rinkinys") },
                text = { LText("Rinkinyje yra šaltinių iš kelių vietų. Atverkite „Įklijuoti į kelias vietas“, kad prieš vykdymą matytumėte visą planą.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.startAfPlanFromClipboard(destinationPanel = panel)
                        pastePanel = null
                    }) { LText("Atverti planą") }
                },
                dismissButton = { TextButton(onClick = { pastePanel = null }) { LText("Atšaukti") } },
            )
        }
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
    displayPanel?.let { panel ->
        val state = if (panel == PanelId.LEFT) left else right
        DirectoryDisplaySettingsDialog(
            initialSettings = state.toDirectoryDisplaySettings(),
            thumbnailsAvailable = true,
            initialSortMode = state.sortMode,
            initialSortDirection = state.sortDirection,
            onDismiss = { displayPanel = null },
            onApply = { settings ->
                viewModel.setDirectoryDisplaySettings(panel, settings)
                displayPanel = null
            },
            onApplySort = { mode, direction -> viewModel.setSort(panel, mode, direction) },
            onApplyToAll = { settings, mode, direction ->
                viewModel.applyDirectoryDisplaySettingsToAll(settings, mode, direction)
                displayPanel = null
            },
        )
    }
    if (showHomeDisplaySettings) {
        DirectoryDisplaySettingsDialog(
            initialSettings = filesHomeDisplaySettings,
            thumbnailsAvailable = false,
            gridColumnRange = 4..6,
            onDismiss = { showHomeDisplaySettings = false },
            onApply = { settings ->
                viewModel.setFilesHomeDisplaySettings(settings)
                showHomeDisplaySettings = false
            },
        )
    }
    infoTarget?.let { entry ->
        FileInfoDialog(entry = entry, onDismiss = { infoTarget = null })
    }
    if (showHomeCustomization) {
        val currentPath = if (activePanel == PanelId.LEFT) left.path else right.path
        HomeCustomizationDialog(
            customization = homeCustomization,
            currentPath = currentPath,
            onDismiss = { showHomeCustomization = false },
            onMoveSection = viewModel::moveHomeSection,
            onMoveShortcut = viewModel::moveHomeShortcut,
            onSetShortcutVisible = viewModel::setHomeShortcutVisible,
            onRemoveShortcut = viewModel::removeHomeShortcut,
            onAddShortcut = viewModel::addHomeShortcut,
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
            title = { LText("Skydelių aplankų palyginimas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LText("Greitas palyginimas pagal pavadinimą, tipą, dydį ir keitimo datą. Failai nekeičiami.", style = MaterialTheme.typography.bodySmall)
                    LText("Kairė: ${panelComparison.leftPath}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LText("Dešinė: ${panelComparison.rightPath}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FilterChip(
                        selected = onlyDifferences,
                        onClick = { onlyDifferences = !onlyDifferences },
                        label = { LText("Tik skirtumai · ${panelComparison.entries.count { it.status != PanelComparisonStatus.SAME }}") },
                    )
                    when {
                        panelComparison.running -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        panelComparison.error != null -> LText(panelComparison.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        visibleEntries.isEmpty() -> LText("Skirtumų nerasta")
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            items(visibleEntries, key = { "compare:${it.status}:${it.name}" }) { entry ->
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                    Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    LText(
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
            confirmButton = { TextButton(onClick = viewModel::closePanelComparison) { LText("Uždaryti") } },
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
                LText("Reikia prieigos prie bendrų failų", fontWeight = FontWeight.SemiBold)
                LText(
                    "Be jos programa matys tik sistemos leistas vietas. Android/data vis tiek lieka sistemos ribojamas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onRequest) { LText("Suteikti") }
        }
    }
}

@Composable
private fun FilesHome(
    roots: List<StorageRoot>,
    recentFiles: List<RecentFileItem>,
    recentFilesLoading: Boolean,
    recentFilesError: String?,
    displaySettings: DirectoryDisplaySettings,
    customization: HomeCustomization,
    favorites: List<String>,
    tagSnapshot: FileTagSnapshot,
    trashCount: Int,
    rootStorageAvailable: Boolean,
    onOpen: (QuickLocation) -> Unit,
    onOpenStorage: (StorageRoot) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenRecent: (FileEntry) -> Unit,
    onOpenFavorite: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenCleanup: () -> Unit,
    onRefreshRecent: () -> Unit,
    onToggleLayout: () -> Unit,
    onConfigureLayout: () -> Unit,
    onConfigureHome: () -> Unit,
) {
    val quickLocations = customization.shortcuts.filter(HomeShortcut::visible).map { shortcut ->
        QuickLocation(
            id = shortcut.id,
            title = shortcut.title,
            path = shortcut.path,
            icon = homeShortcutIcon(shortcut),
            virtual = HomeShortcutNavigationRules.isVirtualCategory(shortcut.id),
        )
    }
    var showAllRecent by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LText(
                            "Failų vietos",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onConfigureHome, modifier = Modifier.testTag("home_customize")) {
                            Icon(Icons.Rounded.Edit, contentDescription = uiText("Tvarkyti pradžios ekraną"))
                        }
                    }
                    LText(
                        "Pasirinkite saugyklą, kategoriją arba dažną vietą.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            customization.sectionOrder.forEach { section ->
                when (section) {
                    HomeSection.RECENT_FILES -> RecentFilesHomeSection(
                        recentFiles = recentFiles,
                        loading = recentFilesLoading,
                        error = recentFilesError,
                        onShowAll = { showAllRecent = true },
                        onRefresh = onRefreshRecent,
                        onOpen = onOpenRecent,
                    )
                    HomeSection.STORAGE -> StorageHomeSection(
                        roots = roots,
                        rootStorageAvailable = rootStorageAvailable,
                        onOpen = onOpenStorage,
                        onOpenRoot = onOpenRoot,
                        onOpenCleanup = onOpenCleanup,
                    )
                    HomeSection.QUICK_LOCATIONS -> QuickLocationsHomeSection(
                        locations = quickLocations,
                        displaySettings = displaySettings,
                        onOpen = onOpen,
                        onToggleLayout = onToggleLayout,
                        onConfigureLayout = onConfigureLayout,
                    )
                    HomeSection.TRASH -> HomeToolCardSection(
                        title = "Šiukšlinė",
                        description = if (trashCount == 1) "1 elementas" else "$trashCount elementų",
                        icon = Icons.Rounded.Delete,
                        onClick = onOpenTrash,
                    )
                    HomeSection.FAVORITES -> FavoritesHomeSection(favorites, onOpenFavorite)
                    HomeSection.TAGS -> TagsHomeSection(tagSnapshot, onOpenTag)
                }
            }
            Spacer(Modifier.height(76.dp))
        }
    }

    if (showAllRecent) {
        AlertDialog(
            onDismissRequest = { showAllRecent = false },
            title = { LText("Naujausi failai") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("recent_files_all"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(recentFiles, key = { it.entry.absolutePath }) { item ->
                        RecentFileListItem(
                            item = item,
                            onOpen = {
                                showAllRecent = false
                                onOpenRecent(item.entry)
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAllRecent = false }) { LText("Uždaryti") } },
        )
    }
}

@Composable
private fun RecentFilesHomeSection(
    recentFiles: List<RecentFileItem>,
    loading: Boolean,
    error: String?,
    onShowAll: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (FileEntry) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LText("Naujausi failai", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (recentFiles.isNotEmpty()) TextButton(onClick = onShowAll) { LText("Rodyti visus") }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Rounded.Refresh, contentDescription = uiText("Atnaujinti naujausius failus"))
        }
    }
    when {
        loading && recentFiles.isEmpty() -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        error != null -> LText(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        recentFiles.isEmpty() -> LText(
            "Naujausių failų dar nėra",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("recent_files_row"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(recentFiles.take(8), key = { it.entry.absolutePath }) { item ->
                RecentFileCard(item = item, onOpen = { onOpen(item.entry) })
            }
        }
    }
}

@Composable
private fun StorageHomeSection(
    roots: List<StorageRoot>,
    rootStorageAvailable: Boolean,
    onOpen: (StorageRoot) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenCleanup: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LText("Saugyklos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(
            onClick = onOpenCleanup,
            modifier = Modifier.testTag("analyze_storage_button"),
        ) {
            Icon(Icons.Rounded.Analytics, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            LText("Analizuoti saugyklą")
        }
    }
    roots.forEach { root ->
        val usageFraction = root.totalBytes.takeIf { it > 0L }?.let { total ->
            (total - root.freeBytes).coerceIn(0L, total).toFloat() / total.toFloat()
        }
        StorageLocationCard(
            title = when (root.kind) {
                StorageRootKind.INTERNAL -> "Vidinė atmintis"
                StorageRootKind.SD_CARD -> root.title.ifBlank { "SD kortelė" }
                StorageRootKind.USB_STORAGE -> root.title.ifBlank { "USB saugykla" }
                StorageRootKind.REMOVABLE -> root.title.ifBlank { "Išimama saugykla" }
            },
            description = "${FileSystemRules.humanBytes(root.freeBytes)} laisva iš ${FileSystemRules.humanBytes(root.totalBytes)}",
            icon = when (root.kind) {
                StorageRootKind.INTERNAL -> Icons.Rounded.Storage
                StorageRootKind.USB_STORAGE -> Icons.Rounded.Usb
                StorageRootKind.SD_CARD, StorageRootKind.REMOVABLE -> Icons.Rounded.SdStorage
            },
            usageFraction = usageFraction,
            onClick = { onOpen(root) },
        )
    }
    val rootSpace = remember(rootStorageAvailable) {
        if (rootStorageAvailable) {
            File("/").let { root -> root.totalSpace.coerceAtLeast(0L) to root.usableSpace.coerceAtLeast(0L) }
        } else 0L to 0L
    }
    val rootUsageFraction = rootSpace.first.takeIf { rootStorageAvailable && it > 0L }?.let { total ->
        (total - rootSpace.second).coerceIn(0L, total).toFloat() / total.toFloat()
    }
    StorageLocationCard(
        title = "Root",
        description = if (!rootStorageAvailable) {
            "Įjunkite Root arba Shizuku root prieigą skiltyje Daugiau"
        } else rootSpace.first.takeIf { it > 0L }?.let { total ->
            "${FileSystemRules.humanBytes(rootSpace.second)} laisva iš ${FileSystemRules.humanBytes(total)}"
        } ?: "Sistemos failai · privilegijuota prieiga",
        icon = if (rootStorageAvailable) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
        usageFraction = rootUsageFraction,
        onClick = onOpenRoot,
        modifier = Modifier.testTag("root_storage_location"),
    )
}

@Composable
private fun QuickLocationsHomeSection(
    locations: List<QuickLocation>,
    displaySettings: DirectoryDisplaySettings,
    onOpen: (QuickLocation) -> Unit,
    onToggleLayout: () -> Unit,
    onConfigureLayout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LText("Greitos vietos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        DirectoryLayoutButton(
            grid = displaySettings.layoutMode == DirectoryLayoutMode.GRID,
            testTag = "home_layout_toggle",
            onToggleLayout = onToggleLayout,
            onOpenSettings = onConfigureLayout,
        )
    }
    if (locations.isEmpty()) {
        LText(
            "Greitųjų vietų nerodoma",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (displaySettings.layoutMode == DirectoryLayoutMode.GRID) {
        val columns = displaySettings.gridColumns.coerceIn(4, 6)
        val spacing = (8f * displaySettings.spacingScalePercent / 100f).dp
        locations.chunked(columns).forEach { rowLocations ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                rowLocations.forEach { location ->
                    QuickLocationTile(
                        location = location,
                        iconScalePercent = displaySettings.iconScalePercent,
                        modifier = Modifier.weight(1f).testTag("quick_location_${location.id}"),
                        onClick = { onOpen(location) },
                    )
                }
                repeat(columns - rowLocations.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        locations.forEach { location ->
            StorageLocationCard(
                title = location.title,
                description = if (location.virtual) "Visa saugykla" else location.path,
                icon = location.icon,
                modifier = Modifier.testTag("quick_location_${location.id}"),
                onClick = { onOpen(location) },
            )
        }
    }
}

@Composable
private fun HomeToolCardSection(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    LText(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    StorageLocationCard(title = title, description = description, icon = icon, onClick = onClick)
}

@Composable
private fun FavoritesHomeSection(favorites: List<String>, onOpen: (String) -> Unit) {
    LText("Mėgstami", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    val existing = remember(favorites) { favorites.map(::File).filter(File::exists).take(8) }
    if (existing.isEmpty()) {
        LText("Mėgstamų vietų dar nėra", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        existing.forEach { file ->
            StorageLocationCard(
                title = file.name.ifBlank { file.absolutePath },
                description = file.absolutePath,
                icon = if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                onClick = { onOpen(file.absolutePath) },
            )
        }
    }
}

@Composable
private fun TagsHomeSection(snapshot: FileTagSnapshot, onOpen: (String) -> Unit) {
    LText("Žymos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (snapshot.definitions.isEmpty()) {
        LText("Žymų dar nėra", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(snapshot.definitions, key = FileTagDefinition::name) { tag ->
                AssistChip(onClick = { onOpen(tag.name) }, label = { Text(tag.name) })
            }
        }
    }
}

@Composable
private fun HomeCustomizationDialog(
    customization: HomeCustomization,
    currentPath: String,
    onDismiss: () -> Unit,
    onMoveSection: (HomeSection, Int) -> Unit,
    onMoveShortcut: (String, Int) -> Unit,
    onSetShortcutVisible: (String, Boolean) -> Unit,
    onRemoveShortcut: (String) -> Unit,
    onAddShortcut: (String, String) -> Boolean,
) {
    var showAdd by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText("Tvarkyti pradžios ekraną") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    LText("Pridėti failo ar aplanko nuorodą")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                item { LText("Sekcijų tvarka", style = MaterialTheme.typography.titleSmall) }
                items(customization.sectionOrder, key = HomeSection::name) { section ->
                    val index = customization.sectionOrder.indexOf(section)
                    HomeOrderRow(
                        title = homeSectionTitle(section),
                        canMoveUp = index > 0,
                        canMoveDown = index < customization.sectionOrder.lastIndex,
                        onMoveUp = { onMoveSection(section, -1) },
                        onMoveDown = { onMoveSection(section, 1) },
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LText("Greitos vietos", style = MaterialTheme.typography.titleSmall)
                }
                items(customization.shortcuts, key = HomeShortcut::id) { shortcut ->
                    val index = customization.shortcuts.indexOfFirst { it.id == shortcut.id }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = shortcut.visible,
                            onCheckedChange = { onSetShortcutVisible(shortcut.id, it) },
                        )
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            if (shortcut.builtIn) LText(shortcut.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else Text(shortcut.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                shortcut.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onMoveShortcut(shortcut.id, -1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Perkelti aukštyn"))
                        }
                        IconButton(
                            onClick = { onMoveShortcut(shortcut.id, 1) },
                            enabled = index < customization.shortcuts.lastIndex,
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = uiText("Perkelti žemyn"))
                        }
                        if (!shortcut.builtIn) {
                            IconButton(onClick = { onRemoveShortcut(shortcut.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti greitą vietą"))
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { LText("Baigti") } },
    )

    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var path by remember(currentPath) { mutableStateOf(currentPath) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { LText("Pridėti greitą vietą") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { LText("Pavadinimas (nebūtina)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { LText("Failo arba aplanko kelias") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { if (onAddShortcut(title, path)) showAdd = false },
                    enabled = path.isNotBlank(),
                ) { LText("Pridėti") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun HomeOrderRow(
    title: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText(title, modifier = Modifier.weight(1f))
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Perkelti aukštyn"))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = uiText("Perkelti žemyn"))
        }
    }
}

private fun homeSectionTitle(section: HomeSection): String = when (section) {
    HomeSection.RECENT_FILES -> "Naujausi failai"
    HomeSection.STORAGE -> "Saugyklos"
    HomeSection.QUICK_LOCATIONS -> "Greitos vietos"
    HomeSection.TRASH -> "Šiukšlinė"
    HomeSection.FAVORITES -> "Mėgstami"
    HomeSection.TAGS -> "Žymos"
}

private fun homeShortcutIcon(shortcut: HomeShortcut): ImageVector = when (shortcut.id) {
    "builtin.downloads" -> Icons.Rounded.Download
    "builtin.documents" -> Icons.Rounded.Description
    "builtin.pictures" -> Icons.Rounded.PhotoLibrary
    "builtin.videos" -> Icons.Rounded.VideoLibrary
    "builtin.music" -> Icons.Rounded.MusicNote
    "builtin.archives" -> Icons.Rounded.Archive
    "builtin.apps" -> Icons.Rounded.Android
    "builtin.installed_apps" -> Icons.Rounded.Android
    else -> if (File(shortcut.path).isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description
}

@Composable
private fun RecentFileCard(item: RecentFileItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier.width(188.dp).height(172.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LocalFileVisual(
                entry = item.entry,
                targetWidth = 72.dp,
                targetHeight = 68.dp,
                showThumbnails = true,
                modifier = Modifier.fillMaxWidth().height(68.dp),
            )
            Text(item.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(FileSystemRules.humanBytes(item.entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(recentTimeLabel(context, item.recentAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RecentFileListItem(item: RecentFileItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LocalFileVisual(
                entry = item.entry,
                targetWidth = 44.dp,
                targetHeight = 44.dp,
                showThumbnails = true,
                modifier = Modifier.size(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${FileSystemRules.humanBytes(item.entry.sizeBytes)} · ${recentTimeLabel(context, item.recentAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun recentTimeLabel(context: android.content.Context, timestampMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(context, timestampMillis, false).toString()

private data class QuickLocation(
    val id: String,
    val title: String,
    val path: String,
    val icon: ImageVector,
    val virtual: Boolean = false,
)

@Composable
private fun QuickLocationTile(
    location: QuickLocation,
    iconScalePercent: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val iconSize = (32f * iconScalePercent / 100f).dp
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.height(92.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(location.icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(5.dp))
            LText(
                location.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StorageLocationCard(
    title: String,
    description: String,
    icon: ImageVector,
    usageFraction: Float? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val animatedUsage by animateFloatAsState(
        targetValue = usageFraction?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = spring(),
        label = "storage usage",
    )
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(modifier = Modifier.weight(1f)) {
                LText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LText(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                usageFraction?.let {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { animatedUsage },
                            modifier = Modifier.weight(1f).height(10.dp).padding(top = 3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        LText(
                            "${(it.coerceIn(0f, 1f) * 100f).roundToInt()}% užimta",
                            modifier = Modifier.padding(start = 9.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun PanelUiState.toDirectoryDisplaySettings(): DirectoryDisplaySettings = DirectoryDisplaySettings(
    layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
    iconScalePercent = iconScalePercent,
    spacingScalePercent = spacingScalePercent,
    gridColumns = gridColumns,
    gridStyle = gridStyle,
    showThumbnails = showThumbnails,
)

@Composable
private fun FilePanel(
    panelId: PanelId,
    state: PanelUiState,
    tabs: PanelWorkspace,
    active: Boolean,
    clipboardAvailable: Boolean,
    batchRenameUndoAvailable: Boolean,
    modifier: Modifier,
    viewModel: MainViewModel,
    onCreate: () -> Unit,
    onRename: (FileEntry) -> Unit,
    onTrash: () -> Unit,
    onTrashEntry: (FileEntry) -> Unit,
    onInfo: (FileEntry) -> Unit,
    onArchive: () -> Unit,
    onTag: () -> Unit,
    onCopyToOther: () -> Unit,
    onPaste: () -> Unit,
    onUndoBatchRename: () -> Unit,
    onDisplaySettings: () -> Unit,
) {
    var compactMenu by remember { mutableStateOf(false) }
    var showViewingHistory by remember { mutableStateOf(false) }
    var searchVisible by remember(panelId) { mutableStateOf(false) }
    var searchQuery by remember(panelId) { mutableStateOf("") }
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val localClipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val tagSnapshot by viewModel.tagSnapshot.collectAsStateWithLifecycle()
    val tagsByPath = remember(tagSnapshot.records) { tagSnapshot.records.associateBy(TaggedFileRecord::path) }
    LaunchedEffect(state.path) {
        searchVisible = false
        searchQuery = ""
    }
    val displayedEntries = remember(state.entries, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) state.entries else state.entries.filter { it.name.contains(query, ignoreCase = true) }
    }
    val displayedState = state.copy(entries = displayedEntries)
    val allEntriesSelected = displayedEntries.isNotEmpty() && displayedEntries.all { it.absolutePath in state.selectedPaths }
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
            SelectionHeader(
                count = state.selectedPaths.size,
                allSelected = allEntriesSelected,
                onClose = { viewModel.clearSelection(panelId) },
                onToggleSelectAll = {
                    if (allEntriesSelected) viewModel.clearSelection(panelId)
                    else viewModel.selectPaths(panelId, displayedEntries.map(FileEntry::absolutePath))
                },
            )
        } else {
            DirectoryBrowserToolbar(
                title = File(state.path).name.ifBlank { state.path },
                path = state.path,
                backEnabled = state.backHistory.isNotEmpty(),
                forwardEnabled = state.forwardHistory.isNotEmpty(),
                upEnabled = File(state.path).parentFile != null,
                searchActive = searchVisible,
                grid = state.grid,
                testTagPrefix = "local_$panelId",
                onBack = { viewModel.navigateBack(panelId) },
                onForward = { viewModel.navigateForward(panelId) },
                onUp = { viewModel.navigateUp(panelId) },
                onToggleSearch = {
                    searchVisible = !searchVisible
                    if (!searchVisible) searchQuery = ""
                },
                onToggleLayout = { viewModel.toggleGrid(panelId) },
                onOpenSettings = onDisplaySettings,
            ) {
                CompactPanelActions(
                    expanded = compactMenu,
                    onExpandedChange = { compactMenu = it },
                    panelId = panelId,
                    state = state,
                    favorites = favorites,
                    clipboardAvailable = clipboardAvailable,
                    batchRenameUndoAvailable = batchRenameUndoAvailable,
                    viewModel = viewModel,
                    onPaste = onPaste,
                    onUndoBatchRename = onUndoBatchRename,
                    onDisplaySettings = onDisplaySettings,
                    onShowViewingHistory = { showViewingHistory = true },
                )
            }
        }

        if (searchVisible) {
            DirectoryQuickSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = { searchVisible = false; searchQuery = "" },
                modifier = Modifier.testTag("directory_search_field_local_$panelId"),
            )
        }

        Breadcrumbs(state.path) { path -> viewModel.navigate(panelId, path) }
        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LText(
                "Rasta ${state.entries.size} · metaduomenys ${state.listingMetadataEntries}/${state.entries.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.listingTruncated) {
            LText(
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
                displayedEntries.isEmpty() -> EmptyPanel("Atitikmenų nerasta", "Pabandykite kitą pavadinimą")
                state.grid -> FileGrid(panelId, displayedState, tagsByPath, scrollKey, viewModel, onRename, onInfo, onTrashEntry)
                else -> FileList(panelId, displayedState, tagsByPath, scrollKey, viewModel, onRename, onInfo, onTrashEntry)
            }
            if (state.selectedPaths.isNotEmpty()) {
                SelectionActionDock(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    IconButton(onClick = { viewModel.copySelection(panelId, move = false) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti"))
                    }
                    if (localClipboard?.mode == ClipboardMode.COPY) {
                        IconButton(
                            onClick = { viewModel.addSelectionToClipboard(panelId) },
                            modifier = Modifier.testTag("copy-more-local"),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = uiText("Kopijuoti daugiau"))
                        }
                    }
                    IconButton(onClick = { viewModel.copySelection(panelId, move = true) }) {
                        Icon(Icons.Rounded.ContentCut, contentDescription = uiText("Perkelti"))
                    }
                    IconButton(onClick = onCopyToOther) {
                        Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = uiText("Kopijuoti į kitą skydelį"))
                    }
                    IconButton(onClick = {
                        val selectedEntries = state.entries.filter { it.absolutePath in state.selectedPaths }
                        if (selectedEntries.size == 1) selectedEntries.firstOrNull()?.let(onRename)
                        else viewModel.beginBatchRename(selectedEntries.map(FileEntry::absolutePath))
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.DriveFileMove,
                            contentDescription = uiText(if (state.selectedPaths.size == 1) "Pervadinti" else "Masinis pervadinimas"),
                        )
                    }
                    IconButton(onClick = onArchive) { Icon(Icons.Rounded.Archive, contentDescription = uiText("Archyvuoti")) }
                    IconButton(onClick = onTag) { Icon(Icons.AutoMirrored.Rounded.Label, contentDescription = uiText("Žymos ir įvertinimas")) }
                    IconButton(onClick = onTrash) {
                        Icon(Icons.Rounded.Delete, contentDescription = uiText("Į šiukšlinę"), tint = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                FloatingActionButton(
                    onClick = { viewModel.activatePanel(panelId); onCreate() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = uiText("Sukurti"))
                }
            }
        }
    }
    if (showViewingHistory) {
        ViewingHistoryDialog(
            recents = recents,
            onDismiss = { showViewingHistory = false },
            onOpen = { recent ->
                showViewingHistory = false
                viewModel.openQuickPath(recent.path, panelId)
            },
            onClear = viewModel::clearRecents,
        )
    }
}

@Composable
private fun CompactPanelActions(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    panelId: PanelId,
    state: PanelUiState,
    favorites: List<String>,
    clipboardAvailable: Boolean,
    batchRenameUndoAvailable: Boolean,
    viewModel: MainViewModel,
    onPaste: () -> Unit,
    onUndoBatchRename: () -> Unit,
    onDisplaySettings: () -> Unit,
    onShowViewingHistory: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Aplanko veiksmai"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (clipboardAvailable) {
                DropdownMenuItem(
                    text = { LText("Įklijuoti") },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                    onClick = { onExpandedChange(false); onPaste() },
                )
                DropdownMenuItem(
                    text = { LText("Įklijuoti į kelias vietas") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        onExpandedChange(false)
                        viewModel.startAfPlanFromClipboard(destinationPanel = panelId)
                    },
                    modifier = Modifier.testTag("paste_to_many_$panelId"),
                )
            }
            if (batchRenameUndoAvailable) {
                DropdownMenuItem(
                    text = { LText("Atšaukti paskutinį masinį pervadinimą") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null) },
                    onClick = { onExpandedChange(false); onUndoBatchRename() },
                )
            }
            DropdownMenuItem(
                text = { LText(if (state.path in favorites) "Pašalinti iš mėgstamų" else "Pridėti prie mėgstamų") },
                leadingIcon = {
                    Icon(if (state.path in favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = null)
                },
                onClick = { viewModel.toggleFavorite(state.path); onExpandedChange(false) },
            )
            favorites.take(4).forEach { path ->
                DropdownMenuItem(
                    text = {
                        Column {
                            LText("★ ${File(path).name.ifBlank { path }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { viewModel.openQuickPath(path, panelId); onExpandedChange(false) },
                )
            }
            DropdownMenuItem(
                text = { LText("Peržiūrų istorija") },
                leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
                onClick = { onExpandedChange(false); onShowViewingHistory() },
            )
            HorizontalDivider()
            DirectoryDisplayMenuItems(
                grid = state.grid,
                includeHidden = state.includeHidden,
                showThumbnails = state.showThumbnails,
                thumbnailsAvailable = true,
                sortMode = state.sortMode,
                sortDirection = state.sortDirection,
                displaySettingsTestTag = "display_settings_$panelId",
                onToggleHidden = { viewModel.toggleHidden(panelId) },
                onToggleLayout = { viewModel.toggleGrid(panelId) },
                onToggleThumbnails = { viewModel.toggleThumbnails(panelId) },
                onOpenSettings = onDisplaySettings,
                onSort = { viewModel.setSort(panelId, it) },
                onDismissMenu = { onExpandedChange(false) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { LText("Atidaryti terminalą šiame aplanke") },
                leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                onClick = { viewModel.openLocalTerminal(panelId); onExpandedChange(false) },
            )
            DropdownMenuItem(
                text = { LText("Atnaujinti") },
                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                onClick = { viewModel.refreshPanel(panelId); onExpandedChange(false) },
            )
        }
    }
}

@Composable
private fun ViewingHistoryDialog(
    recents: List<RecentItem>,
    onDismiss: () -> Unit,
    onOpen: (RecentItem) -> Unit,
    onClear: () -> Unit,
) {
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.SHORT, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText("Peržiūrų istorija") },
        text = {
            if (recents.isEmpty()) {
                LText("Peržiūrėtų failų istorija tuščia")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(recents, key = { "history:${it.path}" }) { recent ->
                        Row(
                            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(recent) }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(File(recent.path).name.ifBlank { recent.path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(recent.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(dateFormat.format(Date(recent.openedAtMillis)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { LText("Uždaryti") } },
        dismissButton = {
            if (recents.isNotEmpty()) TextButton(onClick = onClear) { LText("Išvalyti istoriją") }
        },
    )
}

@Composable
private fun PanelTabsBar(
    panel: PanelId,
    workspace: PanelWorkspace,
    viewModel: MainViewModel,
    onBeforeTabAction: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("panel_tabs_$panel")
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(22.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        workspace.tabs.forEach { tab ->
            FilterChip(
                selected = tab.id == workspace.activeTabId,
                onClick = { onBeforeTabAction(); viewModel.activateTab(panel, tab.id) },
                label = { Text(File(tab.path).name.ifBlank { tab.path }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (tab.locked) {
                    { Icon(Icons.Rounded.Lock, contentDescription = uiText("Užrakinta"), modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
        IconButton(onClick = { onBeforeTabAction(); viewModel.newTab(panel) }) {
            Icon(Icons.Rounded.Add, contentDescription = uiText("Nauja kortelė"))
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Kortelės veiksmai")) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { LText("Dubliuoti kortelę") },
                    onClick = { onBeforeTabAction(); viewModel.duplicateTab(panel); menu = false },
                )
                DropdownMenuItem(
                    text = { LText(if (workspace.activeTab.locked) "Atrakinti kortelę" else "Užrakinti kortelę") },
                    leadingIcon = {
                        Icon(if (workspace.activeTab.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, contentDescription = null)
                    },
                    onClick = { onBeforeTabAction(); viewModel.toggleTabLock(panel); menu = false },
                )
                DropdownMenuItem(
                    text = { LText("Uždaryti kortelę") },
                    enabled = workspace.tabs.size > 1 && !workspace.activeTab.locked,
                    onClick = { onBeforeTabAction(); viewModel.closeActiveTab(panel); menu = false },
                )
                DropdownMenuItem(
                    text = { LText("Atkurti uždarytą kortelę") },
                    enabled = workspace.closedTabs.isNotEmpty(),
                    onClick = { onBeforeTabAction(); viewModel.restoreClosedTab(panel); menu = false },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { LText("Sukeisti skydelius") },
                    leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                    onClick = { onBeforeTabAction(); viewModel.swapPanels(); menu = false },
                )
                DropdownMenuItem(
                    text = { LText("Palyginti skydelių aplankus") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null) },
                    onClick = { onBeforeTabAction(); viewModel.comparePanels(); menu = false },
                )
            }
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
    onRename: (FileEntry) -> Unit,
    onInfo: (FileEntry) -> Unit,
    onTrash: (FileEntry) -> Unit,
) {
    val context = LocalContext.current
    val interfaceLanguage = LocalConfiguration.current.locales[0].language
    val chooserError = uiText("Programų pasirinkiklio atidaryti nepavyko")
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.SHORT, DateFormat.SHORT)
    val initialPosition = remember(scrollKey) { viewModel.fileScrollPosition(scrollKey) }
    var restoringPosition by remember(scrollKey) {
        mutableStateOf(initialPosition.firstVisibleItemIndex > 0 || initialPosition.firstVisibleItemScrollOffset > 0)
    }

    var pinInitialTop by remember(scrollKey) {
        mutableStateOf(ProgressiveScrollRules.startsPinnedToTop(initialPosition))
    }
    val listState = key(scrollKey) {
        rememberLazyListState()
    }
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(scrollKey, userDragging) {
        if (userDragging) pinInitialTop = false
    }
    LaunchedEffect(scrollKey, state.entries.size, state.loading, pinInitialTop) {
        if (pinInitialTop && state.entries.isNotEmpty()) {
            listState.scrollToItem(0)
            if (!state.loading) pinInitialTop = false
        } else if (restoringPosition && state.entries.isNotEmpty() &&
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
            val position = ProgressiveScrollRules.positionToPersist(
                pinnedToTop = pinInitialTop,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
            Triple(restoringPosition, position.firstVisibleItemIndex, position.firstVisibleItemScrollOffset)
        }.collect { (restoring, index, offset) ->
            if (!restoring) viewModel.saveFileScrollPosition(scrollKey, index, offset)
        }
    }
    DisposableEffect(scrollKey, listState, pinInitialTop) {
        onDispose {
            if (!restoringPosition) {
                val position = ProgressiveScrollRules.positionToPersist(
                    pinnedToTop = pinInitialTop,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                )
                viewModel.saveFileScrollPosition(
                    scrollKey,
                    position.firstVisibleItemIndex,
                    position.firstVisibleItemScrollOffset,
                )
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("file_list_$panel"),
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
                    iconScalePercent = state.iconScalePercent,
                    spacingScalePercent = state.spacingScalePercent,
                    onClick = { handleEntryClick(panel, state, entry, viewModel) },
                    onLongClick = { viewModel.toggleSelection(panel, entry.absolutePath) },
                    onPreview = { viewModel.activatePanel(panel); viewModel.open(entry) },
                    onOpenWith = {
                        runCatching { openWith(context, PreviewSource.Local(entry)) }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    it.message?.let { message -> UiTranslator.translate(message, interfaceLanguage) } ?: chooserError,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    },
                    onSelect = { viewModel.toggleSelection(panel, entry.absolutePath) },
                    onRename = { onRename(entry) },
                    onInfo = { onInfo(entry) },
                    onTrash = { onTrash(entry) },
                )
            }
        }
        if (!state.loading) Spacer(Modifier.size(1.dp).testTag("file_list_ready_$panel"))
    }
}

@Composable
private fun FileGrid(
    panel: PanelId,
    state: PanelUiState,
    tagsByPath: Map<String, TaggedFileRecord>,
    scrollKey: FileScrollKey,
    viewModel: MainViewModel,
    onRename: (FileEntry) -> Unit,
    onInfo: (FileEntry) -> Unit,
    onTrash: (FileEntry) -> Unit,
) {
    val context = LocalContext.current
    val interfaceLanguage = LocalConfiguration.current.locales[0].language
    val chooserError = uiText("Programų pasirinkiklio atidaryti nepavyko")
    val initialPosition = remember(scrollKey) { viewModel.fileScrollPosition(scrollKey) }
    var restoringPosition by remember(scrollKey) {
        mutableStateOf(initialPosition.firstVisibleItemIndex > 0 || initialPosition.firstVisibleItemScrollOffset > 0)
    }
    var pinInitialTop by remember(scrollKey) {
        mutableStateOf(ProgressiveScrollRules.startsPinnedToTop(initialPosition))
    }
    val gridState = key(scrollKey) {
        rememberLazyGridState()
    }
    val userDragging by gridState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(scrollKey, userDragging) {
        if (userDragging) pinInitialTop = false
    }
    LaunchedEffect(scrollKey, state.entries.size, state.loading, pinInitialTop) {
        if (pinInitialTop && state.entries.isNotEmpty()) {
            gridState.scrollToItem(0)
            if (!state.loading) pinInitialTop = false
        } else if (restoringPosition && state.entries.isNotEmpty() &&
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
            val position = ProgressiveScrollRules.positionToPersist(
                pinnedToTop = pinInitialTop,
                firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
            )
            Triple(restoringPosition, position.firstVisibleItemIndex, position.firstVisibleItemScrollOffset)
        }.collect { (restoring, index, offset) ->
            if (!restoring) viewModel.saveFileScrollPosition(scrollKey, index, offset)
        }
    }
    DisposableEffect(scrollKey, gridState, pinInitialTop) {
        onDispose {
            if (!restoringPosition) {
                val position = ProgressiveScrollRules.positionToPersist(
                    pinnedToTop = pinInitialTop,
                    firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset,
                )
                viewModel.saveFileScrollPosition(
                    scrollKey,
                    position.firstVisibleItemIndex,
                    position.firstVisibleItemScrollOffset,
                )
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(state.gridColumns.coerceIn(1, 6)),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("file_grid_$panel"),
            contentPadding = PaddingValues(8.dp, 6.dp, 8.dp, 88.dp),
            horizontalArrangement = Arrangement.spacedBy((5f * state.spacingScalePercent / 100f).dp),
            verticalArrangement = Arrangement.spacedBy((5f * state.spacingScalePercent / 100f).dp),
        ) {
            items(state.entries, key = FileEntry::absolutePath, contentType = FileEntry::kind) { entry ->
                val tagRecord = tagsByPath[entry.absolutePath]
                val tagText = remember(entry, tagRecord) { tagSummary(entry, tagRecord) }
                FileTile(
                    entry = entry,
                    tagText = tagText,
                    selected = entry.absolutePath in state.selectedPaths,
                    showThumbnails = state.showThumbnails,
                    iconScalePercent = state.iconScalePercent,
                    spacingScalePercent = state.spacingScalePercent,
                    gridStyle = state.gridStyle,
                    onClick = { handleEntryClick(panel, state, entry, viewModel) },
                    onLongClick = { viewModel.toggleSelection(panel, entry.absolutePath) },
                    onPreview = { viewModel.activatePanel(panel); viewModel.open(entry) },
                    onOpenWith = {
                        runCatching { openWith(context, PreviewSource.Local(entry)) }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    it.message?.let { message -> UiTranslator.translate(message, interfaceLanguage) } ?: chooserError,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    },
                    onSelect = { viewModel.toggleSelection(panel, entry.absolutePath) },
                    onRename = { onRename(entry) },
                    onInfo = { onInfo(entry) },
                    onTrash = { onTrash(entry) },
                )
            }
        }
        if (!state.loading) Spacer(Modifier.size(1.dp).testTag("file_grid_ready_$panel"))
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
    iconScalePercent: Int,
    spacingScalePercent: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: () -> Unit,
    onOpenWith: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onTrash: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(8.dp)
    val iconSize = (42f * iconScalePercent / 100f).dp
    val verticalPadding = (9f * spacingScalePercent / 100f).dp
    val itemSpacing = (12f * spacingScalePercent / 100f).dp
    val itemAlpha = if (entry.isHidden && !selected) 0.64f else 1f
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
            .alpha(itemAlpha)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        LocalFileVisual(
            entry = entry,
            targetWidth = iconSize,
            targetHeight = iconSize,
            showThumbnails = showThumbnails,
            modifier = Modifier.size(iconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal)
            LText(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tagText?.let { summary ->
                Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!entry.isReadable) LText("Neprieinama", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        EntryActionsButton(entry, onPreview, onOpenWith, onSelect, onRename, onInfo, onTrash)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = iconSize + itemSpacing + 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    entry: FileEntry,
    tagText: String?,
    selected: Boolean,
    showThumbnails: Boolean,
    iconScalePercent: Int,
    spacingScalePercent: Int,
    gridStyle: DirectoryGridStyle,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: () -> Unit,
    onOpenWith: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onTrash: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(12.dp)
    val visualHeight = (76f * iconScalePercent / 100f).dp
    val cardHeight = 158.dp + (visualHeight - 76.dp)
    val innerPadding = (9f * spacingScalePercent / 100f).dp
    val itemAlpha = if (entry.isHidden && !selected) 0.64f else 1f
    Card(
        modifier = Modifier
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, selectionShape) else Modifier)
            .alpha(itemAlpha)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = if (gridStyle == DirectoryGridStyle.CLASSIC) RoundedCornerShape(4.dp) else selectionShape,
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                gridStyle == DirectoryGridStyle.CLASSIC -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(cardHeight)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LocalFileVisual(
                    entry = entry,
                    targetWidth = (96f * iconScalePercent / 100f).dp,
                    targetHeight = visualHeight,
                    showThumbnails = showThumbnails,
                    modifier = Modifier.fillMaxWidth().height(visualHeight),
                )
                Spacer(Modifier.height(6.dp))
                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (!entry.isDirectory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                tagText?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                EntryActionsButton(entry, onPreview, onOpenWith, onSelect, onRename, onInfo, onTrash)
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
        title = { LText("Žymos ir įvertinimas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LText("Pasirinkta: ${paths.size}. Naujos žymos pridedamos prie esamų; viską pašalina atskiras mygtukas.", style = MaterialTheme.typography.bodySmall)
                if (currentTags.isNotEmpty()) {
                    LText("Dabartinės: ${currentTags.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (definitions.isNotEmpty()) {
                    LText("Esamos žymos", style = MaterialTheme.typography.labelLarge)
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
                    label = { LText("Naujos žymos, atskirtos kableliais") },
                    supportingText = { LText("Hierarchija: Projektas/Dokumentai") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LText("Naujų žymų spalva", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colors.forEachIndexed { index, color ->
                        FilterChip(
                            selected = color == selectedColor,
                            onClick = { selectedColor = color },
                            label = { LText("●", color = Color(color)) },
                        )
                    }
                }
                LText("Įvertinimas (nepasirinkus nekeičiamas)", style = MaterialTheme.typography.labelLarge)
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
                    OutlinedButton(onClick = onClear, enabled = currentRecords.isNotEmpty()) { LText("Pašalinti visas") }
                    OutlinedButton(onClick = onExport) { LText("Eksportuoti") }
                    onImport?.let { importAction -> OutlinedButton(onClick = importAction) { LText("Importuoti JSON") } }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(parsedTags, rating, selectedColor) }, enabled = parsedTags.isNotEmpty() || rating != null) { LText("Taikyti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

@Composable
private fun EntryActionsButton(
    entry: FileEntry,
    onPreview: () -> Unit,
    onOpenWith: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onInfo: () -> Unit,
    onTrash: () -> Unit,
) {
    var expanded by remember(entry.absolutePath) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Failo veiksmai: ${entry.name}"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { LText(if (entry.isDirectory) "Atidaryti aplanką" else "Peržiūrėti čia") },
                leadingIcon = { Icon(if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Visibility, contentDescription = null) },
                onClick = { expanded = false; onPreview() },
                enabled = entry.isReadable,
            )
            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { LText("Atidaryti su kita programa") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                    onClick = { expanded = false; onOpenWith() },
                    enabled = entry.isReadable,
                )
            }
            DropdownMenuItem(
                text = { LText("Pasirinkti") },
                leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                onClick = { expanded = false; onSelect() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { LText("Pervadinti") },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                onClick = { expanded = false; onRename() },
            )
            DropdownMenuItem(
                text = { LText("Informacija") },
                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                onClick = { expanded = false; onInfo() },
            )
            DropdownMenuItem(
                text = { LText("Į šiukšlinę") },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onTrash() },
            )
        }
    }
}

@Composable
private fun FileInfoDialog(entry: FileEntry, onDismiss: () -> Unit) {
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("Tipas", if (entry.isDirectory) uiText("Aplankas") else entry.extension.uppercase().ifBlank { entry.kind.name })
                if (!entry.isDirectory) InfoLine("Dydis", FileSystemRules.humanBytes(entry.sizeBytes))
                if (entry.modifiedAtMillis > 0L) InfoLine("Pakeista", dateFormat.format(Date(entry.modifiedAtMillis)))
                InfoLine(
                    "Prieiga",
                    listOfNotNull(
                        uiText("Skaitoma").takeIf { entry.isReadable },
                        uiText("Rašoma").takeIf { entry.isWritable },
                    ).ifEmpty { listOf(uiText("Neprieinama")) }.joinToString(" · "),
                )
                LText("Kelias", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(entry.absolutePath, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { LText("Uždaryti") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LText(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
        LText(title, style = MaterialTheme.typography.titleMedium)
        LText(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreateItemDialog(onDismiss: () -> Unit, onCreateFolder: (String) -> Unit, onCreateFile: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText(if (folder) "Naujas aplankas" else "Naujas failas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { folder = true }) { LText("Aplankas") }
                    OutlinedButton(onClick = { folder = false }) { LText("Failas") }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { LText("Pavadinimas") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = { if (folder) onCreateFolder(name) else onCreateFile(name) },
                enabled = name.isNotBlank(),
            ) { LText("Sukurti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
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
        title = { LText(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { LText(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

@Composable
private fun ArchiveDialog(onDismiss: () -> Unit, onCreate: (String, ArchiveFormat, CharArray?) -> Unit) {
    var name by remember { mutableStateOf("archyvas") }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText("Sukurti archyvą") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { LText("Pavadinimas") }, singleLine = true)
                LText("Formatas", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ).forEach { candidate ->
                        AssistChip(onClick = { format = candidate }, label = { Text(candidate.name) })
                    }
                }
                if (format == ArchiveFormat.ZIP) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { LText("Slaptažodis (nebūtinas, AES-256)") },
                        singleLine = true,
                    )
                }
                LText("Pasirinkta: ${format.name}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, format, password.takeIf(String::isNotBlank)?.toCharArray()) }, enabled = name.isNotBlank()) {
                LText("Kurti")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
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
        title = { LText(if (moving) "Patikimai perkelti" else "Patikimai kopijuoti") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LText("Planas bus išsaugotas prieš vykdymą. Ši taisyklė bus taikoma visiems sutampantiems vardams.")
                choices.forEach { (candidate, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { policy = candidate }, onLongClick = {}),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = policy == candidate, onClick = { policy = candidate })
                        LText(label)
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        LText("Patikrinti SHA-256", fontWeight = FontWeight.SemiBold)
                        LText(
                            if (moving) "Rekomenduojama: šaltinis šalinamas tik patvirtinus kopiją" else "Lėčiau, bet patvirtina failo turinį",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = verifySha256, onCheckedChange = { verifySha256 = it })
                }
                if (!moving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            LText("Praleisti klaidingą failą ir tęsti", fontWeight = FontWeight.SemiBold)
                            LText("Iki 100 klaidų bus palikta galutinėje ataskaitoje", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = skipErrors, onCheckedChange = { skipErrors = it })
                    }
                } else {
                    LText("Perkėlimas klaidos atveju visada sustoja; nepatikrintas šaltinis nešalinamas.", style = MaterialTheme.typography.bodySmall)
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
            ) { LText("Išsaugoti planą ir pradėti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

internal fun sortLabel(mode: SortMode): String = when (mode) {
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
