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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.TrashBrowserUiState
import com.affilemanager.app.ui.components.LocalFileVisual
import java.text.DateFormat
import java.util.Date

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

    BackHandler(enabled = preview == null) { viewModel.navigateTrashBack() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("trash-browser-dialog"),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.navigateTrashBack() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = uiText(if (state.itemId == null) "Uždaryti šiukšliadėžę" else "Aukštyn"),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        state.rootName?.let { rootName ->
                            Text(rootName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } ?: LText("Šiukšliadėžė", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LText(
                            text = trashLocationLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = viewModel::toggleTrashThumbnails) {
                        Icon(
                            if (state.showThumbnails) Icons.AutoMirrored.Rounded.InsertDriveFile else Icons.Rounded.PhotoLibrary,
                            contentDescription = uiText(if (state.showThumbnails) "Rodyti piktogramas" else "Rodyti miniatiūras"),
                        )
                    }
                    IconButton(onClick = viewModel::refreshTrashBrowser, enabled = !state.loading && !state.emptying) {
                        Icon(Icons.Rounded.Refresh, contentDescription = uiText("Atnaujinti šiukšliadėžę"))
                    }
                    IconButton(onClick = { confirmEmpty = true }, enabled = itemCount > 0 && !state.emptying) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = uiText("Išvalyti visą šiukšliadėžę"))
                    }
                }
                HorizontalDivider()
                if (state.loading || state.emptying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.error != null -> TrashEmptyState("Katalogo atidaryti nepavyko", state.error)
                        state.loading && state.entries.isEmpty() -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        state.entries.isEmpty() -> TrashEmptyState(
                            if (state.itemId == null) "Šiukšliadėžė tuščia" else "Katalogas tuščias",
                            if (state.itemId == null) "Ištrinti elementai bus rodomi čia." else "Šiame kataloge nėra failų.",
                        )
                        else -> LazyColumn(
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            items(state.entries, key = { "${it.itemId}|${it.relativePath}|${it.storedPath}" }) { entry ->
                                TrashBrowserRow(
                                    entry = entry,
                                    showThumbnails = state.showThumbnails,
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
}

@Composable
private fun TrashBrowserRow(
    entry: TrashBrowserEntry,
    showThumbnails: Boolean,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val fileEntry = remember(entry) { entry.toFileEntry() }
    val dateFormat = rememberLocalizedDateTimeFormat()
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalFileVisual(
                entry = fileEntry,
                targetWidth = 52.dp,
                targetHeight = 52.dp,
                showThumbnails = showThumbnails,
                modifier = Modifier.size(52.dp),
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

private fun trashLocationLabel(state: TrashBrowserUiState): String = buildString {
    append("Šiukšliadėžė")
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
