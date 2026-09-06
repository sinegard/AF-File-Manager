package com.affilemanager.app.ui.preview

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.apk.SplitApkArchive
import com.affilemanager.app.apk.SplitApkInstallActivity
import com.affilemanager.app.apk.SplitApkPlan
import com.affilemanager.app.ui.localization.LText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun SplitApkPreview(file: File, onExtract: () -> Unit) {
    val context = LocalContext.current
    val result by produceState<Result<SplitApkPlan>?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { SplitApkArchive.plan(file) } }
    }
    var selected by remember(file.path) { mutableStateOf<Set<String>?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(14.dp).testTag("split_apk_preview")) {
        LText("APK rinkinys", style = MaterialTheme.typography.titleLarge)
        LText("Diegiamos originalios pasirašytos dalys. Jų jungimas į vieną APK pakeistų parašą.", style = MaterialTheme.typography.bodySmall)
        val plan = result?.getOrNull()
        val chosen = selected ?: plan?.parts?.map { it.name }?.toSet().orEmpty()
        OutlinedButton(onClick = onExtract, modifier = Modifier.testTag("split_apk_extract")) { LText("Išpakuoti originalias dalis") }
        if (result == null) CircularProgressIndicator()
        result?.exceptionOrNull()?.let { LText(it.message ?: "Netinkamas APK rinkinys", color = MaterialTheme.colorScheme.error) }
        if (plan != null) {
            if (plan.hasExtraData) LText("Papildomi OBB duomenys automatiškai nediegiami. Juos galima išpakuoti atskirai.")
            Button(onClick = {
                runCatching {
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else context.startActivity(Intent(context, SplitApkInstallActivity::class.java)
                        .putExtra("archive", file.path).putStringArrayListExtra("parts", ArrayList(chosen)))
                }.onFailure { error = it.message ?: "Diegti nepavyko" }
            }, modifier = Modifier.testTag("split_apk_install")) { LText("Atidaryti diegimo lange") }
            error?.let { LText(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(plan.parts, key = { it.name }) { part ->
                    val required = part == plan.base
                    Row(Modifier.fillMaxWidth().clickable(enabled = !required) {
                        selected = if (part.name in chosen) chosen - part.name else chosen + part.name
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = part.name in chosen, enabled = !required,
                            onCheckedChange = { enabled -> selected = if (enabled) chosen + part.name else chosen - part.name })
                        Text(part.name, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
