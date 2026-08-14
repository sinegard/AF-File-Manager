package com.affilemanager.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
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
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.SafBrowserUiState
import com.affilemanager.app.ui.components.SafFileVisual

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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Uždaryti") }
                    IconButton(onClick = viewModel::navigateSafBack, enabled = state.backStack.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atgal")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Android dokumentų sistema", style = MaterialTheme.typography.labelSmall)
                    }
                    if (selectedLocalPath != null) {
                        IconButton(onClick = { viewModel.copyLocalToSaf(selectedLocalPath) }) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = "Kopijuoti pasirinktą vietinį elementą čia")
                        }
                    }
                    IconButton(onClick = viewModel::refreshSafBrowser) { Icon(Icons.Rounded.Refresh, contentDescription = "Atnaujinti") }
                }
                HorizontalDivider()
                state.error?.let { error ->
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(error, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (state.loading && state.entries.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (state.entries.isEmpty() && state.error == null) {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(56.dp))
                            Text("Aplankas tuščias", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 92.dp)) {
                            items(state.entries, key = SafEntry::uri) { entry ->
                                SafEntryRow(
                                    entry = entry,
                                    onOpen = { viewModel.openSafEntry(entry) },
                                    onRename = { rename = entry },
                                    onDelete = { delete = entry },
                                    onDownload = { viewModel.copySafToLocal(entry) },
                                )
                            }
                        }
                    }
                    FloatingActionButton(onClick = { create = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = "Sukurti")
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
    delete?.let { entry ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Ištrinti visam laikui?") },
            text = { Text("„${entry.name}“ bus trinamas per Android dokumentų teikėją ir nepateks į AF File Manager šiukšlinę.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteSafEntry(entry); delete = null }) { Text("Ištrinti") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Atšaukti") } },
        )
    }
}

@Composable
private fun SafEntryRow(
    entry: SafEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SafFileVisual(
                    entry = entry,
                    targetWidth = 42.dp,
                    targetHeight = 42.dp,
                    modifier = Modifier.size(42.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.name, fontWeight = if (entry.directory) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!entry.directory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Veiksmai") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Kopijuoti į aktyvų langą") },
                    leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                    onClick = { menu = false; onDownload() },
                )
                DropdownMenuItem(text = { Text("Pervadinti") }, enabled = entry.canWrite, onClick = { menu = false; onRename() })
                DropdownMenuItem(
                    text = { Text("Ištrinti") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    enabled = entry.canWrite,
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun SafCreateDialog(onDismiss: () -> Unit, onFolder: (String) -> Unit, onFile: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
        title = { Text("Sukurti") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Pavadinimas") }, singleLine = true) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFile(name) }, enabled = name.isNotBlank()) { Text("Failą") }
                Button(onClick = { onFolder(name) }, enabled = name.isNotBlank()) { Text("Aplanką") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
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
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Pavadinimas") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
}
