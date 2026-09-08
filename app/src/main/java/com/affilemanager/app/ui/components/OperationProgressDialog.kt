package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.theme.AfButton as Button

@Composable
internal fun OperationProgressDialog(
    operation: OperationSnapshot,
    onCancel: () -> Unit,
    onHide: () -> Unit,
) {
    val progress = when {
        operation.totalBytes != null && operation.totalBytes > 0 ->
            (operation.completedBytes.toFloat() / operation.totalBytes).coerceIn(0f, 1f)
        operation.totalItems != null && operation.totalItems > 0 ->
            (operation.completedItems.toFloat() / operation.totalItems).coerceIn(0f, 1f)
        else -> null
    }
    AfModalDialog(
        title = operation.title,
        icon = Icons.Rounded.Sync,
        onDismissRequest = onHide,
        modifier = Modifier.testTag("operation_progress_dialog"),
        actions = {
            TextButton(onClick = onHide, modifier = Modifier.testTag("hide_operation")) { LText("Slėpti") }
            Button(onClick = onCancel, modifier = Modifier.testTag("cancel_operation")) { LText("Atšaukti") }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            operation.currentName?.let {
                Text(java.io.File(it).name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("operation_current_name"))
            }
            operation.totalBytes?.let { total ->
                Text("${FileSystemRules.humanBytes(operation.completedBytes)} / ${FileSystemRules.humanBytes(total)}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("operation_bytes"))
            }
            operation.totalItems?.let { total ->
                LText("Atlikta ${operation.completedItems} iš $total", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("operation_items"))
            }
            operation.message?.let { LText(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            LText("Paslėpus operacija tęsis fone ir bus rodoma Android pranešimuose.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
