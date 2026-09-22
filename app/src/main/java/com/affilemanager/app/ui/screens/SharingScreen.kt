package com.affilemanager.app.ui.screens
import com.affilemanager.app.ui.components.AfActionRow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import com.affilemanager.app.ui.theme.AfButton as Button
import com.affilemanager.app.ui.theme.AfCard as Card
import androidx.compose.material3.CircularProgressIndicator
import com.affilemanager.app.ui.theme.AfFilterChip as FilterChip
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
import com.affilemanager.app.transfer.LanSessionDuration
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.transfer.QuickTunnelController
import com.affilemanager.app.transfer.QuickTunnelState
import com.affilemanager.app.transfer.QuickTunnelStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.AfPullToRefresh
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import java.io.File
import kotlin.math.roundToInt

@Composable
fun SharingScreen(viewModel: MainViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val left by viewModel.leftPanel.collectAsStateWithLifecycle()
    val right by viewModel.rightPanel.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val transfer by LanTransferController.state.collectAsStateWithLifecycle()
    val quickTunnel by QuickTunnelController.state.collectAsStateWithLifecycle()
    val nearbyPeer by com.affilemanager.app.transfer.NearbyTransferController.connection.state.collectAsStateWithLifecycle()
    val incomingShare by viewModel.incomingShare.collectAsStateWithLifecycle()
    val preferences by viewModel.shareScreenPreferences.collectAsStateWithLifecycle()
    val activePath = if (activePanel == PanelId.LEFT) left.path else right.path
    val protocol = preferences.protocol
    val sharedPath = preferences.pathFor(protocol)
    val duration = preferences.durationMinutes
    val portText = preferences.portText
    val username = preferences.username
    val readOnly = preferences.readOnly
    val anonymous = preferences.anonymous
    var pickerStartPath by remember { mutableStateOf<String?>(null) }
    var pickerProtocol by remember { mutableStateOf<LanTransferProtocol?>(protocol) }
    var password by remember { mutableStateOf("") }
    var showQuickTunnelDialog by remember { mutableStateOf(false) }
    var pendingQuickTunnelStart by remember { mutableStateOf(false) }
    val running = transfer.status == LanTransferStatus.RUNNING || transfer.status == LanTransferStatus.STARTING
    val optionsResult = runCatching {
        LanTransferOptions(
            port = if (portText.isBlank()) 0 else portText.trim().toIntOrNull() ?: -1,
            username = username,
            password = password,
            readOnly = readOnly,
            anonymous = anonymous,
        ).validated(protocol)
    }

    LaunchedEffect(pendingQuickTunnelStart, transfer.status, transfer.url) {
        if (pendingQuickTunnelStart && transfer.status == LanTransferStatus.RUNNING &&
            transfer.protocol == LanTransferProtocol.WEB && !transfer.groupMode
        ) {
            transfer.url?.let { url -> QuickTunnelController.start(context, url, transfer.expiresAtMillis) }
            pendingQuickTunnelStart = false
        } else if (pendingQuickTunnelStart && transfer.status == LanTransferStatus.ERROR) {
            pendingQuickTunnelStart = false
        }
    }
    LaunchedEffect(transfer.status) {
        if (transfer.status !in setOf(LanTransferStatus.RUNNING, LanTransferStatus.STARTING) &&
            quickTunnel.status in setOf(QuickTunnelStatus.STARTING, QuickTunnelStatus.RUNNING)
        ) {
            QuickTunnelController.stop(context)
        }
    }

    pickerStartPath?.let { initialPath ->
        SharedFolderPickerDialog(
            initialPath = initialPath,
            loadDirectory = viewModel::listLocalDirectoryForUpload,
            onDismiss = { pickerStartPath = null },
            onSelect = { selected ->
                viewModel.updateShareScreenPreferences {
                    pickerProtocol?.let { target -> it.withPathFor(target, selected) }
                        ?: it.copy(nearbyReceivePath = selected)
                }
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
                receiveDirectory = preferences.nearbyReceivePath,
                lanState = transfer,
                incomingShare = incomingShare,
                onIncomingShareConsumed = viewModel::consumeIncomingShare,
                onChooseReceiveDirectory = { pickerProtocol = null; pickerStartPath = preferences.nearbyReceivePath },
                receiverName = preferences.receiverName,
                onReceiverNameChange = { receiverName ->
                    viewModel.updateShareScreenPreferences { it.copy(receiverName = receiverName) }
                },
                durationMinutes = duration,
                onDurationMinutesChange = { minutes ->
                    viewModel.updateShareScreenPreferences { it.copy(durationMinutes = minutes) }
                },
            )
        }
        if (nearbyPeer == null) {
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
                            onClick = { viewModel.updateShareScreenPreferences { it.withPathFor(protocol, activePath) } },
                            enabled = !running,
                        ) {
                            LText("Naudoti aktyvų aplanką")
                        }
                        OutlinedButton(onClick = { pickerProtocol = protocol; pickerStartPath = sharedPath }, enabled = !running) {
                            LText("Naršyti aplankus")
                        }
                    }
                    roots.forEach { root ->
                        OutlinedButton(onClick = { pickerProtocol = protocol; pickerStartPath = root.path }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
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
                            enabled = !running && !anonymous,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("share_username"),
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(LanTransferOptions.MAX_PASSWORD_LENGTH) },
                        label = { LText("Laikinas slaptažodis (tuščias = sugeneruotas)") },
                        enabled = !running && !anonymous,
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
                    if (protocol != LanTransferProtocol.WEB) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                LText("Anoniminė prieiga", fontWeight = FontWeight.Medium)
                                LText("Leisti prisijungti be naudotojo vardo ir slaptažodžio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = anonymous,
                                onCheckedChange = { selected ->
                                    viewModel.updateShareScreenPreferences { it.copy(anonymous = selected) }
                                    if (selected) password = ""
                                },
                                enabled = !running,
                                modifier = Modifier.testTag("share_anonymous"),
                            )
                        }
                        if (anonymous) {
                            LText(
                                "Visi šiame privačiame tinkle galės pasiekti bendrinamą aplanką be slaptažodžio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
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
                    LText(
                        if (duration == LanSessionDuration.MANUAL_MINUTES) "Sesijos trukmė · rankinis sustabdymas"
                        else "Sesijos trukmė · $duration min.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Slider(
                        value = if (duration == LanSessionDuration.MANUAL_MINUTES) 0f
                            else (duration / LanSessionDuration.STEP_MINUTES).toFloat(),
                        onValueChange = { value ->
                            viewModel.updateShareScreenPreferences {
                                val index = value.roundToInt().coerceIn(0, LanSessionDuration.MAX_TIMED_MINUTES / LanSessionDuration.STEP_MINUTES)
                                it.copy(durationMinutes = if (index == 0) LanSessionDuration.MANUAL_MINUTES else index * LanSessionDuration.STEP_MINUTES)
                            }
                        },
                        valueRange = 0f..(LanSessionDuration.MAX_TIMED_MINUTES / LanSessionDuration.STEP_MINUTES).toFloat(),
                        steps = (LanSessionDuration.MAX_TIMED_MINUTES / LanSessionDuration.STEP_MINUTES) - 1,
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
                            LanTransferProtocol.WEB -> "Prisijungimui naudojamas vienkartinis 8 skaitmenų kodas. Sesija baigsis pasirinktu laiku arba ją sustabdžius."
                            LanTransferProtocol.FTP -> if (anonymous) "FTP srautas nėra šifruojamas, o anoniminė sesija neturi slaptažodžio. Naudokite tik patikimame privačiame tinkle." else "FTP srautas nėra šifruojamas. Naudokite tik patikimame privačiame tinkle; prisijungimas ribojamas laikinu vardu ir kodu."
                            LanTransferProtocol.WEBDAV -> if (anonymous) "WebDAV srautas nėra šifruojamas, o anoniminė sesija neturi slaptažodžio. Naudokite tik patikimame privačiame tinkle." else "Ši laikina WebDAV sesija naudoja HTTP Basic prisijungimą be TLS. Naudokite tik patikimame privačiame tinkle."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            when (transfer.status) {
                LanTransferStatus.RUNNING -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RunningShareCard(context, transfer) {
                        QuickTunnelController.stop(context)
                        LanTransferController.stop(context)
                    }
                    if (transfer.protocol == LanTransferProtocol.WEB && !transfer.groupMode) {
                        QuickTunnelCard(
                            context = context,
                            state = quickTunnel,
                            onStart = { showQuickTunnelDialog = true },
                            onStop = { QuickTunnelController.stop(context) },
                        )
                    }
                }
                LanTransferStatus.STARTING -> Card(modifier = Modifier.fillMaxWidth()) {
                    LText("Paleidžiama…", modifier = Modifier.padding(18.dp))
                }
                LanTransferStatus.STOPPED, LanTransferStatus.ERROR -> {
                    transfer.message?.let {
                        LText(it, color = if (transfer.status == LanTransferStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AfActionRow(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { optionsResult.getOrNull()?.let { LanTransferController.start(context, sharedPath, duration, protocol, it) } },
                            enabled = optionsResult.isSuccess,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Computer, contentDescription = null)
                            LText("Paleisti bendrinimą", modifier = Modifier.padding(start = 8.dp))
                        }
                        if (protocol == LanTransferProtocol.WEB) {
                            OutlinedButton(
                                onClick = { showQuickTunnelDialog = true },
                                enabled = optionsResult.isSuccess,
                                modifier = Modifier.weight(1f).testTag("share_quick_tunnel"),
                            ) {
                                Icon(Icons.Rounded.Public, contentDescription = null)
                                LText("Quick Tunnel", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        }
        }
    }
    }
    if (showQuickTunnelDialog) {
        AfModalDialog(
            title = "Bendrinti per Quick Tunnel",
            icon = Icons.Rounded.Public,
            onDismissRequest = { showQuickTunnelDialog = false },
            actions = {
                TextButton(onClick = { showQuickTunnelDialog = false }) { LText("Atšaukti") }
                Button(onClick = {
                    showQuickTunnelDialog = false
                    if (transfer.status == LanTransferStatus.RUNNING && transfer.protocol == LanTransferProtocol.WEB) {
                        transfer.url?.let { QuickTunnelController.start(context, it, transfer.expiresAtMillis) }
                    } else {
                        val webOptions = optionsResult.getOrNull()
                        if (webOptions != null) {
                            pendingQuickTunnelStart = true
                            LanTransferController.start(
                                context,
                                sharedPath,
                                duration,
                                LanTransferProtocol.WEB,
                                webOptions,
                            )
                        }
                    }
                }) { LText("Tęsti") }
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LText(
                    "Bus sukurta laikina vieša HTTPS nuoroda per Cloudflare. Failus ir toliau saugo AF vienkartinis prisijungimo kodas.",
                )
                LText(
                    "Quick Tunnel yra pasirenkama išorinė paslauga be veikimo garantijos. Tunelis baigsis kartu su AF Web sesija arba jį sustabdžius.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LText(
                    "Tunelio komponentas įdiegtas pačiame AF File Manager; atskiros programėlės nereikia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
            if (state.anonymous) LText("Anoniminė prieiga", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            if (state.expiresAtMillis == LanSessionDuration.MANUAL_EXPIRY) {
                LText("Veiks iki rankinio sustabdymo", style = MaterialTheme.typography.bodySmall)
            }
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

@Composable
private fun QuickTunnelCard(
    context: Context,
    state: QuickTunnelState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val shareChooserTitle = uiText("Bendrinti")
    Card(modifier = Modifier.fillMaxWidth().testTag("quick_tunnel_card")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                LText("Cloudflare Quick Tunnel", fontWeight = FontWeight.SemiBold)
            }
            when (state.status) {
                QuickTunnelStatus.STOPPED -> {
                    LText("Sukurkite laikiną HTTPS adresą prieigai ne vietiniame tinkle.")
                    OutlinedButton(onClick = onStart) { LText("Bendrinti per Quick Tunnel") }
                }
                QuickTunnelStatus.STARTING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        LText(state.message ?: "Kuriama laikina vieša nuoroda")
                    }
                    TextButton(onClick = onStop) { LText("Sustabdyti") }
                }
                QuickTunnelStatus.RUNNING -> {
                    val url = state.publicUrl.orEmpty()
                    Text(url, style = MaterialTheme.typography.titleSmall)
                    AfActionRow {
                        OutlinedButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("AF Quick Tunnel", url))
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            LText("Kopijuoti", modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, url),
                                    shareChooserTitle,
                                ),
                            )
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = null)
                            LText("Bendrinti", modifier = Modifier.padding(start = 6.dp))
                        }
                        Button(onClick = onStop) { LText("Sustabdyti") }
                    }
                }
                QuickTunnelStatus.ERROR -> {
                    LText(state.message ?: "Tunelio paleisti nepavyko", color = MaterialTheme.colorScheme.error)
                    AfActionRow {
                        OutlinedButton(onClick = onStart) { LText("Bandyti dar kartą") }
                        TextButton(onClick = onStop) { LText("Uždaryti") }
                    }
                }
            }
        }
    }
}
