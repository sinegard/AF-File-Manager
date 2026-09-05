package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.transfer.TransferFileProgress
import com.affilemanager.app.transfer.TransferFileStatus
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

@Composable
internal fun NearbyTransferDetails(
    files: List<TransferFileProgress>,
    transferredBytes: Long,
    totalBytes: Long,
    totalFiles: Int,
    onPreview: (FileEntry) -> Unit,
    onDismiss: () -> Unit,
    onCancel: (() -> Unit)? = null,
    message: String? = null,
    cancelLabel: String = "Atšaukti",
) {
    AfModalDialog(
        title = "Perdavimas tarp telefonų", icon = Icons.Rounded.PhoneAndroid,
        onDismissRequest = onDismiss, expandedContent = true,
        modifier = Modifier.testTag("nearby_transfer_details"),
        actions = {
            onCancel?.let { cancel -> TextButton(onClick = cancel) { LText(cancelLabel) } }
            TextButton(onClick = onDismiss) { LText("Uždaryti") }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            LinearProgressIndicator(
                progress = { if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    else if (files.isNotEmpty() && files.all { it.status == TransferFileStatus.COMPLETED }) 1f else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${files.count { it.status == TransferFileStatus.COMPLETED }}/$totalFiles · " +
                "${FileSystemRules.humanBytes(transferredBytes)} / ${FileSystemRules.humanBytes(totalBytes)}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            message?.let { LText(it, modifier = Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall) }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).testTag("nearby_transfer_files")) {
                itemsIndexed(files, key = { index, _ -> index }) { index, file ->
                    TransferFileRow(file, index, onPreview)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TransferFileRow(file: TransferFileProgress, index: Int, onPreview: (FileEntry) -> Unit) {
    // Pure metadata mapping, no filesystem stat or decoding on the UI thread.
    val entry = remember(file.localPath, file.relativePath, file.sizeBytes, file.modifiedAtMillis) {
        val visibleName = file.localPath?.let { java.io.File(it).name } ?: file.name
        FileEntry(file.localPath.orEmpty(), visibleName, FileSystemRules.detectKind(file.name, null),
            file.sizeBytes, file.modifiedAtMillis, false, file.localPath != null, false)
    }
    val visiblePath = file.relativePath.substringBeforeLast('/', "").takeIf(String::isNotEmpty)
        ?.let { "$it/${entry.name}" } ?: entry.name
    val status = when (file.status) {
        TransferFileStatus.WAITING -> "Eilėje"
        TransferFileStatus.TRANSFERRING -> "Vykdoma"
        TransferFileStatus.COMPLETED -> "Baigta"
        TransferFileStatus.FAILED -> "Nepavyko"
        TransferFileStatus.CANCELLED -> "Atšaukta"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("nearby_transfer_file_$index"),
        verticalAlignment = Alignment.CenterVertically) {
        LocalFileVisual(entry, 48.dp, 48.dp, showThumbnails = file.localPath != null, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(visiblePath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            Text("${FileSystemRules.humanBytes(file.transferredBytes)} / ${FileSystemRules.humanBytes(file.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall)
            LText(status, style = MaterialTheme.typography.labelSmall,
                color = if (file.status == TransferFileStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { if (file.sizeBytes > 0) (file.transferredBytes.toFloat() / file.sizeBytes).coerceIn(0f, 1f)
                    else if (file.status == TransferFileStatus.COMPLETED) 1f else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        IconButton(onClick = { onPreview(entry) }, enabled = file.localPath != null,
            modifier = Modifier.testTag("nearby_transfer_preview_$index")) {
            Icon(Icons.Rounded.Visibility, contentDescription = uiText("Peržiūra"))
        }
    }
}
