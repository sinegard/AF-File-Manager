package com.affilemanager.app.ui.screens

import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.BuildConfig
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.update.AppUpdateState
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    contentPadding: PaddingValues,
    onAddSafLocation: () -> Unit,
    onToggleAppLock: (Boolean) -> Unit,
) {
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val trash by viewModel.trashItems.collectAsStateWithLifecycle()
    val trashBrowser by viewModel.trashBrowser.collectAsStateWithLifecycle()
    val safLocations by viewModel.safLocations.collectAsStateWithLifecycle()
    val safBrowser by viewModel.safBrowser.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val syncSchedules by viewModel.syncSchedules.collectAsStateWithLifecycle()
    val lanTransfer by LanTransferController.state.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val active = viewModel.activePanelState()
    val selectedEntry = active.entries.singleOrNull { it.absolutePath in active.selectedPaths }
    val context = LocalContext.current
    val shizukuInstalled = remember {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess
    }
    val rootPresent = remember { listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() } }

    var encryptTarget by remember { mutableStateOf(selectedEntry) }
    var showEncrypt by remember { mutableStateOf(false) }
    var removeSaf by remember { mutableStateOf<com.affilemanager.app.data.SafLocation?>(null) }
    var lanDuration by remember { mutableStateOf(15) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Įrankiai ir saugumas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Jokių reklamų, sekimo SDK ar privalomos paskyros.", style = MaterialTheme.typography.bodySmall)
        }

        item { SectionHeader("Operacijų centras", operations.size.toString()) }
        if (operations.isEmpty()) {
            item { InfoCard("Nėra operacijų", "Kopijavimas, archyvavimas ir tinklo perdavimai bus rodomi čia.", Icons.Rounded.CheckCircle) }
        } else {
            items(operations, key = OperationSnapshot::id) { operation ->
                OperationCard(
                    operation = operation,
                    onPause = { viewModel.pauseOperation(operation.id) },
                    onResume = { viewModel.resumeOperation(operation.id) },
                    onCancel = { viewModel.cancelOperation(operation.id) },
                    onRetry = { viewModel.retryOperation(operation.id) },
                )
            }
            item {
                TextButton(onClick = viewModel::dismissFinishedOperations) { Text("Paslėpti užbaigtas") }
            }
        }

        item { SectionHeader("Perdavimas kompiuteriui", if (lanTransfer.status == LanTransferStatus.RUNNING) "Veikia" else "Išjungta") }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Trumpalaikis LAN tinklalapis", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Tik vienas pasirinktas katalogas · prisijungimas vienkartiniu kodu · jokio viešo tunelio",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    when (lanTransfer.status) {
                        LanTransferStatus.RUNNING -> {
                            Text("Adresas: ${lanTransfer.url}", fontWeight = FontWeight.SemiBold)
                            Text("Vienkartinis kodas: ${lanTransfer.code}", style = MaterialTheme.typography.titleMedium)
                            lanTransfer.expiresAtMillis?.let { expires ->
                                Text("Baigsis: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(expires))}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Katalogas: ${lanTransfer.rootName}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(ClipData.newPlainText("AF File Manager LAN", lanTransfer.url.orEmpty()))
                                    Toast.makeText(context, "Adresas nukopijuotas", Toast.LENGTH_SHORT).show()
                                }) { Text("Kopijuoti adresą") }
                                OutlinedButton(onClick = { LanTransferController.stop(context) }) { Text("Sustabdyti") }
                            }
                        }
                        LanTransferStatus.STARTING -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(lanTransfer.message ?: "Paleidžiama…", style = MaterialTheme.typography.bodySmall)
                        }
                        LanTransferStatus.STOPPED, LanTransferStatus.ERROR -> {
                            Text("Bus bendrinamas aktyvus katalogas: ${File(active.path).name.ifBlank { active.path }}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(15, 30, 60).forEach { minutes ->
                                    FilterChip(
                                        selected = lanDuration == minutes,
                                        onClick = { lanDuration = minutes },
                                        label = { Text("$minutes min.") },
                                    )
                                }
                            }
                            lanTransfer.message?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall, color = if (lanTransfer.status == LanTransferStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { LanTransferController.start(context, active.path, lanDuration) }) { Text("Paleisti") }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Šiukšliadėžė", trash.size.toString()) }
        item {
            Card(
                onClick = viewModel::openTrashBrowser,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Atidaryti šiukšliadėžę", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (trash.isEmpty()) "Šiukšliadėžė tuščia" else "${trash.size} elementų · galima atkurti arba išvalyti viską",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Atidaryti", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item { SectionHeader("Pasirinktos ir debesijos vietos", safLocations.size.toString()) }
        item {
            FilledTonalButton(onClick = onAddSafLocation) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text("Pridėti per Android dokumentų sistemą", modifier = Modifier.padding(start = 8.dp))
            }
        }
        items(safLocations, key = { it.uri }) { location ->
            Card(onClick = { viewModel.openSafLocation(location) }, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderSpecial, contentDescription = null)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(location.title, fontWeight = FontWeight.SemiBold)
                        Text(location.uri, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { removeSaf = location }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Pašalinti vietą")
                    }
                }
            }
        }

        item { SectionHeader("Programos užraktas", if (appLockEnabled) "Įjungtas" else "Išjungtas") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Biometrinis arba įrenginio užraktas", fontWeight = FontWeight.SemiBold)
                        Text("Grįžus iš fono failų langas bus užrakintas. Keitimą patvirtina Android.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = appLockEnabled, onCheckedChange = onToggleAppLock)
                }
            }
        }

        item { SectionHeader("Fono sinchronizavimas", syncSchedules.size.toString()) }
        if (syncSchedules.isEmpty()) {
            item { InfoCard("Tvarkaraščių nėra", "Prisijunkite skiltyje „Ryšiai“, atidarykite sinchronizavimą ir pasirinkite intervalą.", Icons.Rounded.Sync) }
        } else {
            items(syncSchedules, key = { it.id }) { schedule ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sync, contentDescription = null)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(schedule.profileName, fontWeight = FontWeight.SemiBold)
                            Text("${schedule.localRoot} ↔ ${schedule.remoteRoot}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Kas ${schedule.intervalHours} val. · ${if (schedule.unmeteredOnly) "tik Wi‑Fi/Ethernet" else "bet koks tinklas"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            schedule.lastStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                        IconButton(onClick = { viewModel.removeSyncSchedule(schedule.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Atšaukti tvarkaraštį")
                        }
                    }
                }
            }
        }

        item { SectionHeader("Šifruota saugykla", if (selectedEntry == null) "Pasirinkite failą" else "1 failas") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(34.dp))
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text("AES-256-GCM failų šifravimas", fontWeight = FontWeight.SemiBold)
                            Text("Slaptafrazė neišsaugoma. Originalas automatiškai netrinamas.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { encryptTarget = selectedEntry; showEncrypt = true },
                        enabled = selectedEntry?.isDirectory == false,
                    ) { Text("Šifruoti pasirinktą failą") }
                }
            }
        }

        item { SectionHeader("Pažengusio naudotojo režimas", "Neprivalomas") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLine("Shizuku", if (shizukuInstalled) "Aptikta, leidimas dar nesuteiktas" else "Neįdiegta")
                    StatusLine("Root", if (rootPresent) "Galimas su dvejetainiu su" else "Neaptiktas")
                    Text(
                        "Šis režimas pagal nutylėjimą išjungtas. Jis negali pažadėti prieigos prie Android/data visuose įrenginiuose dėl SELinux ir gamintojo ribojimų.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { SectionHeader("Programos atnaujinimas", BuildConfig.VERSION_NAME.removeSuffix("-debug")) }
        item {
            AppUpdateCard(
                state = updateState,
                onCheck = viewModel::checkForUpdates,
                onDownload = viewModel::downloadUpdate,
                onInstall = viewModel::installUpdate,
            )
        }

        item { SectionHeader("Privatumas", "Be reklamų") }
        item {
            InfoCard(
                "Vietiniai duomenys lieka įrenginyje",
                "Prisijungimų paslaptys šifruojamos Android Keystore. Analitika ir reklamos SDK nepridėti.",
                Icons.Rounded.Security,
            )
        }
    }

    removeSaf?.let { location ->
        AlertDialog(
            onDismissRequest = { removeSaf = null },
            title = { Text("Pašalinti pasirinktą vietą?") },
            text = { Text("Bus atšauktas AF File Manager ilgalaikis leidimas vietai „${location.title}“. Failai nebus trinami.") },
            confirmButton = {
                Button(onClick = { viewModel.removeSafLocation(location.uri); removeSaf = null }) { Text("Pašalinti") }
            },
            dismissButton = { TextButton(onClick = { removeSaf = null }) { Text("Atšaukti") } },
        )
    }

    if (showEncrypt && encryptTarget != null) {
        PasswordDialog(
            title = "Šifruoti ${encryptTarget?.name}",
            explanation = "Mažiausiai 8 ženklai. Pametus slaptafrazę failo atkurti nebus galima.",
            onDismiss = { showEncrypt = false },
            onConfirm = { password ->
                viewModel.encryptFile(requireNotNull(encryptTarget), password)
                showEncrypt = false
            },
        )
    }


    if (safBrowser.location != null) {
        SafBrowserDialog(
            state = safBrowser,
            selectedLocalPath = selectedEntry?.absolutePath,
            viewModel = viewModel,
            onDismiss = viewModel::closeSafBrowser,
        )
    }

    if (trashBrowser.open) {
        TrashBrowserDialog(
            state = trashBrowser,
            itemCount = trash.size,
            viewModel = viewModel,
            onDismiss = viewModel::closeTrashBrowser,
        )
    }
}

@Composable
private fun AppUpdateCard(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: (com.affilemanager.app.update.AppRelease) -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("AF File Manager ${BuildConfig.VERSION_NAME.removeSuffix("-debug")}", fontWeight = FontWeight.SemiBold)
                    Text("Saugūs leidimai iš viešos GitHub repozitorijos", style = MaterialTheme.typography.bodySmall)
                }
            }
            when (state) {
                AppUpdateState.Idle -> {
                    Text("Paleidus programą nauja versija tikrinama ne dažniau kaip kas 6 valandas. APK automatiškai siunčiamas tik nematuojamame tinkle.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCheck) { Text("Tikrinti dabar") }
                }
                AppUpdateState.Checking -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Tikrinamas naujausias GitHub leidimas…", style = MaterialTheme.typography.bodySmall)
                }
                is AppUpdateState.UpToDate -> {
                    Text("Įdiegta naujausia versija ${state.currentVersion}.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCheck) { Text("Tikrinti dar kartą") }
                }
                is AppUpdateState.Available -> {
                    Text("Galima versija ${state.release.version}.", fontWeight = FontWeight.SemiBold)
                    Text(state.release.notes.ifBlank { "Paskelbtas naujas stabilus leidimas." }, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Button(onClick = { onDownload(state.release) }) { Text("Atsisiųsti ir patikrinti") }
                }
                is AppUpdateState.Downloading -> {
                    val total = state.release.asset.sizeBytes.coerceAtLeast(1L)
                    LinearProgressIndicator(
                        progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${FileSystemRules.humanBytes(state.downloadedBytes)} / ${FileSystemRules.humanBytes(total)}", style = MaterialTheme.typography.bodySmall)
                }
                is AppUpdateState.Ready -> {
                    Text("Versija ${state.release.version} atsisiųsta ir patikrinta.", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.installPermissionRequired) "Android nustatymuose leiskite diegti iš AF File Manager, grįžkite ir spauskite „Diegti“." else "Diegimą dar turės patvirtinti Android sistemos lange.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onInstall) { Text("Diegti") }
                }
                is AppUpdateState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.release?.let { release ->
                            Button(onClick = { onDownload(release) }) { Text("Bandyti siųsti dar kartą") }
                        }
                        OutlinedButton(onClick = onCheck) { Text("Tikrinti dar kartą") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationCard(
    operation: OperationSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(operation.title, fontWeight = FontWeight.SemiBold)
                    Text(operation.currentName ?: operation.message ?: operation.status.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                when (operation.status) {
                    OperationStatus.RUNNING -> IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, contentDescription = "Pauzė") }
                    OperationStatus.PAUSED -> IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Tęsti") }
                    else -> Unit
                }
                if (operation.status == OperationStatus.RUNNING || operation.status == OperationStatus.PAUSED || operation.status == OperationStatus.QUEUED) {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Cancel, contentDescription = "Atšaukti") }
                }
                if (operation.retryable && operation.status !in setOf(OperationStatus.RUNNING, OperationStatus.PAUSED, OperationStatus.QUEUED)) {
                    IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, contentDescription = "Bandyti dar kartą") }
                }
            }
            val totalBytes = operation.totalBytes
            if (totalBytes != null && totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (operation.completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${FileSystemRules.humanBytes(operation.completedBytes)} / ${FileSystemRules.humanBytes(totalBytes)}", style = MaterialTheme.typography.labelSmall)
                val startedAt = operation.startedAtMillis
                if (startedAt != null && operation.completedBytes > 0 && operation.status == OperationStatus.RUNNING) {
                    val elapsedSeconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L) / 1_000.0)
                    val bytesPerSecond = (operation.completedBytes / elapsedSeconds).toLong().coerceAtLeast(1L)
                    val remainingSeconds = ((totalBytes - operation.completedBytes).coerceAtLeast(0L) / bytesPerSecond)
                    Text(
                        "${FileSystemRules.humanBytes(bytesPerSecond)}/s · liko apie ${remainingSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else if (operation.status == OperationStatus.RUNNING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (operation.errorCount > 0) {
                Text("Klaidų: ${operation.errorCount}. Galima bandyti dar kartą.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun InfoCard(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusLine(name: String, status: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    explanation: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val valid = password.length >= 8 && password == repeated
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(explanation, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Slaptafrazė") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = repeated,
                    onValueChange = { repeated = it },
                    label = { Text("Pakartokite") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(password.toCharArray())
                    password = ""
                    repeated = ""
                },
                enabled = valid,
            ) { Text("Šifruoti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Atšaukti") } },
    )
}
