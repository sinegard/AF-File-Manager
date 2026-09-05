package com.affilemanager.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.LanTransferOptions
import com.affilemanager.app.transfer.LanTransferProtocol
import com.affilemanager.app.transfer.LanTransferState
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.transfer.NearbyPairing
import com.affilemanager.app.transfer.NearbyQrCode
import com.affilemanager.app.transfer.NearbySourcePreparer
import com.affilemanager.app.transfer.NearbyTransferController
import com.affilemanager.app.transfer.NearbyTransferState
import com.affilemanager.app.transfer.NearbyTransferStatus
import com.affilemanager.app.transfer.PreparedNearbyTransfer
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.IncomingShareUiState
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.AfPullToRefresh
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID

private enum class NearbySendStep { PICK, PAIR }

@Composable
internal fun NearbyPhoneTransferCard(
    viewModel: MainViewModel,
    receiveDirectory: String,
    lanState: LanTransferState,
    incomingShare: IncomingShareUiState? = null,
    onIncomingShareConsumed: (Long) -> Unit = {},
    onChooseReceiveDirectory: () -> Unit = {},
    receiverName: String,
    onReceiverNameChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val nearbyState by NearbyTransferController.state.collectAsStateWithLifecycle()
    var showSender by remember { mutableStateOf(false) }
    var showReceiver by remember { mutableStateOf(false) }

    LaunchedEffect(incomingShare?.requestId, nearbyState.status) {
        if (incomingShare != null && !nearbyState.isActive()) showSender = true
    }

    Card(modifier = Modifier.fillMaxWidth().testTag("nearby_phone_transfer")) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    LText("Perdavimas tarp telefonų", fontWeight = FontWeight.SemiBold)
                    LText("Tiesiogiai tame pačiame privačiame Wi-Fi arba telefono prieigos taško tinkle.", style = MaterialTheme.typography.bodySmall)
                }
            }
            LText(
                "Perdavimas nėra šifruojamas. Naudokite tik savo telefono prieigos tašką arba patikimą privatų Wi-Fi tinklą.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showSender = true }, modifier = Modifier.weight(1f), enabled = !nearbyState.isActive()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    LText("Siųsti", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = { showReceiver = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null)
                    LText("Gauti", modifier = Modifier.padding(start = 6.dp))
                }
            }
            NearbyProgress(state = nearbyState, onCancel = { NearbyTransferController.cancel(context) })
        }
    }

    if (showSender) {
        NearbySendDialog(
            viewModel = viewModel,
            incomingShare = incomingShare,
            onIncomingShareConsumed = onIncomingShareConsumed,
            onDismiss = { showSender = false },
        )
    }
    if (showReceiver) {
        NearbyReceiveDialog(
            receiveDirectory = receiveDirectory,
            lanState = lanState,
            receiverName = receiverName,
            onReceiverNameChange = onReceiverNameChange,
            onChooseDirectory = {
                showReceiver = false
                onChooseReceiveDirectory()
            },
            onDismiss = { showReceiver = false },
        )
    }
}

@Composable
private fun NearbyProgress(state: NearbyTransferState, onCancel: () -> Unit) {
    if (state.status == NearbyTransferStatus.IDLE) return
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LText(
            when (state.status) {
                NearbyTransferStatus.STARTING -> "Ruošiamas siuntimas"
                NearbyTransferStatus.RUNNING -> "Siunčiama į ${state.receiverName.orEmpty()}"
                NearbyTransferStatus.COMPLETED -> "Siuntimas baigtas"
                NearbyTransferStatus.CANCELLED -> "Siuntimas atšauktas"
                NearbyTransferStatus.ERROR -> "Siuntimas nepavyko"
                NearbyTransferStatus.IDLE -> ""
            },
            fontWeight = FontWeight.SemiBold,
        )
        if (state.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { (state.sentBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            LText(
                "${state.completedFiles}/${state.fileCount} · ${FileSystemRules.humanBytes(state.sentBytes)} / ${FileSystemRules.humanBytes(state.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (state.isActive()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.currentFile?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { LText(it, style = MaterialTheme.typography.bodySmall, color = if (state.status == NearbyTransferStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.isActive()) {
            TextButton(onClick = onCancel) {
                Icon(Icons.Rounded.Cancel, contentDescription = null)
                LText("Atšaukti siuntimą", modifier = Modifier.padding(start = 6.dp))
            }
        } else {
            TextButton(onClick = NearbyTransferController::clearFinished) { LText("Uždaryti būseną") }
        }
    }
}

@Composable
private fun NearbyReceiveDialog(
    receiveDirectory: String,
    lanState: LanTransferState,
    receiverName: String,
    onReceiverNameChange: (String) -> Unit,
    onChooseDirectory: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pairingCopiedMessage = uiText("Susiejimo kodas nukopijuotas")
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val activeWebReceiver = lanState.status == LanTransferStatus.RUNNING &&
        lanState.protocol == LanTransferProtocol.WEB && !lanState.readOnly
    val pairing = remember(lanState.url, lanState.code, receiverName, activeWebReceiver) {
        if (!activeWebReceiver) null else runCatching {
            val uri = URI(lanState.url.orEmpty())
            NearbyPairing.create(uri.host.orEmpty(), uri.port, lanState.code.orEmpty(), receiverName)
        }.getOrNull()
    }
    val qrBitmap by produceState<android.graphics.Bitmap?>(null, pairing) {
        value = pairing?.let { withContext(Dispatchers.Default) { NearbyQrCode.create(it.encoded()) } }
    }

    AfModalDialog(
        title = "Gauti iš kito telefono",
        icon = Icons.Rounded.QrCode2,
        onDismissRequest = onDismiss,
        expandedContent = true,
        modifier = Modifier.testTag("nearby_receive_dialog"),
        actions = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("nearby_receive_close")) {
                LText("Uždaryti")
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!activeWebReceiver) {
                LText("Pirmiausia abu telefonai turi būti tame pačiame privačiame tinkle.")
                LText("Gauti failai bus įrašyti į:", style = MaterialTheme.typography.bodySmall)
                Text(receiveDirectory, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onChooseDirectory, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    LText("Keisti išsaugojimo vietą", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedTextField(
                    value = receiverName,
                    onValueChange = { value ->
                        onReceiverNameChange(value.filterNot(Char::isISOControl).take(NearbyPairing.MAX_NAME_LENGTH))
                    },
                    label = { LText("Šio telefono vardas") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.filterNot(Char::isISOControl).take(LanTransferOptions.MAX_PASSWORD_LENGTH) },
                    label = { LText("Laikinas kodas (tuščias = sugeneruotas)") },
                    supportingText = { LText("Jei įvedate patys, naudokite bent 8 ženklus.") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { openHotspotSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                    LText("Atidaryti Wi-Fi ir prieigos taško nustatymus", modifier = Modifier.padding(start = 7.dp))
                }
                LText("5 GHz dažnį galima pasirinkti sistemos nustatymuose tik tada, kai jį palaiko abu telefonai.", style = MaterialTheme.typography.bodySmall)
                error?.let { LText(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        runCatching {
                            val options = LanTransferOptions(password = password, readOnly = false).validated(LanTransferProtocol.WEB)
                            LanTransferController.start(context, receiveDirectory, 30, LanTransferProtocol.WEB, options)
                            password = ""
                            error = null
                        }.onFailure { error = it.message ?: "Gavimo sesijos paleisti nepavyko" }
                    },
                    enabled = lanState.status !in setOf(LanTransferStatus.STARTING, LanTransferStatus.RUNNING),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
                    LText("Paleisti gavimą", modifier = Modifier.padding(start = 7.dp))
                }
                if (lanState.status == LanTransferStatus.ERROR) lanState.message?.let { LText(it, color = MaterialTheme.colorScheme.error) }
                if (lanState.status == LanTransferStatus.RUNNING && !activeWebReceiver) {
                    LText("Šiuo metu veikia kita bendrinimo sesija. Ją sustabdykite prieš paleisdami gavimą.", color = MaterialTheme.colorScheme.error)
                }
            } else {
                LText("Kitame telefone pasirinkite „Siųsti“ ir nuskaitykite šį kodą.", fontWeight = FontWeight.SemiBold)
                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = uiText("Telefono perdavimo QR kodas"),
                        modifier = Modifier.size(280.dp).testTag("nearby_receive_qr"),
                    )
                } ?: CircularProgressIndicator()
                LText(receiverName, fontWeight = FontWeight.SemiBold)
                Text(lanState.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                lanState.code?.let { LText("Kodas: $it", fontWeight = FontWeight.Bold) }
                lanState.incomingUpload?.let { progress ->
                    LinearProgressIndicator(
                        progress = {
                            if (progress.totalBytes <= 0L) 0f
                            else (progress.receivedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("nearby_receive_progress"),
                    )
                    LText(
                        "${progress.currentFileIndex}/${progress.totalFiles} · ${FileSystemRules.humanBytes(progress.receivedBytes)} / ${FileSystemRules.humanBytes(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${progress.currentFile} · ${FileSystemRules.humanBytes(progress.currentFileBytes)} / ${FileSystemRules.humanBytes(progress.currentFileSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = {
                        pairing?.encoded()?.let { payload ->
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("AF File Manager pairing", payload))
                            Toast.makeText(context, pairingCopiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = pairing != null,
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    LText("Kopijuoti susiejimo kodą", modifier = Modifier.padding(start = 7.dp))
                }
                Button(onClick = { LanTransferController.stop(context) }) { LText("Sustabdyti gavimą") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NearbySendDialog(
    viewModel: MainViewModel,
    incomingShare: IncomingShareUiState?,
    onIncomingShareConsumed: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(NearbySendStep.PICK) }
    var category by remember { mutableStateOf<FileCategory?>(null) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASCENDING) }
    var openStorage by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingPayload by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<PreparedNearbyTransfer?>(null) }
    var qrCaptureFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(incomingShare?.requestId) {
        val request = incomingShare ?: return@LaunchedEffect
        onIncomingShareConsumed(request.requestId)
        loading = true
        error = null
        viewModel.prepareNearbyTransferDocuments(request.uris).fold(
            onSuccess = { result -> prepared = result; step = NearbySendStep.PAIR },
            onFailure = { failure -> error = failure.message ?: "Failų paruošti nepavyko" },
        )
        loading = false
    }

    fun discardAndDismiss() {
        qrCaptureFile?.delete()
        qrCaptureFile = null
        prepared?.let(viewModel::discardNearbyTransferSources)
        prepared = null
        onDismiss()
    }

    val documentsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            error = null
            viewModel.prepareNearbyTransferDocuments(uris).fold(
                onSuccess = { result -> prepared = result; step = NearbySendStep.PAIR },
                onFailure = { failure -> error = failure.message ?: "Failų paruošti nepavyko" },
            )
            loading = false
        }
    }
    val qrCaptureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val capture = qrCaptureFile
        qrCaptureFile = null
        if (!captured || capture == null) {
            capture?.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            loading = true
            error = null
            withContext(Dispatchers.IO) { runCatching { NearbyQrCode.decode(capture) } }
                .onSuccess { payload -> pairingPayload = payload }
                .onFailure { failure -> error = failure.message ?: "QR kodo nuskaityti nepavyko" }
            capture.delete()
            loading = false
        }
    }

    LaunchedEffect(category, refreshToken, sortMode, sortDirection) {
        val selectedCategory = category ?: return@LaunchedEffect
        loading = true
        error = null
        entries = emptyList()
        selectedPaths = emptySet()
        viewModel.loadNearbyTransferCategory(selectedCategory, sortMode, sortDirection).fold(
            onSuccess = { entries = it },
            onFailure = { failure -> error = failure.message ?: "Failų sąrašo įkelti nepavyko" },
        )
        loading = false
    }

    val parsedPairing = remember(pairingPayload) {
        pairingPayload.takeIf(String::isNotBlank)?.let { runCatching { NearbyPairing.parse(it) } }
    }
    val visibleEntries = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val selectablePaths = remember(visibleEntries) {
        visibleEntries.asSequence().map(FileEntry::absolutePath).distinct()
            .take(NearbySourcePreparer.MAX_FILES).toCollection(linkedSetOf())
    }
    val allSelectableSelected = selectablePaths.isNotEmpty() && selectablePaths.all(selectedPaths::contains)
    val scanQr: () -> Unit = {
        runCatching {
            val directory = File(context.cacheDir, "qr-scans").apply {
                require(isDirectory || mkdirs()) { "QR nuotraukos vietos sukurti nepavyko" }
            }
            directory.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.name.startsWith("capture-") && it.extension.equals("jpg", ignoreCase = true) }
                .take(32)
                .forEach(File::delete)
            val capture = File(directory, "capture-${UUID.randomUUID()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", capture)
            qrCaptureFile = capture
            qrCaptureLauncher.launch(uri)
        }.onFailure { failure ->
            qrCaptureFile?.delete()
            qrCaptureFile = null
            error = failure.message ?: "QR skaitytuvas šiame telefone nepasiekiamas"
        }
    }

    AfModalDialog(
        title = if (step == NearbySendStep.PICK) "Pasirinkti siunčiamus failus" else "Susieti gaunantį telefoną",
        icon = if (step == NearbySendStep.PICK) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.QrCodeScanner,
        onDismissRequest = ::discardAndDismiss,
        expandedContent = true,
        modifier = Modifier.testTag("nearby_send_dialog"),
        actions = {
            TextButton(onClick = {
                if (step == NearbySendStep.PAIR) {
                    prepared?.let(viewModel::discardNearbyTransferSources)
                    prepared = null
                    step = NearbySendStep.PICK
                } else discardAndDismiss()
            }) { LText(if (step == NearbySendStep.PAIR) "Grįžti" else "Atšaukti") }
            if (step == NearbySendStep.PICK && category != null) {
                Button(
                    onClick = {
                        val chosen = entries.filter { it.absolutePath in selectedPaths }
                        scope.launch {
                            loading = true
                            error = null
                            viewModel.prepareNearbyTransferEntries(
                                chosen,
                                installedApps = category == FileCategory.INSTALLED_APPS,
                            ).fold(
                                onSuccess = { result -> prepared = result; step = NearbySendStep.PAIR },
                                onFailure = { failure -> error = failure.message ?: "Failų paruošti nepavyko" },
                            )
                            loading = false
                        }
                    },
                    enabled = selectedPaths.isNotEmpty() && !loading,
                ) { LText("Toliau (${selectedPaths.size})") }
            }
            if (step == NearbySendStep.PAIR) {
                Button(
                    onClick = {
                        val pairing = parsedPairing?.getOrNull() ?: return@Button
                        val sources = prepared ?: return@Button
                        runCatching { NearbyTransferController.start(context, pairing, sources) }
                            .onSuccess { prepared = null; onDismiss() }
                            .onFailure { failure -> error = failure.message ?: "Siuntimo pradėti nepavyko" }
                    },
                    enabled = parsedPairing?.isSuccess == true && prepared != null,
                ) { LText("Pradėti siuntimą") }
            }
        },
    ) {
        if (step == NearbySendStep.PICK) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NearbyCategoryChip("Failai", Icons.Rounded.FolderOpen, selected = category == null) {
                        category = null
                        documentsLauncher.launch(arrayOf("*/*"))
                    }
                    NearbyCategoryChip("Nuotraukos", Icons.Rounded.Image, selected = category == FileCategory.IMAGES) { category = FileCategory.IMAGES }
                    NearbyCategoryChip("Vaizdo įrašai", Icons.Rounded.VideoFile, selected = category == FileCategory.VIDEOS) { category = FileCategory.VIDEOS }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    NearbyCategoryChip("Muzika", Icons.Rounded.AudioFile, selected = category == FileCategory.AUDIO) { category = FileCategory.AUDIO }
                    NearbyCategoryChip("Dokumentai", Icons.Rounded.Description, selected = category == FileCategory.DOCUMENTS) { category = FileCategory.DOCUMENTS }
                    NearbyCategoryChip("Archyvai", Icons.Rounded.Archive, selected = category == FileCategory.ARCHIVES) { category = FileCategory.ARCHIVES }
                    NearbyCategoryChip("APK", Icons.Rounded.Android, selected = category == FileCategory.APPS) { category = FileCategory.APPS }
                    NearbyCategoryChip("Programos", Icons.Rounded.Apps, selected = category == FileCategory.INSTALLED_APPS) { category = FileCategory.INSTALLED_APPS }
                }
                OutlinedButton(onClick = { openStorage = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    LText("Atidaryti saugyklą", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(200) },
                    label = { LText("Ieškoti šiame sąraše") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        SortMode.NAME to "Pavadinimas",
                        SortMode.MODIFIED to "Data",
                        SortMode.SIZE to "Dydis",
                        SortMode.TYPE to "Tipas",
                    ).forEach { (mode, label) ->
                        FilterChip(selected = sortMode == mode, onClick = { sortMode = mode }, label = { LText(label) })
                    }
                    IconButton(onClick = {
                        sortDirection = if (sortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
                    }) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = uiText("Keisti rūšiavimo kryptį"))
                    }
                }
                LText(
                    "Sąrašas įkeliamas puslapiais (iki ${com.affilemanager.app.data.FileCategoryRepository.MAX_RESULTS} elementų); vienu siuntimu galima pasirinkti iki ${NearbySourcePreparer.MAX_FILES} failų ar aplankų. Ilgiau palaikykite medijos failą, kad jį peržiūrėtumėte.",
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (category != null && selectablePaths.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allSelectableSelected,
                            onCheckedChange = { checked ->
                                selectedPaths = if (checked) selectedPaths + selectablePaths else selectedPaths - selectablePaths
                            },
                        )
                        LText(
                            if (visibleEntries.size > NearbySourcePreparer.MAX_FILES) "Pasirinkti pirmus ${NearbySourcePreparer.MAX_FILES}"
                            else if (allSelectableSelected) "Atžymėti visus" else "Pasirinkti visus",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (selectedPaths.isNotEmpty()) LText("Pasirinkta: ${selectedPaths.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { LText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
                AfPullToRefresh(
                    isRefreshing = loading,
                    onRefresh = { refreshToken += 1 },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    testTag = "pull_to_refresh_nearby_picker",
                ) {
                    when {
                        category == null && !loading -> Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(60.dp))
                            LText("Pasirinkite kategoriją arba atverkite Android failų pasirinkimą.")
                            Button(onClick = { documentsLauncher.launch(arrayOf("*/*")) }) { LText("Rinktis failus") }
                        }
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        visibleEntries.isEmpty() -> LText("Šioje kategorijoje atitinkančių failų nerasta", modifier = Modifier.align(Alignment.Center))
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(visibleEntries, key = FileEntry::absolutePath) { entry ->
                                val selected = entry.absolutePath in selectedPaths
                                Row(
                                    modifier = Modifier.fillMaxWidth().combinedClickable(
                                        onClick = {
                                            selectedPaths = toggleNearbySelection(selectedPaths, entry.absolutePath)
                                        },
                                        onLongClick = {
                                            if (entry.kind in setOf(com.affilemanager.app.model.EntryKind.IMAGE, com.affilemanager.app.model.EntryKind.VIDEO, com.affilemanager.app.model.EntryKind.AUDIO)) {
                                                viewModel.open(entry)
                                            }
                                        },
                                    ).padding(horizontal = 4.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { selectedPaths = toggleNearbySelection(selectedPaths, entry.absolutePath) },
                                    )
                                    LocalFileVisual(entry, 46.dp, 46.dp, showThumbnails = true, modifier = Modifier.size(46.dp))
                                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                LText("Gaunančiame telefone atverkite „Gauti“, tada nuskaitykite rodomą QR kodą.")
                Button(onClick = scanQr, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    LText("Nuskaityti QR kodą", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedButton(onClick = { openHotspotSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                    LText("Atidaryti Wi-Fi nustatymus", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedTextField(
                    value = pairingPayload,
                    onValueChange = { pairingPayload = it.take(NearbyPairing.MAX_PAYLOAD_LENGTH) },
                    label = { LText("Arba įklijuokite susiejimo kodą") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    isError = pairingPayload.isNotBlank() && parsedPairing?.isFailure == true,
                )
                parsedPairing?.exceptionOrNull()?.message?.let { LText(it, color = MaterialTheme.colorScheme.error) }
                parsedPairing?.getOrNull()?.let { pairing ->
                    LText("Gavėjas: ${pairing.receiverName}", fontWeight = FontWeight.SemiBold)
                    LText("Privatus adresas: ${pairing.host}:${pairing.port}", style = MaterialTheme.typography.bodySmall)
                }
                prepared?.let { sources -> LText("Paruošta siųsti: ${sources.paths.size}", style = MaterialTheme.typography.bodySmall) }
                error?.let { LText(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (openStorage) {
        LocalUploadDialog(
            initialDirectoryPath = viewModel.activePanelState().path,
            remotePath = "",
            initialEntries = emptyList(),
            initiallySelected = emptySet(),
            loadDirectory = viewModel::listLocalDirectoryForUpload,
            onDismiss = { openStorage = false },
            onCopy = { paths ->
                openStorage = false
                scope.launch {
                    loading = true
                    error = null
                    viewModel.prepareNearbyTransferPaths(paths).fold(
                        onSuccess = { result -> prepared = result; step = NearbySendStep.PAIR },
                        onFailure = { failure -> error = failure.message ?: "Failų paruošti nepavyko" },
                    )
                    loading = false
                }
            },
            title = "Pasirinkti siunčiamus failus ir aplankus",
            confirmLabel = "Paruošti",
        )
    }
}

@Composable
private fun NearbyCategoryChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { LText(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

private fun toggleNearbySelection(current: Set<String>, path: String): Set<String> = when {
    path in current -> current - path
    current.size >= NearbySourcePreparer.MAX_FILES -> current
    else -> current + path
}

private fun NearbyTransferState.isActive(): Boolean =
    status == NearbyTransferStatus.STARTING || status == NearbyTransferStatus.RUNNING

private fun openHotspotSettings(context: Context) {
    val intent = Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
}
