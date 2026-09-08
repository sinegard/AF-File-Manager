package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.affilemanager.app.ui.theme.AfSurface as Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.transfer.TransferFileProgress
import com.affilemanager.app.transfer.TransferFileStatus
import com.affilemanager.app.transfer.NearbyChatMessage
import com.affilemanager.app.transfer.NearbyChatController
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
    onSendMore: (() -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
    outgoingCount: Int? = null,
    localName: String? = null,
    peerName: String? = null,
    chatMessages: List<NearbyChatMessage> = emptyList(),
    chatSending: Boolean = false,
    chatError: String? = null,
    onSendMessage: ((String) -> Unit)? = null,
) {
    var draft by remember { mutableStateOf("") }
    val resolvedLocalName = localName ?: uiText("Šis telefonas")
    val resolvedPeerName = peerName ?: uiText("Kitas telefonas")
    AfModalDialog(
        title = "Perdavimas tarp telefonų", icon = Icons.Rounded.PhoneAndroid,
        onDismissRequest = onDismiss, expandedContent = true,
        modifier = Modifier.testTag("nearby_transfer_details"),
        actions = {
            onSendMore?.let { send -> TextButton(onClick = send, modifier = Modifier.testTag("nearby_send_more")) { LText("Siųsti daugiau") } }
            onCancel?.let { cancel -> TextButton(onClick = cancel) { LText(cancelLabel) } }
            onDisconnect?.let { disconnect -> TextButton(onClick = disconnect) { LText("Atsijungti") } }
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
                if (files.isNotEmpty()) item("outgoing_header") {
                    SenderCapsule(if ((outgoingCount ?: files.size) > 0) resolvedLocalName else resolvedPeerName,
                        outgoing = (outgoingCount ?: files.size) > 0)
                }
                itemsIndexed(files, key = { index, file ->
                    val incoming = outgoingCount?.let { index >= it } == true
                    val localIndex = if (incoming) index - requireNotNull(outgoingCount) else index
                    "$incoming:${file.batchId}:$localIndex:${file.relativePath}"
                }) { index, file ->
                    if (outgoingCount != null && index == outgoingCount && index > 0) {
                        SenderCapsule(resolvedPeerName, outgoing = false)
                    }
                    TransferFileRow(file, index, onPreview, outgoingCount?.let { index >= it })
                    HorizontalDivider()
                }
                if (chatMessages.isNotEmpty()) item("message_divider") {
                    LText("Žinutės", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                }
                itemsIndexed(chatMessages, key = { _, chat -> chat.id }) { index, chat ->
                    val prior = chatMessages.getOrNull(index - 1)
                    if (prior?.senderName != chat.senderName || prior.outgoing != chat.outgoing) {
                        SenderCapsule(chat.senderName, chat.outgoing)
                    }
                    Surface(
                        color = if (chat.outgoing) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .widthIn(max = 360.dp)
                            .testTag("nearby_chat_message_$index"),
                    ) { Text(chat.body, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                }
            }
            chatError?.let { LText(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            if (onSendMessage != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= NearbyChatController.MAX_MESSAGE_CHARACTERS) draft = it },
                    label = { LText("Žinutė") }, maxLines = 3, singleLine = false,
                    modifier = Modifier.weight(1f).testTag("nearby_chat_input"),
                )
                IconButton(
                    onClick = { val text = draft.trim(); if (text.isNotEmpty()) { onSendMessage(text); draft = "" } },
                    enabled = !chatSending && draft.isNotBlank(), modifier = Modifier.testTag("nearby_chat_send"),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = uiText("Siųsti žinutę")) }
            }
        }
    }
}

@Composable
private fun SenderCapsule(name: String, outgoing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
            Text(name, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun TransferFileRow(file: TransferFileProgress, index: Int, onPreview: (FileEntry) -> Unit, incoming: Boolean? = null) {
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
            incoming?.let { LText(if (it) "Gaunami failai" else "Siunčiami failai", style = MaterialTheme.typography.labelSmall) }
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
