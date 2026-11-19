package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.affilemanager.app.ui.theme.AfDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.transfer.LanTransferState
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.ui.theme.AfAlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.AfActionRow
import com.affilemanager.app.ui.localization.LText
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Same location and collapsed routing controls for a single receiver and a group. */
@Composable
internal fun NearbyReceiveLocation(viewModel: MainViewModel, state: LanTransferState) {
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val preferences by viewModel.shareScreenPreferences.collectAsStateWithLifecycle()
    val changing by viewModel.nearbyReceiveFolderChanging.collectAsStateWithLifecycle()
    val error by viewModel.nearbyReceiveFolderError.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.changeNearbyReceiveDirectory(uri.toString(), grant = uri)
    }
    LText("Gauti failai bus įrašyti į:", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { menu = true }, enabled = !changing && state.status != LanTransferStatus.STARTING,
                modifier = Modifier.fillMaxWidth().testTag("nearby_receive_path")) {
                Icon(Icons.Rounded.FolderOpen, null)
                NearbyReceivePathLabel(viewModel, preferences.nearbyReceivePath, Modifier.weight(1f).padding(start = 7.dp))
            }
            AfDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                (listOf(preferences.nearbyReceivePath) + preferences.nearbyPathHistory).distinct().forEach { path ->
                    DropdownMenuItem(text = { NearbyReceivePathLabel(viewModel, path, Modifier.widthIn(max = 300.dp)) }, onClick = {
                        menu = false; viewModel.changeNearbyReceiveDirectory(path)
                    })
                }
                DropdownMenuItem(text = { LText("Keisti išsaugojimo vietą") }, onClick = { menu = false; choosing = true },
                    modifier = Modifier.testTag("nearby_choose_receive_path"))
            }
        }
        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("nearby_receive_type_toggle")) {
            Icon(Icons.Rounded.FilterAlt, uiText(if (expanded) "Slėpti filtrus" else "Rodyti filtrus"))
        }
    }
    error?.let { LText(it, color = MaterialTheme.colorScheme.error) }
    NearbyReceiveSettings(viewModel, !changing && state.status !in setOf(LanTransferStatus.RUNNING, LanTransferStatus.STARTING), expanded)
    if (choosing) SharedFolderPickerDialog(
        initialPath = preferences.nearbyReceivePath, loadDirectory = { path ->
            viewModel.listNearbyReceivePickerDirectory(path).map { entries -> entries.map { entry ->
                if (path.isEmpty() && !entry.absolutePath.startsWith("content://")) entry.copy(name =
                    com.affilemanager.app.ui.localization.UiTranslator.translate(entry.name, language)) else entry
            } }
        },
        parentDirectory = viewModel::nearbyReceivePickerParent,
        title = "Pasirinkti gavimo aplanką", confirmLabel = "Pasirinkti",
        onDismiss = { choosing = false }, onSelect = { choosing = false; viewModel.changeNearbyReceiveDirectory(it) },
        onAndroidPicker = { choosing = false; treePicker.launch(null) },
    )
}

@Composable
private fun NearbyReceivePathLabel(viewModel: MainViewModel, path: String, modifier: Modifier) {
    val fallback = uiText("Pasirinkti gavimo aplanką")
    val label by produceState(if (path.startsWith("content://")) fallback else path, path, fallback) {
        value = if (path.startsWith("content://")) fallback else path
        value = try { viewModel.nearbyReceivePathLabel(path) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { fallback }
    }
    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
internal fun NearbyReceiveConcurrency(viewModel: MainViewModel, enabled: Boolean) {
    val preferences by viewModel.shareScreenPreferences.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag("nearby_receive_concurrency_control")) {
        LText("Vienu metu gaunami failai: ${preferences.nearbyConcurrentUploads}")
        Slider(value = preferences.nearbyConcurrentUploads.toFloat(), valueRange = 1f..5f, steps = 3, enabled = enabled,
            onValueChange = { next -> viewModel.updateShareScreenPreferences { it.copy(nearbyConcurrentUploads = next.roundToInt()) } },
            modifier = Modifier.fillMaxWidth().testTag("nearby_receive_concurrency"))
    }
}

@Composable
internal fun NearbyReceiveSettings(viewModel: MainViewModel, enabled: Boolean, expanded: Boolean? = null) {
    val preferences by viewModel.shareScreenPreferences.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<EntryKind?>(null) }
    var previewFolders by remember { mutableStateOf(false) }
    var creatingFolders by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var androidType by remember { mutableStateOf<EntryKind?>(null) }
    val typeTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val kind = androidType
        androidType = null
        if (uri != null && kind != null) viewModel.changeNearbyReceiveTypeDirectory(kind, uri.toString(), grant = uri)
    }
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    if (expanded == null) TextButton(onClick = { visible = !visible }, modifier = Modifier.testTag("nearby_receive_type_toggle")) {
        Icon(Icons.Rounded.FilterAlt, contentDescription = null)
        LText(if (visible) "Slėpti filtrus" else "Rodyti filtrus", modifier = Modifier.padding(start = 6.dp))
    }
    if (!(expanded ?: visible)) return
    AfActionRow {
        listOf(EntryKind.IMAGE, EntryKind.VIDEO, EntryKind.AUDIO, EntryKind.DOCUMENT, EntryKind.ARCHIVE, EntryKind.APK).forEach { kind ->
            TextButton(onClick = { choosing = kind }, enabled = enabled, modifier = Modifier.testTag("receive_path_${kind.name}")) {
                LText(when(kind) {
                    EntryKind.IMAGE -> "Nuotraukos"; EntryKind.VIDEO -> "Vaizdo įrašai"; EntryKind.AUDIO -> "Muzika"
                    EntryKind.DOCUMENT -> "Dokumentai"; EntryKind.ARCHIVE -> "Archyvai"; else -> "APK"
                })
            }
        }
    }
    OutlinedButton(onClick = { previewFolders = true; folderError = null }, enabled = enabled && !creatingFolders,
        modifier = Modifier.fillMaxWidth().testTag("nearby_create_type_folders")) {
        LText("Sukurti aplankus pagal failų tipus")
    }
    folderError?.let { LText(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
    if (previewFolders) AfAlertDialog(
        onDismissRequest = { if (!creatingFolders) previewFolders = false },
        title = { LText("Sukurti aplankus pagal failų tipus") },
        text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.affilemanager.app.transfer.NearbyReceiveFolderRules.names.forEach { (kind, name) ->
                LText(when (kind) {
                    EntryKind.IMAGE -> "Nuotraukos"; EntryKind.VIDEO -> "Vaizdo įrašai"; EntryKind.AUDIO -> "Muzika"
                    EntryKind.DOCUMENT -> "Dokumentai"; EntryKind.ARCHIVE -> "Archyvai"; else -> "APK"
                })
                androidx.compose.material3.Text(if (preferences.nearbyReceivePath.startsWith("content://")) name
                    else java.io.File(preferences.nearbyReceivePath, name).path,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(enabled = enabled && !creatingFolders, onClick = {
            creatingFolders = true
            scope.launch {
                try {
                    viewModel.createNearbyReceiveTypeFolders().fold(
                        onSuccess = { previewFolders = false },
                        onFailure = { folderError = it.message ?: "Nepavyko sukurti aplanko"; previewFolders = false },
                    )
                } finally { creatingFolders = false }
            }
        }, modifier = Modifier.testTag("nearby_confirm_type_folders")) { LText("Sukurti") } },
        dismissButton = { TextButton(enabled = !creatingFolders, onClick = { previewFolders = false }) { LText("Atšaukti") } },
    )
    choosing?.let { kind ->
        SharedFolderPickerDialog(initialPath = preferences.nearbyTypePaths[kind] ?: preferences.nearbyReceivePath,
            loadDirectory = { path -> viewModel.listNearbyReceivePickerDirectory(path).map { entries -> entries.map { entry ->
                if (path.isEmpty() && !entry.absolutePath.startsWith("content://")) entry.copy(name =
                    com.affilemanager.app.ui.localization.UiTranslator.translate(entry.name, language)) else entry
            } } }, parentDirectory = viewModel::nearbyReceivePickerParent, onDismiss = { choosing = null },
            title = "Pasirinkti gavimo aplanką",
            confirmLabel = "Pasirinkti",
            onUseDefault = { viewModel.updateShareScreenPreferences { it.copy(nearbyTypePaths = it.nearbyTypePaths - kind) }; choosing = null },
            onSelect = { path -> viewModel.changeNearbyReceiveTypeDirectory(kind, path); choosing = null },
            onAndroidPicker = { androidType = kind; choosing = null; typeTreePicker.launch(null) })
    }
}
