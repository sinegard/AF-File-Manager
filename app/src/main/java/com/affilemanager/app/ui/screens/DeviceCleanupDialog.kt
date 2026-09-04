package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.cleanup.DeviceCleanupApp
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.ui.DeviceCleanupUiState
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.AfPullToRefresh
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat
import java.text.DateFormat
import java.util.Date

private enum class DeviceCleanupPage { UNUSED_APPS, APP_CACHE, OLD_MEDIA }

@Composable
internal fun DeviceCleanupDialog(
    state: DeviceCleanupUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onGrantUsageAccess: () -> Unit,
    onOpenAppSettings: (String) -> Unit,
    onUninstall: (String) -> Unit,
    oldMediaScanned: Boolean,
    oldMediaLoading: Boolean,
    oldMediaCount: Int,
    onRefreshOldMedia: () -> Unit,
    onReviewOldMedia: () -> Unit,
) {
    if (!state.open) return
    var page by remember { mutableStateOf(DeviceCleanupPage.UNUSED_APPS) }
    val snapshot = state.snapshot
    val apps = when (page) {
        DeviceCleanupPage.UNUSED_APPS -> snapshot?.unusedApps.orEmpty()
        DeviceCleanupPage.APP_CACHE -> snapshot?.cachedApps.orEmpty()
        DeviceCleanupPage.OLD_MEDIA -> emptyList()
    }
    AfModalDialog(
        title = "Įrenginio valymas",
        subtitle = "Tik peržiūra — AF nieko nešalina be jūsų patvirtinimo",
        icon = Icons.Rounded.CleaningServices,
        onDismissRequest = onDismiss,
        expandedContent = true,
        modifier = Modifier.testTag("device_cleanup_dialog"),
        actions = {
            TextButton(
                onClick = if (page == DeviceCleanupPage.OLD_MEDIA) onRefreshOldMedia else onRefresh,
                enabled = if (page == DeviceCleanupPage.OLD_MEDIA) !oldMediaLoading else !state.loading,
            ) { LText("Atnaujinti") }
            TextButton(onClick = onDismiss) { LText("Uždaryti") }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = page == DeviceCleanupPage.UNUSED_APPS,
                    onClick = { page = DeviceCleanupPage.UNUSED_APPS },
                    label = { LText("Nenaudojamos programos") },
                )
                FilterChip(
                    selected = page == DeviceCleanupPage.APP_CACHE,
                    onClick = { page = DeviceCleanupPage.APP_CACHE },
                    label = { LText("Programų talpykla") },
                )
                FilterChip(
                    selected = page == DeviceCleanupPage.OLD_MEDIA,
                    onClick = { page = DeviceCleanupPage.OLD_MEDIA },
                    label = { LText("Sena medija ir ekrano kopijos") },
                )
            }
            if ((state.loading && page != DeviceCleanupPage.OLD_MEDIA) ||
                (oldMediaLoading && page == DeviceCleanupPage.OLD_MEDIA)
            ) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (page == DeviceCleanupPage.OLD_MEDIA) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LText("Sena medija ir ekrano kopijos", style = MaterialTheme.typography.titleMedium)
                        LText(
                            when {
                                !oldMediaScanned -> "Pirmiausia nuskaitykite saugyklą"
                                oldMediaCount == 0 -> "Senų medijos failų nerasta"
                                else -> "Rasta $oldMediaCount failų"
                            },
                        )
                        Button(
                            onClick = if (oldMediaScanned) onReviewOldMedia else onRefreshOldMedia,
                            enabled = !oldMediaLoading && (!oldMediaScanned || oldMediaCount > 0),
                        ) {
                            LText(if (oldMediaScanned) "Peržiūrėti seną mediją" else "Nuskaityti saugyklą")
                        }
                        LText(
                            "Failai pirmiausia atveriami peržiūrai; jie nešalinami automatiškai.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else if (state.loading && snapshot == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LText("Nuskaitomos programos…")
                }
            } else if (state.error != null && snapshot == null) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LText(state.error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRefresh) { LText("Bandyti dar kartą") }
                    }
                }
            } else if (snapshot?.usageAccessGranted != true) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LText("Norint apskaičiuoti nenaudojamas programas ir teikėjo nurodytus talpyklos dydžius, reikia Android naudojimo prieigos.")
                        Button(onClick = onGrantUsageAccess) { LText("Atidaryti naudojimo prieigos nustatymus") }
                        LText("AF nevalo kitų programų talpyklos ir nešalina programų be Android patvirtinimo.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                state.error?.let { LText(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (page == DeviceCleanupPage.APP_CACHE && snapshot.cacheSizesAvailable.not()) {
                    LText(
                        "Ši Android versija neatskleidžia kitų programų talpyklos dydžių. Peržiūrėkite juos programos sistemos nustatymuose.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AfPullToRefresh(
                    isRefreshing = state.loading,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    testTag = "pull_to_refresh_device_cleanup",
                ) {
                    if (apps.isEmpty() && !state.loading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            LText(if (page == DeviceCleanupPage.UNUSED_APPS) "Nė viena programa neatitinka 90 dienų taisyklės" else "Nėra pasiekiamų talpyklos dydžių")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(apps, key = DeviceCleanupApp::packageName) { app ->
                                DeviceCleanupAppRow(
                                    app = app,
                                    showCache = page == DeviceCleanupPage.APP_CACHE,
                                    onOpenSettings = { onOpenAppSettings(app.packageName) },
                                    onUninstall = { onUninstall(app.packageName) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCleanupAppRow(
    app: DeviceCleanupApp,
    showCache: Boolean,
    onOpenSettings: () -> Unit,
    onUninstall: () -> Unit,
) {
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT)
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LText(
                    if (showCache) {
                        app.cacheBytes?.let(FileSystemRules::humanBytes) ?: "Talpyklos dydis nepasiekiamas"
                    } else {
                        app.lastUsedMillis?.let { dateFormat.format(Date(it)) }
                            ?: "Nėra užregistruoto naudojimo per pasiekiamą laikotarpį"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = null) }
            if (!showCache) {
                IconButton(onClick = onUninstall) { Icon(Icons.Rounded.DeleteForever, contentDescription = null) }
            }
        }
    }
}
