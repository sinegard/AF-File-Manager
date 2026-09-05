package com.affilemanager.app.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

/** One header menu for every preview source, including temporary remote working copies. */
@Composable
internal fun PreviewActionsMenu(
    sourceKey: String,
    editEnabled: Boolean,
    hashRunning: Boolean,
    onOpenWith: () -> Unit,
    onEditWith: (() -> Unit)?,
    onSignPdf: (() -> Unit)?,
    onShare: () -> Unit,
    onCalculateHash: () -> Unit,
) {
    var expanded by remember(sourceKey) { mutableStateOf(false) }
    fun invoke(action: () -> Unit) { expanded = false; action() }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("preview_actions_menu")) {
            Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Veiksmai"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { LText("Atidaryti su kita programa") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                onClick = { invoke(onOpenWith) }, modifier = Modifier.testTag("open-with-action"),
            )
            onSignPdf?.let { action ->
                DropdownMenuItem(
                    text = { LText("Pasirašyti PDF") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    enabled = editEnabled, onClick = { invoke(action) }, modifier = Modifier.testTag("sign-pdf-action"),
                )
            }
            onEditWith?.let { action ->
                DropdownMenuItem(
                    text = { LText("Redaguoti su kita programa") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    enabled = editEnabled, onClick = { invoke(action) }, modifier = Modifier.testTag("edit-with-action"),
                )
            }
            DropdownMenuItem(
                text = { LText("Dalintis") },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = { invoke(onShare) }, modifier = Modifier.testTag("preview_share_action"),
            )
            DropdownMenuItem(
                text = { LText("SHA-256") },
                leadingIcon = {
                    if (hashRunning) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Calculate, contentDescription = null)
                },
                enabled = !hashRunning, onClick = { invoke(onCalculateHash) }, modifier = Modifier.testTag("preview_hash_action"),
            )
        }
    }
}
