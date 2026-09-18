package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.data.SafLocation
import com.affilemanager.app.data.SafLocationRules
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AfAlertDialog
import com.affilemanager.app.ui.theme.AfButton

@Composable
internal fun SafLocationActionButtons(
    location: SafLocation,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val key = location.uri.hashCode().toUInt().toString(16)
    IconButton(onClick = onEdit, modifier = Modifier.testTag("saf_location_edit_$key")) {
        Icon(Icons.Rounded.Edit, contentDescription = uiText("Redaguoti jungtį"))
    }
    IconButton(onClick = onRemove, modifier = Modifier.testTag("saf_location_remove_$key")) {
        Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti vietą"))
    }
}

@Composable
internal fun SafLocationEditDialog(
    location: SafLocation,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var title by remember(location.uri, location.title) { mutableStateOf(location.title) }
    val normalized = remember(title) { runCatching { SafLocationRules.normalizeTitle(title) }.getOrNull() }
    AfModalDialog(
        title = "Redaguoti jungtį",
        icon = Icons.Rounded.Edit,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("saf_location_edit_dialog"),
        actions = {
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            AfButton(onClick = { normalized?.let(onSave) }, enabled = normalized != null) { LText("Išsaugoti") }
        },
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { LText("Pavadinimas") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)
                .testTag("saf_location_title"),
        )
    }
}

@Composable
internal fun SafLocationRemovalDialog(
    location: SafLocation,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    AfAlertDialog(
        onDismissRequest = onDismiss,
        title = { LText("Pašalinti pasirinktą vietą?") },
        text = { LText("Bus atšauktas AF File Manager ilgalaikis leidimas vietai „${location.title}“. Failai nebus trinami.") },
        confirmButton = { AfButton(onClick = onRemove) { LText("Pašalinti") } },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
        modifier = Modifier.testTag("saf_location_remove_dialog"),
    )
}
