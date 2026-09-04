package com.affilemanager.app.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.archive.ArchiveFormat
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryGridStyle
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.data.FileTagDefinition
import com.affilemanager.app.data.FileTagSnapshot
import com.affilemanager.app.data.HomeCustomization
import com.affilemanager.app.data.HomeCustomizationRules
import com.affilemanager.app.data.HomeDisplayArea
import com.affilemanager.app.data.HomeSection
import com.affilemanager.app.data.HomeShortcut
import com.affilemanager.app.data.HomeShortcutNavigationRules
import com.affilemanager.app.data.PanelWorkspace
import com.affilemanager.app.data.RecentFileItem
import com.affilemanager.app.data.RecentItem
import com.affilemanager.app.data.TaggedFileRecord
import com.affilemanager.app.data.SafLocation
import com.affilemanager.app.model.ClipboardMode
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.model.StorageRootKind
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.HomeToolPage
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelComparisonStatus
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.PanelUiState
import com.affilemanager.app.ui.ProgressiveScrollRules
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.AfPullToRefresh
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.ui.components.DirectoryGridItemContent
import com.affilemanager.app.ui.components.DirectoryLayoutButton
import com.affilemanager.app.ui.components.DirectoryQuickSearchField
import com.affilemanager.app.ui.components.DirectorySearchButton
import com.affilemanager.app.ui.components.FileInfoDialog
import com.affilemanager.app.ui.components.FileSizeBar
import com.affilemanager.app.ui.components.LocalFileVisual
import com.affilemanager.app.ui.components.SelectionActionDock
import com.affilemanager.app.ui.components.SelectionHeader
import com.affilemanager.app.ui.components.longPressDragSelect
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.preview.PreviewSource
import com.affilemanager.app.ui.preview.openWith
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * AF's own virtual browser for personal organization tools. It deliberately reuses the app's
 * standard directory chrome instead of reproducing another file manager's branded screen.
 */
@Composable
internal fun HomeToolsBrowser(
    page: HomeToolPage,
    favorites: List<String>,
    tagSnapshot: FileTagSnapshot,
    displaySettings: DirectoryDisplaySettings,
    onBack: () -> Unit,
    onOpenFavorite: (String) -> Unit,
    onOpenTag: (String) -> Unit,
    onToggleLayout: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    var searchVisible by remember(page) { mutableStateOf(false) }
    var query by remember(page) { mutableStateOf("") }
    LaunchedEffect(page) {
        searchVisible = false
        query = ""
    }
    val favoriteFiles = remember(favorites, query) {
        val normalizedQuery = query.trim()
        favorites.asSequence()
            .map(::File)
            .filter(File::exists)
            .filter { normalizedQuery.isEmpty() || it.name.contains(normalizedQuery, ignoreCase = true) || it.absolutePath.contains(normalizedQuery, ignoreCase = true) }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }
    val tagDefinitions = remember(tagSnapshot.definitions, query) {
        val normalizedQuery = query.trim()
        tagSnapshot.definitions
            .filter { normalizedQuery.isEmpty() || it.name.contains(normalizedQuery, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }
    val count = when (page) {
        HomeToolPage.FAVORITES -> favoriteFiles.size
        HomeToolPage.TAGS -> tagDefinitions.size
    }
    val title = uiText(if (page == HomeToolPage.FAVORITES) "Mėgstami" else "Žymos")

    Column(modifier = Modifier.fillMaxSize().testTag("home_tools_page_${page.name.lowercase()}")) {
        DirectoryBrowserToolbar(
            title = title,
            path = uiText(if (count == 1) "1 elementas" else "$count elementų"),
            backEnabled = true,
            forwardEnabled = false,
            upEnabled = false,
            searchActive = searchVisible,
            grid = displaySettings.layoutMode == DirectoryLayoutMode.GRID,
            testTagPrefix = "home_${page.name.lowercase()}",
            onBack = onBack,
            onForward = {},
            onUp = {},
            onToggleSearch = {
                searchVisible = !searchVisible
                if (!searchVisible) query = ""
            },
            onToggleLayout = onToggleLayout,
            onOpenSettings = onOpenDisplaySettings,
            actions = {},
        )
        if (searchVisible) {
            DirectoryQuickSearchField(
                query = query,
                onQueryChange = { query = it },
                onClose = { searchVisible = false; query = "" },
                modifier = Modifier.testTag("home_tools_search_${page.name.lowercase()}"),
            )
        }
        AfPullToRefresh(
            isRefreshing = false,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            testTag = "pull_to_refresh_home_tools",
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (page) {
                    HomeToolPage.FAVORITES -> FavoriteBrowserItems(
                        files = favoriteFiles,
                        settings = displaySettings,
                        onOpen = onOpenFavorite,
                    )
                    HomeToolPage.TAGS -> TagBrowserItems(
                        definitions = tagDefinitions,
                        snapshot = tagSnapshot,
                        settings = displaySettings,
                        onOpen = onOpenTag,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteBrowserItems(
    files: List<File>,
    settings: DirectoryDisplaySettings,
    onOpen: (String) -> Unit,
) {
    if (files.isEmpty()) {
        EmptyHomeToolPage("Mėgstamų vietų dar nėra", Icons.Rounded.Star)
    } else if (settings.layoutMode == DirectoryLayoutMode.GRID) {
        val columns = settings.gridColumns.coerceIn(1, 6)
        val spacing = (8f * settings.spacingScalePercent / 100f).dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("favorites_grid"),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(files, key = File::getAbsolutePath) { file ->
                HomeBrowserGridCard(
                    title = file.name.ifBlank { file.absolutePath },
                    detail = file.absolutePath,
                    icon = if (file.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                    compact = columns >= 4,
                    iconScalePercent = settings.iconScalePercent,
                    onClick = { onOpen(file.absolutePath) },
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("favorites_list"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(files, key = File::getAbsolutePath) { file ->
                HomeBrowserListCard(
                    title = file.name.ifBlank { file.absolutePath },
                    detail = file.absolutePath,
                    icon = if (file.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                    onClick = { onOpen(file.absolutePath) },
                )
            }
        }
    }
}

@Composable
private fun TagBrowserItems(
    definitions: List<FileTagDefinition>,
    snapshot: FileTagSnapshot,
    settings: DirectoryDisplaySettings,
    onOpen: (String) -> Unit,
) {
    if (definitions.isEmpty()) {
        EmptyHomeToolPage("Žymų dar nėra", Icons.AutoMirrored.Rounded.Label)
        return
    }
    val counts = remember(snapshot.records) {
        buildMap<String, Int> {
            snapshot.records.forEach { record -> record.tags.forEach { tag -> put(tag, getOrDefault(tag, 0) + 1) } }
        }
    }
    if (settings.layoutMode == DirectoryLayoutMode.GRID) {
        val columns = settings.gridColumns.coerceIn(1, 6)
        val spacing = (8f * settings.spacingScalePercent / 100f).dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("tags_grid"),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(definitions, key = FileTagDefinition::name) { tag ->
                HomeBrowserGridCard(
                    title = tag.name,
                    detail = uiText(itemCountLabel(counts[tag.name] ?: 0)),
                    icon = Icons.AutoMirrored.Rounded.Label,
                    accent = Color(tag.colorArgb),
                    compact = columns >= 4,
                    iconScalePercent = settings.iconScalePercent,
                    onClick = { onOpen(tag.name) },
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("tags_list"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(definitions, key = FileTagDefinition::name) { tag ->
                HomeBrowserListCard(
                    title = tag.name,
                    detail = uiText(itemCountLabel(counts[tag.name] ?: 0)),
                    icon = Icons.AutoMirrored.Rounded.Label,
                    accent = Color(tag.colorArgb),
                    onClick = { onOpen(tag.name) },
                )
            }
        }
    }
}

@Composable
private fun HomeBrowserListCard(
    title: String,
    detail: String,
    icon: ImageVector,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeBrowserGridCard(
    title: String,
    detail: String,
    icon: ImageVector,
    accent: Color = MaterialTheme.colorScheme.primary,
    compact: Boolean = false,
    iconScalePercent: Int = 100,
    onClick: () -> Unit,
) {
    val iconSize = ((if (compact) 26f else 38f) * iconScalePercent / 100f).dp
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(if (compact) 104.dp else 132.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(iconSize))
            Text(title, modifier = Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            if (!compact) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyHomeToolPage(message: String, icon: ImageVector) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        LText(message, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FavoriteLocationsDialog(
    favorites: List<String>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val files = remember(favorites) { favorites.map(::File).filter(File::exists) }
    AfModalDialog(
        title = "Mėgstami",
        icon = Icons.Rounded.Star,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("favorite_locations_dialog"),
        showFooter = false,
        expandedContent = true,
        actions = {},
    ) {
        AfPullToRefresh(
            isRefreshing = false,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            testTag = "pull_to_refresh_favorite_locations",
        ) {
            if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LText("Mėgstamų vietų dar nėra")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
                    items(files, key = File::getAbsolutePath) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("favorite_location_${file.absolutePath.hashCode()}")
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (file.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(file.name.ifBlank { file.absolutePath }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(file.absolutePath, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { onOpen(file.absolutePath) }) { LText("Atidaryti") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}


@Composable
internal fun FilesHome(
    roots: List<StorageRoot>,
    safLocations: List<SafLocation>,
    recentFiles: List<RecentFileItem>,
    recentFilesLoading: Boolean,
    recentFilesError: String?,
    storageDisplaySettings: DirectoryDisplaySettings,
    quickLocationsDisplaySettings: DirectoryDisplaySettings,
    customization: HomeCustomization,
    favorites: List<String>,
    tagSnapshot: FileTagSnapshot,
    trashCount: Int,
    rootStorageAvailable: Boolean,
    onOpen: (QuickLocation) -> Unit,
    onOpenStorage: (StorageRoot) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenRecent: (FileEntry) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenFavoritesPage: () -> Unit,
    onOpenTagsPage: () -> Unit,
    onOpenPlans: () -> Unit,
    onOpenCleanup: () -> Unit,
    onRefreshRecent: () -> Unit,
    onToggleLayout: (HomeDisplayArea) -> Unit,
    onConfigureLayout: (HomeDisplayArea) -> Unit,
    onAddSafLocation: () -> Unit,
    onOpenSafLocation: (SafLocation) -> Unit,
    onOpenSystemFiles: () -> Unit,
) {
    val quickLocations = customization.shortcuts.filter(HomeShortcut::visible).map { shortcut ->
        QuickLocation(
            id = shortcut.id,
            title = shortcut.title,
            path = shortcut.path,
            icon = homeShortcutIcon(shortcut),
            virtual = HomeShortcutNavigationRules.isVirtualCategory(shortcut.id),
        )
    }
    var showAllRecent by remember { mutableStateOf(false) }
    var showCloudLocations by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    val bookmarks = remember(customization.shortcuts) { customization.shortcuts.filter { !it.builtIn } }
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).widthIn(max = 760.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LText(
                        "Failų vietos",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenSystemFiles, modifier = Modifier.testTag("open_system_files")) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = uiText("Atidaryti Android sistemos failus"))
                    }
                }
                LText(
                    "Pasirinkite saugyklą, kategoriją arba dažną vietą.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AfPullToRefresh(
            isRefreshing = recentFilesLoading,
            onRefresh = onRefreshRecent,
            modifier = Modifier.fillMaxWidth().weight(1f),
            testTag = "pull_to_refresh_home",
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
            customization.sectionOrder.forEach { section ->
                when (section) {
                    HomeSection.RECENT_FILES -> RecentFilesHomeSection(
                        recentFiles = recentFiles,
                        loading = recentFilesLoading,
                        error = recentFilesError,
                        onShowAll = { showAllRecent = true },
                        onRefresh = onRefreshRecent,
                        onOpen = onOpenRecent,
                    )
                    HomeSection.STORAGE -> StorageHomeSection(
                        roots = roots,
                        customization = customization,
                        rootStorageAvailable = rootStorageAvailable,
                        displaySettings = storageDisplaySettings,
                        onOpen = onOpenStorage,
                        onOpenRoot = onOpenRoot,
                        onOpenCleanup = onOpenCleanup,
                        onToggleLayout = { onToggleLayout(HomeDisplayArea.STORAGE) },
                        onConfigureLayout = { onConfigureLayout(HomeDisplayArea.STORAGE) },
                    )
                    HomeSection.TOOLS -> HomeToolsSection(
                        trashCount = trashCount,
                        favoritesCount = favorites.count { File(it).exists() },
                        tagsCount = tagSnapshot.definitions.size,
                        cloudCount = safLocations.size,
                        bookmarkCount = bookmarks.size,
                        onOpenTrash = onOpenTrash,
                        onOpenPlans = onOpenPlans,
                        onOpenFavorites = onOpenFavoritesPage,
                        onOpenTags = onOpenTagsPage,
                        // Always open the provider list: even with one saved location the user
                        // still needs a visible route for adding a second cloud/provider account.
                        onOpenCloud = { showCloudLocations = true },
                        onOpenBookmarks = { showBookmarks = true },
                    )
                    HomeSection.QUICK_LOCATIONS -> QuickLocationsHomeSection(
                        locations = quickLocations,
                        displaySettings = quickLocationsDisplaySettings,
                        onOpen = onOpen,
                        onToggleLayout = { onToggleLayout(HomeDisplayArea.QUICK_LOCATIONS) },
                        onConfigureLayout = { onConfigureLayout(HomeDisplayArea.QUICK_LOCATIONS) },
                    )
                }
            }
            Spacer(Modifier.height(76.dp))
                }
            }
        }
    }

    if (showAllRecent) {
        AfModalDialog(
            title = "Naujausi failai",
            icon = Icons.Rounded.History,
            onDismissRequest = { showAllRecent = false },
            modifier = Modifier.testTag("recent_files_dialog"),
            showFooter = false,
            expandedContent = true,
            actions = {},
        ) {
            AfPullToRefresh(
                isRefreshing = recentFilesLoading,
                onRefresh = onRefreshRecent,
                modifier = Modifier.fillMaxSize(),
                testTag = "pull_to_refresh_recent_files",
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp).testTag("recent_files_all"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(recentFiles, key = { it.entry.absolutePath }) { item ->
                        RecentFileListItem(
                            item = item,
                            onOpen = {
                                showAllRecent = false
                                onOpenRecent(item.entry)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCloudLocations) {
        AfModalDialog(
            title = "Debesija ir teikėjų vietos",
            icon = Icons.Rounded.Cloud,
            onDismissRequest = { showCloudLocations = false },
            expandedContent = true,
            modifier = Modifier.testTag("cloud_locations_dialog"),
            actions = {
                TextButton(onClick = onAddSafLocation) { LText("Pridėti vietą") }
                TextButton(onClick = { showCloudLocations = false }) { LText("Uždaryti") }
            },
        ) {
            if (safLocations.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LText("Teikėjo vietų dar nepridėta")
                    LText("Pridėkite Google Drive, Nextcloud, Files arba bet kurį Android įdiegtą teikėją.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onAddSafLocation, modifier = Modifier.padding(top = 12.dp)) { LText("Pridėti vietą") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(safLocations, key = SafLocation::uri) { location ->
                        Card(onClick = { showCloudLocations = false; onOpenSafLocation(location) }) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Cloud, contentDescription = null)
                                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(location.title, fontWeight = FontWeight.SemiBold)
                                    Text(location.uri, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBookmarks) {
        AfModalDialog(
            title = "Žymelės",
            icon = Icons.Rounded.Bookmark,
            onDismissRequest = { showBookmarks = false },
            expandedContent = true,
            modifier = Modifier.testTag("bookmarks_dialog"),
            actions = { TextButton(onClick = { showBookmarks = false }) { LText("Uždaryti") } },
        ) {
            if (bookmarks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    LText("Naudokite „Pridėti žymelę“ bet kurio failo ar aplanko meniu.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(bookmarks, key = HomeShortcut::id) { shortcut ->
                        Card(onClick = {
                            showBookmarks = false
                            onOpen(
                                QuickLocation(
                                    id = shortcut.id,
                                    title = shortcut.title,
                                    path = shortcut.path,
                                    icon = homeShortcutIcon(shortcut),
                                    virtual = false,
                                ),
                            )
                        }) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(homeShortcutIcon(shortcut), contentDescription = null)
                                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(shortcut.title, fontWeight = FontWeight.SemiBold)
                                    Text(shortcut.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentFilesHomeSection(
    recentFiles: List<RecentFileItem>,
    loading: Boolean,
    error: String?,
    onShowAll: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (FileEntry) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LText("Naujausi failai", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (recentFiles.isNotEmpty()) TextButton(onClick = onShowAll) { LText("Rodyti visus") }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Rounded.Refresh, contentDescription = uiText("Atnaujinti naujausius failus"))
        }
    }
    when {
        loading && recentFiles.isEmpty() -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        error != null -> LText(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        recentFiles.isEmpty() -> LText(
            "Naujausių failų dar nėra",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("recent_files_row"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(recentFiles.take(8), key = { it.entry.absolutePath }) { item ->
                RecentFileCard(item = item, onOpen = { onOpen(item.entry) })
            }
        }
    }
}

@Composable
private fun StorageHomeSection(
    roots: List<StorageRoot>,
    customization: HomeCustomization,
    rootStorageAvailable: Boolean,
    displaySettings: DirectoryDisplaySettings,
    onOpen: (StorageRoot) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenCleanup: () -> Unit,
    onToggleLayout: () -> Unit,
    onConfigureLayout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LText("Saugyklos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(
            onClick = onOpenCleanup,
            modifier = Modifier.testTag("analyze_storage_button"),
        ) {
            Icon(Icons.Rounded.Analytics, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            LText("Analizuoti saugyklą")
        }
        DirectoryLayoutButton(
            grid = displaySettings.layoutMode == DirectoryLayoutMode.GRID,
            testTag = "home_storage_layout_toggle",
            onToggleLayout = onToggleLayout,
            onOpenSettings = onConfigureLayout,
        )
    }
    val locationsById = roots.associate { root ->
        val usageFraction = root.totalBytes.takeIf { it > 0L }?.let { total ->
            (total - root.freeBytes).coerceIn(0L, total).toFloat() / total.toFloat()
        }
        root.id to HomeStorageLocation(
            id = root.id,
            title = when (root.kind) {
                StorageRootKind.INTERNAL -> "Vidinė atmintis"
                StorageRootKind.SD_CARD -> root.title.ifBlank { "SD kortelė" }
                StorageRootKind.USB_STORAGE -> root.title.ifBlank { "USB saugykla" }
                StorageRootKind.REMOVABLE -> root.title.ifBlank { "Išimama saugykla" }
            },
            description = "${FileSystemRules.humanBytes(root.freeBytes)} laisva iš ${FileSystemRules.humanBytes(root.totalBytes)}",
            icon = when (root.kind) {
                StorageRootKind.INTERNAL -> Icons.Rounded.Storage
                StorageRootKind.USB_STORAGE -> Icons.Rounded.Usb
                StorageRootKind.SD_CARD, StorageRootKind.REMOVABLE -> Icons.Rounded.SdStorage
            },
            usageFraction = usageFraction,
            onClick = { onOpen(root) },
        )
    }
    val rootSpace = remember {
        File("/").let { root -> root.totalSpace.coerceAtLeast(0L) to root.usableSpace.coerceAtLeast(0L) }
    }
    val rootUsageFraction = rootSpace.first.takeIf { it > 0L }?.let { total ->
        (total - rootSpace.second).coerceIn(0L, total).toFloat() / total.toFloat()
    }
    val allLocations = locationsById + (HomeCustomizationRules.ROOT_STORAGE_ID to HomeStorageLocation(
        id = HomeCustomizationRules.ROOT_STORAGE_ID,
        title = "Root",
        description = rootSpace.first.takeIf { it > 0L }?.let { total ->
            "${FileSystemRules.humanBytes(rootSpace.second)} laisva iš ${FileSystemRules.humanBytes(total)}"
        } ?: "Sistemos failai · privilegijuota prieiga",
        icon = if (rootStorageAvailable) Icons.Rounded.LockOpen else Icons.Rounded.Folder,
        usageFraction = rootUsageFraction,
        onClick = onOpenRoot,
    ))
    val locations = HomeCustomizationRules.orderedStorageIds(customization, allLocations.keys)
        .asSequence()
        .filterNot(customization.hiddenStorageIds::contains)
        .mapNotNull(allLocations::get)
        .toList()
    if (displaySettings.layoutMode == DirectoryLayoutMode.GRID) {
        val columns = displaySettings.gridColumns.coerceIn(1, 3)
        val spacing = (8f * displaySettings.spacingScalePercent / 100f).dp
        locations.chunked(columns).forEach { rowLocations ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                rowLocations.forEach { location ->
                    StorageLocationTile(
                        location = location,
                        iconScalePercent = displaySettings.iconScalePercent,
                        modifier = Modifier.weight(1f).then(
                            if (location.id == HomeCustomizationRules.ROOT_STORAGE_ID) Modifier.testTag("root_storage_location") else Modifier,
                        ),
                    )
                }
                repeat(columns - rowLocations.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        locations.forEach { location ->
            StorageLocationCard(
                title = location.title,
                description = location.description,
                icon = location.icon,
                usageFraction = location.usageFraction,
                onClick = location.onClick,
                modifier = if (location.id == HomeCustomizationRules.ROOT_STORAGE_ID) Modifier.testTag("root_storage_location") else Modifier,
            )
        }
    }
}

@Composable
private fun QuickLocationsHomeSection(
    locations: List<QuickLocation>,
    displaySettings: DirectoryDisplaySettings,
    onOpen: (QuickLocation) -> Unit,
    onToggleLayout: () -> Unit,
    onConfigureLayout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LText("Greitos vietos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        DirectoryLayoutButton(
            grid = displaySettings.layoutMode == DirectoryLayoutMode.GRID,
            testTag = "home_layout_toggle",
            onToggleLayout = onToggleLayout,
            onOpenSettings = onConfigureLayout,
        )
    }
    if (locations.isEmpty()) {
        LText(
            "Greitųjų vietų nerodoma",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (displaySettings.layoutMode == DirectoryLayoutMode.GRID) {
        val columns = displaySettings.gridColumns.coerceIn(1, 6)
        val spacing = (8f * displaySettings.spacingScalePercent / 100f).dp
        locations.chunked(columns).forEach { rowLocations ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                rowLocations.forEach { location ->
                    QuickLocationTile(
                        location = location,
                        iconScalePercent = displaySettings.iconScalePercent,
                        compact = columns >= 5,
                        modifier = Modifier.weight(1f).testTag("quick_location_${location.id}"),
                        onClick = { onOpen(location) },
                    )
                }
                repeat(columns - rowLocations.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        locations.forEach { location ->
            StorageLocationCard(
                title = location.title,
                description = if (location.virtual) "Visa saugykla" else location.path,
                icon = location.icon,
                modifier = Modifier.testTag("quick_location_${location.id}"),
                onClick = { onOpen(location) },
            )
        }
    }
}

@Composable
private fun HomeToolsSection(
    trashCount: Int,
    favoritesCount: Int,
    tagsCount: Int,
    cloudCount: Int,
    bookmarkCount: Int,
    onOpenTrash: () -> Unit,
    onOpenPlans: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    LText("Įrankiai ir saugumas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    val tools = listOf(
        HomeToolLocation("trash", "Šiukšlinė", itemCountLabel(trashCount), Icons.Rounded.Delete, onOpenTrash),
        HomeToolLocation("plans", "AF planai", "AF planai ir operacijų istorija", Icons.AutoMirrored.Rounded.PlaylistAdd, onOpenPlans),
        HomeToolLocation("favorites", "Mėgstami", itemCountLabel(favoritesCount), Icons.Rounded.Star, onOpenFavorites),
        HomeToolLocation("tags", "Žymos", itemCountLabel(tagsCount), Icons.AutoMirrored.Rounded.Label, onOpenTags),
        HomeToolLocation("cloud", "Debesija", itemCountLabel(cloudCount), Icons.Rounded.Cloud, onOpenCloud),
        HomeToolLocation("bookmarks", "Žymelės", itemCountLabel(bookmarkCount), Icons.Rounded.Bookmark, onOpenBookmarks),
    )
    tools.chunked(2).forEach { rowTools ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowTools.forEach { tool ->
                ElevatedCard(
                    onClick = tool.onClick,
                    modifier = Modifier.weight(1f).height(116.dp).testTag("home_tool_${tool.id}"),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                        LText(tool.title, modifier = Modifier.padding(top = 6.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LText(tool.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeCustomizationDialog(
    customization: HomeCustomization,
    roots: List<StorageRoot>,
    currentPath: String,
    onDismiss: () -> Unit,
    onMoveSection: (HomeSection, Int) -> Unit,
    onMoveShortcut: (String, Int) -> Unit,
    onSetShortcutVisible: (String, Boolean) -> Unit,
    onMoveStorage: (String, Int) -> Unit,
    onSetStorageVisible: (String, Boolean) -> Unit,
    onRemoveShortcut: (String) -> Unit,
    onAddShortcut: (String, String) -> Boolean,
) {
    var showAdd by remember { mutableStateOf(false) }
    val storageItems = remember(roots, customization.storageOrder) {
        val labels = roots.associate { root ->
            root.id to when (root.kind) {
                StorageRootKind.INTERNAL -> "Vidinė atmintis"
                StorageRootKind.SD_CARD -> root.title.ifBlank { "SD kortelė" }
                StorageRootKind.USB_STORAGE -> root.title.ifBlank { "USB saugykla" }
                StorageRootKind.REMOVABLE -> root.title.ifBlank { "Išimama saugykla" }
            }
        } + (HomeCustomizationRules.ROOT_STORAGE_ID to "Root")
        HomeCustomizationRules.orderedStorageIds(customization, labels.keys).mapNotNull { id -> labels[id]?.let { id to it } }
    }
    AfModalDialog(
        title = "Tvarkyti pradžios ekraną",
        icon = Icons.Rounded.Edit,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("home_customization_dialog"),
        expandedContent = true,
        actions = {
            TextButton(onClick = onDismiss) { LText("Baigti") }
        },
    ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    LText("Pridėti failo ar aplanko nuorodą")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                item { LText("Sekcijų tvarka", style = MaterialTheme.typography.titleSmall) }
                items(customization.sectionOrder, key = HomeSection::name) { section ->
                    val index = customization.sectionOrder.indexOf(section)
                    HomeOrderRow(
                        title = homeSectionTitle(section),
                        canMoveUp = index > 0,
                        canMoveDown = index < customization.sectionOrder.lastIndex,
                        onMoveUp = { onMoveSection(section, -1) },
                        onMoveDown = { onMoveSection(section, 1) },
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LText("Saugyklos", style = MaterialTheme.typography.titleSmall)
                }
                items(storageItems, key = { it.first }) { (id, title) ->
                    val index = storageItems.indexOfFirst { it.first == id }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = id !in customization.hiddenStorageIds,
                            onCheckedChange = { onSetStorageVisible(id, it) },
                        )
                        LText(title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        IconButton(onClick = { onMoveStorage(id, -1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Perkelti aukštyn"))
                        }
                        IconButton(onClick = { onMoveStorage(id, 1) }, enabled = index < storageItems.lastIndex) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = uiText("Perkelti žemyn"))
                        }
                    }
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LText("Greitos vietos", style = MaterialTheme.typography.titleSmall)
                }
                items(customization.shortcuts, key = HomeShortcut::id) { shortcut ->
                    val index = customization.shortcuts.indexOfFirst { it.id == shortcut.id }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = shortcut.visible,
                            onCheckedChange = { onSetShortcutVisible(shortcut.id, it) },
                        )
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            if (shortcut.builtIn) LText(shortcut.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else Text(shortcut.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                shortcut.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onMoveShortcut(shortcut.id, -1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Perkelti aukštyn"))
                        }
                        IconButton(
                            onClick = { onMoveShortcut(shortcut.id, 1) },
                            enabled = index < customization.shortcuts.lastIndex,
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = uiText("Perkelti žemyn"))
                        }
                        if (!shortcut.builtIn) {
                            IconButton(onClick = { onRemoveShortcut(shortcut.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti greitą vietą"))
                            }
                        }
                    }
                }
                }
            }
    }

    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var path by remember(currentPath) { mutableStateOf(currentPath) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { LText("Pridėti greitą vietą") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { LText("Pavadinimas (nebūtina)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { LText("Failo arba aplanko kelias") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { if (onAddShortcut(title, path)) showAdd = false },
                    enabled = path.isNotBlank(),
                ) { LText("Pridėti") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun HomeOrderRow(
    title: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText(title, modifier = Modifier.weight(1f))
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Perkelti aukštyn"))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = uiText("Perkelti žemyn"))
        }
    }
}

private fun homeSectionTitle(section: HomeSection): String = when (section) {
    HomeSection.STORAGE -> "Saugyklos"
    HomeSection.TOOLS -> "Įrankiai ir saugumas"
    HomeSection.QUICK_LOCATIONS -> "Greitos vietos"
    HomeSection.RECENT_FILES -> "Naujausi failai"
}

private fun homeShortcutIcon(shortcut: HomeShortcut): ImageVector = when (shortcut.id) {
    "builtin.downloads" -> Icons.Rounded.Download
    "builtin.documents" -> Icons.Rounded.Description
    "builtin.pictures" -> Icons.Rounded.PhotoLibrary
    "builtin.videos" -> Icons.Rounded.VideoLibrary
    "builtin.music" -> Icons.Rounded.MusicNote
    "builtin.archives" -> Icons.Rounded.Archive
    "builtin.apps" -> Icons.Rounded.Android
    "builtin.installed_apps" -> Icons.Rounded.Apps
    else -> if (File(shortcut.path).isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description
}

@Composable
private fun RecentFileCard(item: RecentFileItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier.width(188.dp).height(172.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LocalFileVisual(
                entry = item.entry,
                targetWidth = 72.dp,
                targetHeight = 68.dp,
                showThumbnails = true,
                modifier = Modifier.fillMaxWidth().height(68.dp),
            )
            Text(item.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(FileSystemRules.humanBytes(item.entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(recentTimeLabel(context, item.recentAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RecentFileListItem(item: RecentFileItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LocalFileVisual(
                entry = item.entry,
                targetWidth = 44.dp,
                targetHeight = 44.dp,
                showThumbnails = true,
                modifier = Modifier.size(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${FileSystemRules.humanBytes(item.entry.sizeBytes)} · ${recentTimeLabel(context, item.recentAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun recentTimeLabel(context: android.content.Context, timestampMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(context, timestampMillis, false).toString()

internal data class QuickLocation(
    val id: String,
    val title: String,
    val path: String,
    val icon: ImageVector,
    val virtual: Boolean = false,
)

private data class HomeStorageLocation(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val usageFraction: Float?,
    val onClick: () -> Unit,
)

private data class HomeToolLocation(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun QuickLocationTile(
    location: QuickLocation,
    iconScalePercent: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val iconSize = ((if (compact) 24f else 32f) * iconScalePercent / 100f).dp
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 80.dp else 92.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(location.icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(5.dp))
            LText(
                location.title,
                style = MaterialTheme.typography.labelSmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StorageLocationTile(
    location: HomeStorageLocation,
    iconScalePercent: Int,
    modifier: Modifier = Modifier,
) {
    val iconSize = (34f * iconScalePercent / 100f).dp
    val animatedUsage by animateFloatAsState(
        targetValue = location.usageFraction?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = spring(),
        label = "storage tile usage",
    )
    ElevatedCard(
        onClick = location.onClick,
        modifier = modifier.height(132.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(location.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize))
            LText(location.title, modifier = Modifier.padding(top = 6.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LText(location.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            location.usageFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { animatedUsage },
                    modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                LText(
                    "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}% užimta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StorageLocationCard(
    title: String,
    description: String,
    icon: ImageVector,
    usageFraction: Float? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val animatedUsage by animateFloatAsState(
        targetValue = usageFraction?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = spring(),
        label = "storage usage",
    )
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(modifier = Modifier.weight(1f)) {
                LText(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LText(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                usageFraction?.let {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { animatedUsage },
                            modifier = Modifier.weight(1f).height(10.dp).padding(top = 3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        LText(
                            "${(it.coerceIn(0f, 1f) * 100f).roundToInt()}% užimta",
                            modifier = Modifier.padding(start = 9.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun itemCountLabel(count: Int): String = if (count == 1) "1 elementas" else "$count elementų"
