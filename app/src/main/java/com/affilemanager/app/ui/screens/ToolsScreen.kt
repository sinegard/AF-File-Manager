package com.affilemanager.app.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.BuildConfig
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.advanced.CapabilityState
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.localization.AppLanguageOption
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AppColorPalette
import com.affilemanager.app.ui.theme.AppThemeMode
import com.affilemanager.app.ui.theme.AppearanceRules
import com.affilemanager.app.ui.theme.AppearanceSettings
import com.affilemanager.app.ui.theme.palettePreviewColors
import com.affilemanager.app.update.AppUpdateState
import java.io.File
import java.util.Locale

@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    contentPadding: PaddingValues,
    onAddSafLocation: () -> Unit,
    onToggleAppLock: (Boolean) -> Unit,
) {
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val trash by viewModel.trashItems.collectAsStateWithLifecycle()
    val safLocations by viewModel.safLocations.collectAsStateWithLifecycle()
    val safBrowser by viewModel.safBrowser.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val syncSchedules by viewModel.syncSchedules.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val appearanceSettings by viewModel.appearanceSettings.collectAsStateWithLifecycle()
    val advancedAccess by viewModel.advancedAccess.collectAsStateWithLifecycle()
    val afClipboard by viewModel.afClipboard.collectAsStateWithLifecycle()
    val active = viewModel.activePanelState()
    val selectedEntry = active.entries.singleOrNull { it.absolutePath in active.selectedPaths }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val displayLocale = configuration.locales[0]
    val interfaceLanguage = AppLanguageManager.normalizeLanguageTag(displayLocale.language)
    val languageOptions = remember(displayLocale) { AppLanguageManager.languageOptions(displayLocale) }
    val currentLanguage = remember(interfaceLanguage, languageOptions) {
        languageOptions.firstOrNull { it.tag == interfaceLanguage }
            ?: languageOptions.first { it.tag == AppLanguageManager.ENGLISH }
    }
    var encryptTarget by remember { mutableStateOf(selectedEntry) }
    var showEncrypt by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var removeSaf by remember { mutableStateOf<com.affilemanager.app.data.SafLocation?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).testTag("tools_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LText("Įrankiai ir saugumas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            LText("Jokių reklamų, sekimo SDK ar privalomos paskyros.", style = MaterialTheme.typography.bodySmall)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, contentDescription = null)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            LText("Kalba", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = currentLanguage.nativeName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(
                            onClick = { showLanguagePicker = true },
                            modifier = Modifier.testTag("change_language"),
                        ) { LText("Keisti kalbą") }
                    }
                    LText(
                        "Visi 59 kalbų paketai įtraukti į programą ir veikia neprisijungus.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            AppearanceSettingsCard(
                settings = appearanceSettings,
                onThemeMode = viewModel::setThemeMode,
                onPalette = viewModel::setColorPalette,
                onAmoledBlack = viewModel::setAmoledBlack,
            )
        }

        item {
            Card(
                onClick = { viewModel.openAfWorkflowCenter() },
                modifier = Modifier.fillMaxWidth().testTag("open_af_plans"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesomeMotion,
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        LText("AF planai ir operacijų istorija", fontWeight = FontWeight.Bold)
                        LText(
                            "Kopijuokite į kelias vietas, pirmiausia patikrinkite konfliktus, eksportuokite kvitą ir saugiai atšaukite.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        afClipboard?.let { LText("Kopijavimo rinkinyje: ${it.sources.size}", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
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
                TextButton(onClick = viewModel::dismissFinishedOperations) { LText("Paslėpti užbaigtas") }
            }
        }

        item { SectionHeader("Šiukšliadėžė", trash.size.toString()) }
        item {
            Card(
                onClick = viewModel::openTrashBrowser,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
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
                        LText("Atidaryti šiukšliadėžę", fontWeight = FontWeight.SemiBold)
                        LText(
                            if (trash.isEmpty()) "Šiukšliadėžė tuščia" else "${trash.size} elementų · galima atkurti arba išvalyti viską",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LText("Atidaryti", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item { SectionHeader("Pasirinktos ir debesijos vietos", safLocations.size.toString()) }
        item {
            FilledTonalButton(onClick = onAddSafLocation) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                LText("Pridėti per Android dokumentų sistemą", modifier = Modifier.padding(start = 8.dp))
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
                        Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti vietą"))
                    }
                }
            }
        }

        item { SectionHeader("Programos užraktas", if (appLockEnabled) "Įjungtas" else "Išjungtas") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        LText("Biometrinis arba įrenginio užraktas", fontWeight = FontWeight.SemiBold)
                        LText("Grįžus iš fono failų langas bus užrakintas. Keitimą patvirtina Android.", style = MaterialTheme.typography.bodySmall)
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
                            LText("${schedule.localRoot} ↔ ${schedule.remoteRoot}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            LText(
                                "Kas ${schedule.intervalHours} val. · ${if (schedule.unmeteredOnly) "tik Wi‑Fi/Ethernet" else "bet koks tinklas"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            schedule.lastStatus?.let { LText(it, style = MaterialTheme.typography.labelSmall) }
                        }
                        IconButton(onClick = { viewModel.removeSyncSchedule(schedule.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = uiText("Atšaukti tvarkaraštį"))
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
                            LText("AES-256-GCM failų šifravimas", fontWeight = FontWeight.SemiBold)
                            LText("Slaptafrazė neišsaugoma. Originalas automatiškai netrinamas.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = { encryptTarget = selectedEntry; showEncrypt = true },
                        enabled = selectedEntry?.isDirectory == false,
                    ) { LText("Šifruoti pasirinktą failą") }
                }
            }
        }

        item { SectionHeader("Pažengusio naudotojo režimas", "Neprivalomas") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LText("Apsaugotų Android aplankų prieiga", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        AdvancedAccessMode.entries.forEach { mode ->
                            FilterChip(
                                selected = advancedAccess.selectedMode == mode,
                                onClick = { viewModel.setAdvancedAccessMode(mode) },
                                label = { LText(advancedModeLabel(mode)) },
                                modifier = Modifier.testTag("advanced_mode_${mode.name.lowercase()}"),
                            )
                        }
                    }
                    StatusLine(
                        "Shizuku",
                        shizukuStatus(
                            managerDetected = advancedAccess.shizukuManagerDetected,
                            running = advancedAccess.shizukuRunning,
                            permission = advancedAccess.shizukuPermission,
                        ),
                    )
                    StatusLine("Root", capabilityLabel(advancedAccess.rootPermission))
                    StatusLine("Aktyvi prieiga", if (advancedAccess.connected) "Prisijungta · UID ${advancedAccess.serviceUid}" else "Neprisijungta")
                    advancedAccess.androidDataAccessible?.let { accessible ->
                        StatusLine("Android/data", if (accessible) "Pasiekiamas" else "Nepasiekiamas šiuo režimu")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::requestShizukuAccess,
                            enabled = !advancedAccess.connecting,
                        ) {
                            LText(
                                when {
                                    !advancedAccess.shizukuRunning -> "Patikrinti Shizuku"
                                    advancedAccess.shizukuPermission == CapabilityState.GRANTED -> "Jungtis per Shizuku"
                                    else -> "Suteikti Shizuku leidimą"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::requestRootAccess,
                            enabled = advancedAccess.rootPermission != CapabilityState.UNAVAILABLE && !advancedAccess.connecting,
                        ) { LText("Jungtis per root") }
                    }
                    Button(
                        onClick = viewModel::openAdvancedBrowser,
                        enabled = advancedAccess.connected && !advancedAccess.connecting,
                        modifier = Modifier.testTag("open_android_data"),
                    ) {
                        Icon(Icons.Rounded.FolderSpecial, contentDescription = null)
                        LText("Atidaryti Android/data", modifier = Modifier.padding(start = 6.dp))
                    }
                    if (advancedAccess.connecting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    advancedAccess.error?.let { LText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    LText(
                        "Pagal nutylėjimą ši prieiga išjungta. Įprastas režimas neapeina Android ribojimų. Shizuku ir root naudojami tik pasirinkus šį režimą ir suteikus leidimą.",
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

    if (showLanguagePicker) {
        LanguagePickerDialog(
            selectedLanguage = interfaceLanguage,
            languages = languageOptions,
            onSelect = { language ->
                showLanguagePicker = false
                AppLanguageManager.setLanguage(context, language.tag)
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    removeSaf?.let { location ->
        AlertDialog(
            onDismissRequest = { removeSaf = null },
            title = { LText("Pašalinti pasirinktą vietą?") },
            text = { LText("Bus atšauktas AF File Manager ilgalaikis leidimas vietai „${location.title}“. Failai nebus trinami.") },
            confirmButton = {
                Button(onClick = { viewModel.removeSafLocation(location.uri); removeSaf = null }) { LText("Pašalinti") }
            },
            dismissButton = { TextButton(onClick = { removeSaf = null }) { LText("Atšaukti") } },
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

}

@Composable
private fun LanguagePickerDialog(
    selectedLanguage: String,
    languages: List<AppLanguageOption>,
    onSelect: (AppLanguageOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredLanguages = remember(query, languages) {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) {
            languages
        } else {
            languages.filter { language ->
                language.tag.contains(needle, ignoreCase = true) ||
                    language.nativeName.lowercase(Locale.ROOT).contains(needle) ||
                    language.displayName.lowercase(Locale.ROOT).contains(needle) ||
                    language.englishName.lowercase(Locale.ROOT).contains(needle)
            }
        }
    }

    AfModalDialog(
        title = "Pasirinkti kalbą",
        subtitle = "59 kalbos",
        icon = Icons.Rounded.Language,
        onDismissRequest = onDismiss,
        expandedContent = true,
        modifier = Modifier.testTag("language_picker"),
        actions = {
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("language_search"),
                    singleLine = true,
                    label = { LText("Kalbos paieška") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filteredLanguages, key = AppLanguageOption::tag) { language ->
                        FilledTonalButton(
                            onClick = { onSelect(language) },
                            modifier = Modifier.fillMaxWidth().testTag("language_option_${language.tag}"),
                        ) {
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(language.nativeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!language.nativeName.equals(language.displayName, ignoreCase = true)) {
                                    Text(
                                        language.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (language.tag == selectedLanguage) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                            }
                        }
                    }
                }
                if (filteredLanguages.isEmpty()) {
                    LText("Kalbų nerasta", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                LText(
                    "Anglų ir lietuvių vertimai peržiūrėti. Kitų kalbų pataisymai laukiami.",
                    style = MaterialTheme.typography.labelSmall,
                )
        }
    }
}

private fun advancedModeLabel(mode: AdvancedAccessMode): String = when (mode) {
    AdvancedAccessMode.OFF -> "Išjungta"
    AdvancedAccessMode.AUTO -> "Automatiškai"
    AdvancedAccessMode.SHIZUKU -> "Shizuku"
    AdvancedAccessMode.ROOT -> "Root"
}

private fun capabilityLabel(state: CapabilityState): String = when (state) {
    CapabilityState.UNAVAILABLE -> "Neaptikta"
    CapabilityState.AVAILABLE -> "Galima paprašyti leidimo"
    CapabilityState.GRANTED -> "Leidimas suteiktas"
    CapabilityState.DENIED -> "Leidimas atmestas"
}

private fun shizukuStatus(managerDetected: Boolean, running: Boolean, permission: CapabilityState): String = when {
    running -> capabilityLabel(permission)
    managerDetected -> "Programa aptikta, tarnyba nepaleista"
    else -> "Tarnyba nepaleista"
}

@Composable
private fun AppearanceSettingsCard(
    settings: AppearanceSettings,
    onThemeMode: (AppThemeMode) -> Unit,
    onPalette: (AppColorPalette) -> Unit,
    onAmoledBlack: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("appearance_settings"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LText("Išvaizda", fontWeight = FontWeight.SemiBold)
            LText("Temos režimas", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        AppThemeMode.SYSTEM -> "Sistema"
                        AppThemeMode.LIGHT -> "Šviesi"
                        AppThemeMode.DARK -> "Tamsi"
                    }
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        label = { LText(label) },
                        modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}"),
                    )
                }
            }

            LText("Spalvų paletė", style = MaterialTheme.typography.labelLarge)
            AppColorPalette.entries.chunked(2).forEach { paletteRow ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paletteRow.forEach { palette ->
                        val supported = AppearanceRules.paletteSupported(palette, Build.VERSION.SDK_INT)
                        val selected = settings.colorPalette == palette
                        Card(
                            onClick = { onPalette(palette) },
                            enabled = supported,
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                    else Modifier,
                                )
                                .testTag("palette_${palette.name.lowercase()}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    palettePreviewColors(palette).forEach { color ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(24.dp)
                                                .background(color, RoundedCornerShape(6.dp)),
                                        )
                                    }
                                }
                                LText(
                                    when (palette) {
                                        AppColorPalette.DEFAULT -> "Numatytoji"
                                        AppColorPalette.DYNAMIC -> "Dinaminė"
                                        AppColorPalette.CATPPUCCIN -> "Catppuccin"
                                        AppColorPalette.ORANGE -> "Oranžinė"
                                        AppColorPalette.MATERIAL_BLUE -> "Material mėlyna"
                                        AppColorPalette.AURA -> "Aura"
                                        AppColorPalette.TOKYO -> "Tokyo"
                                        AppColorPalette.YIN_YANG -> "Yin Yang"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                if (!supported) {
                                    LText("Reikia Android 12+", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (paletteRow.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    LText("AMOLED juodas režimas", fontWeight = FontWeight.Medium)
                    LText("Tamsioje temoje fonas tampa visiškai juodas.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings.amoledBlack,
                    onCheckedChange = onAmoledBlack,
                    modifier = Modifier.testTag("amoled_black"),
                )
            }
        }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    LText("AF File Manager ${BuildConfig.VERSION_NAME.removeSuffix("-debug")}", fontWeight = FontWeight.SemiBold)
                    LText("Saugūs leidimai iš viešos GitHub repozitorijos", style = MaterialTheme.typography.bodySmall)
                }
            }
            when (state) {
                AppUpdateState.Idle -> {
                    LText("Paleidus programą nauja versija tikrinama ne dažniau kaip kas 6 valandas. APK automatiškai siunčiamas tik nematuojamame tinkle.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCheck) { LText("Tikrinti dabar") }
                }
                AppUpdateState.Checking -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    LText("Tikrinamas naujausias GitHub leidimas…", style = MaterialTheme.typography.bodySmall)
                }
                is AppUpdateState.UpToDate -> {
                    LText("Įdiegta naujausia versija ${state.currentVersion}.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCheck) { LText("Tikrinti dar kartą") }
                }
                is AppUpdateState.Available -> {
                    LText("Galima versija ${state.release.version}.", fontWeight = FontWeight.SemiBold)
                    if (state.release.notes.isBlank()) {
                        LText("Paskelbtas naujas stabilus leidimas.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(state.release.notes, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { onDownload(state.release) }) { LText("Atsisiųsti ir patikrinti") }
                }
                is AppUpdateState.Downloading -> {
                    val total = state.release.asset.sizeBytes.coerceAtLeast(1L)
                    LinearProgressIndicator(
                        progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LText("${FileSystemRules.humanBytes(state.downloadedBytes)} / ${FileSystemRules.humanBytes(total)}", style = MaterialTheme.typography.bodySmall)
                }
                is AppUpdateState.Ready -> {
                    LText("Versija ${state.release.version} atsisiųsta ir patikrinta.", fontWeight = FontWeight.SemiBold)
                    LText(
                        if (state.installPermissionRequired) "Android nustatymuose leiskite diegti iš AF File Manager, grįžkite ir spauskite „Diegti“." else "Diegimą dar turės patvirtinti Android sistemos lange.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onInstall) { LText("Diegti") }
                }
                is AppUpdateState.Failed -> {
                    LText(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.release?.let { release ->
                            Button(onClick = { onDownload(release) }) { LText("Bandyti siųsti dar kartą") }
                        }
                        OutlinedButton(onClick = onCheck) { LText("Tikrinti dar kartą") }
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
                    LText(operation.title, fontWeight = FontWeight.SemiBold)
                    operation.currentName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    } ?: LText(operation.message ?: operation.status.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                when (operation.status) {
                    OperationStatus.RUNNING -> IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, contentDescription = uiText("Pauzė")) }
                    OperationStatus.PAUSED -> IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, contentDescription = uiText("Tęsti")) }
                    else -> Unit
                }
                if (operation.status == OperationStatus.RUNNING || operation.status == OperationStatus.PAUSED || operation.status == OperationStatus.QUEUED) {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Cancel, contentDescription = uiText("Atšaukti")) }
                }
                if (operation.retryable && operation.status !in setOf(OperationStatus.RUNNING, OperationStatus.PAUSED, OperationStatus.QUEUED)) {
                    IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, contentDescription = uiText("Bandyti dar kartą")) }
                }
            }
            val totalBytes = operation.totalBytes
            if (totalBytes != null && totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (operation.completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LText("${FileSystemRules.humanBytes(operation.completedBytes)} / ${FileSystemRules.humanBytes(totalBytes)}", style = MaterialTheme.typography.labelSmall)
                val startedAt = operation.startedAtMillis
                if (startedAt != null && operation.completedBytes > 0 && operation.status == OperationStatus.RUNNING) {
                    val elapsedSeconds = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L) / 1_000.0)
                    val bytesPerSecond = (operation.completedBytes / elapsedSeconds).toLong().coerceAtLeast(1L)
                    val remainingSeconds = ((totalBytes - operation.completedBytes).coerceAtLeast(0L) / bytesPerSecond)
                    LText(
                        "${FileSystemRules.humanBytes(bytesPerSecond)}/s · liko apie ${remainingSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else if (operation.status == OperationStatus.RUNNING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (operation.errorCount > 0) {
                LText("Klaidų: ${operation.errorCount}. Galima bandyti dar kartą.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        LText(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun InfoCard(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                LText(title, fontWeight = FontWeight.SemiBold)
                LText(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusLine(name: String, status: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        LText(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        LText(status, style = MaterialTheme.typography.bodySmall)
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
        title = { LText(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LText(explanation, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { LText("Slaptafrazė") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = repeated,
                    onValueChange = { repeated = it },
                    label = { LText("Pakartokite") },
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
            ) { LText("Šifruoti") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { LText("Atšaukti") } },
    )
}
