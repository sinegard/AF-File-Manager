package com.affilemanager.app.ui.screens

import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.ClipboardMode
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProfileRules
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemoteErrorInfo
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.RemoteBrowserRules
import com.affilemanager.app.ui.components.RemoteFileVisual
import com.affilemanager.app.ui.components.SelectionActionBar
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.network.RemoteCopyEngine
import com.affilemanager.app.sync.SyncActionType
import com.affilemanager.app.sync.SyncConflictPolicy
import com.affilemanager.app.sync.SyncMode
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectionsScreen(viewModel: MainViewModel, contentPadding: PaddingValues) {
    val state by viewModel.networkState.collectAsStateWithLifecycle()
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val left by viewModel.leftPanel.collectAsStateWithLifecycle()
    val right by viewModel.rightPanel.collectAsStateWithLifecycle()
    val sync by viewModel.syncState.collectAsStateWithLifecycle()
    val localClipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val remoteClipboard by viewModel.remoteClipboard.collectAsStateWithLifecycle()
    val activeLocalState = if (activePanel == com.affilemanager.app.ui.PanelId.LEFT) left else right
    val activeSelection = activeLocalState.selectedPaths

    var showAdd by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<NetworkProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<NetworkProfile?>(null) }
    var createRemoteFolder by remember { mutableStateOf(false) }
    var renameRemote by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteRemote by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var showSync by remember { mutableStateOf(false) }
    var showUploadPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        if (state.connectedProfile == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    LText("Tinklas ir nuotolinės vietos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    LText("SMB 2/3 · SFTP · WebDAV · FTP/FTPS", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.connectedProfile == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.profiles.isEmpty()) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                    LText("Dar nėra jungčių", style = MaterialTheme.typography.titleMedium)
                                    LText("Pridėkite NAS, serverį arba WebDAV vietą.")
                                }
                            }
                        }
                    }
                    items(state.profiles, key = NetworkProfile::id) { profile ->
                        ProfileCard(
                            profile = profile,
                            loading = state.loading,
                            onConnect = { viewModel.connectNetwork(profile) },
                            onEdit = { editingProfile = profile },
                            onDelete = { deleteProfile = profile },
                        )
                    }
                    state.error?.let { error -> item { NetworkError(error) } }
                }
                FloatingActionButton(onClick = { showAdd = true }, modifier = Modifier.padding(20.dp).align(Alignment.BottomEnd)) {
                    Icon(Icons.Rounded.Add, contentDescription = uiText("Pridėti jungtį"))
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                RemoteBrowser(
                    state = state,
                    localDirectory = activeLocalState.path,
                    compactToolbar = maxWidth < 600.dp,
                    onBack = viewModel::navigateRemoteBack,
                    onForward = viewModel::navigateRemoteForward,
                    onUp = viewModel::navigateRemoteUp,
                    onRefresh = { viewModel.refreshRemote() },
                    onOpen = viewModel::openRemoteEntry,
                    onDownload = viewModel::remoteDownload,
                    onToggleSelection = viewModel::toggleRemoteSelection,
                    onClearSelection = viewModel::clearRemoteSelection,
                    onSelectAll = viewModel::selectAllRemote,
                    onDownloadSelected = viewModel::remoteDownloadSelection,
                    onCopySelected = viewModel::copyRemoteSelection,
                    canAddToRemoteClipboard = remoteClipboard?.profileId == state.connectedProfile?.id,
                    onAddToRemoteClipboard = viewModel::addRemoteSelectionToClipboard,
                    localClipboardCount = localClipboard?.takeIf { it.mode == ClipboardMode.COPY }?.paths?.size ?: 0,
                    onPasteLocalClipboard = { viewModel.pasteLocalClipboardToRemote() },
                    onChooseUpload = { showUploadPicker = true },
                    onCreateFolder = { createRemoteFolder = true },
                    onRename = { renameRemote = it },
                    onDelete = { deleteRemote = it },
                    onSync = { showSync = true },
                    onToggleHidden = viewModel::toggleRemoteHidden,
                    onToggleGrid = viewModel::toggleRemoteGrid,
                    onSort = viewModel::setRemoteSort,
                    onDisconnect = viewModel::disconnectNetwork,
                )
            }
        }
    }

    if (showAdd || editingProfile != null) {
        NetworkProfileDialog(
            existingProfile = editingProfile,
            onDismiss = {
                showAdd = false
                editingProfile = null
            },
            onSave = { profile, password, privateKey ->
                viewModel.saveNetworkProfile(profile, password, privateKey)
                showAdd = false
                editingProfile = null
            },
        )
    }
    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { LText("Pašalinti jungtį?") },
            text = { LText("Bus pašalintas „${profile.name}“ profilis ir jo užšifruotas prisijungimo įrašas.") },
            confirmButton = {
                Button(onClick = { viewModel.removeNetworkProfile(profile.id); deleteProfile = null }) { LText("Pašalinti") }
            },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { LText("Atšaukti") } },
        )
    }
    if (createRemoteFolder) {
        RemoteNameDialog("Naujas nuotolinis aplankas", "Sukurti", "", { createRemoteFolder = false }) {
            viewModel.remoteCreateDirectory(it)
            createRemoteFolder = false
        }
    }
    renameRemote?.let { entry ->
        RemoteNameDialog("Pervadinti", "Pervadinti", entry.name, { renameRemote = null }) {
            viewModel.remoteRename(entry, it)
            renameRemote = null
        }
    }
    deleteRemote?.let { entries ->
        val single = entries.singleOrNull()
        AlertDialog(
            onDismissRequest = { deleteRemote = null },
            title = {
                LText(
                    when {
                        entries.size > 1 -> "Ištrinti pasirinktus elementus?"
                        single?.directory == true -> "Ištrinti aplanką ir jo turinį?"
                        else -> "Ištrinti failą?"
                    },
                )
            },
            text = {
                LText(
                    single?.let { "„${it.name}“ bus ištrintas nuotoliniame serveryje be vietinės šiukšlinės." }
                        ?: "${entries.size} elementai bus ištrinti nuotoliniame serveryje be vietinės šiukšlinės.",
                )
            },
            confirmButton = { Button(onClick = { viewModel.remoteDelete(entries); deleteRemote = null }) { LText("Ištrinti") } },
            dismissButton = { TextButton(onClick = { deleteRemote = null }) { LText("Atšaukti") } },
        )
    }
    if (showSync) {
        SyncDialog(
            state = sync,
            onDismiss = { showSync = false },
            onMode = viewModel::setSyncMode,
            onPolicy = viewModel::setSyncConflictPolicy,
            onPreview = viewModel::previewSync,
            onExecute = { viewModel.executeSync(); showSync = false },
            onSchedule = viewModel::scheduleCurrentSync,
        )
    }
    if (showUploadPicker && state.connectedProfile != null) {
        LocalUploadDialog(
            initialDirectoryPath = activeLocalState.path,
            remotePath = state.path,
            initialEntries = activeLocalState.entries,
            initiallySelected = activeSelection,
            loadDirectory = viewModel::listLocalDirectoryForUpload,
            onDismiss = { showUploadPicker = false },
            onCopy = { paths ->
                if (viewModel.remoteUpload(paths)) {
                    viewModel.clearSelection(activePanel)
                    showUploadPicker = false
                }
            },
        )
    }
}

@Composable
internal fun ProfileCard(
    profile: NetworkProfile,
    loading: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val normalized = NetworkProfileRules.normalize(profile)
    val profileProblem = runCatching { NetworkProfileRules.validate(normalized) }.exceptionOrNull()?.message
    val safeName = if (NetworkProfileRules.nameError(profile.name) == null) normalized.name else "Jungtis"
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(safeName, fontWeight = FontWeight.SemiBold)
                if (profileProblem == null) {
                    Text("${profile.protocol} · ${normalized.host}:${profile.port}", style = MaterialTheme.typography.bodySmall)
                } else {
                    LText("${profile.protocol} · Neteisingi jungties duomenys", style = MaterialTheme.typography.bodySmall)
                }
                if (profileProblem != null) {
                    LText(
                        "$profileProblem. Paspauskite pieštuką.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (profile.protocol == NetworkProtocol.SFTP) {
                    LText(
                        profile.expectedHostKeySha256?.let { "SSH ${it.take(22)}…" } ?: "Pirmasis raktas bus aiškiai patikėtas",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = uiText("Redaguoti jungtį")) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti")) }
            Button(onClick = onConnect, enabled = !loading) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else LText("Jungtis")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RemoteBrowser(
    state: com.affilemanager.app.ui.NetworkUiState,
    localDirectory: String,
    compactToolbar: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (RemoteEntry) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDownloadSelected: () -> Unit,
    onCopySelected: () -> Unit,
    canAddToRemoteClipboard: Boolean = false,
    onAddToRemoteClipboard: () -> Unit = {},
    localClipboardCount: Int,
    onPasteLocalClipboard: () -> Unit,
    onChooseUpload: () -> Unit,
    onCreateFolder: () -> Unit,
    onRename: (RemoteEntry) -> Unit,
    onDelete: (List<RemoteEntry>) -> Unit,
    onSync: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleGrid: () -> Unit,
    onSort: (SortMode) -> Unit,
    onDisconnect: () -> Unit,
) {
    val displayedEntries = remember(state.entries, state.includeHidden, state.sortMode, state.sortDirection) {
        RemoteBrowserRules.displayEntries(
            entries = state.entries,
            includeHidden = state.includeHidden,
            sortMode = state.sortMode,
            sortDirection = state.sortDirection,
        )
    }
    val selectableEntries = displayedEntries.take(RemoteCopyEngine.MAX_SELECTED_ROOTS)
    val allSelected = selectableEntries.isNotEmpty() && selectableEntries.all { it.path in state.selectedPaths }
    val selectedEntries = displayedEntries.filter { it.path in state.selectedPaths }
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.selectedPaths.isNotEmpty()) {
            SelectionActionBar(
                count = state.selectedPaths.size,
                allSelected = allSelected,
                onClose = onClearSelection,
                onToggleSelectAll = { if (allSelected) onClearSelection() else onSelectAll() },
            ) {
                IconButton(onClick = onCopySelected, enabled = !state.loading) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti"))
                }
                if (canAddToRemoteClipboard) {
                    TextButton(
                        onClick = onAddToRemoteClipboard,
                        enabled = !state.loading,
                        modifier = Modifier.testTag("copy-more-remote"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = uiText("Įtraukti į iškarpinę"))
                        LText("Kopijuoti daugiau", modifier = Modifier.padding(start = 5.dp))
                    }
                }
                IconButton(onClick = onDownloadSelected, enabled = !state.loading) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = uiText("Kopijuoti į aktyvų vietinį aplanką"))
                }
                IconButton(
                    onClick = { selectedEntries.singleOrNull()?.let(onRename) },
                    enabled = !state.loading && selectedEntries.size == 1,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = uiText("Pervadinti"))
                }
                IconButton(
                    onClick = { onDelete(selectedEntries) },
                    enabled = !state.loading && selectedEntries.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = uiText("Ištrinti"), tint = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            RemoteFolderToolbar(
                state = state,
                localDirectory = localDirectory,
                localClipboardCount = localClipboardCount,
                compactToolbar = compactToolbar,
                onBack = onBack,
                onForward = onForward,
                onUp = onUp,
                onRefresh = onRefresh,
                onPasteLocalClipboard = onPasteLocalClipboard,
                onChooseUpload = onChooseUpload,
                onSync = onSync,
                onToggleHidden = onToggleHidden,
                onToggleGrid = onToggleGrid,
                onSort = onSort,
                onDisconnect = onDisconnect,
            )
        }
        RemoteBreadcrumbs(state.path) { onPath ->
            if (onPath != state.path) {
                val entry = RemoteEntry(onPath.substringAfterLast('/').ifBlank { "/" }, onPath, true, 0, null)
                onOpen(entry)
            }
        }
        if (state.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.error?.let { NetworkError(it) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.error != null && displayedEntries.isEmpty() -> Unit
                state.loading && displayedEntries.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                displayedEntries.isEmpty() -> RemoteEmptyPanel()
                state.grid -> RemoteEntryGrid(
                    entries = displayedEntries,
                    selectedPaths = state.selectedPaths,
                    selectionActive = state.selectedPaths.isNotEmpty(),
                    openingPath = state.openingPath,
                    onOpen = onOpen,
                    onDownload = onDownload,
                    onToggleSelection = onToggleSelection,
                )
                else -> RemoteEntryList(
                    entries = displayedEntries,
                    selectedPaths = state.selectedPaths,
                    selectionActive = state.selectedPaths.isNotEmpty(),
                    openingPath = state.openingPath,
                    onOpen = onOpen,
                    onDownload = onDownload,
                    onToggleSelection = onToggleSelection,
                )
            }
            FloatingActionButton(
                onClick = onCreateFolder,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = uiText("Sukurti aplanką"))
            }
        }
    }
}

@Composable
private fun RemoteFolderToolbar(
    state: com.affilemanager.app.ui.NetworkUiState,
    localDirectory: String,
    localClipboardCount: Int,
    compactToolbar: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onPasteLocalClipboard: () -> Unit,
    onChooseUpload: () -> Unit,
    onSync: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleGrid: () -> Unit,
    onSort: (SortMode) -> Unit,
    onDisconnect: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = state.backHistory.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Atgal"))
        }
        IconButton(onClick = onForward, enabled = state.forwardHistory.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = uiText("Pirmyn"))
        }
        IconButton(onClick = onUp, enabled = state.path != "/") {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Aukštyn"))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.path.substringAfterLast('/').ifBlank { state.connectedProfile?.name.orEmpty() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(state.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!compactToolbar) {
            if (localClipboardCount > 0) {
                IconButton(
                    onClick = onPasteLocalClipboard,
                    enabled = !state.loading,
                    modifier = Modifier.testTag("remote_paste_local"),
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = uiText("Įklijuoti"))
                }
            }
            IconButton(onClick = onToggleHidden) {
                Icon(
                    if (state.includeHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = uiText("Paslėpti failai"),
                )
            }
            IconButton(onClick = onToggleGrid) {
                Icon(if (state.grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView, contentDescription = uiText("Rodinys"))
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = uiText("Rikiuoti"))
                }
                RemoteSortMenu(state, sortMenu, { sortMenu = false }, onSort)
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = uiText("Atnaujinti"))
            }
        }
        RemoteFolderActionsMenu(
            state = state,
            localDirectory = localDirectory,
            localClipboardCount = localClipboardCount,
            includeDisplayActions = compactToolbar,
            onPasteLocalClipboard = onPasteLocalClipboard,
            onChooseUpload = onChooseUpload,
            onSync = onSync,
            onToggleHidden = onToggleHidden,
            onToggleGrid = onToggleGrid,
            onSort = onSort,
            onRefresh = onRefresh,
            onDisconnect = onDisconnect,
        )
    }
}

@Composable
private fun RemoteFolderActionsMenu(
    state: com.affilemanager.app.ui.NetworkUiState,
    localDirectory: String,
    localClipboardCount: Int,
    includeDisplayActions: Boolean,
    onPasteLocalClipboard: () -> Unit,
    onChooseUpload: () -> Unit,
    onSync: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleGrid: () -> Unit,
    onSort: (SortMode) -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Aplanko veiksmai"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (localClipboardCount > 0 && includeDisplayActions) {
                DropdownMenuItem(
                    text = { LText("Įklijuoti ($localClipboardCount)") },
                    leadingIcon = { Icon(Icons.Rounded.ContentPaste, contentDescription = null) },
                    enabled = !state.loading,
                    modifier = Modifier.testTag("remote_paste_local"),
                    onClick = { expanded = false; onPasteLocalClipboard() },
                )
            }
            DropdownMenuItem(
                text = { LText("Pasirinkti iš telefono") },
                leadingIcon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
                enabled = !state.loading,
                modifier = Modifier.testTag("remote_upload_choose"),
                onClick = { expanded = false; onChooseUpload() },
            )
            DropdownMenuItem(
                text = { LText("Sinchronizuoti") },
                leadingIcon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                enabled = !state.loading,
                onClick = { expanded = false; onSync() },
            )
            if (includeDisplayActions) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { LText(if (state.includeHidden) "Slėpti paslėptus failus" else "Rodyti paslėptus failus") },
                    leadingIcon = {
                        Icon(if (state.includeHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null)
                    },
                    onClick = { expanded = false; onToggleHidden() },
                )
                DropdownMenuItem(
                    text = { LText(if (state.grid) "Rodyti sąrašą" else "Rodyti tinklelį") },
                    leadingIcon = { Icon(if (state.grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView, contentDescription = null) },
                    onClick = { expanded = false; onToggleGrid() },
                )
                SortMode.entries.forEach { mode ->
                    val suffix = if (state.sortMode == mode) {
                        if (state.sortDirection == SortDirection.ASCENDING) " ↑" else " ↓"
                    } else {
                        ""
                    }
                    DropdownMenuItem(
                        text = { LText("${sortLabel(mode)}$suffix") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null) },
                        onClick = { expanded = false; onSort(mode) },
                    )
                }
                DropdownMenuItem(
                    text = { LText("Atnaujinti") },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    onClick = { expanded = false; onRefresh() },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { LText("Iš serverio → $localDirectory") },
                enabled = false,
                onClick = {},
            )
            DropdownMenuItem(
                text = { LText("Atjungti") },
                leadingIcon = { Icon(Icons.Rounded.CloudOff, contentDescription = null) },
                onClick = { expanded = false; onDisconnect() },
            )
        }
    }
}

@Composable
private fun RemoteSortMenu(
    state: com.affilemanager.app.ui.NetworkUiState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSort: (SortMode) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMode.entries.forEach { mode ->
            val suffix = if (state.sortMode == mode) {
                if (state.sortDirection == SortDirection.ASCENDING) " ↑" else " ↓"
            } else {
                ""
            }
            DropdownMenuItem(
                text = { LText("${sortLabel(mode)}$suffix") },
                onClick = { onDismiss(); onSort(mode) },
            )
        }
    }
}

@Composable
private fun RemoteBreadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val paths = remember(path) {
        buildList {
            add("/")
            var current = ""
            path.split('/').filter(String::isNotBlank).forEach { part ->
                current += "/$part"
                add(current)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        paths.forEach { itemPath ->
            AssistChip(
                onClick = { onNavigate(itemPath) },
                label = { Text(itemPath.substringAfterLast('/').ifBlank { "/" }, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun RemoteEntryList(
    entries: List<RemoteEntry>,
    selectedPaths: Set<String>,
    selectionActive: Boolean,
    openingPath: String?,
    onOpen: (RemoteEntry) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onToggleSelection: (String) -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(entries, key = RemoteEntry::path) { entry ->
            RemoteEntryRow(
                entry = entry,
                metadata = remoteEntryMeta(entry, dateFormat),
                selected = entry.path in selectedPaths,
                selectionActive = selectionActive,
                opening = entry.path == openingPath,
                onOpen = { onOpen(entry) },
                onDownload = { onDownload(entry) },
                onToggleSelection = { onToggleSelection(entry.path) },
            )
        }
    }
}

@Composable
private fun RemoteEntryGrid(
    entries: List<RemoteEntry>,
    selectedPaths: Set<String>,
    selectionActive: Boolean,
    openingPath: String?,
    onOpen: (RemoteEntry) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onToggleSelection: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(124.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp, 6.dp, 8.dp, 88.dp),
    ) {
        items(entries, key = RemoteEntry::path) { entry ->
            RemoteEntryTile(
                entry = entry,
                selected = entry.path in selectedPaths,
                selectionActive = selectionActive,
                opening = entry.path == openingPath,
                onOpen = { onOpen(entry) },
                onDownload = { onDownload(entry) },
                onToggleSelection = { onToggleSelection(entry.path) },
            )
        }
    }
}

@Composable
private fun RemoteEntryRow(
    entry: RemoteEntry,
    metadata: String,
    selected: Boolean,
    selectionActive: Boolean,
    opening: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_entry_${entry.path}")
            .then(
                if (selected) {
                    Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, selectionShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, selectionShape)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                },
            )
            .combinedClickable(
                enabled = !opening,
                onClick = { if (selectionActive) onToggleSelection() else onOpen() },
                onLongClick = onToggleSelection,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (opening) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp).testTag("remote_opening_${entry.path}"),
                strokeWidth = 3.dp,
            )
        } else {
            RemoteFileVisual(entry = entry, modifier = Modifier.size(42.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (entry.directory) FontWeight.SemiBold else FontWeight.Normal)
            LText(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RemoteEntryActionsButton(entry, onOpen, onDownload, onToggleSelection)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun RemoteEntryTile(
    entry: RemoteEntry,
    selected: Boolean,
    selectionActive: Boolean,
    opening: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val selectionShape = RoundedCornerShape(12.dp)
    Card(
        modifier = Modifier
            .padding(5.dp)
            .testTag("remote_entry_${entry.path}")
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, selectionShape) else Modifier)
            .combinedClickable(
                enabled = !opening,
                onClick = { if (selectionActive) onToggleSelection() else onOpen() },
                onLongClick = onToggleSelection,
            ),
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
                if (opening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(52.dp).testTag("remote_opening_${entry.path}"),
                        strokeWidth = 4.dp,
                    )
                } else {
                    RemoteFileVisual(entry = entry, modifier = Modifier.fillMaxWidth().height(76.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (!entry.directory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                RemoteEntryActionsButton(entry, onOpen, onDownload, onToggleSelection)
            }
        }
    }
}

@Composable
private fun RemoteEntryActionsButton(
    entry: RemoteEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    var expanded by remember(entry.path) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Failo veiksmai: ${entry.name}"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { LText(if (entry.directory) "Atidaryti aplanką" else "Peržiūrėti čia") },
                leadingIcon = { Icon(if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Visibility, contentDescription = null) },
                onClick = { expanded = false; onOpen() },
            )
            if (!entry.directory) {
                DropdownMenuItem(
                    text = { LText("Kopijuoti į telefoną") },
                    leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                    onClick = { expanded = false; onDownload() },
                )
            }
            DropdownMenuItem(
                text = { LText("Pasirinkti") },
                onClick = { expanded = false; onToggleSelection() },
            )
        }
    }
}

@Composable
private fun RemoteEmptyPanel() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        LText("Aplankas tuščias", style = MaterialTheme.typography.titleMedium)
        LText("Čia dar nėra failų", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun remoteEntryMeta(entry: RemoteEntry, dateFormat: DateFormat): String {
    val date = entry.modifiedAtMillis?.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) }
    return listOfNotNull(if (entry.directory) "Aplankas" else FileSystemRules.humanBytes(entry.sizeBytes), date).joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalUploadDialog(
    initialDirectoryPath: String,
    remotePath: String,
    initialEntries: List<FileEntry>,
    initiallySelected: Set<String>,
    loadDirectory: suspend (String) -> Result<List<FileEntry>>,
    onDismiss: () -> Unit,
    onCopy: (List<String>) -> Unit,
) {
    var navigation by remember(initialDirectoryPath, remotePath) {
        mutableStateOf(LocalUploadNavigationState(initialDirectoryPath))
    }
    val currentPath = navigation.currentPath
    var entries by remember(initialDirectoryPath, remotePath) { mutableStateOf(initialEntries) }
    var loading by remember(initialDirectoryPath, remotePath) { mutableStateOf(false) }
    var error by remember(initialDirectoryPath, remotePath) { mutableStateOf<String?>(null) }
    var selected: Set<String> by remember(initialDirectoryPath, remotePath) {
        mutableStateOf(initiallySelected.take(RemoteCopyEngine.MAX_SELECTED_ROOTS).toCollection(linkedSetOf()))
    }

    LaunchedEffect(currentPath) {
        loading = true
        error = null
        if (currentPath != initialDirectoryPath) entries = emptyList()
        loadDirectory(currentPath).fold(
            onSuccess = { loaded -> entries = loaded },
            onFailure = { failure ->
                entries = emptyList()
                error = failure.message ?: "Katalogo atverti nepavyko"
            },
        )
        loading = false
    }

    fun toggle(path: String) {
        selected = if (path in selected) {
            selected - path
        } else if (selected.size < RemoteCopyEngine.MAX_SELECTED_ROOTS) {
            selected + path
        } else {
            selected
        }
    }

    fun navigateTo(path: String) {
        navigation = navigation.navigateTo(path)
    }

    fun navigateBack() {
        navigation = navigation.navigateBack()
    }

    val parentPath = remember(currentPath) { File(currentPath).parentFile?.absolutePath }
    val visiblePaths = remember(entries) {
        entries.take(RemoteCopyEngine.MAX_SELECTED_ROOTS).map(FileEntry::absolutePath)
    }
    val allSelected = visiblePaths.isNotEmpty() && visiblePaths.all(selected::contains)

    AlertDialog(
        onDismissRequest = { if (navigation.canNavigateBack) navigateBack() else onDismiss() },
        icon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
        title = { LText("Kopijuoti į serverį") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { parentPath?.let(::navigateTo) },
                            enabled = parentPath != null && !loading,
                            modifier = Modifier.testTag("local_upload_up"),
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Aukštyn"))
                        }
                        Text(
                            currentPath,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LText("Iš: $currentPath", style = MaterialTheme.typography.bodySmall)
                    LText("Į: $remotePath", style = MaterialTheme.typography.bodySmall)
                    LText("Galima pasirinkti failus ir ištisus aplankus. Esami tokio pat vardo objektai nebus perrašyti.", style = MaterialTheme.typography.labelSmall)
                }
                if (selected.isNotEmpty()) {
                    item {
                        SelectionActionBar(
                            count = selected.size,
                            allSelected = allSelected,
                            onClose = { selected = emptySet() },
                            onToggleSelectAll = {
                                selected = if (allSelected) {
                                    selected - visiblePaths.toSet()
                                } else {
                                    val available = RemoteCopyEngine.MAX_SELECTED_ROOTS - selected.size
                                    selected + visiblePaths.filterNot(selected::contains).take(available)
                                }
                            },
                            modifier = Modifier.testTag("local_upload_selection_bar"),
                        )
                    }
                }
                if (loading) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (error != null) {
                    item {
                        LText(
                            error.orEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (entries.isEmpty()) {
                    item { LText("Katalogas tuščias") }
                }
                items(entries, key = FileEntry::absolutePath) { entry ->
                    val entrySelected = entry.absolutePath in selected
                    val selectionShape = RoundedCornerShape(8.dp)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("local_upload_entry_${entry.absolutePath}")
                            .then(if (entrySelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, selectionShape) else Modifier)
                            .combinedClickable(
                                onClick = {
                                    if (entry.isDirectory && selected.isEmpty()) {
                                        navigateTo(entry.absolutePath)
                                    } else {
                                        toggle(entry.absolutePath)
                                    }
                                },
                                onLongClick = { toggle(entry.absolutePath) },
                            ),
                        shape = selectionShape,
                        colors = CardDefaults.cardColors(
                            containerColor = if (entrySelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = entrySelected,
                                onCheckedChange = { toggle(entry.absolutePath) },
                            )
                            Icon(
                                if (entry.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (entry.isDirectory) {
                                    LText("Aplankas", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (entry.isDirectory) {
                                IconButton(
                                    onClick = { navigateTo(entry.absolutePath) },
                                    modifier = Modifier.testTag("local_upload_open_${entry.absolutePath}"),
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = uiText("Atidaryti aplanką"))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCopy(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { LText("Kopijuoti (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

internal data class LocalUploadNavigationState(
    val currentPath: String,
    val backStack: List<String> = emptyList(),
) {
    val canNavigateBack: Boolean get() = backStack.isNotEmpty()

    fun navigateTo(path: String): LocalUploadNavigationState = if (path == currentPath) {
        this
    } else {
        copy(currentPath = path, backStack = backStack + currentPath)
    }

    fun navigateBack(): LocalUploadNavigationState = backStack.lastOrNull()?.let { previous ->
        copy(currentPath = previous, backStack = backStack.dropLast(1))
    } ?: this
}

@Composable
private fun RemoteNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { LText("Pavadinimas") }, singleLine = true) },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank() && '/' !in name && '\\' !in name) { LText(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

@Composable
private fun SyncDialog(
    state: com.affilemanager.app.ui.SyncUiState,
    onDismiss: () -> Unit,
    onMode: (SyncMode) -> Unit,
    onPolicy: (SyncConflictPolicy) -> Unit,
    onPreview: () -> Unit,
    onExecute: () -> Unit,
    onSchedule: (Long, Boolean) -> Unit,
) {
    val conflicts = state.preview?.actions?.count { it.type == SyncActionType.CONFLICT } ?: 0
    var intervalHours by remember { mutableStateOf(24L) }
    var unmeteredOnly by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
        title = { LText("Aplankų sinchronizavimas") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    val localRootLabel = state.localRoot.ifBlank { uiText("aktyvus failų langas") }
                    LText("Vietinis: $localRootLabel", style = MaterialTheme.typography.bodySmall)
                    LText("Nuotolinis: ${state.remoteRoot}", style = MaterialTheme.typography.bodySmall)
                    LText("Prieš vykdymą planas apskaičiuojamas dar kartą. Failai tyliai netrinami.", style = MaterialTheme.typography.labelSmall)
                }
                item {
                    LText("Kryptis", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SyncMode.entries.forEach { mode ->
                            AssistChip(onClick = { onMode(mode) }, label = { LText(syncModeLabel(mode)) }, leadingIcon = {
                                Checkbox(checked = state.mode == mode, onCheckedChange = null)
                            })
                        }
                    }
                }
                if (state.mode == SyncMode.TWO_WAY) {
                    item {
                        LText("Konfliktai", fontWeight = FontWeight.SemiBold)
                        Column {
                            SyncConflictPolicy.entries.forEach { policy ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.RadioButton(selected = state.conflictPolicy == policy, onClick = { onPolicy(policy) })
                                    LText(syncPolicyLabel(policy))
                                }
                            }
                        }
                    }
                }
                item {
                    LText("Fono tvarkaraštis", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1L, 6L, 12L, 24L, 168L).forEach { hours ->
                            AssistChip(
                                onClick = { intervalHours = hours },
                                label = { LText(if (hours == 168L) "Kas savaitę" else "Kas $hours val.") },
                                leadingIcon = { Checkbox(checked = intervalHours == hours, onCheckedChange = null) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = unmeteredOnly, onCheckedChange = { unmeteredOnly = it })
                        LText("Tik nematuojamas tinklas (Wi‑Fi / Ethernet)")
                    }
                    OutlinedButton(onClick = { onSchedule(intervalHours, unmeteredOnly) }) { LText("Išsaugoti tvarkaraštį") }
                    LText("Fone vienu paleidimu leidžiama iki 10 000 veiksmų ir 1 GB; konfliktai bei trynimai nevykdomi tyliai.", style = MaterialTheme.typography.labelSmall)
                }
                if (state.running) item { CircularProgressIndicator() }
                state.error?.let {
                    item {
                        NetworkError(
                            RemoteErrorInfo(
                                title = "Sinchronizavimas nepavyko",
                                detail = "Sinchronizavimo plano arba operacijos užbaigti nepavyko.",
                                suggestion = "Patikrinkite abi vietas, ryšį ir bandykite parengti planą dar kartą.",
                                diagnosticCode = "SYNC-FAILED",
                            ),
                        )
                    }
                }
                state.preview?.let { preview ->
                    item {
                        LText(
                            "Planas: ${preview.actions.count { it.type != SyncActionType.SKIP }} veiksmų · ${FileSystemRules.humanBytes(preview.totalTransferBytes)} · konfliktų $conflicts",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(preview.actions.filter { it.type != SyncActionType.SKIP }.take(100)) { action ->
                        val actionLabel = uiText(syncActionLabel(action.type))
                        val reason = uiText(action.reason)
                        Text("$actionLabel: ${action.relativePath}${action.targetRelativePath?.let { " → $it" }.orEmpty()} — $reason", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (state.preview == null) {
                Button(onClick = onPreview, enabled = !state.running) { LText("Peržiūrėti planą") }
            } else {
                Button(onClick = onExecute, enabled = conflicts == 0 && !state.running) { LText("Vykdyti planą") }
            }
        },
        dismissButton = {
            Row {
                if (state.preview != null) TextButton(onClick = onPreview) { LText("Atnaujinti") }
                TextButton(onClick = onDismiss) { LText("Uždaryti") }
            }
        },
    )
}

private fun syncActionLabel(type: SyncActionType): String = when (type) {
    SyncActionType.CREATE_LOCAL_DIRECTORY -> "Kurti vietinį aplanką"
    SyncActionType.CREATE_REMOTE_DIRECTORY -> "Kurti nuotolinį aplanką"
    SyncActionType.UPLOAD -> "Įkelti"
    SyncActionType.DOWNLOAD -> "Atsisiųsti"
    SyncActionType.CONFLICT -> "Konfliktas"
    SyncActionType.SKIP -> "Praleisti"
}

private fun syncModeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.LOCAL_TO_REMOTE -> "Vietinis → serveris"
    SyncMode.REMOTE_TO_LOCAL -> "Serveris → vietinis"
    SyncMode.TWO_WAY -> "Abiem kryptimis"
}

private fun syncPolicyLabel(policy: SyncConflictPolicy): String = when (policy) {
    SyncConflictPolicy.REPORT_ONLY -> "Tik pranešti"
    SyncConflictPolicy.NEWEST_WINS -> "Naujesnis laimi"
    SyncConflictPolicy.LOCAL_WINS -> "Vietinis laimi"
    SyncConflictPolicy.REMOTE_WINS -> "Nuotolinis laimi"
    SyncConflictPolicy.KEEP_BOTH -> "Palikti abi versijas"
}

@Composable
internal fun NetworkProfileDialog(
    existingProfile: NetworkProfile?,
    onDismiss: () -> Unit,
    onSave: (NetworkProfile, CharArray?, CharArray?) -> Unit,
    onAutofillViewReady: (View) -> Unit = {},
) {
    val stateKey = existingProfile?.id ?: "new"
    var protocol by remember(stateKey) { mutableStateOf(existingProfile?.protocol ?: NetworkProtocol.SFTP) }
    var name by remember(stateKey) { mutableStateOf(existingProfile?.name.orEmpty().firstInputLine()) }
    var host by remember(stateKey) {
        mutableStateOf(NetworkProfileRules.sanitizeHostInput(existingProfile?.host.orEmpty().firstInputLine()))
    }
    var port by remember(stateKey) { mutableStateOf((existingProfile?.port ?: defaultPort(protocol)).toString()) }
    var username by remember(stateKey) { mutableStateOf(existingProfile?.username.orEmpty().firstInputLine()) }
    var password by remember(stateKey) { mutableStateOf("") }
    var privateKeyPem by remember(stateKey) { mutableStateOf("") }
    var usePrivateKey by remember(stateKey) { mutableStateOf(false) }
    var basePath by remember(stateKey) { mutableStateOf((existingProfile?.basePath ?: "/").firstInputLine()) }
    var domain by remember(stateKey) { mutableStateOf(existingProfile?.domain.orEmpty().firstInputLine()) }
    var share by remember(stateKey) { mutableStateOf(existingProfile?.smbShare.orEmpty().firstInputLine()) }
    var fingerprint by remember(stateKey) { mutableStateOf(existingProfile?.expectedHostKeySha256.orEmpty().firstInputLine()) }
    var trustFirstUse by remember(stateKey) { mutableStateOf(existingProfile?.allowFirstUseTrust ?: true) }
    val hasNewSecret = password.isNotEmpty() || (protocol == NetworkProtocol.SFTP && usePrivateKey && privateKeyPem.isNotBlank())
    val nameProblem = NetworkProfileRules.nameError(name)
    val hostProblem = NetworkProfileRules.hostError(host)
    val usernameProblem = NetworkProfileRules.usernameError(username)
    val basePathProblem = NetworkProfileRules.basePathError(basePath)
    val portValue = port.toIntOrNull()
    val portProblem = if (portValue == null) "Netinkamas prievadas" else NetworkProfileRules.portError(portValue)
    val profileFieldsValid = nameProblem == null && hostProblem == null && usernameProblem == null &&
        basePathProblem == null && portProblem == null &&
        (protocol != NetworkProtocol.SMB || share.isNotBlank()) &&
        (protocol != NetworkProtocol.SFTP || trustFirstUse || fingerprint.startsWith("SHA256:"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText(if (existingProfile == null) "Nauja jungtis" else "Redaguoti jungtį") },
        text = {
            Box {
                ExcludeDialogFromAutofill(onAutofillViewReady)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NetworkProtocol.entries.forEach { candidate ->
                            AssistChip(
                                onClick = {
                                    val previousDefault = defaultPort(protocol).toString()
                                    protocol = candidate
                                    if (port == previousDefault) port = defaultPort(candidate).toString()
                                },
                                label = { Text(candidate.name) },
                            )
                        }
                    }
                    LText("Pasirinkta: ${protocol.name}", style = MaterialTheme.typography.labelMedium)
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { LText("Jungties pavadinimas") },
                        singleLine = true,
                        isError = nameProblem != null,
                        supportingText = { nameProblem?.let { LText(it) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        modifier = Modifier
                            .testTag("network_host")
                            .onFocusChanged { focus ->
                                if (!focus.isFocused) host = NetworkProfileRules.sanitizeHostInput(host)
                            },
                        label = { LText("Serveris") },
                        singleLine = true,
                        isError = hostProblem != null,
                        supportingText = { hostProblem?.let { LText(it) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { LText("Prievadas") },
                        singleLine = true,
                        isError = portProblem != null,
                        supportingText = { portProblem?.let { LText(it) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { LText("Naudotojas") },
                        singleLine = true,
                        isError = usernameProblem != null,
                        supportingText = { usernameProblem?.let { LText(it) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { LText(if (existingProfile == null) "Slaptažodis" else "Naujas slaptažodis (nebūtinas)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (existingProfile != null) {
                    item {
                        LText(
                            "Palikite slaptažodį ir privataus rakto lauką tuščius, kad išliktų dabartiniai užšifruoti prisijungimo duomenys.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = basePath,
                        onValueChange = { basePath = it },
                        label = { LText("Pradinis kelias") },
                        singleLine = true,
                        isError = basePathProblem != null,
                        supportingText = { basePathProblem?.let { LText(it) } },
                    )
                }
                if (protocol == NetworkProtocol.SMB) {
                    item { OutlinedTextField(value = share, onValueChange = { share = it }, label = { LText("Bendrinimo vardas") }, singleLine = true) }
                    item { OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { LText("Domenas (nebūtinas)") }, singleLine = true) }
                }
                if (protocol == NetworkProtocol.SFTP) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = usePrivateKey, onCheckedChange = { usePrivateKey = it })
                            Column {
                                LText("Naudoti privatų SSH raktą")
                                LText("Raktas bus užšifruotas Android Keystore; slaptažodžio laukas taps rakto slaptafraze.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (usePrivateKey) {
                        item {
                            OutlinedTextField(
                                value = privateKeyPem,
                                onValueChange = { if (it.length <= 1_048_576) privateKeyPem = it },
                                label = { LText(if (existingProfile == null) "Privatus raktas PEM / OpenSSH" else "Naujas privatus raktas PEM / OpenSSH") },
                                minLines = 4,
                                maxLines = 8,
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = fingerprint,
                            onValueChange = { fingerprint = it },
                            label = { LText("SSH rakto SHA256 atspaudas") },
                            singleLine = true,
                            enabled = !trustFirstUse,
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = trustFirstUse, onCheckedChange = { trustFirstUse = it })
                            Column {
                                LText("Patikėti pirmą gautą raktą")
                                LText("Raktas bus įrašytas ir vėliau pasikeitęs bus blokuojamas.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (protocol == NetworkProtocol.WEBDAV) {
                    item { LText("WebDAV jungiamas tik per HTTPS; peradresavimai su prisijungimu nevykdomi.", style = MaterialTheme.typography.bodySmall) }
                }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val profile = NetworkProfileRules.normalize(NetworkProfile(
                        id = existingProfile?.id.orEmpty(),
                        name = name,
                        protocol = protocol,
                        host = host,
                        port = port.toIntOrNull() ?: defaultPort(protocol),
                        username = username,
                        basePath = basePath.ifBlank { "/" },
                        domain = domain,
                        smbShare = share,
                        expectedHostKeySha256 = fingerprint.ifBlank { null },
                        allowFirstUseTrust = protocol == NetworkProtocol.SFTP && trustFirstUse,
                    ))
                    onSave(
                        profile,
                        password.takeIf(String::isNotEmpty)?.toCharArray(),
                        privateKeyPem.takeIf { usePrivateKey && it.isNotBlank() }?.toCharArray(),
                    )
                    password = ""
                    privateKeyPem = ""
                },
                enabled = profileFieldsValid && (existingProfile != null || hasNewSecret),
            ) { LText("Išsaugoti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}

@Composable
private fun ExcludeDialogFromAutofill(onApplied: (View) -> Unit) {
    val dialogRoot = LocalView.current.rootView
    DisposableEffect(dialogRoot) {
        val previous = dialogRoot.importantForAutofill
        dialogRoot.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onApplied(dialogRoot)
        onDispose { dialogRoot.importantForAutofill = previous }
    }
}

@Composable
internal fun NetworkError(error: RemoteErrorInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("network_error"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LText(
                    error.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                LText(error.detail, color = MaterialTheme.colorScheme.onErrorContainer)
                LText(
                    "Ką daryti: ${error.suggestion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                LText(
                    "Diagnostikos kodas: ${error.diagnosticCode}",
                    modifier = Modifier.testTag("network_error_code"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

private fun defaultPort(protocol: NetworkProtocol): Int = when (protocol) {
    NetworkProtocol.SFTP -> 22
    NetworkProtocol.SMB -> 445
    NetworkProtocol.WEBDAV -> 443
    NetworkProtocol.FTP -> 21
    NetworkProtocol.FTPS -> 21
}

private fun String.firstInputLine(): String = lineSequence().firstOrNull().orEmpty().trim()
