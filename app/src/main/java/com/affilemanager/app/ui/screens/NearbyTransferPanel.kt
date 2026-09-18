package com.affilemanager.app.ui.screens
import com.affilemanager.app.ui.components.AfActionRow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Contacts
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
import com.affilemanager.app.ui.theme.AfButton as Button
import com.affilemanager.app.ui.theme.AfCard as Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import com.affilemanager.app.ui.theme.AfFilterChip as FilterChip
import com.affilemanager.app.ui.theme.AfDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.FileCategoryPagingRules
import com.affilemanager.app.data.SafEntry
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.LanTransferOptions
import com.affilemanager.app.transfer.LanTransferProtocol
import com.affilemanager.app.transfer.LanSessionDuration
import com.affilemanager.app.transfer.LanTransferState
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.transfer.NearbyPairing
import com.affilemanager.app.transfer.NearbyContact
import com.affilemanager.app.transfer.NearbyDeviceAdvertiser
import com.affilemanager.app.transfer.NearbyDeviceDiscovery
import com.affilemanager.app.transfer.NearbyQrCode
import com.affilemanager.app.transfer.NearbySourcePreparer
import com.affilemanager.app.transfer.NearbyTransferController
import com.affilemanager.app.transfer.NearbyTransferHistoryController
import com.affilemanager.app.transfer.NearbyChatController
import com.affilemanager.app.transfer.NearbyTransferState
import com.affilemanager.app.transfer.NearbyTransferStatus
import com.affilemanager.app.transfer.PreparedNearbyTransfer
import com.affilemanager.app.transfer.TransferFileProgress
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.IncomingShareUiState
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.AfPullToRefresh
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.SafFileVisual
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.localization.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.math.roundToInt

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
    durationMinutes: Int,
    onDurationMinutesChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val interfaceLanguage = LocalConfiguration.current.locales[0].language
    LaunchedEffect(Unit) { NearbyTransferHistoryController.initialize(context) }
    val nearbyState by NearbyTransferController.state.collectAsStateWithLifecycle()
    val peer by NearbyTransferController.connection.state.collectAsStateWithLifecycle()
    val chatState by NearbyChatController.state.collectAsStateWithLifecycle()
    val historySessions by NearbyTransferHistoryController.state.collectAsStateWithLifecycle()
    val historyError by NearbyTransferHistoryController.error.collectAsStateWithLifecycle()
    val chatSendError = uiText("Žinutės išsiųsti nepavyko")
    var showSender by remember { mutableStateOf(false) }
    var showReceiver by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var hadPeer by remember { mutableStateOf(peer != null) }
    var sessionStartedAtMillis by remember { mutableStateOf(if (peer != null) System.currentTimeMillis() else null) }
    var receivedDetailsShown by remember(lanState.url) { mutableStateOf(false) }
    val incoming = lanState.incomingUpload?.files.orEmpty()
    val allFiles = remember(nearbyState.files, incoming) { nearbyState.files + incoming }
    val incomingProgress = lanState.incomingUpload
    val activeBytesPerSecond = if (nearbyState.isActive()) nearbyState.bytesPerSecond
        else incomingProgress?.bytesPerSecond ?: 0L
    val activeRemainingMillis = if (nearbyState.isActive()) nearbyState.remainingMillis
        else incomingProgress?.remainingMillis
    val hasCurrentSessionDetails = allFiles.isNotEmpty() || chatState.messages.isNotEmpty()
    val receiving = incoming.any { it.status in setOf(com.affilemanager.app.transfer.TransferFileStatus.WAITING,
        com.affilemanager.app.transfer.TransferFileStatus.TRANSFERRING) }
    fun disconnect() { NearbyTransferController.disconnect(context); confirmDisconnect = false; showDetails = false }
    fun requestDisconnect() { if (nearbyState.isActive() || receiving) confirmDisconnect = true else disconnect() }

    LaunchedEffect(peer) {
        if (peer != null) {
            if (!hadPeer) sessionStartedAtMillis = System.currentTimeMillis()
            hadPeer = true
        }
        else if (hadPeer) {
            showDetails = false; showReceiver = false; confirmDisconnect = false; hadPeer = false
            // Keep progress/errors and the reopenable file history on the session card.
            // A locally prepared, unsent selection is not discarded by a peer disconnect.
        }
    }

    LaunchedEffect(incoming.isNotEmpty(), showReceiver) {
        if (showReceiver && !receivedDetailsShown && incoming.isNotEmpty()) {
            receivedDetailsShown = true; showReceiver = false; showDetails = true
        }
    }
    LaunchedEffect(incomingShare?.requestId) {
        if (incomingShare != null) { showDetails = false; showReceiver = false; showSender = true }
    }

    Card(modifier = Modifier.fillMaxWidth().testTag("nearby_phone_transfer")) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    LText("Perdavimas tarp telefonų", fontWeight = FontWeight.SemiBold)
                    if (peer != null) {
                        LText("Prisijungta", style = MaterialTheme.typography.labelSmall)
                        Text(peer!!.receiverName, style = MaterialTheme.typography.bodySmall)
                    } else LText("Tiesiogiai tame pačiame privačiame Wi-Fi arba telefono prieigos taško tinkle.", style = MaterialTheme.typography.bodySmall)
                }
            }
            LText("Perdavimas nėra šifruojamas. Naudokite tik savo telefono prieigos tašką arba patikimą privatų Wi-Fi tinklą.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AfActionRow {
                if (peer == null) {
                    OutlinedButton(onClick = { showSender = true }, enabled = !nearbyState.isActive()) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        LText("Siųsti", modifier = Modifier.padding(start = 6.dp))
                    }
                    Button(onClick = { showReceiver = true }) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null)
                        LText("Gauti", modifier = Modifier.padding(start = 6.dp))
                    }
                } else {
                    OutlinedButton(onClick = { showSender = true }, modifier = Modifier.testTag("nearby_add_files")) { LText("Siųsti daugiau") }
                    TextButton(onClick = ::requestDisconnect, modifier = Modifier.testTag("nearby_disconnect")) { LText("Atsijungti") }
                }
                if (peer != null || allFiles.isNotEmpty() || chatState.messages.isNotEmpty() || historySessions.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            if (peer == null && !hasCurrentSessionDetails) showHistory = true
                            else showDetails = true
                        },
                        modifier = Modifier.testTag("nearby_send_details"),
                    ) {
                        LText(
                            when {
                                peer == null && !hasCurrentSessionDetails -> "Istorija"
                                allFiles.isEmpty() && chatState.messages.isNotEmpty() -> "Žinutės"
                                else -> "Failai"
                            },
                        )
                    }
                }
            }
            NearbyProgress(state = nearbyState, onCancel = { NearbyTransferController.cancel(context) })
        }
    }
    if (showSender) NearbySendDialog(viewModel, incomingShare, onIncomingShareConsumed,
        onDismiss = { showSender = false }, onTransferStarted = { showDetails = true }, connectedPairing = peer)
    if (showReceiver) NearbyReceiveDialog(receiveDirectory, lanState, receiverName, onReceiverNameChange,
        durationMinutes = durationMinutes, onDurationMinutesChange = onDurationMinutesChange,
        onChooseDirectory = { showReceiver = false; onChooseReceiveDirectory() }, onDismiss = { showReceiver = false },
        onOpenDetails = { showReceiver = false; showDetails = true })
    if (showDetails) NearbyTransferDetails(
        files = allFiles, transferredBytes = allFiles.sumOf { it.transferredBytes }, totalBytes = allFiles.sumOf { it.sizeBytes },
        totalFiles = allFiles.size, onPreview = viewModel::open, onDismiss = { showDetails = false },
        onCancel = when {
            nearbyState.isActive() -> ({ NearbyTransferController.cancel(context) })
            peer == null && lanState.status == LanTransferStatus.RUNNING -> ({ LanTransferController.stop(context) })
            else -> null
        },
        onCancelFile = { file, combinedIndex ->
            if (combinedIndex < nearbyState.files.size) {
                val fileIndexInBatch = nearbyFileIndexInBatch(nearbyState.files, combinedIndex)
                if (file.batchId.isNotBlank() && fileIndexInBatch > 0) {
                    NearbyTransferController.cancelFile(context, file.batchId, fileIndexInBatch)
                }
            } else {
                val incomingIndex = combinedIndex - nearbyState.files.size
                val fileIndexInBatch = nearbyFileIndexInBatch(incoming, incomingIndex)
                if (file.batchId.isNotBlank() && fileIndexInBatch > 0) {
                    LanTransferController.cancelIncomingFile(context, file.batchId, fileIndexInBatch)
                }
            }
        },
        cancelLabel = if (nearbyState.isActive()) "Sustabdyti siuntimą" else "Sustabdyti gavimą",
        message = nearbyState.message, outgoingCount = nearbyState.files.size,
        onSendMore = if (peer != null) ({ showDetails = false; showSender = true }) else null,
        onDisconnect = if (peer != null) ::requestDisconnect else null,
        localName = receiverName,
        peerName = peer?.receiverName ?: nearbyState.receiverName ?: uiText("Kitas telefonas"),
        sessionStartedAtMillis = sessionStartedAtMillis,
        chatMessages = chatState.messages,
        chatSending = chatState.sending,
        chatError = chatState.error,
        bytesPerSecond = activeBytesPerSecond,
        remainingMillis = activeRemainingMillis,
        onSendMessage = if (peer != null) ({ text ->
            runCatching { NearbyTransferController.sendMessage(context, text, receiverName) }
                .onFailure { failure ->
                    val message = nearbyFriendlyError(failure, "Žinutės išsiųsti nepavyko")
                    Toast.makeText(
                        context,
                        UiTranslator.translate(message, interfaceLanguage).ifBlank { chatSendError },
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }) else null,
    )
    if (showHistory) NearbyTransferHistoryDialog(
        sessions = historySessions,
        error = historyError,
        onDismiss = { showHistory = false },
        onClear = NearbyTransferHistoryController::clear,
        onPreview = viewModel::open,
    )
    if (confirmDisconnect) com.affilemanager.app.ui.theme.AfAlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { LText("Atsijungti") },
        text = { LText("Atsijungus nebaigti siuntimai bus atšaukti. Jau gauti failai liks.") },
        confirmButton = { TextButton(onClick = ::disconnect, modifier = Modifier.testTag("nearby_confirm_disconnect")) { LText("Atsijungti") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { LText("Atšaukti") } },
    )
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
            TransferRateAndEta(state.bytesPerSecond, state.remainingMillis)
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
    durationMinutes: Int,
    onDurationMinutesChange: (Int) -> Unit,
    onChooseDirectory: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val context = LocalContext.current
    val pairingCopiedMessage = uiText("Susiejimo kodas nukopijuotas")
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var advertisementError by remember { mutableStateOf<String?>(null) }
    var nearbyPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
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
    val advertiser = remember(context) { NearbyDeviceAdvertiser(context) { advertisementError = it } }
    DisposableEffect(advertiser, pairing, nearbyPermissionGranted) {
        if (pairing != null && nearbyPermissionGranted) advertiser.start(pairing, receiverName)
        else advertiser.stop()
        onDispose { advertiser.stop() }
    }
    val startReceiver: () -> Unit = {
        runCatching {
            val options = LanTransferOptions(password = password, readOnly = false).validated(LanTransferProtocol.WEB)
            LanTransferController.start(context, receiveDirectory, durationMinutes, LanTransferProtocol.WEB, options)
            password = ""
            error = null
        }.onFailure { failure ->
            error = nearbyFriendlyError(failure, "Gavimo sesijos paleisti nepavyko")
        }
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        nearbyPermissionGranted = granted
        if (!granted) advertisementError = "Artimų įrenginių paieška išjungta. QR kodas ir rankinis susiejimas vis tiek veikia."
        startReceiver()
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
                LText(
                    if (durationMinutes == LanSessionDuration.MANUAL_MINUTES) "Atsijungimas · rankinis"
                    else "Atsijungimas · $durationMinutes min.",
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = if (durationMinutes == LanSessionDuration.MANUAL_MINUTES) 0f
                        else (durationMinutes / LanSessionDuration.STEP_MINUTES).toFloat(),
                    onValueChange = { value ->
                        val index = value.roundToInt().coerceIn(
                            0,
                            LanSessionDuration.MAX_TIMED_MINUTES / LanSessionDuration.STEP_MINUTES,
                        )
                        onDurationMinutesChange(
                            if (index == 0) LanSessionDuration.MANUAL_MINUTES
                            else index * LanSessionDuration.STEP_MINUTES,
                        )
                    },
                    valueRange = 0f..(LanSessionDuration.MAX_TIMED_MINUTES / LanSessionDuration.STEP_MINUTES).toFloat(),
                    steps = (LanSessionDuration.MAX_TIMED_MINUTES / LanSessionDuration.STEP_MINUTES) - 1,
                    modifier = Modifier.fillMaxWidth().testTag("nearby_receive_duration"),
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
                advertisementError?.let { LText(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nearbyPermissionGranted) {
                            nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                        } else startReceiver()
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
                Text(receiverName, fontWeight = FontWeight.SemiBold)
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
                    TransferRateAndEta(progress.bytesPerSecond, progress.remainingMillis)
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
    connectedPairing: NearbyPairing? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (incomingShare != null) NearbySendStep.PAIR else NearbySendStep.PICK) }
    var category by remember { mutableStateOf<FileCategory?>(null) }
    var contactsMode by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<NearbyContact>>(emptyList()) }
    var contactsTruncated by remember { mutableStateOf(false) }
    var selectedContacts by remember { mutableStateOf<Map<String, NearbyContact>>(emptyMap()) }
    var contactsPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    var contactsPermissionDenied by remember { mutableStateOf(false) }
    var contactRetryToken by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASCENDING) }
    var searchVisible by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
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
    var showNearbyDiscovery by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingPayload by remember { mutableStateOf(connectedPairing?.encoded().orEmpty()) }
    var prepared by remember { mutableStateOf<PreparedNearbyTransfer?>(null) }
    var startAfterPreparation by remember { mutableStateOf(false) }
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
                onFailure = { failure -> error = nearbyFriendlyError(failure, "Failų paruošti nepavyko") },
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
            // ACTION_OPEN_DOCUMENT grants the app direct read access; avoid duplicating a
            // multi-gigabyte video in cache before the actual transfer starts.
            viewModel.prepareNearbyTransferDocuments(uris, copyToPrivateStage = false).fold(
                onSuccess = { result -> prepared = result; step = NearbySendStep.PAIR },
                onFailure = { failure -> error = nearbyFriendlyError(failure, "Failų paruošti nepavyko") },
            )
            loading = false
        }
    }
    val contactPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsPermissionGranted = granted
        contactsPermissionDenied = !granted
        if (granted) contactRetryToken += 1
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showNearbyDiscovery = true
        else error = "Artimų įrenginių leidimas nesuteiktas. Galite nuskaityti QR kodą arba įklijuoti susiejimo kodą."
    }

    LaunchedEffect(contactsMode, contactsPermissionGranted, contactRetryToken) {
        if (!contactsMode || !contactsPermissionGranted) return@LaunchedEffect
        loading = true
        error = null
        try {
            viewModel.loadNearbyContacts().fold(
                onSuccess = { page ->
                    contacts = page.contacts
                    contactsTruncated = page.truncated
                    val available = page.contacts.mapTo(HashSet(), NearbyContact::lookupKey)
                    selectedContacts = selectedContacts.filterKeys(available::contains)
                },
                onFailure = { failure -> error = nearbyFriendlyError(failure, "Kontaktų sąrašo perskaityti nepavyko") },
            )
        } finally {
            // Changing category cancels this effect; never leave the shared action row disabled.
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
                .onFailure { failure -> error = nearbyFriendlyError(failure, "QR kodo nuskaityti nepavyko") }
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
            onFailure = { failure -> error = nearbyFriendlyError(failure, "Failų sąrašo įkelti nepavyko") },
        )
        pageLoading = false
    }

    val parsedPairing = remember(pairingPayload) {
        pairingPayload.takeIf(String::isNotBlank)?.let { runCatching { NearbyPairing.parse(it) } }
    }
    suspend fun startPreparedTransfer(pairing: NearbyPairing, sources: PreparedNearbyTransfer) {
        loading = true
        try {
            val preferences = viewModel.shareScreenPreferences.value
            val returnPairing = NearbyTransferController.prepareReturnPairing(
                context = context,
                root = preferences.nearbyReceivePath,
                name = preferences.receiverName,
                peer = pairing,
                durationMinutes = preferences.durationMinutes,
            )
            NearbyTransferController.start(context, pairing, sources, returnPairing)
            transferredOwnership.set(true)
            prepared = null
            onTransferStarted()
            onDismiss()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = nearbyFriendlyError(failure, "Siuntimo pradėti nepavyko")
        } finally { loading = false }
    }
    LaunchedEffect(prepared, connectedPairing, loading) {
        val pairing = connectedPairing ?: return@LaunchedEffect
        val sources = prepared ?: return@LaunchedEffect
        if (startAfterPreparation && !loading && error == null) {
            startAfterPreparation = false
            // Only an explicit Start action on the picker submits another batch.
            scope.launch { startPreparedTransfer(pairing, sources) }
        }
    }
    val visibleEntries = entries
    val selectablePaths = remember(visibleEntries) {
        visibleEntries.asSequence().map(FileEntry::absolutePath).distinct()
            .take(NearbySourcePreparer.MAX_FILES).toCollection(linkedSetOf())
    }
    val allSelectableSelected = selectablePaths.isNotEmpty() && selectablePaths.all(selectedPaths::contains)
    val visibleContacts = remember(contacts, query) {
        val value = query.trim()
        if (value.isEmpty()) contacts else contacts.filter { it.displayName.contains(value, ignoreCase = true) }
    }
    val visibleContactKeys = remember(visibleContacts) { visibleContacts.mapTo(linkedSetOf(), NearbyContact::lookupKey) }
    val allVisibleContactsSelected = visibleContactKeys.isNotEmpty() && visibleContactKeys.all(selectedContacts::containsKey)
    val selectAllDescription = uiText(
        if (if (contactsMode) allVisibleContactsSelected else allSelectableSelected) "Atžymėti visus" else "Pasirinkti visus",
    )
    val sortDescription = uiText("Rūšiuoti")
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
            error = nearbyFriendlyError(failure, "QR skaitytuvas šiame telefone nepasiekiamas")
        }
    }

    if (!openStorage && !showNearbyDiscovery) AfModalDialog(
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
            if (step == NearbySendStep.PICK) {
                Button(
                    onClick = {
                        startAfterPreparation = connectedPairing != null
                        scope.launch {
                            loading = true
                            error = null
                            val result = when {
                                contactsMode && selectedContacts.isNotEmpty() ->
                                    viewModel.prepareNearbyTransferContacts(selectedContacts.values)
                                selectedEntries.isNotEmpty() -> viewModel.prepareNearbyTransferEntries(
                                    selectedEntries.values,
                                    installedApps = category == FileCategory.INSTALLED_APPS,
                                )
                                else -> Result.success(PreparedNearbyTransfer.empty())
                            }
                            result.fold(
                                onSuccess = { sources -> prepared = sources; step = NearbySendStep.PAIR },
                                onFailure = { failure -> error = nearbyFriendlyError(failure, "Failų paruošti nepavyko") },
                            )
                            loading = false
                        }
                    },
                    enabled = !loading && !pageLoading,
                    modifier = Modifier.testTag("nearby_continue"),
                ) {
                    val count = if (contactsMode) selectedContacts.size else selectedPaths.size
                    if (connectedPairing != null && count > 0) { LText("Pradėti siuntimą"); Text(" ($count)") }
                    else if (count > 0) LText("Toliau ($count)")
                    else LText("Toliau")
                }
            }
            if (step == NearbySendStep.PAIR) {
                Button(
                    onClick = {
                        val pairing = parsedPairing?.getOrNull() ?: return@Button
                        val sources = prepared ?: return@Button
                        scope.launch { startPreparedTransfer(pairing, sources) }
                    },
                    enabled = parsedPairing?.isSuccess == true && prepared != null && !loading,
                    modifier = Modifier.testTag("nearby_start_transfer"),
                ) {
                    LText(
                        if (prepared?.paths?.isEmpty() == true && prepared?.directories?.isEmpty() == true) "Susieti telefonus"
                        else "Pradėti siuntimą",
                    )
                }
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
                            NearbyCategoryChip("Failai", Icons.Rounded.FolderOpen, selected = category == null && !contactsMode) {
                                contactsMode = false
                                category = null
                                openStorage = true
                            }
                            NearbyCategoryChip("Nuotraukos", Icons.Rounded.Image, selected = category == FileCategory.IMAGES) { contactsMode = false; category = FileCategory.IMAGES }
                            NearbyCategoryChip("Vaizdo įrašai", Icons.Rounded.VideoFile, selected = category == FileCategory.VIDEOS) { contactsMode = false; category = FileCategory.VIDEOS }
                            NearbyCategoryChip("Muzika", Icons.Rounded.AudioFile, selected = category == FileCategory.AUDIO) { contactsMode = false; category = FileCategory.AUDIO }
                            NearbyCategoryChip("Dokumentai", Icons.Rounded.Description, selected = category == FileCategory.DOCUMENTS) { contactsMode = false; category = FileCategory.DOCUMENTS }
                            NearbyCategoryChip("Archyvai", Icons.Rounded.Archive, selected = category == FileCategory.ARCHIVES) { contactsMode = false; category = FileCategory.ARCHIVES }
                            NearbyCategoryChip("APK", Icons.Rounded.Android, selected = category == FileCategory.APPS) { contactsMode = false; category = FileCategory.APPS }
                            NearbyCategoryChip("Programos", Icons.Rounded.Apps, selected = category == FileCategory.INSTALLED_APPS) { contactsMode = false; category = FileCategory.INSTALLED_APPS }
                            NearbyCategoryChip("Kontaktai", Icons.Rounded.Contacts, selected = contactsMode) {
                                contactsMode = true
                                category = null
                                query = ""
                                error = null
                                if (!contactsPermissionGranted) contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LText(
                                if (contactsMode) "Kontaktai" else when (sortMode) {
                                    SortMode.NAME -> "Pavadinimas"
                                    SortMode.MODIFIED -> "Pakeista"
                                    SortMode.SIZE -> "Dydis"
                                    SortMode.TYPE -> "Tipas"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            IconButton(
                                onClick = {
                                    searchVisible = !searchVisible
                                    if (!searchVisible) query = ""
                                },
                                enabled = category != null || contactsMode,
                                modifier = Modifier.testTag("nearby_search_toggle"),
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = uiText(if (searchVisible) "Slėpti paiešką" else "Ieškoti"))
                            }
                            if (!contactsMode) Box {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .combinedClickable(
                                            onClick = {
                                                sortDirection = if (sortDirection == SortDirection.ASCENDING) {
                                                    SortDirection.DESCENDING
                                                } else SortDirection.ASCENDING
                                            },
                                            onLongClick = { sortMenu = true },
                                        )
                                        .semantics { contentDescription = sortDescription }
                                        .testTag("nearby_sort_direction"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.SwapVert, contentDescription = null)
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    listOf(
                                        SortMode.NAME to "Pavadinimas",
                                        SortMode.MODIFIED to "Pakeista",
                                        SortMode.SIZE to "Dydis",
                                        SortMode.TYPE to "Tipas",
                                    ).forEach { (mode, label) ->
                                        DropdownMenuItem(
                                            text = { LText(label) },
                                            onClick = { sortMode = mode; sortMenu = false },
                                            leadingIcon = {
                                                if (sortMode == mode) Icon(Icons.Rounded.SwapVert, contentDescription = null)
                                            },
                                            modifier = Modifier.testTag("nearby_sort_$mode"),
                                        )
                                    }
                                }
                            }
                            Checkbox(
                                checked = if (contactsMode) allVisibleContactsSelected else allSelectableSelected,
                                enabled = if (contactsMode) {
                                    contactsPermissionGranted && visibleContactKeys.isNotEmpty() && !loading
                                } else category != null && selectablePaths.isNotEmpty() && !pageLoading,
                                onCheckedChange = {
                                    if (contactsMode) {
                                        selectedContacts = if (allVisibleContactsSelected) {
                                            selectedContacts - visibleContactKeys
                                        } else {
                                            selectedContacts + visibleContacts.associateBy(NearbyContact::lookupKey)
                                        }
                                    } else {
                                        selectedEntries = NearbyPickerSelection.togglePage(selectedEntries, visibleEntries)
                                    }
                                },
                                modifier = Modifier.testTag("nearby_select_all").semantics {
                                    contentDescription = selectAllDescription
                                },
                            )
                        }
                        if (searchVisible) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it.take(200) },
                                label = { LText(if (contactsMode) "Ieškoti kontaktų" else "Ieškoti šioje kategorijoje") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("nearby_search"),
                                enabled = category != null || contactsMode,
                            )
                        }
                        LText(
                            if (contactsMode) "Vienu kartu galima siųsti iki ${NearbySourcePreparer.MAX_CONTACTS} kontaktų"
                            else "Vienu kartu galima siųsti iki ${NearbySourcePreparer.MAX_FILES} failų",
                            modifier = Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        error?.let {
                            LText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
                            if ((category != null || contactsMode) && !loading && !pageLoading) {
                                TextButton(onClick = {
                                    if (contactsMode) contactRetryToken += 1 else retryToken += 1
                                }) { LText("Bandyti dar kartą") }
                            }
                        }
                    }
                    AfPullToRefresh(
                        isRefreshing = if (contactsMode) loading && contacts.isNotEmpty()
                            else (loading || pageLoading) && visibleEntries.isNotEmpty(),
                        onRefresh = { if (contactsMode) contactRetryToken += 1 else refreshToken += 1 },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        testTag = "pull_to_refresh_nearby_picker",
                    ) {
                        when {
                            contactsMode && !contactsPermissionGranted -> Column(
                                modifier = Modifier.align(Alignment.Center).padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.Contacts, contentDescription = null, modifier = Modifier.size(60.dp))
                                LText(
                                    if (contactsPermissionDenied) "Kontaktų leidimas nesuteiktas. Failus vis tiek galite siųsti be šio leidimo."
                                    else "Kontaktų leidimas reikalingas tik pasirinktiems kontaktams parodyti ir išsiųsti.",
                                )
                                Button(onClick = { contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
                                    LText("Leisti pasiekti kontaktus")
                                }
                            }
                            contactsMode && visibleContacts.isEmpty() && loading ->
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            contactsMode && visibleContacts.isEmpty() && error != null -> Unit
                            contactsMode && visibleContacts.isEmpty() ->
                                LText("Atitinkančių kontaktų nerasta", modifier = Modifier.align(Alignment.Center))
                            contactsMode -> LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("nearby_contacts"),
                            ) {
                                items(visibleContacts, key = NearbyContact::lookupKey) { contact ->
                                    NearbyContactRow(
                                        contact = contact,
                                        selected = contact.lookupKey in selectedContacts,
                                        onToggle = {
                                            selectedContacts = if (contact.lookupKey in selectedContacts) {
                                                selectedContacts - contact.lookupKey
                                            } else selectedContacts + (contact.lookupKey to contact)
                                        },
                                    )
                                    HorizontalDivider()
                                }
                                if (contactsTruncated) item("contacts_truncated") {
                                    LText(
                                        "Rodomi pirmi ${NearbySourcePreparer.MAX_CONTACTS} kontaktų",
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
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
                                itemsIndexed(visibleEntries, key = { _, entry -> entry.absolutePath }) { entryIndex, entry ->
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
                                            Text(
                                                entry.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.then(
                                                    if (entryIndex == 0) Modifier.testTag("nearby_first_entry") else Modifier,
                                                ),
                                            )
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
                    } else if (contactsMode && selectedContacts.isNotEmpty()) {
                        LText("Pasirinkta: ${selectedContacts.size}", style = MaterialTheme.typography.labelSmall)
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
                if (connectedPairing == null) {
                LText("Gaunančiame telefone atverkite „Gauti“, tada nuskaitykite rodomą QR kodą.")
                LText("Gavimas atgal į pasirinktą aplanką veiks 15 minučių. Atsijungti galima bendrinimo lange.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = scanQr, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    LText("Nuskaityti QR kodą", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedButton(
                    onClick = {
                        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) showNearbyDiscovery = true
                        else nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("nearby_find_devices"),
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, contentDescription = null)
                    LText("Rasti artimus AF įrenginius", modifier = Modifier.padding(start = 7.dp))
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
                    modifier = Modifier.fillMaxWidth().testTag("nearby_pairing_input"),
                    isError = pairingPayload.isNotBlank() && parsedPairing?.isFailure == true,
                )
                parsedPairing?.exceptionOrNull()?.message?.let { LText(it, color = MaterialTheme.colorScheme.error) }
                }
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
                startAfterPreparation = connectedPairing != null
                scope.launch {
                    loading = true
                    error = null
                    viewModel.prepareNearbyTransferPaths(paths).fold(
                        onSuccess = { result -> prepared = result; step = NearbySendStep.PAIR },
                        onFailure = { failure -> error = nearbyFriendlyError(failure, "Failų paruošti nepavyko") },
                    )
                    loading = false
                }
            },
            title = "Pasirinkti siunčiamus failus ir aplankus",
            confirmLabel = if (connectedPairing != null) "Pradėti siuntimą" else "Paruošti",
        )
    }
    if (showNearbyDiscovery) {
        NearbyDiscoveryDialog(
            onDismiss = { showNearbyDiscovery = false },
            onSelect = { device ->
                pairingPayload = device.pairing.encoded()
                error = null
                showNearbyDiscovery = false
                // Selecting a discovered receiver is the user's explicit send/pair action.
                // Reuse the same authenticated path as the manual Start button; an empty
                // prepared batch establishes the two-way session without sending a file.
                val sources = prepared
                if (sources != null && !loading) {
                    loading = true
                    scope.launch { startPreparedTransfer(device.pairing, sources) }
                }
            },
        )
    }
}

@Composable
private fun NearbyDiscoveryDialog(
    onDismiss: () -> Unit,
    onSelect: (com.affilemanager.app.transfer.NearbyDiscoveredDevice) -> Unit,
) {
    val context = LocalContext.current
    val discovery = remember(context) { NearbyDeviceDiscovery(context) }
    val state by discovery.state.collectAsStateWithLifecycle()
    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.close() }
    }
    AfModalDialog(
        title = "Artimi AF įrenginiai",
        icon = Icons.Rounded.PhoneAndroid,
        onDismissRequest = onDismiss,
        expandedContent = true,
        modifier = Modifier.testTag("nearby_discovery_dialog"),
        actions = {
            TextButton(onClick = {
                discovery.stop()
                discovery.start()
            }) { LText("Ieškoti dar kartą") }
            TextButton(onClick = onDismiss) { LText("Uždaryti") }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.searching) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            state.message?.let { message -> item { LText(message, color = MaterialTheme.colorScheme.error) } }
            if (!state.searching && state.devices.isEmpty() && state.message == null) {
                item { LText("Artimų AF įrenginių nerasta. Abiejuose telefonuose įjunkite Wi-Fi ir gavėjo telefone palikite atvertą gavimo langą.") }
            }
            items(state.devices, key = { it.serviceName }) { device ->
                Card(
                    onClick = { onSelect(device) },
                    modifier = Modifier.fillMaxWidth().testTag("nearby_discovered_device"),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(device.receiverName, fontWeight = FontWeight.SemiBold)
                        Text(device.deviceName, style = MaterialTheme.typography.bodySmall)
                        LText("Kodas: ${device.pairing.code}", style = MaterialTheme.typography.labelSmall)
                        Text("${device.pairing.host}:${device.pairing.port}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NearbyContactRow(
    contact: NearbyContact,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 7.dp)
            .testTag("nearby_contact_${contact.lookupKey.hashCode()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        val photo = contact.photoUri
        if (photo == null) {
            Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Contacts, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        } else {
            SafFileVisual(
                entry = SafEntry(
                    uri = photo,
                    name = contact.displayName.ifBlank { "contact" },
                    directory = false,
                    sizeBytes = 0L,
                    modifiedAtMillis = 0L,
                    mimeType = "image/*",
                    kind = EntryKind.IMAGE,
                    canWrite = false,
                ),
                targetWidth = 46.dp,
                targetHeight = 46.dp,
                showThumbnails = true,
                modifier = Modifier.size(46.dp),
            )
        }
        val nameModifier = Modifier.weight(1f).padding(start = 10.dp)
        if (contact.displayName.isBlank()) {
            LText("Kontaktas be vardo", modifier = nameModifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text(contact.displayName, modifier = nameModifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun NearbyTransferState.isActive(): Boolean =
    status == NearbyTransferStatus.STARTING || status == NearbyTransferStatus.RUNNING

internal fun nearbyFileIndexInBatch(files: List<TransferFileProgress>, combinedIndex: Int): Int {
    val selected = files.getOrNull(combinedIndex) ?: return 0
    if (selected.batchId.isBlank()) return 0
    return files.take(combinedIndex + 1).count { it.batchId == selected.batchId }
}

private fun nearbyFriendlyError(failure: Throwable, fallback: String): String = when (failure) {
    is SecurityException -> "Leidimas nesuteiktas"
    // Provider and socket messages are platform/vendor text and cannot be translated reliably.
    // Keep the localized operation context instead of leaking a second interface language.
    is java.io.IOException -> fallback
    is IllegalArgumentException, is IllegalStateException -> failure.message
        ?.takeIf { message ->
            !message.contains("Permission Denial", ignoreCase = true) &&
                !message.contains("requires android.permission", ignoreCase = true)
        }
        ?.take(240)
        ?: fallback
    else -> fallback
}

private fun openHotspotSettings(context: Context) {
    val intent = Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
}
