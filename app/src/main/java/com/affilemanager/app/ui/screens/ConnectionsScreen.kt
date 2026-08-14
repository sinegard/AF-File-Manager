package com.affilemanager.app.ui.screens

import android.view.View
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProfileRules
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemoteErrorInfo
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.sync.SyncActionType
import com.affilemanager.app.sync.SyncConflictPolicy
import com.affilemanager.app.sync.SyncMode

@Composable
fun ConnectionsScreen(viewModel: MainViewModel, contentPadding: PaddingValues) {
    val state by viewModel.networkState.collectAsStateWithLifecycle()
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val left by viewModel.leftPanel.collectAsStateWithLifecycle()
    val right by viewModel.rightPanel.collectAsStateWithLifecycle()
    val sync by viewModel.syncState.collectAsStateWithLifecycle()
    val activeLocalState = if (activePanel == com.affilemanager.app.ui.PanelId.LEFT) left else right
    val activeSelection = activeLocalState.selectedPaths

    var showAdd by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<NetworkProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<NetworkProfile?>(null) }
    var createRemoteFolder by remember { mutableStateOf(false) }
    var renameRemote by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteRemote by remember { mutableStateOf<RemoteEntry?>(null) }
    var showSync by remember { mutableStateOf(false) }
    var showUploadPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Tinklas ir nuotolinės vietos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("SMB 2/3 · SFTP · WebDAV · FTP/FTPS", style = MaterialTheme.typography.bodySmall)
            }
            if (state.connectedProfile != null) {
                OutlinedButton(onClick = viewModel::disconnectNetwork) {
                    Icon(Icons.Rounded.CloudOff, contentDescription = null)
                    Text("Atjungti", modifier = Modifier.padding(start = 6.dp))
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
                                    Text("Dar nėra jungčių", style = MaterialTheme.typography.titleMedium)
                                    Text("Pridėkite NAS, serverį arba WebDAV vietą.")
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
                    Icon(Icons.Rounded.Add, contentDescription = "Pridėti jungtį")
                }
            }
        } else {
            RemoteBrowser(
                state = state,
                localDirectory = activeLocalState.path,
                onUp = {
                    val parent = RemotePath.normalize("${state.path}/..")
                    viewModel.refreshRemote(parent)
                },
                onRefresh = { viewModel.refreshRemote() },
                onOpen = { entry -> if (entry.directory) viewModel.refreshRemote(entry.path) else viewModel.remoteDownload(entry) },
                onDownload = viewModel::remoteDownload,
                onChooseUpload = { showUploadPicker = true },
                onCreateFolder = { createRemoteFolder = true },
                onRename = { renameRemote = it },
                onDelete = { deleteRemote = it },
                onSync = { showSync = true },
            )
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
            title = { Text("Pašalinti jungtį?") },
            text = { Text("Bus pašalintas „${profile.name}“ profilis ir jo užšifruotas prisijungimo įrašas.") },
            confirmButton = {
                Button(onClick = { viewModel.removeNetworkProfile(profile.id); deleteProfile = null }) { Text("Pašalinti") }
            },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("Atšaukti") } },
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
    deleteRemote?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteRemote = null },
            title = { Text(if (entry.directory) "Ištrinti aplanką ir jo turinį?" else "Ištrinti failą?") },
            text = { Text("„${entry.name}“ bus ištrintas nuotoliniame serveryje be vietinės šiukšlinės.") },
            confirmButton = { Button(onClick = { viewModel.remoteDelete(entry); deleteRemote = null }) { Text("Ištrinti") } },
            dismissButton = { TextButton(onClick = { deleteRemote = null }) { Text("Atšaukti") } },
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
            directoryPath = activeLocalState.path,
            remotePath = state.path,
            entries = activeLocalState.entries,
            initiallySelected = activeSelection,
            onDismiss = { showUploadPicker = false },
            onCopy = { paths ->
                viewModel.remoteUpload(paths)
                showUploadPicker = false
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
                Text(
                    if (profileProblem == null) "${profile.protocol} · ${normalized.host}:${profile.port}"
                    else "${profile.protocol} · Neteisingi jungties duomenys",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (profileProblem != null) {
                    Text(
                        "$profileProblem. Paspauskite pieštuką.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (profile.protocol == NetworkProtocol.SFTP) {
                    Text(
                        profile.expectedHostKeySha256?.let { "SSH ${it.take(22)}…" } ?: "Pirmasis raktas bus aiškiai patikėtas",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Redaguoti jungtį") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Pašalinti") }
            Button(onClick = onConnect, enabled = !loading) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Jungtis")
            }
        }
    }
}

@Composable
internal fun RemoteBrowser(
    state: com.affilemanager.app.ui.NetworkUiState,
    localDirectory: String,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (RemoteEntry) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onChooseUpload: () -> Unit,
    onCreateFolder: () -> Unit,
    onRename: (RemoteEntry) -> Unit,
    onDelete: (RemoteEntry) -> Unit,
    onSync: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Aukštyn") }
        Column(modifier = Modifier.weight(1f)) {
            Text(state.connectedProfile?.name.orEmpty(), fontWeight = FontWeight.SemiBold)
            Text(state.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCreateFolder) { Icon(Icons.Rounded.Add, contentDescription = "Sukurti aplanką") }
        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Atnaujinti") }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onChooseUpload, enabled = !state.loading, modifier = Modifier.testTag("remote_upload_choose")) {
            Icon(Icons.Rounded.CloudUpload, contentDescription = null)
            Text("Į serverį", modifier = Modifier.padding(start = 6.dp))
        }
        OutlinedButton(onClick = onSync, enabled = !state.loading) {
            Icon(Icons.Rounded.Sync, contentDescription = null)
            Text("Sinchronizuoti", modifier = Modifier.padding(start = 6.dp))
        }
        Text("Iš serverio → $localDirectory", style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
    if (state.loading) {
        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
    }
    state.error?.let { NetworkError(it) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.entries, key = RemoteEntry::path) { entry ->
            var menu by remember(entry.path) { mutableStateOf(false) }
            Card(onClick = { onOpen(entry) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (entry.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!entry.directory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onDownload(entry) }) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = "Kopijuoti į aktyvų vietinį aplanką")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Veiksmai") }
                        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Kopijuoti į telefoną") },
                                leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                onClick = { menu = false; onDownload(entry) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Pervadinti") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = { menu = false; onRename(entry) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Ištrinti") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = { menu = false; onDelete(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalUploadDialog(
    directoryPath: String,
    remotePath: String,
    entries: List<FileEntry>,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onCopy: (List<String>) -> Unit,
) {
    val availablePaths = remember(entries) { entries.mapTo(HashSet(entries.size), FileEntry::absolutePath) }
    var selected by remember(directoryPath, remotePath) {
        mutableStateOf(initiallySelected.filterTo(linkedSetOf()) { it in availablePaths }.take(1_000).toSet())
    }
    fun toggle(path: String) {
        selected = if (path in selected) {
            selected - path
        } else if (selected.size < 1_000) {
            selected + path
        } else {
            selected
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
        title = { Text("Kopijuoti į serverį") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("Iš: $directoryPath", style = MaterialTheme.typography.bodySmall)
                    Text("Į: $remotePath", style = MaterialTheme.typography.bodySmall)
                    Text("Galima pasirinkti failus ir ištisus aplankus. Esami tokio pat vardo objektai nebus perrašyti.", style = MaterialTheme.typography.labelSmall)
                }
                if (entries.isEmpty()) {
                    item { Text("Aktyviame vietiniame aplanke nėra ką kopijuoti.") }
                }
                items(entries, key = FileEntry::absolutePath) { entry ->
                    Card(onClick = { toggle(entry.absolutePath) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = entry.absolutePath in selected,
                                onCheckedChange = { toggle(entry.absolutePath) },
                            )
                            Icon(
                                if (entry.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (entry.isDirectory) "Aplankas" else FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCopy(entries.map(FileEntry::absolutePath).filter(selected::contains)) },
                enabled = selected.isNotEmpty(),
            ) { Text("Kopijuoti (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
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
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Pavadinimas") }, singleLine = true) },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank() && '/' !in name && '\\' !in name) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
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
        title = { Text("Aplankų sinchronizavimas") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Vietinis: ${state.localRoot.ifBlank { "aktyvus failų langas" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Nuotolinis: ${state.remoteRoot}", style = MaterialTheme.typography.bodySmall)
                    Text("Prieš vykdymą planas apskaičiuojamas dar kartą. Failai tyliai netrinami.", style = MaterialTheme.typography.labelSmall)
                }
                item {
                    Text("Kryptis", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SyncMode.entries.forEach { mode ->
                            AssistChip(onClick = { onMode(mode) }, label = { Text(syncModeLabel(mode)) }, leadingIcon = {
                                Checkbox(checked = state.mode == mode, onCheckedChange = null)
                            })
                        }
                    }
                }
                if (state.mode == SyncMode.TWO_WAY) {
                    item {
                        Text("Konfliktai", fontWeight = FontWeight.SemiBold)
                        Column {
                            SyncConflictPolicy.entries.forEach { policy ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.RadioButton(selected = state.conflictPolicy == policy, onClick = { onPolicy(policy) })
                                    Text(syncPolicyLabel(policy))
                                }
                            }
                        }
                    }
                }
                item {
                    Text("Fono tvarkaraštis", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1L, 6L, 12L, 24L, 168L).forEach { hours ->
                            AssistChip(
                                onClick = { intervalHours = hours },
                                label = { Text(if (hours == 168L) "Kas savaitę" else "Kas $hours val.") },
                                leadingIcon = { Checkbox(checked = intervalHours == hours, onCheckedChange = null) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = unmeteredOnly, onCheckedChange = { unmeteredOnly = it })
                        Text("Tik nematuojamas tinklas (Wi‑Fi / Ethernet)")
                    }
                    OutlinedButton(onClick = { onSchedule(intervalHours, unmeteredOnly) }) { Text("Išsaugoti tvarkaraštį") }
                    Text("Fone vienu paleidimu leidžiama iki 10 000 veiksmų ir 1 GB; konfliktai bei trynimai nevykdomi tyliai.", style = MaterialTheme.typography.labelSmall)
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
                        Text(
                            "Planas: ${preview.actions.count { it.type != SyncActionType.SKIP }} veiksmų · ${FileSystemRules.humanBytes(preview.totalTransferBytes)} · konfliktų $conflicts",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(preview.actions.filter { it.type != SyncActionType.SKIP }.take(100)) { action ->
                        Text("${action.type}: ${action.relativePath}${action.targetRelativePath?.let { " → $it" }.orEmpty()} — ${action.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (state.preview == null) {
                Button(onClick = onPreview, enabled = !state.running) { Text("Peržiūrėti planą") }
            } else {
                Button(onClick = onExecute, enabled = conflicts == 0 && !state.running) { Text("Vykdyti planą") }
            }
        },
        dismissButton = {
            Row {
                if (state.preview != null) TextButton(onClick = onPreview) { Text("Atnaujinti") }
                TextButton(onClick = onDismiss) { Text("Uždaryti") }
            }
        },
    )
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
        title = { Text(if (existingProfile == null) "Nauja jungtis" else "Redaguoti jungtį") },
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
                    Text("Pasirinkta: ${protocol.name}", style = MaterialTheme.typography.labelMedium)
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Jungties pavadinimas") },
                        singleLine = true,
                        isError = nameProblem != null,
                        supportingText = { nameProblem?.let { Text(it) } },
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
                        label = { Text("Serveris") },
                        singleLine = true,
                        isError = hostProblem != null,
                        supportingText = { hostProblem?.let { Text(it) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Prievadas") },
                        singleLine = true,
                        isError = portProblem != null,
                        supportingText = { portProblem?.let { Text(it) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Naudotojas") },
                        singleLine = true,
                        isError = usernameProblem != null,
                        supportingText = { usernameProblem?.let { Text(it) } },
                    )
                }
                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (existingProfile == null) "Slaptažodis" else "Naujas slaptažodis (nebūtinas)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (existingProfile != null) {
                    item {
                        Text(
                            "Palikite slaptažodį ir privataus rakto lauką tuščius, kad išliktų dabartiniai užšifruoti prisijungimo duomenys.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = basePath,
                        onValueChange = { basePath = it },
                        label = { Text("Pradinis kelias") },
                        singleLine = true,
                        isError = basePathProblem != null,
                        supportingText = { basePathProblem?.let { Text(it) } },
                    )
                }
                if (protocol == NetworkProtocol.SMB) {
                    item { OutlinedTextField(value = share, onValueChange = { share = it }, label = { Text("Bendrinimo vardas") }, singleLine = true) }
                    item { OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Domenas (nebūtinas)") }, singleLine = true) }
                }
                if (protocol == NetworkProtocol.SFTP) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = usePrivateKey, onCheckedChange = { usePrivateKey = it })
                            Column {
                                Text("Naudoti privatų SSH raktą")
                                Text("Raktas bus užšifruotas Android Keystore; slaptažodžio laukas taps rakto slaptafraze.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (usePrivateKey) {
                        item {
                            OutlinedTextField(
                                value = privateKeyPem,
                                onValueChange = { if (it.length <= 1_048_576) privateKeyPem = it },
                                label = { Text(if (existingProfile == null) "Privatus raktas PEM / OpenSSH" else "Naujas privatus raktas PEM / OpenSSH") },
                                minLines = 4,
                                maxLines = 8,
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = fingerprint,
                            onValueChange = { fingerprint = it },
                            label = { Text("SSH rakto SHA256 atspaudas") },
                            singleLine = true,
                            enabled = !trustFirstUse,
                        )
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = trustFirstUse, onCheckedChange = { trustFirstUse = it })
                            Column {
                                Text("Patikėti pirmą gautą raktą")
                                Text("Raktas bus įrašytas ir vėliau pasikeitęs bus blokuojamas.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (protocol == NetworkProtocol.WEBDAV) {
                    item { Text("WebDAV jungiamas tik per HTTPS; peradresavimai su prisijungimu nevykdomi.", style = MaterialTheme.typography.bodySmall) }
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
            ) { Text("Išsaugoti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
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
                Text(
                    error.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(error.detail, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Ką daryti: ${error.suggestion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
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
