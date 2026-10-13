package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
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

@Composable
internal fun NearbyReceiveSettings(viewModel: MainViewModel, enabled: Boolean) {
    val preferences by viewModel.shareScreenPreferences.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<EntryKind?>(null) }
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
    choosing?.let { kind ->
        SharedFolderPickerDialog(initialPath = preferences.nearbyTypePaths[kind] ?: preferences.nearbyReceivePath,
            loadDirectory = viewModel::listLocalDirectoryForUpload, onDismiss = { choosing = null },
            title = "Pasirinkti gavimo aplanką",
            onUseDefault = { viewModel.updateShareScreenPreferences { it.copy(nearbyTypePaths = it.nearbyTypePaths - kind) }; choosing = null },
            onSelect = { path -> viewModel.updateShareScreenPreferences { it.copy(nearbyTypePaths = it.nearbyTypePaths + (kind to path)) }; choosing = null })
    }
}
