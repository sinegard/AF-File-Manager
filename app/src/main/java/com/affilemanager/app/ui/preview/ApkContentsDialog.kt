package com.affilemanager.app.ui.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.archive.ArchiveBrowserIndex
import com.affilemanager.app.archive.ArchiveEngine
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** APK-family ZIP containers are browsed without extracting, executing or rewriting signed bytes. */
@Composable
internal fun ApkContentsDialog(file: File, onDismiss: () -> Unit) {
    var currentPath by remember(file.absolutePath) { mutableStateOf("") }
    val index by produceState<Result<ArchiveBrowserIndex>?>(null, file.absolutePath, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching { ArchiveBrowserIndex.from(ArchiveEngine().list(file)) }
        }
    }
    val navigateBack: () -> Unit = {
        if (currentPath.isEmpty()) onDismiss()
        else currentPath = ArchiveBrowserIndex.parentOf(currentPath)
    }
    AfModalDialog(
        title = file.name,
        icon = Icons.Rounded.Archive,
        translateTitle = false,
        subtitle = "Tik skaitymui",
        expandedContent = true,
        onDismissRequest = navigateBack,
        onClose = onDismiss,
        modifier = Modifier.testTag("apk_contents_dialog"),
        actions = { TextButton(onClick = onDismiss) { LText("Uždaryti") } },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = navigateBack, modifier = Modifier.testTag("apk_contents_back")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Grįžti"))
                }
                Text(currentPath.ifBlank { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            val loaded = index
            when {
                loaded == null -> CircularProgressIndicator()
                loaded.isFailure -> LText(loaded.exceptionOrNull()?.message ?: "Archyvas nepasiekiamas",
                    color = MaterialTheme.colorScheme.error)
                else -> {
                    val children = requireNotNull(loaded.getOrNull()).children(currentPath)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("apk_contents_entries")) {
                        items(children, key = { it.path }) { entry ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = entry.directory) { currentPath = entry.path }
                                    .padding(horizontal = 8.dp, vertical = 9.dp)
                                    .testTag("apk_contents_entry"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
                                    contentDescription = null, modifier = Modifier.size(30.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!entry.directory && entry.sizeBytes >= 0) Text(
                                        FileSystemRules.humanBytes(entry.sizeBytes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
