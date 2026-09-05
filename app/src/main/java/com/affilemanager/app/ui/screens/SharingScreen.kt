package com.affilemanager.app.ui.screens
import com.affilemanager.app.ui.components.AfActionRow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.LanTransferOptions
import com.affilemanager.app.transfer.LanTransferProtocol
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.AfPullToRefresh
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import java.io.File

@Composable
fun SharingScreen(viewModel: MainViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val left by viewModel.leftPanel.collectAsStateWithLifecycle()
    val right by viewModel.rightPanel.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val transfer by LanTransferController.state.collectAsStateWithLifecycle()
    val incomingShare by viewModel.incomingShare.collectAsStateWithLifecycle()
    val preferences by viewModel.shareScreenPreferences.collectAsStateWithLifecycle()
    val activePath = if (activePanel == PanelId.LEFT) left.path else right.path
    val sharedPath = preferences.sharedPath
    val protocol = preferences.protocol
    val duration = preferences.durationMinutes
    val portText = preferences.portText
    val username = preferences.username
    val readOnly = preferences.readOnly
    var pickerStartPath by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    val running = transfer.status == LanTransferStatus.RUNNING || transfer.status == LanTransferStatus.STARTING
    val optionsResult = runCatching {
        LanTransferOptions(
            port = if (portText.isBlank()) 0 else portText.trim().toIntOrNull() ?: -1,
            username = username,
            password = password,
            readOnly = readOnly,
        ).validated(protocol)
    }

    pickerStartPath?.let { initialPath ->
        SharedFolderPickerDialog(
            initialPath = initialPath,
            loadDirectory = viewModel::listLocalDirectoryForUpload,
            onDismiss = { pickerStartPath = null },
            onSelect = { selected ->
                viewModel.updateShareScreenPreferences { it.copy(sharedPath = selected) }
                pickerStartPath = null
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
            LText("Bendrinti su kompiuteriu", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            LText(
                "Laikinai atverkite pasirinktą aplanką tame pačiame privačiame Wi-Fi arba Ethernet tinkle.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("sharing_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        item {
            NearbyPhoneTransferCard(
                viewModel = viewModel,
                receiveDirectory = sharedPath,
                lanState = transfer,
                incomingShare = incomingShare,
                onIncomingShareConsumed = viewModel::consumeIncomingShare,
                onChooseReceiveDirectory = { pickerStartPath = sharedPath },
                receiverName = preferences.receiverName,
                onReceiverNameChange = { receiverName ->
                    viewModel.updateShareScreenPreferences { it.copy(receiverName = receiverName) }
                },
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                protocolChip("Web", LanTransferProtocol.WEB, protocol, !running) { selected ->
                    viewModel.updateShareScreenPreferences { it.copy(protocol = selected) }
                }
                protocolChip("FTP", LanTransferProtocol.FTP, protocol, !running) { selected ->
                    viewModel.updateShareScreenPreferences { it.copy(protocol = selected) }
                }
                protocolChip("WebDAV", LanTransferProtocol.WEBDAV, protocol, !running) { selected ->
                    viewModel.updateShareScreenPreferences { it.copy(protocol = selected) }
                }
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
                    AfActionRow {
                        OutlinedButton(
                            onClick = { viewModel.updateShareScreenPreferences { it.copy(sharedPath = activePath) } },
                            enabled = !running,
                        ) {
                            LText("Naudoti aktyvų aplanką")
                        }
                        OutlinedButton(onClick = { pickerStartPath = sharedPath }, enabled = !running) {
                            LText("Naršyti aplankus")
                        }
                    }
                    roots.forEach { root ->
                        OutlinedButton(onClick = { pickerStartPath = root.path }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                            LText(root.title.ifBlank { root.path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LText("Bendrinimo nustatymai", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value ->
                            val filtered = value.filter(Char::isDigit).take(5)
                            viewModel.updateShareScreenPreferences { it.copy(portText = filtered) }
                        },
                        label = { LText("Prievadas (tuščias = automatinis)") },
                        enabled = !running,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("share_port"),
                    )
                    if (protocol != LanTransferProtocol.WEB) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { value ->
                                viewModel.updateShareScreenPreferences {
                                    it.copy(
                                        username = value.filterNot(Char::isISOControl)
                                            .take(LanTransferOptions.MAX_USERNAME_LENGTH),
                                    )
                                }
                            },
                            label = { LText("Naudotojo vardas (tuščias = af)") },
                            enabled = !running,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("share_username"),
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(LanTransferOptions.MAX_PASSWORD_LENGTH) },
                        label = { LText("Laikinas slaptažodis (tuščias = sugeneruotas)") },
                        enabled = !running,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("share_password"),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            LText("Tik skaityti", fontWeight = FontWeight.Medium)
                            LText("Neleisti įkelti, pervadinti ar trinti", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = readOnly,
                            onCheckedChange = { selected ->
                                viewModel.updateShareScreenPreferences { it.copy(readOnly = selected) }
                            },
                            enabled = !running,
                            modifier = Modifier.testTag("share_read_only"),
                        )
                    }
                    if (protocol == LanTransferProtocol.WEBDAV) {
                        LText("WebDAV naudoja HTTP. HTTPS/TLS šiame leidime dar nepalaikomas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    optionsResult.exceptionOrNull()?.message?.let { LText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LText("Sesijos trukmė · $duration min.", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = duration.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateShareScreenPreferences {
                                it.copy(durationMinutes = value.toInt().coerceIn(5, 60))
                            }
                        },
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
                        onClick = { optionsResult.getOrNull()?.let { LanTransferController.start(context, sharedPath, duration, protocol, it) } },
                        enabled = optionsResult.isSuccess,
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
}

@Composable
private fun SharedFolderPickerDialog(
    initialPath: String,
    loadDirectory: suspend (String) -> Result<List<FileEntry>>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var navigation by remember(initialPath) { mutableStateOf(LocalUploadNavigationState(initialPath)) }
    var directories by remember(initialPath) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember(initialPath) { mutableStateOf(true) }
    var error by remember(initialPath) { mutableStateOf<String?>(null) }
    var refreshToken by remember(initialPath) { mutableStateOf(0) }
    val currentPath = navigation.currentPath

    LaunchedEffect(currentPath, refreshToken) {
        loading = true
        error = null
        loadDirectory(currentPath).fold(
            onSuccess = { entries ->
                directories = entries.asSequence().filter(FileEntry::isDirectory).take(5_000).toList()
            },
            onFailure = { failure ->
                directories = emptyList()
                error = failure.message ?: "Katalogo atverti nepavyko"
            },
        )
        loading = false
    }

    fun dismissOrBack() {
        if (navigation.canNavigateBack) navigation = navigation.navigateBack() else onDismiss()
    }

    BackHandler(onBack = ::dismissOrBack)
    AfModalDialog(
        title = "Pasirinkti bendrinamą aplanką",
        icon = Icons.Rounded.Folder,
        onDismissRequest = ::dismissOrBack,
        expandedContent = true,
        modifier = Modifier.testTag("share_folder_picker_dialog"),
        actions = {
            TextButton(onClick = ::dismissOrBack) { LText(if (navigation.canNavigateBack) "Grįžti" else "Atšaukti") }
            Button(
                onClick = { onSelect(currentPath) },
                enabled = !loading && error == null,
                modifier = Modifier.testTag("share_folder_select"),
            ) {
                LText("Bendrinti šį aplanką")
            }
        },
    ) {
            AfPullToRefresh(
                isRefreshing = loading,
                onRefresh = { refreshToken += 1 },
                modifier = Modifier.fillMaxSize(),
                testTag = "pull_to_refresh_share_folder_picker",
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp).testTag("share_folder_picker"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        val parent = File(currentPath).parentFile?.absolutePath
                        IconButton(
                            onClick = { parent?.let { navigation = navigation.navigateTo(it) } },
                            enabled = parent != null && !loading,
                            modifier = Modifier.testTag("share_folder_up"),
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Aukštyn"))
                        }
                        Text(currentPath, modifier = Modifier.weight(1f).padding(top = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (loading) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (error != null) {
                    item { LText(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp)) }
                } else if (directories.isEmpty()) {
                    item { LText("Šiame aplanke nėra kitų aplankų", modifier = Modifier.padding(10.dp)) }
                }
                items(directories, key = FileEntry::absolutePath) { directory ->
                    OutlinedButton(
                        onClick = { navigation = navigation.navigateTo(directory.absolutePath) },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().testTag("share_folder_${directory.absolutePath}"),
                    ) {
                        Icon(Icons.Rounded.Folder, contentDescription = null)
                        Text(directory.name, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = uiText("Atidaryti aplanką"))
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
            if (state.readOnly) LText("Tik skaityti", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(state.rootPath.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            AfActionRow {
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
