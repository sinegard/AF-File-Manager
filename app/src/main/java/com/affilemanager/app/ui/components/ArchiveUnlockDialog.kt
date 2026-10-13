package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.affilemanager.app.ui.theme.AfAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.affilemanager.app.ui.ArchiveUnlockUiState
import com.affilemanager.app.ui.localization.LText

@Composable
internal fun ArchiveUnlockDialog(state: ArchiveUnlockUiState, onUnlock: (CharArray) -> Unit, onCancel: () -> Unit) {
    var password by remember(state.target) { mutableStateOf("") }
    val cancel = { password = ""; onCancel() }
    AlertDialog(
        modifier = Modifier.testTag("archive_unlock_dialog"), onDismissRequest = cancel,
        title = { LText("Archyvo slaptažodis") },
        text = {
            Column {
                OutlinedTextField(value = password, onValueChange = { if (it.length <= 1024) password = it },
                    label = { LText("Slaptažodis") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("archive_unlock_password"))
                if (state.busy) LText("Ruošiama…")
                state.error?.let { LText(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { val secret = password.toCharArray(); password = ""; onUnlock(secret) },
            enabled = password.isNotEmpty() && !state.busy, modifier = Modifier.testTag("archive_unlock_confirm")) { LText("Atidaryti") } },
        dismissButton = { TextButton(onClick = cancel) { LText("Atšaukti") } },
    )
}
