package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
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

@Composable
internal fun NearbyReceiveSettings(viewModel: MainViewModel, enabled: Boolean) {
    val preferences by viewModel.shareScreenPreferences.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<EntryKind?>(null) }
    var previewFolders by remember { mutableStateOf(false) }
    var creatingFolders by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LText("Vienu metu gaunami failai: ${preferences.nearbyConcurrentUploads}")
    Slider(value = preferences.nearbyConcurrentUploads.toFloat(), valueRange = 1f..5f, steps = 3, enabled = enabled,
        onValueChange = { next -> viewModel.updateShareScreenPreferences { it.copy(nearbyConcurrentUploads = next.roundToInt()) } },
        modifier = Modifier.fillMaxWidth().testTag("nearby_receive_concurrency"))
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
            com.affilemanager.app.transfer.NearbyReceiveFolderRules.destinations(preferences.nearbyReceivePath).forEach { (kind, file) ->
                LText(when (kind) {
                    EntryKind.IMAGE -> "Nuotraukos"; EntryKind.VIDEO -> "Vaizdo įrašai"; EntryKind.AUDIO -> "Muzika"
                    EntryKind.DOCUMENT -> "Dokumentai"; EntryKind.ARCHIVE -> "Archyvai"; else -> "APK"
                })
                androidx.compose.material3.Text(file.path, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
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
            loadDirectory = viewModel::listLocalDirectoryForUpload, onDismiss = { choosing = null },
            title = "Pasirinkti gavimo aplanką",
            onUseDefault = { viewModel.updateShareScreenPreferences { it.copy(nearbyTypePaths = it.nearbyTypePaths - kind) }; choosing = null },
            onSelect = { path -> viewModel.updateShareScreenPreferences { it.copy(nearbyTypePaths = it.nearbyTypePaths + (kind to path)) }; choosing = null })
    }
}
