package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.affilemanager.app.ui.localization.LText

@Composable
internal fun DirectoryAddressDialog(path: String, onDismiss: () -> Unit, onOpen: (String) -> Unit,
                                    onChooseProvider: (() -> Unit)? = null) {
    var value by remember(path) { mutableStateOf(TextFieldValue(path, TextRange(0, path.length))) }
    val valid = value.text.trim().isNotEmpty() && value.text.length <= 4096 && value.text.none(Char::isISOControl)
    AfModalDialog(title = "Atidaryti aplanką", icon = Icons.Rounded.Folder, onDismissRequest = onDismiss, expandedContent = false,
        actions = {
            if (onChooseProvider != null) TextButton(onClick = onChooseProvider, modifier = Modifier.testTag("directory_choose_provider")) { LText("Pasirinkti aplanką") }
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            TextButton(onClick = { onOpen(value.text.trim()) }, enabled = valid, modifier = Modifier.testTag("directory_address_open")) { LText("Atidaryti") }
        }) {
        OutlinedTextField(value = value, onValueChange = { if (it.text.length <= 4096) value = it },
            singleLine = true, label = { LText("Kelias") },
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("directory_address_input"))
    }
}
