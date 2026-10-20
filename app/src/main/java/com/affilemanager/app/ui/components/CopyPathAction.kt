package com.affilemanager.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.affilemanager.app.core.CopyPathRules
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

@Composable
internal fun CopyPathAction(paths: Collection<String>, menu: Boolean = false, onCopied: () -> Unit = {}) {
    if (!featureVisible(com.affilemanager.app.data.OptionalFeature.FILE_COPY_PATH)) return
    val context = LocalContext.current
    val uriOnly = paths.size == 1 && paths.first().startsWith("content://")
    val label = if (uriOnly) uiText("Kopijuoti") + " URI" else uiText("Kopijuoti kelią") + " (POSIX)"
    val error = uiText("Kopijuoti nepavyko")
    val invalidName = uiText("Pavadinime yra neleistinų ženklų")
    val tooLarge = uiText("Iškarpinės turinys viršija 64 KiB terminalo įklijavimo ribą")
    val copy = {
        runCatching {
            val quoted = CopyPathRules.quotedArguments(paths)
            // A document-provider URI is not a filesystem path. Keep a single
            // URI directly reusable in the address field or another app.
            val text = if (uriOnly) paths.first() else quoted
            requireNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("AF paths", text))
            onCopied()
        }.onFailure { failure ->
            val detail = when (failure.message) {
                "Pavadinime yra neleistinų ženklų" -> invalidName
                "Tekstas per ilgas" -> tooLarge
                else -> error
            }
            Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
        }
        Unit
    }
    if (menu) DropdownMenuItem(text = { androidx.compose.material3.Text(label) }, onClick = copy,
        leadingIcon = { Icon(Icons.Rounded.Link, null) }, modifier = Modifier.testTag("copy_quoted_paths"))
    else IconButton(onClick = copy, enabled = paths.isNotEmpty(), modifier = Modifier.testTag("copy_quoted_paths")) {
        Icon(Icons.Rounded.Link, label)
    }
}
