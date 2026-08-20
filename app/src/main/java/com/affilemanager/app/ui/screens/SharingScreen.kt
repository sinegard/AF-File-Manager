package com.affilemanager.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.LanTransferProtocol
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

@Composable
fun SharingScreen(viewModel: MainViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val left by viewModel.leftPanel.collectAsStateWithLifecycle()
    val right by viewModel.rightPanel.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val transfer by LanTransferController.state.collectAsStateWithLifecycle()
    val activePath = if (activePanel == PanelId.LEFT) left.path else right.path
    var sharedPath by remember(activePath) { mutableStateOf(activePath) }
    var protocol by remember { mutableStateOf(LanTransferProtocol.WEB) }
    var duration by remember { mutableIntStateOf(15) }
    val running = transfer.status == LanTransferStatus.RUNNING || transfer.status == LanTransferStatus.STARTING

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            LText("Bendrinti su kompiuteriu", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            LText(
                "Laikinai atverkite pasirinktą aplanką tame pačiame privačiame Wi-Fi arba Ethernet tinkle.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                protocolChip("Web", LanTransferProtocol.WEB, protocol, !running) { protocol = it }
                protocolChip("FTP", LanTransferProtocol.FTP, protocol, !running) { protocol = it }
                protocolChip("WebDAV", LanTransferProtocol.WEBDAV, protocol, !running) { protocol = it }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            LText("Bendrinamas aplankas", fontWeight = FontWeight.SemiBold)
                            Text(sharedPath, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = { sharedPath = activePath }, enabled = !running) { LText("Naudoti aktyvų aplanką") }
                    roots.forEach { root ->
                        OutlinedButton(onClick = { sharedPath = root.path }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                            LText(root.title.ifBlank { root.path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LText("Sesijos trukmė · $duration min.", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = duration.toFloat(),
                        onValueChange = { duration = it.toInt().coerceIn(5, 60) },
                        valueRange = 5f..60f,
                        steps = 10,
                        enabled = !running,
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        LText("Laikina ir aiškiai valdoma sesija", fontWeight = FontWeight.SemiBold)
                    }
                    LText(
                        when (protocol) {
                            LanTransferProtocol.WEB -> "Prisijungimui naudojamas vienkartinis 8 skaitmenų kodas. Sesija automatiškai baigsis."
                            LanTransferProtocol.FTP -> "FTP srautas nėra šifruojamas. Naudokite tik patikimame privačiame tinkle; prisijungimas ribojamas laikinu vardu ir kodu."
                            LanTransferProtocol.WEBDAV -> "Ši laikina WebDAV sesija naudoja HTTP Basic prisijungimą be TLS. Naudokite tik patikimame privačiame tinkle."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            when (transfer.status) {
                LanTransferStatus.RUNNING -> RunningShareCard(context, transfer) { LanTransferController.stop(context) }
                LanTransferStatus.STARTING -> Card(modifier = Modifier.fillMaxWidth()) {
                    LText("Paleidžiama…", modifier = Modifier.padding(18.dp))
                }
                LanTransferStatus.STOPPED, LanTransferStatus.ERROR -> {
                    transfer.message?.let {
                        LText(it, color = if (transfer.status == LanTransferStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { LanTransferController.start(context, sharedPath, duration, protocol) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Computer, contentDescription = null)
                        LText("Paleisti bendrinimą", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun protocolChip(
    label: String,
    value: LanTransferProtocol,
    selected: LanTransferProtocol,
    enabled: Boolean,
    onSelected: (LanTransferProtocol) -> Unit,
) {
    FilterChip(selected = selected == value, onClick = { onSelected(value) }, enabled = enabled, label = { Text(label) })
}

@Composable
private fun RunningShareCard(context: Context, state: com.affilemanager.app.transfer.LanTransferState, onStop: () -> Unit) {
    val copiedMessage = uiText("Prisijungimo duomenys nukopijuoti")
    val usernameLabel = uiText("Naudotojas")
    val codeLabel = uiText("Kodas")
    val details = buildString {
        append(state.url.orEmpty())
        state.username?.let { append('\n').append(usernameLabel).append(": ").append(it) }
        state.code?.let { append('\n').append(codeLabel).append(": ").append(it) }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LText("Bendrinimas veikia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(state.url.orEmpty(), style = MaterialTheme.typography.titleSmall)
            state.username?.let { LText("Naudotojas: $it") }
            state.code?.let { LText("Kodas: $it", fontWeight = FontWeight.Bold) }
            Text(state.rootPath.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AF File Manager", details))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    LText("Kopijuoti duomenis", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = onStop) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    LText("Sustabdyti", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
