package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileSelectionSummary
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat
import com.affilemanager.app.ui.localization.uiText
import java.text.DateFormat
import java.util.Date

@Composable
fun FileInfoDialog(entry: FileEntry, onDismiss: () -> Unit) {
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT)
    AfModalDialog(
        title = entry.name,
        translateTitle = false,
        icon = Icons.Rounded.Info,
        modifier = Modifier.testTag("file_info_dialog"),
        onDismissRequest = onDismiss,
        showFooter = false,
        actions = {},
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoLine("Tipas", if (entry.isDirectory) uiText("Aplankas") else entry.extension.uppercase().ifBlank { entry.kind.name })
            if (!entry.isDirectory || entry.sizeBytes > 0L) InfoLine("Dydis", FileSystemRules.humanBytes(entry.sizeBytes))
            if (entry.modifiedAtMillis > 0L) InfoLine("Pakeista", dateFormat.format(Date(entry.modifiedAtMillis)))
            entry.packageName?.let { InfoLine("Paketas", it) }
            entry.appVersionName?.takeIf(String::isNotBlank)?.let { InfoLine("Versija", it) }
            if (entry.packageName != null) InfoLine("Programos tipas", uiText(if (entry.isSystemApp) "Sisteminė" else "Naudotojo"))
            InfoLine(
                "Prieiga",
                listOfNotNull(
                    uiText("Skaitoma").takeIf { entry.isReadable },
                    uiText("Rašoma").takeIf { entry.isWritable },
                ).ifEmpty { listOf(uiText("Neprieinama")) }.joinToString(" · "),
            )
            LText("Kelias", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(entry.absolutePath, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun FileInfoDialog(
    entries: List<FileEntry>,
    loadSummary: suspend (Collection<String>) -> Result<FileSelectionSummary>,
    onDismiss: () -> Unit,
) {
    val stableEntries = remember(entries) { entries.distinctBy(FileEntry::absolutePath) }
    val paths = remember(stableEntries) { stableEntries.map(FileEntry::absolutePath) }
    val single = stableEntries.singleOrNull()
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT)
    var loading by remember(paths) { mutableStateOf(true) }
    var summary by remember(paths) { mutableStateOf<FileSelectionSummary?>(null) }
    var error by remember(paths) { mutableStateOf<String?>(null) }

    LaunchedEffect(paths) {
        loading = true
        summary = null
        error = null
        loadSummary(paths).fold(
            onSuccess = { summary = it },
            onFailure = { error = it.message ?: "Informacijos apskaičiuoti nepavyko" },
        )
        loading = false
    }

    AfModalDialog(
        title = single?.name ?: "Informacija",
        translateTitle = single == null,
        icon = Icons.Rounded.Info,
        modifier = Modifier.testTag("file_info_dialog"),
        onDismissRequest = onDismiss,
        showFooter = false,
        actions = {},
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                if (single == null) InfoLine("Pasirinkta", stableEntries.size.toString())
                single?.let { entry ->
                    InfoLine("Tipas", if (entry.isDirectory) uiText("Aplankas") else entry.extension.uppercase().ifBlank { entry.kind.name })
                    if (entry.modifiedAtMillis > 0L) InfoLine("Pakeista", dateFormat.format(Date(entry.modifiedAtMillis)))
                    entry.packageName?.let { InfoLine("Paketas", it) }
                    entry.appVersionName?.takeIf(String::isNotBlank)?.let { InfoLine("Versija", it) }
                    if (entry.packageName != null) InfoLine("Programos tipas", uiText(if (entry.isSystemApp) "Sisteminė" else "Naudotojo"))
                    InfoLine(
                        "Prieiga",
                        listOfNotNull(
                            uiText("Skaitoma").takeIf { entry.isReadable },
                            uiText("Rašoma").takeIf { entry.isWritable },
                        ).ifEmpty { listOf(uiText("Neprieinama")) }.joinToString(" · "),
                    )
                }
                when {
                    loading -> Row(
                        modifier = Modifier.fillMaxWidth().testTag("file_info_loading"),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    error != null -> LText(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("file_info_error"),
                    )
                    summary != null -> summary?.let { result ->
                        InfoLine("Failai", result.fileCount.toString(), Modifier.testTag("file_info_files"))
                        InfoLine("Aplankai", result.folderCount.toString(), Modifier.testTag("file_info_folders"))
                        InfoLine("Dydis", FileSystemRules.humanBytes(result.totalBytes), Modifier.testTag("file_info_size"))
                        if (!result.complete) {
                            LText(
                                "Rodomas dalinis turinys arba daliniai aplankų dydžiai, nes pasiekta saugi skenavimo riba.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("file_info_partial"),
                            )
                        }
                    }
                }
                if (single != null) {
                    LText("Kelias", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(single.absolutePath, style = MaterialTheme.typography.bodySmall)
                }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LText(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
