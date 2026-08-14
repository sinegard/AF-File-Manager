package com.affilemanager.app.ui.screens

import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.DirectoryUsage
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.components.LocalFileVisual
import java.util.Locale

private const val MEBIBYTE = 1_024L * 1_024L
private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

private enum class SearchScope { CURRENT_FOLDER, ALL_STORAGE }

@Composable
fun AnalyzeScreen(viewModel: MainViewModel, contentPadding: PaddingValues) {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val analysisState by viewModel.analysisState.collectAsStateWithLifecycle()
    val savedSearches by viewModel.savedSearches.collectAsStateWithLifecycle()
    val storageRoots by viewModel.roots.collectAsStateWithLifecycle()
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val leftPanel by viewModel.leftPanel.collectAsStateWithLifecycle()
    val rightPanel by viewModel.rightPanel.collectAsStateWithLifecycle()
    val renameUndo by viewModel.renameUndo.collectAsStateWithLifecycle()
    val tagSnapshot by viewModel.tagSnapshot.collectAsStateWithLifecycle()
    val activePath = if (activePanel == PanelId.LEFT) leftPanel.path else rightPanel.path

    var query by remember { mutableStateOf(searchState.filters.query) }
    var includeHidden by remember { mutableStateOf(searchState.filters.includeHidden) }
    var regex by remember { mutableStateOf(searchState.filters.useRegex) }
    var kinds by remember { mutableStateOf(searchState.filters.kinds) }
    var minimumMiB by remember { mutableStateOf(bytesToMiBText(searchState.filters.minBytes)) }
    var maximumMiB by remember { mutableStateOf(bytesToMiBText(searchState.filters.maxBytes)) }
    var newerThanDays by remember { mutableStateOf(daysFrom(searchState.filters.modifiedAfter)) }
    var olderThanDays by remember { mutableStateOf(daysFrom(searchState.filters.modifiedBefore)) }
    var tags by remember { mutableStateOf(searchState.filters.tags) }
    var advancedExpanded by remember { mutableStateOf(searchState.filters.hasAdvancedFilters()) }
    var scope by remember { mutableStateOf(SearchScope.CURRENT_FOLDER) }
    var scopedRoots by remember { mutableStateOf(listOf(activePath)) }
    var showSave by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var duplicateGroup by remember { mutableStateOf<DuplicateGroup?>(null) }

    LaunchedEffect(searchState.filters, searchState.roots) {
        query = searchState.filters.query
        includeHidden = searchState.filters.includeHidden
        regex = searchState.filters.useRegex
        kinds = searchState.filters.kinds
        minimumMiB = bytesToMiBText(searchState.filters.minBytes)
        maximumMiB = bytesToMiBText(searchState.filters.maxBytes)
        newerThanDays = daysFrom(searchState.filters.modifiedAfter)
        olderThanDays = daysFrom(searchState.filters.modifiedBefore)
        tags = searchState.filters.tags
        if (searchState.filters.hasAdvancedFilters()) advancedExpanded = true
        if (searchState.roots.isNotEmpty()) {
            scopedRoots = searchState.roots
            scope = if (searchState.roots.size > 1) SearchScope.ALL_STORAGE else SearchScope.CURRENT_FOLDER
        }
    }

    val minBytes = miBTextToBytes(minimumMiB)
    val maxBytes = miBTextToBytes(maximumMiB)
    val sizeInputValid = (minimumMiB.isBlank() || minBytes != null) &&
        (maximumMiB.isBlank() || maxBytes != null) &&
        (minBytes == null || maxBytes == null || minBytes <= maxBytes)
    val newerDaysValue = newerThanDays
    val olderDaysValue = olderThanDays
    val dateInputValid = newerDaysValue == null || olderDaysValue == null || newerDaysValue >= olderDaysValue
    val hasCondition = query.isNotBlank() || minBytes != null || maxBytes != null ||
        newerThanDays != null || olderThanDays != null || kinds.isNotEmpty() || tags.isNotEmpty()
    val advancedFilterCount = listOf(
        kinds.isNotEmpty(),
        minBytes != null || maxBytes != null,
        newerThanDays != null || olderThanDays != null,
        tags.isNotEmpty(),
    ).count { it }
    val selectedRoots = when (scope) {
        SearchScope.CURRENT_FOLDER -> scopedRoots.takeIf { it.size == 1 } ?: listOf(activePath)
        SearchScope.ALL_STORAGE -> storageRoots.map { it.path }.distinct()
    }
    val currentFilters = SearchFilters(
        query = query,
        minBytes = minBytes,
        maxBytes = maxBytes,
        modifiedAfter = newerThanDays?.let { System.currentTimeMillis() - it * DAY_MILLIS },
        modifiedBefore = olderThanDays?.let { System.currentTimeMillis() - it * DAY_MILLIS },
        kinds = kinds,
        includeHidden = includeHidden,
        useRegex = regex,
        tags = tags,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).testTag("analyze_list"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        LText("Paieška ir vietos analizė", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        LText(
                            if (scope == SearchScope.ALL_STORAGE) "Visos Android matomos saugyklos" else selectedRoots.firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (renameUndo != null) {
                        IconButton(onClick = viewModel::undoBatchRename) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = uiText("Atšaukti paskutinį masinį pervadinimą"))
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LText("Kur ieškoti", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = scope == SearchScope.CURRENT_FOLDER,
                            onClick = { scope = SearchScope.CURRENT_FOLDER; scopedRoots = listOf(activePath) },
                            label = { LText("Šiame aplanke") },
                        )
                        FilterChip(
                            selected = scope == SearchScope.ALL_STORAGE,
                            onClick = { scope = SearchScope.ALL_STORAGE },
                            enabled = storageRoots.isNotEmpty(),
                            label = { LText("Visose saugyklose") },
                            modifier = Modifier.testTag("search_scope_all"),
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().testTag("search_query"),
                        label = { LText("Failo arba aplanko pavadinimas") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = includeHidden, onClick = { includeHidden = !includeHidden }, label = { LText("Paslėpti") })
                        FilterChip(
                            selected = regex,
                            onClick = { regex = !regex },
                            enabled = query.isNotBlank(),
                            label = { LText("Regex") },
                        )
                    }
                    FilterChip(
                        selected = advancedExpanded,
                        onClick = { advancedExpanded = !advancedExpanded },
                        leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                        label = {
                            LText(
                                if (advancedFilterCount > 0) "Išplėstiniai filtrai · $advancedFilterCount" else "Išplėstiniai filtrai",
                            )
                        },
                        modifier = Modifier.testTag("search_advanced_toggle"),
                    )
                    if (advancedExpanded) {
                        LText("Failų tipai", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            EntryKind.entries.forEach { kind ->
                                FilterChip(
                                    selected = kind in kinds,
                                    onClick = { kinds = if (kind in kinds) kinds - kind else kinds + kind },
                                    label = { LText(kindLabel(kind)) },
                                )
                            }
                        }
                        if (tagSnapshot.definitions.isNotEmpty()) {
                            LText("Žymos (turi atitikti visas)", style = MaterialTheme.typography.labelLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                tagSnapshot.definitions.take(40).forEach { definition ->
                                    FilterChip(
                                        selected = definition.name in tags,
                                        onClick = { tags = if (definition.name in tags) tags - definition.name else tags + definition.name },
                                        label = { Text(definition.name) },
                                    )
                                }
                            }
                        }
                        LText("Dydis (MiB)", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = minimumMiB,
                                onValueChange = { minimumMiB = decimalInput(it) },
                                modifier = Modifier.weight(1f),
                                label = { LText("Nuo") },
                                isError = !sizeInputValid,
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = maximumMiB,
                                onValueChange = { maximumMiB = decimalInput(it) },
                                modifier = Modifier.weight(1f),
                                label = { LText("Iki") },
                                isError = !sizeInputValid,
                                singleLine = true,
                            )
                        }
                        LText("Ne senesni nei", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            listOf<Int?>(null, 7, 30, 365).forEach { days ->
                                FilterChip(
                                    selected = newerThanDays == days,
                                    onClick = { newerThanDays = days },
                                    label = { LText(days?.let(::daysLabel) ?: "Bet kada") },
                                )
                            }
                        }
                        LText("Senesni nei", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            listOf<Int?>(null, 7, 30, 365).forEach { days ->
                                FilterChip(
                                    selected = olderThanDays == days,
                                    onClick = { olderThanDays = days },
                                    label = { LText(days?.let(::daysLabel) ?: "Neriboti") },
                                )
                            }
                        }
                        if (!sizeInputValid) {
                            LText("Dydžio ribos netinkamos", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!dateInputValid) {
                            LText("Datos intervalas negalimas", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.search(currentFilters, selectedRoots) },
                            enabled = hasCondition && sizeInputValid && dateInputValid && selectedRoots.isNotEmpty(),
                            modifier = Modifier.testTag("search_execute"),
                        ) { LText("Ieškoti") }
                        OutlinedButton(onClick = { viewModel.analyze(activePath) }) { LText("Analizuoti aplanką") }
                        OutlinedButton(
                            onClick = { showSave = true },
                            enabled = hasCondition && sizeInputValid && dateInputValid && selectedRoots.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null)
                            LText("Išsaugoti", modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                query = ""
                                includeHidden = false
                                regex = false
                                kinds = emptySet()
                                minimumMiB = ""
                                maximumMiB = ""
                                newerThanDays = null
                                olderThanDays = null
                                tags = emptySet()
                            },
                            enabled = hasCondition || includeHidden || regex,
                        ) { LText("Išvalyti filtrus") }
                    }
                }
            }
        }

        if (savedSearches.isNotEmpty()) {
            item { SectionTitle("Išsaugotos paieškos", savedSearches.size.toString(), Modifier.padding(horizontal = 16.dp)) }
            items(savedSearches, key = { "saved:${it.id}" }) { saved ->
                Card(onClick = { viewModel.runSavedSearch(saved) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(saved.name, fontWeight = FontWeight.SemiBold)
                            LText(
                                "${saved.query.ifBlank { "Keli filtrai" }} · ${if (saved.rootPaths.size > 1) "${saved.rootPaths.size} saugyklos" else saved.rootPath}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { viewModel.removeSavedSearch(saved.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti išsaugotą paiešką"))
                        }
                    }
                }
            }
        }

        if (searchState.running || analysisState.running) {
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        searchState.error?.let { error -> item { ErrorCard(error, Modifier.padding(horizontal = 16.dp)) } }
        analysisState.error?.let { error -> item { ErrorCard(error, Modifier.padding(horizontal = 16.dp)) } }

        if (searchState.results.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionTitle("Paieškos virtualus aplankas", searchState.results.size.toString())
                    LText(
                        "Perskaityta ${searchState.scannedEntries} elementų · ${searchState.roots.size} vieta(-os)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (searchState.truncated) {
                        LText(
                            "Rezultatai sutrumpinti pasiekus 5 000 rezultatų arba 200 000 skenuotų elementų ribą.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (searchState.selectedPaths.isNotEmpty()) {
                item {
                    SearchSelectionToolbar(
                        count = searchState.selectedPaths.size,
                        onClose = viewModel::clearSearchSelection,
                        onSelectAll = viewModel::selectAllSearchResults,
                        onCopy = { viewModel.copySearchSelection(move = false) },
                        onMove = { viewModel.copySearchSelection(move = true) },
                        onBatchRename = viewModel::batchRenameSearchSelection,
                        onTrash = { confirmTrash = true },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(searchState.results, key = FileEntry::absolutePath) { entry ->
                ResultRow(
                    entry = entry,
                    selected = entry.absolutePath in searchState.selectedPaths,
                    selectionActive = searchState.selectedPaths.isNotEmpty(),
                    onOpen = { viewModel.open(entry) },
                    onToggleSelection = { viewModel.toggleSearchSelection(entry.absolutePath) },
                    onReveal = { viewModel.revealSearchResult(entry) },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("search_result"),
                )
            }
        }

        analysisState.analysis?.let { analysis ->
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            LText("Rasta ${analysis.scannedFiles} failų", fontWeight = FontWeight.SemiBold)
                            LText("${analysis.scannedDirectories} aplankų · ${FileSystemRules.humanBytes(analysis.totalBytes)}")
                            if (analysis.truncated) LText("Rezultatas sutrumpintas pasiekus saugos ribą", color = MaterialTheme.colorScheme.error)
                        }
                        Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(42.dp))
                    }
                }
            }
            item { SectionTitle("Didžiausi failai", analysis.largestFiles.size.toString(), Modifier.padding(horizontal = 16.dp)) }
            items(analysis.largestFiles.take(30), key = { "large:${it.absolutePath}" }) { entry ->
                ResultRow(
                    entry = entry,
                    onOpen = { viewModel.open(entry) },
                    onReveal = { viewModel.revealSearchResult(entry) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (analysis.typeUsage.isNotEmpty()) {
                item { SectionTitle("Failų tipų pasiskirstymas", analysis.typeUsage.size.toString(), Modifier.padding(horizontal = 16.dp), Icons.Rounded.Analytics) }
                item {
                    val maxTypeBytes = analysis.typeUsage.maxOfOrNull { it.sizeBytes }?.coerceAtLeast(1L) ?: 1L
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            analysis.typeUsage.forEach { usage ->
                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        LText(kindLabel(usage.kind), fontWeight = FontWeight.SemiBold)
                                        LText("${FileSystemRules.humanBytes(usage.sizeBytes)} · ${usage.fileCount}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    LinearProgressIndicator(
                                        progress = { usage.sizeBytes.toFloat() / maxTypeBytes.toFloat() },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (analysis.largestDirectories.isNotEmpty()) {
                item { SectionTitle("Didžiausi aplankai", analysis.largestDirectories.size.toString(), Modifier.padding(horizontal = 16.dp), Icons.Rounded.FolderOpen) }
                items(analysis.largestDirectories.take(50), key = { "directory:${it.path}" }) { usage ->
                    DirectoryUsageRow(
                        usage = usage,
                        maximumBytes = analysis.largestDirectories.first().sizeBytes.coerceAtLeast(1L),
                        onOpen = { viewModel.openQuickPath(usage.path) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item { SectionTitle("Seniausiai keisti failai", analysis.oldestFiles.size.toString(), Modifier.padding(horizontal = 16.dp)) }
            items(analysis.oldestFiles.take(30), key = { "old:${it.absolutePath}" }) { entry ->
                ResultRow(
                    entry = entry,
                    onOpen = { viewModel.open(entry) },
                    onReveal = { viewModel.revealSearchResult(entry) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item { SectionTitle("Tušti aplankai", analysis.emptyDirectories.size.toString(), Modifier.padding(horizontal = 16.dp), Icons.Rounded.FolderOff) }
            items(analysis.emptyDirectories.take(50), key = { "empty:$it" }) { pathValue ->
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(pathValue, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider()
                }
            }
        }

        if (analysisState.duplicates.isNotEmpty()) {
            item { SectionTitle("Dublikatų grupės", analysisState.duplicates.size.toString(), Modifier.padding(horizontal = 16.dp), Icons.Rounded.ContentCopy) }
            items(analysisState.duplicates.take(100), key = { it.sha256 }) { group ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LText("${group.paths.size} vienodi failai · ${FileSystemRules.humanBytes(group.sizeBytes)} kiekvienas", fontWeight = FontWeight.SemiBold)
                        group.paths.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (group.paths.size > 5) LText("… ir dar ${group.paths.size - 5}", style = MaterialTheme.typography.labelSmall)
                        LText("SHA-256 ${group.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
                        OutlinedButton(onClick = { duplicateGroup = group }) { LText("Tvarkyti kopijas") }
                    }
                }
            }
        }
    }

    if (showSave) {
        var name by remember(query) { mutableStateOf(query.take(40).ifBlank { "Mano paieška" }) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { LText("Išsaugoti paiešką") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LText("Bus išsaugotos vietos ir visi dabar pasirinkti filtrai.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { LText("Pavadinimas") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveSearch(name, selectedRoots, currentFilters)
                        showSave = false
                    },
                    enabled = name.isNotBlank(),
                ) { LText("Išsaugoti") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { LText("Atšaukti") } },
        )
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { LText("Perkelti rezultatus į šiukšlinę?") },
            text = { LText("Pasirinkta: ${searchState.selectedPaths.size}. Failus bus galima atkurti skiltyje „Daugiau“.") },
            confirmButton = {
                Button(onClick = { viewModel.trashSearchSelection(); confirmTrash = false }) { LText("Perkelti") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { LText("Atšaukti") } },
        )
    }

    duplicateGroup?.let { group ->
        var selectedPaths by remember(group.sha256) { mutableStateOf(emptySet<String>()) }
        AlertDialog(
            onDismissRequest = { duplicateGroup = null },
            title = { LText("Pasirinkti dublikatų kopijas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LText(
                        "Programa nieko nepažymi automatiškai. Patikrinkite kelius; pažymėti failai bus perkelti į atkuriamą šiukšlinę.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { selectedPaths = group.paths.drop(1).toSet() },
                        enabled = group.paths.size > 1,
                    ) { LText("Pažymėti visas, išskyrus pirmą") }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(group.paths, key = { "duplicate-path:$it" }) { path ->
                            Row(
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path },
                                    onLongClick = {},
                                ).padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = path in selectedPaths,
                                    onCheckedChange = { checked -> selectedPaths = if (checked) selectedPaths + path else selectedPaths - path },
                                )
                                Text(path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    LText("Pažymėta: ${selectedPaths.size} iš ${group.paths.size}", style = MaterialTheme.typography.labelLarge)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.trashDuplicateCopies(selectedPaths.toList(), activePath)
                        duplicateGroup = null
                    },
                    enabled = selectedPaths.isNotEmpty() && selectedPaths.size < group.paths.size,
                ) { LText("Perkelti į šiukšlinę") }
            },
            dismissButton = { TextButton(onClick = { duplicateGroup = null }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun SearchSelectionToolbar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onBatchRename: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti")) }
            LText("Pasirinkta: $count", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.CheckBox, contentDescription = uiText("Pasirinkti visus")) }
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti")) }
            IconButton(onClick = onMove) { Icon(Icons.Rounded.ContentCut, contentDescription = uiText("Perkelti")) }
            IconButton(onClick = onBatchRename) {
                Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = uiText("Masinis pervadinimas"))
            }
            IconButton(onClick = onTrash) { Icon(Icons.Rounded.Delete, contentDescription = uiText("Į šiukšlinę"), tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    entry: FileEntry,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleSelection: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectionActive) onToggleSelection() else onOpen() },
            onLongClick = onToggleSelection,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalFileVisual(
                entry = entry,
                targetWidth = 44.dp,
                targetHeight = 44.dp,
                showThumbnails = false,
                modifier = Modifier.size(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.absolutePath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!entry.isDirectory) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onReveal) { Icon(Icons.Rounded.FolderOpen, contentDescription = uiText("Rodyti aplanke")) }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    count: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, contentDescription = null)
        LText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(count, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ErrorCard(error: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        LText(error, modifier = Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private fun kindLabel(kind: EntryKind): String = when (kind) {
    EntryKind.DIRECTORY -> "Aplankai"
    EntryKind.IMAGE -> "Nuotraukos"
    EntryKind.VIDEO -> "Vaizdo įrašai"
    EntryKind.AUDIO -> "Garso failai"
    EntryKind.DOCUMENT -> "Dokumentai"
    EntryKind.ARCHIVE -> "Archyvai"
    EntryKind.APK -> "APK"
    EntryKind.OTHER -> "Kita"
}

private fun daysLabel(days: Int): String = when (days) {
    7 -> "7 dienas"
    30 -> "30 dienų"
    365 -> "1 metus"
    else -> "$days dienų"
}

private fun decimalInput(value: String): String {
    val normalized = value.replace(',', '.')
    val filtered = normalized.filterIndexed { index, char -> char.isDigit() || (char == '.' && normalized.indexOf('.') == index) }
    return filtered.take(12)
}

private fun miBTextToBytes(value: String): Long? {
    if (value.isBlank()) return null
    val amount = value.toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() } ?: return null
    val bytes = amount * MEBIBYTE
    return bytes.takeIf { it <= Long.MAX_VALUE.toDouble() }?.toLong()
}

private fun bytesToMiBText(value: Long?): String = value?.let { bytes ->
    val amount = bytes.toDouble() / MEBIBYTE
    if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(Locale.ROOT, "%.2f", amount)
}.orEmpty()

private fun daysFrom(modifiedAfter: Long?): Int? {
    if (modifiedAfter == null) return null
    val days = ((System.currentTimeMillis() - modifiedAfter).coerceAtLeast(DAY_MILLIS) / DAY_MILLIS).toInt()
    return listOf(7, 30, 365).minByOrNull { kotlin.math.abs(it - days) }
}

private fun SearchFilters.hasAdvancedFilters(): Boolean =
    minBytes != null || maxBytes != null || modifiedAfter != null || modifiedBefore != null || kinds.isNotEmpty() || tags.isNotEmpty()

@Composable
private fun DirectoryUsageRow(usage: DirectoryUsage, maximumBytes: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(usage.path, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(FileSystemRules.humanBytes(usage.sizeBytes), style = MaterialTheme.typography.bodySmall)
                LText("${usage.fileCount} failų", style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(
                progress = { usage.sizeBytes.toFloat() / maximumBytes.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
