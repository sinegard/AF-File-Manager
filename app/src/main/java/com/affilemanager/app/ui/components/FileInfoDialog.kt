package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat
import com.affilemanager.app.ui.localization.uiText
import java.text.DateFormat
import java.util.Date

@Composable
fun FileInfoDialog(entry: FileEntry, onDismiss: () -> Unit) {
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT)
    AlertDialog(
        modifier = Modifier.testTag("file_info_dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { LText("Uždaryti") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LText(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
