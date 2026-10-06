package com.affilemanager.app.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.transfer.TransferDiagnostics
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun TransferDiagnosticsDialog(onDismiss: () -> Unit, onExport: ((File) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) { TransferDiagnostics.initialize(context) }
    val enabled by TransferDiagnostics.enabled.collectAsStateWithLifecycle()
    val storageFailed by TransferDiagnostics.storageFailed.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val shareTitle = uiText("Perdavimo diagnostika")
    fun operation(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            failed = runCatching { action() }.isFailure
            busy = false
        }
    }
    AfModalDialog(
        title = "Perdavimo diagnostika", icon = Icons.Rounded.Info,
        onDismissRequest = onDismiss, modifier = Modifier.testTag("transfer_diagnostics_dialog"),
        actions = {
            TextButton(onClick = { operation { TransferDiagnostics.clear(context) } }, enabled = !busy,
                modifier = Modifier.testTag("transfer_diagnostics_clear")) { LText("Išvalyti") }
            TextButton(onClick = { operation {
                val report = TransferDiagnostics.export(context)
                if (onExport != null) onExport(report) else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", report)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(shareTitle, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                }
            } }, enabled = !busy, modifier = Modifier.testTag("transfer_diagnostics_export")) { LText("Eksportuoti") }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("transfer_diagnostics_close")) { LText("Uždaryti") }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LText("Duomenys lieka šiame telefone.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LText(if (enabled) "Įjungtas" else "Išjungta", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { selected -> operation {
                    TransferDiagnostics.setEnabled(context, selected)
                } }, enabled = !busy, modifier = Modifier.testTag("transfer_diagnostics_enabled"))
            }
            if (failed || storageFailed) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LText("Nepavyko", color = MaterialTheme.colorScheme.error)
                    Text(TransferDiagnostics.STORAGE_ERROR_CODE, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
