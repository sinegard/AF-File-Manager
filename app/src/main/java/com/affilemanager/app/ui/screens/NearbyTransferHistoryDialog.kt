package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.transfer.NearbyTransferHistorySession
import com.affilemanager.app.transfer.TransferFileProgress
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat
import com.affilemanager.app.ui.theme.AfAlertDialog as AlertDialog
import com.affilemanager.app.ui.theme.AfButton as Button
import com.affilemanager.app.ui.theme.AfCard as Card
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NearbyTransferHistoryDialog(
    sessions: List<NearbyTransferHistorySession>,
    error: String?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedId }
    if (selected != null) {
        val outgoing = selected.files.filter { it.outgoing }
        val incoming = selected.files.filterNot { it.outgoing }
        val files = (outgoing + incoming).map { file ->
            TransferFileProgress(
                relativePath = file.relativePath,
                sizeBytes = file.sizeBytes,
                transferredBytes = file.transferredBytes,
                status = file.status,
                batchId = file.id,
            )
        }
        NearbyTransferDetails(
            files = files,
            transferredBytes = files.sumOf(TransferFileProgress::transferredBytes),
            totalBytes = selected.totalBytes,
            totalFiles = selected.totalFileCount,
            onPreview = {},
            onDismiss = { selectedId = null },
            message = if (selected.filesTruncated) {
                "Istorijoje išsaugota ${selected.files.size} iš ${selected.totalFileCount} failų įrašų"
            } else null,
            outgoingCount = outgoing.size,
            localName = "Šis telefonas",
            peerName = selected.peerName,
            sessionStartedAtMillis = selected.startedAtMillis,
            chatMessages = selected.messages,
        )
    } else {
        val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT)
        AfModalDialog(
            title = "Perdavimų istorija",
            subtitle = "Duomenys saugomi tik šios programos privačioje saugykloje.",
            icon = Icons.Rounded.History,
            onDismissRequest = onDismiss,
            expandedContent = true,
            modifier = Modifier.testTag("nearby_history_dialog"),
            actions = {
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = sessions.isNotEmpty(),
                    modifier = Modifier.testTag("nearby_history_clear"),
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                    LText("Išvalyti istoriją", modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = onDismiss) { LText("Uždaryti") }
            },
        ) {
            error?.let {
                LText(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
            }
            if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LText("Perdavimų istorija tuščia")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = NearbyTransferHistorySession::id) { session ->
                        Card(onClick = { selectedId = session.id }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.History, contentDescription = null)
                                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(session.peerName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(dateFormat.format(Date(session.startedAtMillis)), style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "${session.totalFileCount} · ${FileSystemRules.humanBytes(session.totalBytes)} · ${session.messages.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { LText("Išvalyti perdavimų istoriją?") },
            text = { LText("Bus pašalinti tik vietiniai istorijos įrašai. Išsiųsti ir gauti failai nebus trinami.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        selectedId = null
                        onClear()
                    },
                    modifier = Modifier.testTag("nearby_history_confirm_clear"),
                ) { LText("Išvalyti") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { LText("Atšaukti") } },
        )
    }
}
