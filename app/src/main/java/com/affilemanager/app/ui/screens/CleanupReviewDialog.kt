package com.affilemanager.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SimilarImageGroup
import com.affilemanager.app.model.StorageAnalysis
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import java.io.File
import java.text.NumberFormat

internal enum class CleanupCategory {
    TYPE_USAGE,
    LARGEST_FOLDERS,
    LARGE,
    OLDEST,
    EMPTY_FOLDERS,
    DUPLICATES,
    PACKAGES,
    SIMILAR_IMAGES,
}

private data class CleanupCandidate(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val directory: Boolean = false,
    val groupLabel: String? = null,
)

@Composable
internal fun CleanupReviewDialog(
    analysis: StorageAnalysis,
    duplicates: List<DuplicateGroup>,
    similarImages: List<SimilarImageGroup>,
    similarImagesRunning: Boolean,
    similarImagesAnalyzed: Boolean,
    similarImagesError: String?,
    initialCategory: CleanupCategory,
    analysisRootPath: String?,
    onAnalyzeSimilarImages: () -> Unit,
    onMoveToTrash: (Set<String>) -> Unit,
    onOpenLocation: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    var selected by remember(analysis) { mutableStateOf(emptySet<String>()) }
    var confirmTrash by remember { mutableStateOf(false) }
    val candidates = remember(category, analysis, duplicates, similarImages, analysisRootPath) {
        cleanupCandidates(category, analysis, duplicates, similarImages, analysisRootPath)
    }
    val selectedBytes = remember(selected, analysis, duplicates, similarImages, analysisRootPath) {
        allCleanupCandidates(analysis, duplicates, similarImages, analysisRootPath)
            .filter { it.path in selected }
            .distinctBy(CleanupCandidate::path)
            .sumOf(CleanupCandidate::sizeBytes)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Uždaryti"))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        LText("Saugaus valymo peržiūra", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        LText("Nieko nepasirenkama ir netrinama automatiškai", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    CleanupCategory.entries.forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { LText(cleanupCategoryLabel(item)) },
                            leadingIcon = { Icon(cleanupCategoryIcon(item), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                if (category != CleanupCategory.TYPE_USAGE) {
                    Card(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LText("Pažymėta: ${selected.size}", fontWeight = FontWeight.SemiBold)
                            LText("Galima atlaisvinti: ${FileSystemRules.humanBytes(selectedBytes)}", style = MaterialTheme.typography.bodySmall)
                            LText("Pasirinkti elementai bus perkelti į atkuriamą AF File Manager šiukšlinę.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (category == CleanupCategory.TYPE_USAGE) {
                    val maximum = analysis.typeUsage.maxOfOrNull { it.sizeBytes }?.coerceAtLeast(1L) ?: 1L
                    LazyColumn(modifier = Modifier.weight(1f).testTag("analysis_type_usage")) {
                        items(analysis.typeUsage, key = { it.kind }) { usage ->
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    LText(cleanupKindLabel(usage.kind), fontWeight = FontWeight.SemiBold)
                                    LText("${FileSystemRules.humanBytes(usage.sizeBytes)} · ${usage.fileCount}", style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(
                                    progress = { usage.sizeBytes.toFloat() / maximum.toFloat() },
                                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                } else if (category == CleanupCategory.SIMILAR_IMAGES && !similarImagesAnalyzed) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (similarImagesRunning) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator()
                                LText("Lyginamos nuotraukos…")
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LText("Panašumas tikrinamas įrenginyje. Tai gali užtrukti, todėl analizė paleidžiama tik paprašius.")
                                OutlinedButton(onClick = onAnalyzeSimilarImages) { LText("Ieškoti panašių nuotraukų") }
                            }
                        }
                    }
                } else if (candidates.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LText(if (category == CleanupCategory.SIMILAR_IMAGES) "Panašių nuotraukų grupių nerasta" else "Šioje grupėje elementų nerasta")
                            similarImagesError?.let { LText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("cleanup_candidates"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(candidates, key = CleanupCandidate::path) { candidate ->
                            CleanupCandidateRow(
                                candidate = candidate,
                                totalBytes = analysis.totalBytes,
                                selected = candidate.path in selected,
                                onToggle = {
                                    selected = if (candidate.path in selected) selected - candidate.path else selected + candidate.path
                                },
                                onOpen = { onOpenLocation(candidate.path, candidate.directory) },
                            )
                        }
                    }
                }

                if (category != CleanupCategory.TYPE_USAGE) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { selected = emptySet() }, enabled = selected.isNotEmpty()) { LText("Atžymėti visus") }
                        Button(onClick = { confirmTrash = true }, enabled = selected.isNotEmpty()) {
                            LText("Perkelti į šiukšlinę (${selected.size})")
                        }
                    }
                }
            }
        }
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { LText("Perkelti pasirinktus elementus į šiukšlinę?") },
            text = { LText("Pasirinkta: ${selected.size}. Elementus vėliau bus galima atkurti arba ištrinti visam laikui.") },
            confirmButton = {
                Button(onClick = {
                    confirmTrash = false
                    onMoveToTrash(selected)
                    selected = emptySet()
                }) { LText("Perkelti") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun CleanupCandidateRow(
    candidate: CleanupCandidate,
    totalBytes: Long,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val percentFormat = remember(locale) {
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 2 }
    }
    val percentage = if (totalBytes <= 0L) 0.0 else candidate.sizeBytes.toDouble() / totalBytes.toDouble()
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag("cleanup_candidate_card"),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("cleanup_candidate_checkbox"),
            )
            Icon(
                if (candidate.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(candidate.path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                candidate.groupLabel?.let { LText(it, style = MaterialTheme.typography.labelSmall) }
                if (candidate.sizeBytes > 0L) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(FileSystemRules.humanBytes(candidate.sizeBytes), style = MaterialTheme.typography.labelSmall)
                        Text(percentFormat.format(percentage), style = MaterialTheme.typography.labelSmall)
                    }
                    LinearProgressIndicator(
                        progress = { percentage.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = uiText("Rodyti aplanke"))
            }
        }
    }
}

private fun cleanupCandidates(
    category: CleanupCategory,
    analysis: StorageAnalysis,
    duplicates: List<DuplicateGroup>,
    similarImages: List<SimilarImageGroup>,
    analysisRootPath: String?,
): List<CleanupCandidate> = when (category) {
    CleanupCategory.TYPE_USAGE -> emptyList()
    CleanupCategory.LARGEST_FOLDERS -> analysis.largestDirectories
        .filterNot { usage -> cleanupPathKey(usage.path) == analysisRootPath?.let(::cleanupPathKey) }
        .map { usage ->
            CleanupCandidate(
                path = usage.path,
                name = File(usage.path).name.ifBlank { usage.path },
                sizeBytes = usage.sizeBytes,
                directory = true,
                groupLabel = "${usage.fileCount} failų",
            )
        }
    CleanupCategory.LARGE -> analysis.largestFiles.map(FileEntry::toCleanupCandidate)
    CleanupCategory.OLDEST -> analysis.oldestFiles.map(FileEntry::toCleanupCandidate)
    CleanupCategory.PACKAGES -> analysis.installerAndArchiveFiles.map(FileEntry::toCleanupCandidate)
    CleanupCategory.DUPLICATES -> duplicates.flatMapIndexed { index, group ->
        group.paths.map { path ->
            CleanupCandidate(path, File(path).name, group.sizeBytes, groupLabel = "Vienodų failų grupė ${index + 1}")
        }
    }
    CleanupCategory.EMPTY_FOLDERS -> analysis.emptyDirectories.map { path ->
        CleanupCandidate(path, File(path).name.ifBlank { path }, 0L, directory = true)
    }
    CleanupCategory.SIMILAR_IMAGES -> similarImages.flatMapIndexed { index, group ->
        group.files.map { it.toCleanupCandidate(groupLabel = "Panašių nuotraukų grupė ${index + 1}") }
    }
}.distinctBy(CleanupCandidate::path)

private fun allCleanupCandidates(
    analysis: StorageAnalysis,
    duplicates: List<DuplicateGroup>,
    similarImages: List<SimilarImageGroup>,
    analysisRootPath: String?,
): List<CleanupCandidate> = CleanupCategory.entries.flatMap {
    cleanupCandidates(it, analysis, duplicates, similarImages, analysisRootPath)
}

private fun cleanupPathKey(value: String): String {
    val path = value.replace('\\', '/')
    return if (path == "/") path else path.trimEnd('/')
}

private fun FileEntry.toCleanupCandidate(groupLabel: String? = null) = CleanupCandidate(
    path = absolutePath,
    name = name,
    sizeBytes = sizeBytes,
    directory = isDirectory,
    groupLabel = groupLabel,
)

private fun cleanupCategoryLabel(category: CleanupCategory): String = when (category) {
    CleanupCategory.TYPE_USAGE -> "Failų tipų pasiskirstymas"
    CleanupCategory.LARGEST_FOLDERS -> "Didžiausi aplankai"
    CleanupCategory.LARGE -> "Didžiausi failai"
    CleanupCategory.OLDEST -> "Seniausiai keisti failai"
    CleanupCategory.PACKAGES -> "APK ir archyvai"
    CleanupCategory.DUPLICATES -> "Vienodi failai"
    CleanupCategory.EMPTY_FOLDERS -> "Tušti aplankai"
    CleanupCategory.SIMILAR_IMAGES -> "Panašios nuotraukos"
}

private fun cleanupCategoryIcon(category: CleanupCategory): ImageVector = when (category) {
    CleanupCategory.TYPE_USAGE -> Icons.AutoMirrored.Rounded.InsertDriveFile
    CleanupCategory.LARGEST_FOLDERS -> Icons.Rounded.Folder
    CleanupCategory.LARGE -> Icons.AutoMirrored.Rounded.InsertDriveFile
    CleanupCategory.OLDEST -> Icons.AutoMirrored.Rounded.InsertDriveFile
    CleanupCategory.PACKAGES -> Icons.Rounded.Archive
    CleanupCategory.DUPLICATES -> Icons.Rounded.ContentCopy
    CleanupCategory.EMPTY_FOLDERS -> Icons.Rounded.Folder
    CleanupCategory.SIMILAR_IMAGES -> Icons.Rounded.Image
}

private fun cleanupKindLabel(kind: com.affilemanager.app.model.EntryKind): String = when (kind) {
    com.affilemanager.app.model.EntryKind.DIRECTORY -> "Aplankai"
    com.affilemanager.app.model.EntryKind.IMAGE -> "Nuotraukos"
    com.affilemanager.app.model.EntryKind.VIDEO -> "Vaizdo įrašai"
    com.affilemanager.app.model.EntryKind.AUDIO -> "Garso failai"
    com.affilemanager.app.model.EntryKind.DOCUMENT -> "Dokumentai"
    com.affilemanager.app.model.EntryKind.ARCHIVE -> "Archyvai"
    com.affilemanager.app.model.EntryKind.APK -> "APK"
    com.affilemanager.app.model.EntryKind.OTHER -> "Kita"
}
