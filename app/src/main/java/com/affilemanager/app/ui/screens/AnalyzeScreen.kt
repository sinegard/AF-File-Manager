package com.affilemanager.app.ui.screens

import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
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
import com.affilemanager.app.model.ClipboardMode
import com.affilemanager.app.model.DirectoryUsage
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.model.StorageAnalysis
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.model.StorageRootKind
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.SelectionActionBar
import java.io.File
import java.util.Locale

private const val MEBIBYTE = 1_024L * 1_024L
private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

private enum class SearchScope { CURRENT_FOLDER, ALL_STORAGE, SELECTED_STORAGE }

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
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val cleanupRequested by viewModel.cleanupRequested.collectAsStateWithLifecycle()
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
    var selectedStoragePaths by remember { mutableStateOf(emptySet<String>()) }
    var storagePickerDraft by remember { mutableStateOf(emptySet<String>()) }
    var showStoragePicker by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var duplicateGroup by remember { mutableStateOf<DuplicateGroup?>(null) }
    var showCleanupReview by remember { mutableStateOf(false) }
    var cleanupCategory by remember { mutableStateOf(CleanupCategory.LARGE) }

    LaunchedEffect(cleanupRequested, analysisState.analysis, analysisState.running, analysisState.error) {
        if (cleanupRequested && analysisState.analysis != null && !analysisState.running) {
            cleanupCategory = CleanupCategory.LARGE
            showCleanupReview = true
            viewModel.consumeCleanupRequest()
        } else if (cleanupRequested && analysisState.error != null && !analysisState.running) {
            viewModel.consumeCleanupRequest()
        }
    }

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
            val allStoragePaths = storageRoots.map(StorageRoot::path).toSet()
            scope = when {
                searchState.roots == listOf(activePath) -> SearchScope.CURRENT_FOLDER
                allStoragePaths.isNotEmpty() && searchState.roots.toSet() == allStoragePaths -> SearchScope.ALL_STORAGE
                searchState.roots.all(allStoragePaths::contains) -> SearchScope.SELECTED_STORAGE
                else -> SearchScope.CURRENT_FOLDER
            }
            selectedStoragePaths = searchState.roots.filter(allStoragePaths::contains).toSet()
        }
    }

    LaunchedEffect(storageRoots) {
        val mounted = storageRoots.map(StorageRoot::path).toSet()
        selectedStoragePaths = selectedStoragePaths.intersect(mounted)
        storagePickerDraft = storagePickerDraft.intersect(mounted)
        if (scope == SearchScope.SELECTED_STORAGE && selectedStoragePaths.isEmpty()) {
            scope = if (mounted.isEmpty()) SearchScope.CURRENT_FOLDER else SearchScope.ALL_STORAGE
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
        SearchScope.SELECTED_STORAGE -> storageRoots.map(StorageRoot::path).filter(selectedStoragePaths::contains)
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
    val overviewPath = analysisState.rootPaths.firstOrNull() ?: analysisState.rootPath ?: activePath
    val overviewRoot = storageRoots
        .filter { root -> overviewPath == root.path || overviewPath.startsWith(root.path.trimEnd('/') + "/") }
        .maxByOrNull { it.path.length }
        ?: storageRoots.firstOrNull()

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
                            when (scope) {
                                SearchScope.ALL_STORAGE -> "Visos Android matomos saugyklos"
                                SearchScope.SELECTED_STORAGE -> "Pasirinkta saugyklų: ${selectedRoots.size}"
                                SearchScope.CURRENT_FOLDER -> selectedRoots.firstOrNull().orEmpty()
                            },
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
        if (overviewRoot != null) {
            item {
                StorageOverviewCard(
                    root = overviewRoot,
                    roots = storageRoots,
                    analysis = analysisState.analysis,
                    analysisRootPaths = analysisState.rootPaths.ifEmpty { listOfNotNull(analysisState.rootPath) },
                    analysisAllStorage = analysisState.allStorage,
                    running = analysisState.running,
                    onAnalyzeRoot = viewModel::analyze,
                    onAnalyzeFolder = { viewModel.analyze(activePath) },
                    onAnalyzeAllStorage = viewModel::analyzeAllStorage,
                    onCleanup = { cleanupCategory = CleanupCategory.LARGE; showCleanupReview = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LText("Kur ieškoti", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                        FilterChip(
                            selected = scope == SearchScope.SELECTED_STORAGE,
                            onClick = {
                                storagePickerDraft = selectedStoragePaths.takeIf { it.isNotEmpty() }
                                    ?: storageRoots.mapTo(linkedSetOf(), StorageRoot::path)
                                showStoragePicker = true
                            },
                            enabled = storageRoots.isNotEmpty(),
                            label = { LText("Pasirinkti saugyklas") },
                            modifier = Modifier.testTag("search_scope_selected"),
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    if (searchState.running) {
                        OutlinedButton(onClick = viewModel::cancelSearch, modifier = Modifier.testTag("search_cancel")) {
                            LText("Sustabdyti paiešką")
                        }
                    }
                }
            }
        }
        searchState.error?.let { error -> item { ErrorCard(error, Modifier.padding(horizontal = 16.dp)) } }
        analysisState.error?.let { error -> item { ErrorCard(error, Modifier.padding(horizontal = 16.dp)) } }
        if (analysisState.duplicateScanTruncated) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LText("Dublikatų paieška dalinė", fontWeight = FontWeight.SemiBold)
                        LText(
                            "Patikrinta ${analysisState.duplicateCandidatesScanned} failų. Rastos grupės rodomos, tačiau likusioje saugyklos dalyje gali būti daugiau dublikatų.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

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
                        canAddToClipboard = clipboard?.mode == ClipboardMode.COPY,
                        onAddToClipboard = viewModel::addSearchSelectionToClipboard,
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
            val largestFolders = analysis.largestDirectories.filterNot { usage ->
                analysisState.rootPaths.any { root -> sameAnalysisPath(usage.path, root) } ||
                    sameAnalysisPath(usage.path, analysisState.rootPath)
            }
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
            item {
                OutlinedButton(
                    onClick = { cleanupCategory = CleanupCategory.LARGE; showCleanupReview = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("open_cleanup_review"),
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                    LText("Atidaryti saugaus valymo peržiūrą", modifier = Modifier.padding(start = 7.dp))
                }
            }
            item {
                AnalysisOverviewCard(
                    title = "Failų tipų pasiskirstymas",
                    testTag = "analysis_overview_types",
                    count = analysis.typeUsage.size,
                    icon = Icons.Rounded.Analytics,
                    onViewAll = { cleanupCategory = CleanupCategory.TYPE_USAGE; showCleanupReview = true },
                ) {
                    val maximum = analysis.typeUsage.maxOfOrNull { it.sizeBytes }?.coerceAtLeast(1L) ?: 1L
                    analysis.typeUsage.take(3).forEach { usage ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                LText(kindLabel(usage.kind), fontWeight = FontWeight.SemiBold)
                                Text(FileSystemRules.humanBytes(usage.sizeBytes), style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(
                                progress = { usage.sizeBytes.toFloat() / maximum.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item {
                AnalysisOverviewCard(
                    title = "Didžiausi aplankai",
                    testTag = "analysis_overview_folders",
                    count = largestFolders.size,
                    icon = Icons.Rounded.FolderOpen,
                    onViewAll = { cleanupCategory = CleanupCategory.LARGEST_FOLDERS; showCleanupReview = true },
                ) {
                    val maximum = largestFolders.firstOrNull()?.sizeBytes?.coerceAtLeast(1L) ?: 1L
                    largestFolders.take(2).forEach { usage ->
                        DirectoryUsageRow(usage, maximum, onOpen = { viewModel.openQuickPath(usage.path) })
                    }
                }
            }
            item {
                AnalysisOverviewCard(
                    title = "Didžiausi failai",
                    testTag = "analysis_overview_files",
                    count = analysis.largestFiles.size,
                    icon = Icons.Rounded.DeleteSweep,
                    onViewAll = { cleanupCategory = CleanupCategory.LARGE; showCleanupReview = true },
                ) {
                    analysis.largestFiles.take(2).forEach { entry ->
                        ResultRow(entry, onOpen = { viewModel.open(entry) }, onReveal = { viewModel.revealSearchResult(entry) })
                    }
                }
            }
            item {
                AnalysisOverviewCard(
                    title = "Seniausiai keisti failai",
                    testTag = "analysis_overview_oldest",
                    count = analysis.oldestFiles.size,
                    icon = Icons.Rounded.Analytics,
                    onViewAll = { cleanupCategory = CleanupCategory.OLDEST; showCleanupReview = true },
                ) {
                    analysis.oldestFiles.take(2).forEach { entry ->
                        ResultRow(entry, onOpen = { viewModel.open(entry) }, onReveal = { viewModel.revealSearchResult(entry) })
                    }
                }
            }
            item {
                AnalysisOverviewCard(
                    title = "Tušti aplankai",
                    testTag = "analysis_overview_empty",
                    count = analysis.emptyDirectories.size,
                    icon = Icons.Rounded.FolderOff,
                    onViewAll = { cleanupCategory = CleanupCategory.EMPTY_FOLDERS; showCleanupReview = true },
                ) {
                    analysis.emptyDirectories.take(2).forEach { pathValue ->
                        Text(pathValue, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        HorizontalDivider()
                    }
                }
            }
            item {
                AnalysisOverviewCard(
                    title = "Dublikatų grupės",
                    testTag = "analysis_overview_duplicates",
                    count = analysisState.duplicates.size,
                    icon = Icons.Rounded.ContentCopy,
                    onViewAll = { cleanupCategory = CleanupCategory.DUPLICATES; showCleanupReview = true },
                ) {
                    analysisState.duplicates.take(2).forEach { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            LText("${group.paths.size} vienodi failai · ${FileSystemRules.humanBytes(group.sizeBytes)} kiekvienas", fontWeight = FontWeight.SemiBold)
                            Text(group.paths.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            OutlinedButton(onClick = { duplicateGroup = group }) { LText("Tvarkyti kopijas") }
                        }
                    }
                }
            }
        }
    }

    if (showStoragePicker) {
        AlertDialog(
            onDismissRequest = { showStoragePicker = false },
            title = { LText("Pasirinkti saugyklas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LText(
                        "Paieška bus vykdoma tik pažymėtose prijungtose saugyklose.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    storageRoots.forEach { root ->
                        Card(
                            onClick = {
                                storagePickerDraft = if (root.path in storagePickerDraft) {
                                    storagePickerDraft - root.path
                                } else {
                                    storagePickerDraft + root.path
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("search_storage_${root.id}"),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = root.path in storagePickerDraft,
                                    onCheckedChange = null,
                                )
                                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                    LText(storageRootLabel(root), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        root.path,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedStoragePaths = storagePickerDraft
                        scope = SearchScope.SELECTED_STORAGE
                        showStoragePicker = false
                    },
                    enabled = storagePickerDraft.isNotEmpty(),
                    modifier = Modifier.testTag("search_storage_apply"),
                ) { LText("Taikyti") }
            },
            dismissButton = { TextButton(onClick = { showStoragePicker = false }) { LText("Atšaukti") } },
        )
    }

    if (showSave) {
        val defaultSearchName = uiText("Mano paieška")
        var name by remember(query, defaultSearchName) {
            mutableStateOf(query.take(40).ifBlank { defaultSearchName })
        }
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
                        viewModel.trashDuplicateCopies(selectedPaths.toList())
                        duplicateGroup = null
                    },
                    enabled = selectedPaths.isNotEmpty() && selectedPaths.size < group.paths.size,
                ) { LText("Perkelti į šiukšlinę") }
            },
            dismissButton = { TextButton(onClick = { duplicateGroup = null }) { LText("Atšaukti") } },
        )
    }

    if (showCleanupReview) {
        analysisState.analysis?.let { analysis ->
            CleanupReviewDialog(
                analysis = analysis,
                duplicates = analysisState.duplicates,
                similarImages = analysisState.similarImages,
                similarImagesRunning = analysisState.similarImagesRunning,
                similarImagesAnalyzed = analysisState.similarImagesAnalyzed,
                similarImagesError = analysisState.similarImagesError,
                initialCategory = cleanupCategory,
                analysisRootPaths = analysisState.rootPaths.ifEmpty { listOfNotNull(analysisState.rootPath) },
                onAnalyzeSimilarImages = viewModel::analyzeSimilarImages,
                onMoveToTrash = viewModel::trashAnalysisSelection,
                onLoadFolder = viewModel::loadCleanupFolder,
                onOpenFile = viewModel::open,
                onDismiss = { showCleanupReview = false },
            )
        }
    }
}

private fun sameAnalysisPath(first: String, second: String?): Boolean {
    if (second == null) return false
    fun normalized(value: String): String {
        val path = value.replace('\\', '/')
        return if (path == "/") path else path.trimEnd('/')
    }
    return normalized(first) == normalized(second)
}

@Composable
private fun AnalysisOverviewCard(
    title: String,
    testTag: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onViewAll: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                LText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelLarge)
                TextButton(
                    onClick = onViewAll,
                    enabled = count > 0,
                    modifier = Modifier.testTag("${testTag}_view_all"),
                ) { LText("Rodyti visus") }
            }
            if (count == 0) {
                LText("Šioje grupėje elementų nerasta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                content()
            }
        }
    }
}

@Composable
private fun StorageOverviewCard(
    root: StorageRoot,
    roots: List<StorageRoot>,
    analysis: StorageAnalysis?,
    analysisRootPaths: List<String>,
    analysisAllStorage: Boolean,
    running: Boolean,
    onAnalyzeRoot: (String) -> Unit,
    onAnalyzeFolder: () -> Unit,
    onAnalyzeAllStorage: () -> Unit,
    onCleanup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = root.totalBytes.coerceAtLeast(0L)
    val used = (total - root.freeBytes.coerceAtLeast(0L)).coerceIn(0L, total)
    val fraction = if (total > 0L) (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    val percentage = (fraction * 100f).toInt().coerceIn(0, 100)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.size(86.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                    )
                    Text("$percentage%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    LText("Saugyklos užpildymas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    LText(storageRootLabel(root), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LText(
                        "Naudojama ${FileSystemRules.humanBytes(used)} iš ${FileSystemRules.humanBytes(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (analysis != null) {
                        LText(
                            "Nuskaityta ${analysis.scannedFiles} failų ir ${analysis.scannedDirectories} aplankų",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LText("Nuskaityti saugyklą", style = MaterialTheme.typography.labelLarge)
            roots.distinctBy(StorageRoot::path).forEach { target ->
                OutlinedButton(
                    onClick = { onAnalyzeRoot(target.path) },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().testTag("analyze_storage_${target.id}"),
                ) {
                    Icon(storageRootIcon(target.kind), contentDescription = null)
                    LText(
                        "Nuskaityti: ${storageRootLabel(target)}",
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    if (running && !analysisAllStorage && analysisRootPaths.singleOrNull()?.let { sameAnalysisPath(it, target.path) } == true) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
            OutlinedButton(
                onClick = onAnalyzeFolder,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("analyze_current_folder"),
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                LText("Analizuoti dabartinį aplanką", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = onAnalyzeAllStorage,
                enabled = !running && roots.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("analyze_all_storage"),
            ) {
                Icon(Icons.Rounded.Storage, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    LText("Analizuoti saugyklą")
                    LText("Visose saugyklose", style = MaterialTheme.typography.labelSmall)
                }
                if (running && analysisAllStorage) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (analysis != null) {
                Button(onClick = onCleanup, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                    LText("Peržiūrėti valymą")
                }
            }
        }
    }
}

private fun storageRootLabel(root: StorageRoot): String = when (root.kind) {
    StorageRootKind.INTERNAL -> "Vidinė atmintis"
    StorageRootKind.SD_CARD -> root.title.ifBlank { "SD kortelė" }
    StorageRootKind.USB_STORAGE -> root.title.ifBlank { "USB saugykla" }
    StorageRootKind.REMOVABLE -> root.title.ifBlank { "Išimama saugykla" }
}

private fun storageRootIcon(kind: StorageRootKind) = when (kind) {
    StorageRootKind.INTERNAL -> Icons.Rounded.Storage
    StorageRootKind.SD_CARD, StorageRootKind.REMOVABLE -> Icons.Rounded.SdStorage
    StorageRootKind.USB_STORAGE -> Icons.Rounded.Usb
}

@Composable
private fun SearchSelectionToolbar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    canAddToClipboard: Boolean,
    onAddToClipboard: () -> Unit,
    onMove: () -> Unit,
    onBatchRename: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectionActionBar(
        count = count,
        allSelected = false,
        onClose = onClose,
        onToggleSelectAll = onSelectAll,
        modifier = modifier,
    ) {
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti")) }
            if (canAddToClipboard) {
                TextButton(onClick = onAddToClipboard, modifier = Modifier.testTag("copy-more-search")) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = uiText("Įtraukti į iškarpinę"))
                    LText("Kopijuoti daugiau", modifier = Modifier.padding(start = 5.dp))
                }
            }
            IconButton(onClick = onMove) { Icon(Icons.Rounded.ContentCut, contentDescription = uiText("Perkelti")) }
            IconButton(onClick = onBatchRename) {
                Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = uiText("Masinis pervadinimas"))
            }
            IconButton(onClick = onTrash) { Icon(Icons.Rounded.Delete, contentDescription = uiText("Į šiukšlinę"), tint = MaterialTheme.colorScheme.error) }
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
