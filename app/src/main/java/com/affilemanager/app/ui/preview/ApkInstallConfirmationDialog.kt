package com.affilemanager.app.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.affilemanager.app.apk.ApkInstallMetadata
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AfButton as Button
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ApkInstallConfirmationDialog(
    cacheKey: Any,
    load: suspend () -> ApkInstallMetadata,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    installedApp: Boolean = false,
) {
    val result by produceState<Result<ApkInstallMetadata>?>(null, cacheKey) {
        value = withContext(Dispatchers.IO) { runCatching { load() } }
    }
    AfModalDialog(
        title = if (installedApp) "Informacija" else "Patvirtinti programos diegimą",
        icon = if (installedApp) Icons.Rounded.OpenInNew else Icons.Rounded.InstallMobile,
        onDismissRequest = onDismiss, modifier = Modifier.testTag(if (installedApp) "installed_app_information" else "apk_install_confirmation"),
        expandedContent = true,
        actions = {
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            Button(onClick = onInstall, enabled = result?.isSuccess == true,
                modifier = Modifier.testTag(if (installedApp) "open_installed_app" else "confirm_apk_install")) { LText(if (installedApp) "Atidaryti" else "Diegti") }
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                result == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                result?.isFailure == true -> LText(result?.exceptionOrNull()?.message ?: "APK informacija nepasiekiama",
                    color = MaterialTheme.colorScheme.error)
                else -> result?.getOrNull()?.let { info ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        info.icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(64.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(info.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(info.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    MetadataLine(if (installedApp) "Versija" else "Diegiama versija", info.candidateVersion)
                    if (!installedApp) MetadataLine("Įdiegta versija", info.installedVersion ?: uiText("Neįdiegta"))
                    MetadataLine("Failo dydis", FileSystemRules.humanBytes(info.fileBytes))
                    MetadataLine("Minimali Android API", info.minimumSdk?.toString() ?: uiText("Nežinoma"))
                    MetadataLine("Tikslinė Android API", info.targetSdk?.toString() ?: uiText("Nežinoma"))
                    MetadataLine("Procesoriaus architektūros", info.abis.ifEmpty { listOf(uiText("Universalus / nėra vietinių bibliotekų")) }.joinToString())
                    LText("APK nenurodo patikimos maksimalios Android versijos; rodoma kūrėjo pasirinkta tikslinė API.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!installedApp) LText("Diegimą visada patvirtina Android sistema. Programa negali jo atlikti tyliai.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        LText(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
