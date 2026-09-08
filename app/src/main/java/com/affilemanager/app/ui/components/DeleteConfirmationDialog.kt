package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileSelectionSummary
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.theme.AfButton as Button

@Composable
internal fun DeleteConfirmationDialog(
    names: List<String>,
    permanent: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    loadSummary: (suspend () -> Result<FileSelectionSummary>)? = null,
    title: String = if (permanent) "Ištrinti visam laikui?" else "Perkelti į šiukšlinę?",
    confirmLabel: String = if (permanent) "Ištrinti visam laikui" else "Perkelti",
    explanation: String = if (permanent) "Šio veiksmo nebus galima atšaukti." else
        "Elementus bus galima atkurti iš AF File Manager šiukšlinės.",
    fallbackFiles: Int = 0,
    fallbackFolders: Int = 0,
    fallbackBytes: Long = 0,
    confirmTestTag: String = "confirm_delete",
) {
    val stableNames = remember(names) { names.map(String::trim).filter(String::isNotEmpty).distinct().take(10_000) }
    var loading by remember(stableNames, loadSummary) { mutableStateOf(loadSummary != null) }
    var summary by remember(stableNames, loadSummary) { mutableStateOf<FileSelectionSummary?>(null) }
    var summaryError by remember(stableNames, loadSummary) { mutableStateOf<String?>(null) }
    LaunchedEffect(stableNames, loadSummary) {
        if (loadSummary == null) return@LaunchedEffect
        loading = true
        loadSummary().fold(onSuccess = { summary = it }, onFailure = { summaryError = it.message ?: "Informacijos apskaičiuoti nepavyko" })
        loading = false
    }
    AfModalDialog(
        title = title,
        icon = if (permanent) Icons.Rounded.DeleteForever else Icons.Rounded.DeleteOutline,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("delete_confirmation_dialog"),
        actions = {
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            Button(
                onClick = onConfirm,
                enabled = !loading,
                modifier = Modifier.testTag(confirmTestTag),
            ) { LText(confirmLabel) }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            when {
                stableNames.size == 1 -> {
                    LText("Pavadinimas", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stableNames.single(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("delete_name"))
                }
                stableNames.isNotEmpty() -> {
                    LText("Pasirinkta: ${stableNames.size}", style = MaterialTheme.typography.titleSmall)
                    Text(stableNames.take(3).joinToString("\n"), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                summary != null -> summary?.let { value ->
                    DeleteSummaryRows(value.fileCount, value.folderCount, value.totalBytes)
                    if (!value.complete) LText("Dalis turinio neįskaičiuota, nes pasiekta saugi skenavimo riba.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    DeleteSummaryRows(fallbackFiles, fallbackFolders, fallbackBytes)
                    summaryError?.let { LText(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error) }
                }
            }
            LText(explanation, style = MaterialTheme.typography.bodySmall,
                color = if (permanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeleteSummaryRows(files: Int, folders: Int, bytes: Long) {
    LText("Failai: $files", modifier = Modifier.testTag("delete_file_count"))
    LText("Aplankai: $folders", modifier = Modifier.testTag("delete_folder_count"))
    LText("Dydis: ${FileSystemRules.humanBytes(bytes.coerceAtLeast(0))}", modifier = Modifier.testTag("delete_size"))
}
