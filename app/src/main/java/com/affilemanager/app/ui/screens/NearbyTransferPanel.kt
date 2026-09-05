package com.affilemanager.app.ui.screens
import com.affilemanager.app.ui.components.AfActionRow

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.FileCategoryPagingRules
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
private enum class NearbyDetailsSide { SEND, RECEIVE }

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
    var detailsSide by remember { mutableStateOf<NearbyDetailsSide?>(null) }
    var receivedDetailsShown by remember(lanState.url) { mutableStateOf(false) }
    LaunchedEffect(lanState.incomingUpload != null, showReceiver) {
        if (showReceiver && !receivedDetailsShown && lanState.incomingUpload?.files?.isNotEmpty() == true) {
            receivedDetailsShown = true
            showReceiver = false
            detailsSide = NearbyDetailsSide.RECEIVE
        }
    }

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
            AfActionRow {
                OutlinedButton(onClick = { showSender = true }, enabled = !nearbyState.isActive()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    LText("Siųsti", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = { showReceiver = true }) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null)
                    LText("Gauti", modifier = Modifier.padding(start = 6.dp))
                }
            }
            NearbyProgress(state = nearbyState, onCancel = { NearbyTransferController.cancel(context) })
            if (nearbyState.status != NearbyTransferStatus.IDLE) {
                TextButton(onClick = { detailsSide = NearbyDetailsSide.SEND }, modifier = Modifier.testTag("nearby_send_details")) { LText("Failai") }
            }
        }
    }

    if (showSender) {
        NearbySendDialog(
            viewModel = viewModel,
            incomingShare = incomingShare,
            onIncomingShareConsumed = onIncomingShareConsumed,
            onDismiss = { showSender = false },
            onTransferStarted = { detailsSide = NearbyDetailsSide.SEND },
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
            onOpenDetails = { showReceiver = false; detailsSide = NearbyDetailsSide.RECEIVE },
        )
    }
    when (detailsSide) {
        NearbyDetailsSide.SEND -> NearbyTransferDetails(
            files = nearbyState.files, transferredBytes = nearbyState.sentBytes,
            totalBytes = nearbyState.totalBytes, totalFiles = nearbyState.fileCount,
            onPreview = viewModel::open, onDismiss = { detailsSide = null },
            onCancel = if (nearbyState.isActive()) ({ NearbyTransferController.cancel(context) }) else null,
            message = nearbyState.message,
        )
        NearbyDetailsSide.RECEIVE -> lanState.incomingUpload?.let { progress ->
            NearbyTransferDetails(progress.files, progress.receivedBytes, progress.totalBytes, progress.totalFiles,
                viewModel::open, { detailsSide = null },
                onCancel = if (lanState.status == LanTransferStatus.RUNNING) ({
                    LanTransferController.stop(context)
                    detailsSide = null
                }) else null, cancelLabel = "Sustabdyti gavimą")
        }
        null -> Unit
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
    onOpenDetails: () -> Unit,
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
                    if (progress.files.isNotEmpty()) TextButton(onClick = onOpenDetails) { LText("Failai") }
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
internal fun NearbySendDialog(
    viewModel: MainViewModel,
    incomingShare: IncomingShareUiState?,
    onIncomingShareConsumed: (Long) -> Unit,
    onDismiss: () -> Unit,
    onTransferStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (incomingShare != null) NearbySendStep.PAIR else NearbySendStep.PICK) }
    var category by remember { mutableStateOf<FileCategory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASCENDING) }
    var pageOffset by remember(category, query, sortMode, sortDirection, refreshToken) { mutableStateOf(0) }
    var nextOffset by remember(category, query, sortMode, sortDirection, refreshToken, pageOffset) { mutableStateOf<Int?>(null) }
    // Refresh revalidates this page without blanking its already available rows.
    // A different category/query/order/page still starts with a distinct list.
    var entries by remember(category, query, sortMode, sortDirection, pageOffset) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var pageLoading by remember { mutableStateOf(false) }
    var retryToken by remember { mutableStateOf(0) }
    var selectedEntries by remember(category, sortMode, sortDirection) { mutableStateOf<Map<String, FileEntry>>(emptyMap()) }
    val selectedPaths = selectedEntries.keys
    val pageListState = rememberLazyListState()
    var openStorage by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingPayload by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<PreparedNearbyTransfer?>(null) }
    var qrCaptureFile by remember { mutableStateOf<File?>(null) }
    val latestPrepared by rememberUpdatedState(prepared)
    val latestCapture by rememberUpdatedState(qrCaptureFile)
    val transferredOwnership = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(viewModel) {
        onDispose {
            if (!transferredOwnership.get()) latestPrepared?.let(viewModel::discardNearbyTransferSources)
            latestCapture?.delete()
        }
    }

    LaunchedEffect(incomingShare?.requestId) {
        val request = incomingShare ?: return@LaunchedEffect
        prepared?.let(viewModel::discardNearbyTransferSources)
        prepared = null
        step = NearbySendStep.PAIR
        loading = true
        error = null
        try {
            viewModel.prepareNearbyTransferDocuments(request.uris).fold(
                onSuccess = { result -> prepared = result },
                onFailure = { failure -> error = failure.message ?: "Failų paruošti nepavyko" },
            )
        } finally {
            loading = false
        }
        // Consuming changes this effect's key in the parent. Acknowledge only
        // after the suspension and ownership handoff, otherwise we cancel ourselves.
        onIncomingShareConsumed(request.requestId)
    }

    fun discardAndDismiss() {
        qrCaptureFile?.delete()
        qrCaptureFile = null
        prepared?.let(viewModel::discardNearbyTransferSources)
        prepared = null
        incomingShare?.let { onIncomingShareConsumed(it.requestId) }
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

    LaunchedEffect(category, refreshToken, sortMode, sortDirection, query, pageOffset, retryToken) {
        val selectedCategory = category
        if (selectedCategory == null) { pageLoading = false; return@LaunchedEffect }
        pageLoading = true
        error = null
        // The list is not composed while its first page is loading. Do not wait
        // for that layout before requesting the data that makes it visible.
        pageListState.requestScrollToItem(0)
        if (query.isNotBlank()) kotlinx.coroutines.delay(180)
        viewModel.loadNearbyTransferCategoryPage(selectedCategory, pageOffset, sortMode, sortDirection, query,
            forceRefresh = pageOffset == 0).fold(
            onSuccess = { entries = it.entries; nextOffset = it.nextOffset },
            onFailure = { failure -> error = failure.message ?: "Failų sąrašo įkelti nepavyko" },
        )
        pageLoading = false
    }

    val parsedPairing = remember(pairingPayload) {
        pairingPayload.takeIf(String::isNotBlank)?.let { runCatching { NearbyPairing.parse(it) } }
    }
    val visibleEntries = entries
    val selectablePaths = remember(visibleEntries) {
        visibleEntries.asSequence().map(FileEntry::absolutePath).distinct()
            .take(NearbySourcePreparer.MAX_FILES).toCollection(linkedSetOf())
    }
    val allSelectableSelected = selectablePaths.isNotEmpty() && selectablePaths.all(selectedPaths::contains)
    val selectAllDescription = uiText(if (allSelectableSelected) "Atžymėti visus" else "Pasirinkti visus")
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
            if (step == NearbySendStep.PICK) {
                TextButton(onClick = { openStorage = true }, enabled = !loading,
                    modifier = Modifier.testTag("nearby_open_storage")) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    LText("Atidaryti saugyklą", modifier = Modifier.padding(start = 6.dp))
                }
            }
            TextButton(onClick = {
                if (step == NearbySendStep.PAIR && !loading) {
                    prepared?.let(viewModel::discardNearbyTransferSources)
                    prepared = null
                    step = NearbySendStep.PICK
                } else discardAndDismiss()
            }) { LText(if (step == NearbySendStep.PAIR && !loading) "Grįžti" else "Atšaukti") }
            if (step == NearbySendStep.PICK && category != null) {
                Button(
                    onClick = {
                        val chosen = selectedEntries.values.toList()
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
                    enabled = selectedPaths.isNotEmpty() && !loading && !pageLoading,
                ) { LText("Toliau (${selectedPaths.size})") }
            }
            if (step == NearbySendStep.PAIR) {
                Button(
                    onClick = {
                        val pairing = parsedPairing?.getOrNull() ?: return@Button
                        val sources = prepared ?: return@Button
                        runCatching { NearbyTransferController.start(context, pairing, sources) }
                            .onSuccess { transferredOwnership.set(true); prepared = null; onTransferStarted(); onDismiss() }
                            .onFailure { failure -> error = failure.message ?: "Siuntimo pradėti nepavyko" }
                    },
                    enabled = parsedPairing?.isSuccess == true && prepared != null && !loading,
                ) { LText("Pradėti siuntimą") }
            }
        },
    ) {
        if (step == NearbySendStep.PICK) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Keep a usable file viewport when a landscape keyboard reduces the
                // dialog height. All filters remain available by scrolling this header.
                val controlsHeight = (maxHeight - 180.dp).coerceIn(80.dp, 340.dp)
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState())) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            NearbyCategoryChip("Failai", Icons.Rounded.FolderOpen, selected = category == null) {
                                category = null
                                openStorage = true
                            }
                            NearbyCategoryChip("Nuotraukos", Icons.Rounded.Image, selected = category == FileCategory.IMAGES) { category = FileCategory.IMAGES }
                            NearbyCategoryChip("Vaizdo įrašai", Icons.Rounded.VideoFile, selected = category == FileCategory.VIDEOS) { category = FileCategory.VIDEOS }
                            NearbyCategoryChip("Muzika", Icons.Rounded.AudioFile, selected = category == FileCategory.AUDIO) { category = FileCategory.AUDIO }
                            NearbyCategoryChip("Dokumentai", Icons.Rounded.Description, selected = category == FileCategory.DOCUMENTS) { category = FileCategory.DOCUMENTS }
                            NearbyCategoryChip("Archyvai", Icons.Rounded.Archive, selected = category == FileCategory.ARCHIVES) { category = FileCategory.ARCHIVES }
                            NearbyCategoryChip("APK", Icons.Rounded.Android, selected = category == FileCategory.APPS) { category = FileCategory.APPS }
                            NearbyCategoryChip("Programos", Icons.Rounded.Apps, selected = category == FileCategory.INSTALLED_APPS) { category = FileCategory.INSTALLED_APPS }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(200) },
                            label = { LText("Ieškoti šioje kategorijoje") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("nearby_search"),
                            enabled = category != null,
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf(
                                    SortMode.NAME to "Pavadinimas",
                                    SortMode.MODIFIED to "Pakeista",
                                    SortMode.SIZE to "Dydis",
                                    SortMode.TYPE to "Tipas",
                                ).forEach { (mode, label) ->
                                    FilterChip(selected = sortMode == mode, onClick = { sortMode = mode }, label = { LText(label) },
                                        modifier = Modifier.testTag("nearby_sort_$mode"))
                                }
                            }
                            IconButton(onClick = {
                                sortDirection = if (sortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING
                            }, modifier = Modifier.testTag("nearby_sort_direction")) {
                                Icon(Icons.Rounded.SwapVert, contentDescription = uiText("Keisti rūšiavimo kryptį"))
                            }
                            Checkbox(
                                checked = allSelectableSelected,
                                enabled = category != null && selectablePaths.isNotEmpty() && !pageLoading,
                                onCheckedChange = { selectedEntries = NearbyPickerSelection.togglePage(selectedEntries, visibleEntries) },
                                modifier = Modifier.testTag("nearby_select_all").semantics {
                                    contentDescription = selectAllDescription
                                },
                            )
                        }
                        LText(
                            "Vienu kartu galima siųsti iki ${NearbySourcePreparer.MAX_FILES} failų",
                            modifier = Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        error?.let {
                            LText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
                            if (category != null && !loading && !pageLoading) TextButton(onClick = { retryToken += 1 }) { LText("Bandyti dar kartą") }
                        }
                    }
                    AfPullToRefresh(
                        isRefreshing = (loading || pageLoading) && visibleEntries.isNotEmpty(),
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
                            visibleEntries.isEmpty() && (loading || pageLoading) -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            visibleEntries.isEmpty() && error != null -> Unit
                            visibleEntries.isEmpty() -> LText("Šioje kategorijoje atitinkančių failų nerasta", modifier = Modifier.align(Alignment.Center))
                            else -> LazyColumn(state = pageListState, modifier = Modifier.fillMaxSize().testTag("nearby_page_entries")) {
                                items(visibleEntries, key = FileEntry::absolutePath) { entry ->
                                    val selected = entry.absolutePath in selectedPaths
                                    Row(
                                        modifier = Modifier.fillMaxWidth().testTag("nearby_entry_${entry.absolutePath}").combinedClickable(
                                            onClick = {
                                                if (!selected && selectedPaths.size >= NearbySourcePreparer.MAX_FILES) {
                                                    error = "Vienu kartu galima siųsti iki ${NearbySourcePreparer.MAX_FILES} failų"
                                                }
                                                selectedEntries = NearbyPickerSelection.toggle(selectedEntries, entry)
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
                                            enabled = selected || selectedPaths.size < NearbySourcePreparer.MAX_FILES,
                                            onCheckedChange = { selectedEntries = NearbyPickerSelection.toggle(selectedEntries, entry) },
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
                    if (category != null) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { pageOffset = (pageOffset - FileCategoryPagingRules.BROWSE_PAGE_ROWS).coerceAtLeast(0) },
                                enabled = pageOffset > 0 && !loading && !pageLoading, modifier = Modifier.testTag("nearby_previous_page")) {
                                LText("Ankstesnis puslapis")
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                LText("Puslapis", style = MaterialTheme.typography.labelSmall)
                                Text((pageOffset / FileCategoryPagingRules.BROWSE_PAGE_ROWS + 1).toString())
                            }
                            TextButton(onClick = { nextOffset?.let { pageOffset = it } },
                                enabled = nextOffset != null && !loading && !pageLoading, modifier = Modifier.testTag("nearby_next_page")) {
                                LText("Kitas puslapis")
                            }
                        }
                        if (selectedPaths.isNotEmpty()) LText("Pasirinkta: ${selectedPaths.size}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("nearby_preparing"))
                    LText("Ruošiamas siuntimas")
                }
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
        modifier = Modifier.testTag("nearby_category_$label"),
        label = { LText(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
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
