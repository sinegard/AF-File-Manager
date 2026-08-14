package com.affilemanager.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.archive.ArchiveEntryInfo
import com.affilemanager.app.archive.ArchiveFormat
import com.affilemanager.app.data.SafLocation
import com.affilemanager.app.data.SafEntry
import com.affilemanager.app.data.RecentItem
import com.affilemanager.app.data.SavedSearch
import com.affilemanager.app.data.TrashBrowserEntry
import com.affilemanager.app.data.TrashItem
import com.affilemanager.app.data.TrashPathRules
import com.affilemanager.app.data.PanelWorkspace
import com.affilemanager.app.data.WorkspaceSession
import com.affilemanager.app.data.WorkspaceTab
import com.affilemanager.app.data.WorkspaceSessionRepository
import com.affilemanager.app.data.FileTagSnapshot
import com.affilemanager.app.model.ClipboardMode
import com.affilemanager.app.model.ContentFileEntry
import com.affilemanager.app.model.ClipboardState
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.StorageAnalysis
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProfileRules
import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemoteErrorInfo
import com.affilemanager.app.network.RemoteErrorPresenter
import com.affilemanager.app.network.RemoteOperation
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.network.ReconnectingRemoteClient
import com.affilemanager.app.network.RemoteCopyEngine
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.operations.BatchRenamePreview
import com.affilemanager.app.operations.BatchRenameSpec
import com.affilemanager.app.operations.BatchRenameUndo
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import com.affilemanager.app.security.VaultHeader
import com.affilemanager.app.sync.SyncActionType
import com.affilemanager.app.sync.SyncConflictPolicy
import com.affilemanager.app.sync.SyncMode
import com.affilemanager.app.sync.SyncPreview
import com.affilemanager.app.sync.SyncSchedule
import com.affilemanager.app.update.AppRelease
import com.affilemanager.app.update.AppUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppSection {
    FILES,
    ANALYZE,
    CONNECTIONS,
    TOOLS,
}

enum class PanelId {
    LEFT,
    RIGHT,
}

data class PanelUiState(
    val path: String = Environment.getExternalStorageDirectory().absolutePath,
    val entries: List<FileEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val sortMode: SortMode = SortMode.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val includeHidden: Boolean = false,
    val grid: Boolean = false,
    val showThumbnails: Boolean = false,
    val loading: Boolean = false,
    val listingScannedEntries: Int = 0,
    val listingMetadataEntries: Int = 0,
    val listingTruncated: Boolean = false,
    val error: String? = null,
    val backHistory: List<String> = emptyList(),
    val forwardHistory: List<String> = emptyList(),
)

data class SearchUiState(
    val filters: SearchFilters = SearchFilters(),
    val roots: List<String> = emptyList(),
    val results: List<FileEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val scannedEntries: Int = 0,
    val truncated: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
)

data class BatchRenameUiState(
    val open: Boolean = false,
    val sourcePaths: List<String> = emptyList(),
    val spec: BatchRenameSpec = BatchRenameSpec(),
    val preview: BatchRenamePreview? = null,
    val running: Boolean = false,
    val error: String? = null,
)

data class AnalysisUiState(
    val analysis: StorageAnalysis? = null,
    val duplicates: List<DuplicateGroup> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

enum class PanelComparisonStatus { SAME, DIFFERENT, LEFT_ONLY, RIGHT_ONLY }

data class PanelComparisonEntry(
    val name: String,
    val status: PanelComparisonStatus,
    val leftPath: String? = null,
    val rightPath: String? = null,
    val detail: String,
)

data class PanelComparisonUiState(
    val open: Boolean = false,
    val running: Boolean = false,
    val leftPath: String = "",
    val rightPath: String = "",
    val entries: List<PanelComparisonEntry> = emptyList(),
    val error: String? = null,
)

data class NetworkUiState(
    val profiles: List<NetworkProfile> = emptyList(),
    val connectedProfile: NetworkProfile? = null,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: RemoteErrorInfo? = null,
)

data class SafBrowserUiState(
    val location: SafLocation? = null,
    val currentUri: String? = null,
    val title: String = "",
    val entries: List<SafEntry> = emptyList(),
    val backStack: List<Pair<String, String>> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class TrashBrowserUiState(
    val open: Boolean = false,
    val itemId: String? = null,
    val relativePath: String = "",
    val rootName: String? = null,
    val entries: List<TrashBrowserEntry> = emptyList(),
    val showThumbnails: Boolean = false,
    val loading: Boolean = false,
    val emptying: Boolean = false,
    val error: String? = null,
)

data class SyncUiState(
    val mode: SyncMode = SyncMode.TWO_WAY,
    val conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.REPORT_ONLY,
    val localRoot: String = "",
    val remoteRoot: String = "/",
    val preview: SyncPreview? = null,
    val running: Boolean = false,
    val error: String? = null,
)

sealed interface PreviewTarget {
    data class LocalFile(val entry: FileEntry) : PreviewTarget
    data class TrashFile(val entry: FileEntry) : PreviewTarget
    data class ContentFile(val entry: ContentFileEntry) : PreviewTarget
    data class Archive(val file: FileEntry, val entries: List<ArchiveEntryInfo>) : PreviewTarget
    data class Vault(val file: FileEntry, val header: VaultHeader) : PreviewTarget
}

enum class UiMessageAction { UNDO_BATCH_RENAME }

data class UiMessage(
    val text: String,
    val error: Boolean = false,
    val action: UiMessageAction? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as AFFileManagerApplication).graph
    private val initialPrimaryPath = Environment.getExternalStorageDirectory().absolutePath
    private val initialDownloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .takeIf(File::isDirectory)?.absolutePath ?: initialPrimaryPath
    private val initialWorkspace = graph.workspaceSession.load(initialDownloadsPath, initialPrimaryPath)

    private val _section = MutableStateFlow(AppSection.FILES)
    val section: StateFlow<AppSection> = _section.asStateFlow()

    val updateState: StateFlow<AppUpdateState> = graph.updates.state

    private val _filesHomeVisible = MutableStateFlow(true)
    val filesHomeVisible: StateFlow<Boolean> = _filesHomeVisible.asStateFlow()

    private val _activePanel = MutableStateFlow(PanelId.LEFT)
    val activePanel: StateFlow<PanelId> = _activePanel.asStateFlow()

    private val _leftTabs = MutableStateFlow(initialWorkspace.left)
    val leftTabs: StateFlow<PanelWorkspace> = _leftTabs.asStateFlow()

    private val _rightTabs = MutableStateFlow(initialWorkspace.right)
    val rightTabs: StateFlow<PanelWorkspace> = _rightTabs.asStateFlow()

    private val fileScrollPositions = FileScrollPositionStore()

    private val _leftPanel = MutableStateFlow(initialWorkspace.left.activeTab.toPanelUiState())
    val leftPanel: StateFlow<PanelUiState> = _leftPanel.asStateFlow()

    private val _rightPanel = MutableStateFlow(initialWorkspace.right.activeTab.toPanelUiState())
    val rightPanel: StateFlow<PanelUiState> = _rightPanel.asStateFlow()

    private val _roots = MutableStateFlow<List<StorageRoot>>(emptyList())
    val roots: StateFlow<List<StorageRoot>> = _roots.asStateFlow()

    private val _clipboard = MutableStateFlow<ClipboardState?>(null)
    val clipboard: StateFlow<ClipboardState?> = _clipboard.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _analysisState = MutableStateFlow(AnalysisUiState())
    val analysisState: StateFlow<AnalysisUiState> = _analysisState.asStateFlow()

    private val _panelComparison = MutableStateFlow(PanelComparisonUiState())
    val panelComparison: StateFlow<PanelComparisonUiState> = _panelComparison.asStateFlow()

    private val _batchRename = MutableStateFlow(BatchRenameUiState())
    val batchRename: StateFlow<BatchRenameUiState> = _batchRename.asStateFlow()

    private val _renameUndo = MutableStateFlow<BatchRenameUndo?>(null)
    val renameUndo: StateFlow<BatchRenameUndo?> = _renameUndo.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkUiState())
    val networkState: StateFlow<NetworkUiState> = _networkState.asStateFlow()

    private val _trashItems = MutableStateFlow<List<TrashItem>>(emptyList())
    val trashItems: StateFlow<List<TrashItem>> = _trashItems.asStateFlow()

    private val _trashBrowser = MutableStateFlow(TrashBrowserUiState())
    val trashBrowser: StateFlow<TrashBrowserUiState> = _trashBrowser.asStateFlow()

    private val _safLocations = MutableStateFlow<List<SafLocation>>(emptyList())
    val safLocations: StateFlow<List<SafLocation>> = _safLocations.asStateFlow()

    private val _safBrowser = MutableStateFlow(SafBrowserUiState())
    val safBrowser: StateFlow<SafBrowserUiState> = _safBrowser.asStateFlow()

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentItem>>(emptyList())
    val recents: StateFlow<List<RecentItem>> = _recents.asStateFlow()

    private val _savedSearches = MutableStateFlow<List<SavedSearch>>(emptyList())
    val savedSearches: StateFlow<List<SavedSearch>> = _savedSearches.asStateFlow()

    private val _tagSnapshot = MutableStateFlow(FileTagSnapshot())
    val tagSnapshot: StateFlow<FileTagSnapshot> = _tagSnapshot.asStateFlow()

    private val _syncState = MutableStateFlow(SyncUiState())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    private val _syncSchedules = MutableStateFlow<List<SyncSchedule>>(emptyList())
    val syncSchedules: StateFlow<List<SyncSchedule>> = _syncSchedules.asStateFlow()

    val appLockEnabled = graph.appLock.enabled

    private val _preview = MutableStateFlow<PreviewTarget?>(null)
    val preview: StateFlow<PreviewTarget?> = _preview.asStateFlow()

    val operations = graph.operationManager.operations

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var remoteClient: RemoteClient? = null
    private var searchJob: Job? = null
    private var analysisJob: Job? = null
    private var batchRenamePreviewJob: Job? = null
    private var leftPanelRefreshJob: Job? = null
    private var rightPanelRefreshJob: Job? = null
    private val handledOperations = ArrayDeque<String>()
    private val workspaceSaveRequests = Channel<WorkspaceSession>(Channel.CONFLATED)

    init {
        graph.applicationScope.launch {
            for (session in workspaceSaveRequests) {
                runCatching { graph.workspaceSession.save(session) }
                    .onFailure { message(it.message ?: "Darbo sesijos išsaugoti nepavyko", true) }
            }
        }
        refreshPermissionDependentState()
        refreshProfiles()
        refreshTrash()
        refreshSafLocations()
        refreshNavigationState()
        refreshTags()
        refreshSyncSchedules()
        viewModelScope.launch {
            operations.collectLatest { snapshots ->
                snapshots.filter {
                    it.status in setOf(OperationStatus.SUCCEEDED, OperationStatus.COMPLETED_WITH_ERRORS) && it.id !in handledOperations
                }.forEach { snapshot ->
                    handledOperations.add(snapshot.id)
                    while (handledOperations.size > 128) handledOperations.removeFirst()
                    refreshPanel(PanelId.LEFT)
                    refreshPanel(PanelId.RIGHT)
                    refreshTrash()
                    if (_safBrowser.value.location != null) refreshSafBrowser()
                }
            }
        }
    }

    fun setSection(section: AppSection) {
        _section.value = section
        if (section == AppSection.FILES) _filesHomeVisible.value = true
        if (section == AppSection.TOOLS) {
            refreshTrash()
            refreshSafLocations()
            refreshSyncSchedules()
        }
    }

    fun activatePanel(panel: PanelId) {
        _activePanel.value = panel
    }

    fun refreshPermissionDependentState() {
        viewModelScope.launch {
            _roots.value = graph.localFiles.roots()
            refreshPanel(PanelId.LEFT)
            refreshPanel(PanelId.RIGHT)
        }
    }

    fun navigate(panel: PanelId, path: String, rememberHistory: Boolean = true) {
        val target = runCatching { File(path).canonicalPath }.getOrElse {
            message(it.message ?: "Kelias nepasiekiamas", true)
            return
        }
        _filesHomeVisible.value = false
        val showThumbnails = savedThumbnailMode(target)
        val currentState = panelFlow(panel).value
        if (currentState.path != target) {
            fileScrollPositions.reset(tabsFlow(panel).value.activeTabId, target)
        }
        panelFlow(panel).update { state ->
            if (state.path == target) return@update state.copy(selectedPaths = emptySet())
            state.copy(
                path = target,
                entries = emptyList(),
                showThumbnails = showThumbnails,
                selectedPaths = emptySet(),
                loading = true,
                listingScannedEntries = 0,
                listingMetadataEntries = 0,
                listingTruncated = false,
                error = null,
                backHistory = if (rememberHistory) (state.backHistory + state.path).takeLast(50) else state.backHistory,
                forwardHistory = if (rememberHistory) emptyList() else state.forwardHistory,
            )
        }
        syncActiveTab(panel)
        refreshPanel(panel)
    }

    fun fileScrollPosition(key: FileScrollKey): FileScrollPosition = fileScrollPositions.read(key)

    fun saveFileScrollPosition(key: FileScrollKey, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        fileScrollPositions.write(key, firstVisibleItemIndex, firstVisibleItemScrollOffset)
    }

    fun navigateBack(panel: PanelId): Boolean {
        val flow = panelFlow(panel)
        val state = flow.value
        val target = state.backHistory.lastOrNull() ?: return false
        val showThumbnails = savedThumbnailMode(target)
        flow.update {
            it.copy(
                path = target,
                entries = emptyList(),
                showThumbnails = showThumbnails,
                selectedPaths = emptySet(),
                loading = true,
                listingScannedEntries = 0,
                listingMetadataEntries = 0,
                listingTruncated = false,
                error = null,
                backHistory = it.backHistory.dropLast(1),
                forwardHistory = (it.forwardHistory + it.path).takeLast(50),
            )
        }
        syncActiveTab(panel)
        refreshPanel(panel)
        return true
    }

    fun navigateForward(panel: PanelId): Boolean {
        val flow = panelFlow(panel)
        val state = flow.value
        val target = state.forwardHistory.lastOrNull() ?: return false
        val showThumbnails = savedThumbnailMode(target)
        flow.update {
            it.copy(
                path = target,
                entries = emptyList(),
                showThumbnails = showThumbnails,
                selectedPaths = emptySet(),
                loading = true,
                listingScannedEntries = 0,
                listingMetadataEntries = 0,
                listingTruncated = false,
                error = null,
                backHistory = (it.backHistory + it.path).takeLast(50),
                forwardHistory = it.forwardHistory.dropLast(1),
            )
        }
        syncActiveTab(panel)
        refreshPanel(panel)
        return true
    }

    fun navigateUp(panel: PanelId): Boolean {
        val state = panelFlow(panel).value
        val parent = File(state.path).parentFile ?: return false
        navigate(panel, parent.absolutePath)
        return true
    }

    fun refreshPanel(panel: PanelId) {
        val flow = panelFlow(panel)
        val snapshot = flow.value
        panelRefreshJob(panel)?.cancel()
        val job = viewModelScope.launch {
            flow.update {
                it.copy(
                    loading = true,
                    listingScannedEntries = 0,
                    listingMetadataEntries = 0,
                    listingTruncated = false,
                    error = null,
                )
            }
            val result = graph.localFiles.listProgressively(
                directoryPath = snapshot.path,
                includeHidden = snapshot.includeHidden,
                sortMode = snapshot.sortMode,
                sortDirection = snapshot.sortDirection,
                onProgress = { update ->
                    flow.update { current ->
                        if (current.matchesListingRequest(snapshot)) {
                            current.copy(
                                entries = update.entries,
                                loading = !update.complete,
                                listingScannedEntries = update.scannedEntries,
                                listingMetadataEntries = update.metadataEntries,
                                listingTruncated = update.truncated,
                                error = null,
                            )
                        } else current
                    }
                },
            )
            result.fold(
                onSuccess = { entries ->
                    flow.update { current ->
                        if (current.matchesListingRequest(snapshot)) {
                            current.copy(
                                entries = entries,
                                loading = false,
                                listingMetadataEntries = entries.size,
                                error = null,
                            )
                        } else current
                    }
                },
                onFailure = { error ->
                    flow.update { current ->
                        if (current.matchesListingRequest(snapshot)) {
                            current.copy(entries = emptyList(), loading = false, error = error.message)
                        } else current
                    }
                },
            )
        }
        setPanelRefreshJob(panel, job)
    }

    fun toggleHidden(panel: PanelId) {
        panelFlow(panel).update { it.copy(includeHidden = !it.includeHidden) }
        syncActiveTab(panel)
        refreshPanel(panel)
    }

    fun toggleGrid(panel: PanelId) {
        panelFlow(panel).update { it.copy(grid = !it.grid) }
        syncActiveTab(panel)
    }

    fun toggleThumbnails(panel: PanelId) {
        val flow = panelFlow(panel)
        val snapshot = flow.value
        val enabled = !snapshot.showThumbnails
        runCatching { graph.navigation.setThumbnailsEnabled(snapshot.path, enabled) }
            .onSuccess {
                _leftPanel.update { current ->
                    if (current.path == snapshot.path) current.copy(showThumbnails = enabled) else current
                }
                _rightPanel.update { current ->
                    if (current.path == snapshot.path) current.copy(showThumbnails = enabled) else current
                }
            }
            .onFailure { message(it.message ?: "Katalogo vaizdo nustatymo išsaugoti nepavyko", true) }
    }

    fun setSort(panel: PanelId, mode: SortMode) {
        panelFlow(panel).update {
            if (it.sortMode == mode) it.copy(sortDirection = if (it.sortDirection == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING)
            else it.copy(sortMode = mode, sortDirection = SortDirection.ASCENDING)
        }
        syncActiveTab(panel)
        refreshPanel(panel)
    }

    fun newTab(panel: PanelId) {
        syncActiveTab(panel, persist = false)
        val tabsFlow = tabsFlow(panel)
        val current = tabsFlow.value
        if (current.tabs.size >= WorkspaceSessionRepository.MAX_TABS_PER_PANEL) {
            message("Viename skydelyje galima turėti iki ${WorkspaceSessionRepository.MAX_TABS_PER_PANEL} kortelių", true)
            return
        }
        val tab = graph.workspaceSession.newTab(panelFlow(panel).value.path)
        tabsFlow.value = current.copy(tabs = current.tabs + tab, activeTabId = tab.id)
        panelFlow(panel).value = tab.toPanelUiState()
        persistWorkspace()
        refreshPanel(panel)
    }

    fun duplicateTab(panel: PanelId) {
        syncActiveTab(panel, persist = false)
        val tabsFlow = tabsFlow(panel)
        val current = tabsFlow.value
        if (current.tabs.size >= WorkspaceSessionRepository.MAX_TABS_PER_PANEL) {
            message("Kortelių riba pasiekta", true)
            return
        }
        val source = current.activeTab
        val duplicate = source.copy(id = java.util.UUID.randomUUID().toString(), locked = false)
        tabsFlow.value = current.copy(tabs = current.tabs + duplicate, activeTabId = duplicate.id)
        panelFlow(panel).value = duplicate.toPanelUiState()
        persistWorkspace()
        refreshPanel(panel)
    }

    fun activateTab(panel: PanelId, tabId: String) {
        val tabsFlow = tabsFlow(panel)
        if (tabsFlow.value.activeTabId == tabId) return
        syncActiveTab(panel, persist = false)
        val tab = tabsFlow.value.tabs.firstOrNull { it.id == tabId } ?: return
        tabsFlow.update { it.copy(activeTabId = tabId) }
        panelFlow(panel).value = tab.toPanelUiState()
        activatePanel(panel)
        persistWorkspace()
        refreshPanel(panel)
    }

    fun toggleTabLock(panel: PanelId) {
        syncActiveTab(panel, persist = false)
        tabsFlow(panel).update { workspace ->
            workspace.copy(tabs = workspace.tabs.map { tab ->
                if (tab.id == workspace.activeTabId) tab.copy(locked = !tab.locked) else tab
            })
        }
        persistWorkspace()
    }

    fun closeActiveTab(panel: PanelId) {
        syncActiveTab(panel, persist = false)
        val flow = tabsFlow(panel)
        val workspace = flow.value
        val closing = workspace.activeTab
        if (closing.locked) {
            message("Kortelė užrakinta; pirmiausia ją atrakinkite", true)
            return
        }
        if (workspace.tabs.size == 1) {
            message("Paskutinės skydelio kortelės uždaryti negalima", true)
            return
        }
        val closingIndex = workspace.tabs.indexOfFirst { it.id == closing.id }
        val remaining = workspace.tabs.filterNot { it.id == closing.id }
        val next = remaining[closingIndex.coerceAtMost(remaining.lastIndex)]
        flow.value = workspace.copy(
            tabs = remaining,
            activeTabId = next.id,
            closedTabs = (listOf(closing.copy(locked = false)) + workspace.closedTabs).take(WorkspaceSessionRepository.MAX_CLOSED_TABS),
        )
        panelFlow(panel).value = next.toPanelUiState()
        persistWorkspace()
        refreshPanel(panel)
    }

    fun restoreClosedTab(panel: PanelId) {
        val flow = tabsFlow(panel)
        syncActiveTab(panel, persist = false)
        val workspace = flow.value
        val restored = workspace.closedTabs.firstOrNull() ?: run {
            message("Atkuriamų uždarytų kortelių nėra")
            return
        }
        if (workspace.tabs.size >= WorkspaceSessionRepository.MAX_TABS_PER_PANEL) {
            message("Kortelių riba pasiekta", true)
            return
        }
        flow.value = workspace.copy(
            tabs = workspace.tabs + restored,
            activeTabId = restored.id,
            closedTabs = workspace.closedTabs.drop(1),
        )
        panelFlow(panel).value = restored.toPanelUiState()
        persistWorkspace()
        refreshPanel(panel)
    }

    fun swapPanels() {
        syncActiveTab(PanelId.LEFT, persist = false)
        syncActiveTab(PanelId.RIGHT, persist = false)
        leftPanelRefreshJob?.cancel()
        rightPanelRefreshJob?.cancel()
        val leftWorkspace = _leftTabs.value
        val rightWorkspace = _rightTabs.value
        val leftState = _leftPanel.value
        val rightState = _rightPanel.value
        _leftTabs.value = rightWorkspace
        _rightTabs.value = leftWorkspace
        _leftPanel.value = rightState.copy(selectedPaths = emptySet())
        _rightPanel.value = leftState.copy(selectedPaths = emptySet())
        persistWorkspace()
        refreshPanel(PanelId.LEFT)
        refreshPanel(PanelId.RIGHT)
    }

    fun comparePanels() {
        val leftPath = _leftPanel.value.path
        val rightPath = _rightPanel.value.path
        _panelComparison.value = PanelComparisonUiState(open = true, running = true, leftPath = leftPath, rightPath = rightPath)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val leftDirectory = File(leftPath).canonicalFile
                    val rightDirectory = File(rightPath).canonicalFile
                    require(leftDirectory.isDirectory && rightDirectory.isDirectory) { "Abu skydeliai turi rodyti pasiekiamus aplankus" }
                    val leftFiles = leftDirectory.listFiles()?.toList().orEmpty()
                    val rightFiles = rightDirectory.listFiles()?.toList().orEmpty()
                    require(leftFiles.size <= 50_000 && rightFiles.size <= 50_000) { "Greito palyginimo riba – 50 000 elementų skydelyje" }
                    val leftByName = leftFiles.associateBy(File::getName)
                    val rightByName = rightFiles.associateBy(File::getName)
                    (leftByName.keys + rightByName.keys).map { name ->
                        val left = leftByName[name]
                        val right = rightByName[name]
                        val status = when {
                            left == null -> PanelComparisonStatus.RIGHT_ONLY
                            right == null -> PanelComparisonStatus.LEFT_ONLY
                            left.isDirectory != right.isDirectory -> PanelComparisonStatus.DIFFERENT
                            left.isDirectory -> PanelComparisonStatus.SAME
                            left.length() == right.length() && left.lastModified() == right.lastModified() -> PanelComparisonStatus.SAME
                            else -> PanelComparisonStatus.DIFFERENT
                        }
                        PanelComparisonEntry(
                            name = name,
                            status = status,
                            leftPath = left?.absolutePath,
                            rightPath = right?.absolutePath,
                            detail = when (status) {
                                PanelComparisonStatus.SAME -> if (left?.isDirectory == true) "Aplankas abiejose pusėse" else "Sutampa dydis ir keitimo data"
                                PanelComparisonStatus.DIFFERENT -> "Skiriasi tipas, dydis arba keitimo data"
                                PanelComparisonStatus.LEFT_ONLY -> "Tik kairiajame skydelyje"
                                PanelComparisonStatus.RIGHT_ONLY -> "Tik dešiniajame skydelyje"
                            },
                        )
                    }.sortedWith(compareBy<PanelComparisonEntry> { it.status == PanelComparisonStatus.SAME }.thenBy { it.name.lowercase() })
                }
            }.onSuccess { entries ->
                if (_panelComparison.value.leftPath == leftPath && _panelComparison.value.rightPath == rightPath) {
                    _panelComparison.value = PanelComparisonUiState(true, false, leftPath, rightPath, entries)
                }
            }.onFailure { error ->
                _panelComparison.value = PanelComparisonUiState(true, false, leftPath, rightPath, error = error.message)
            }
        }
    }

    fun closePanelComparison() {
        _panelComparison.value = PanelComparisonUiState()
    }

    fun toggleSelection(panel: PanelId, path: String) {
        activatePanel(panel)
        panelFlow(panel).update { state ->
            val selection = state.selectedPaths.toMutableSet()
            if (!selection.add(path)) selection.remove(path)
            state.copy(selectedPaths = selection)
        }
    }

    fun selectAll(panel: PanelId) {
        panelFlow(panel).update { it.copy(selectedPaths = it.entries.map(FileEntry::absolutePath).toSet()) }
    }

    fun clearSelection(panel: PanelId) {
        panelFlow(panel).update { it.copy(selectedPaths = emptySet()) }
    }

    fun applyTags(paths: List<String>, tags: Set<String>, rating: Int?, newTagColorArgb: Int = 0xff1976d2.toInt()) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.fileTags.apply(paths, tags, rating, newTagColorArgb) } }
                .onSuccess {
                    _tagSnapshot.value = it
                    message("Žymos išsaugotos ${paths.distinct().size} elementui(-ams)")
                }
                .onFailure { message(it.message ?: "Žymų išsaugoti nepavyko", true) }
        }
    }

    fun clearTags(paths: List<String>) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.fileTags.clear(paths) } }
                .onSuccess {
                    _tagSnapshot.value = it
                    message("Žymos pašalintos")
                }
                .onFailure { message(it.message ?: "Žymų pašalinti nepavyko", true) }
        }
    }

    fun exportTags(panel: PanelId) {
        val directory = File(panelFlow(panel).value.path)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.fileTags.exportTo(directory) } }
                .onSuccess { message("Žymos eksportuotos: ${it.name}") }
                .onFailure { message(it.message ?: "Žymų eksportuoti nepavyko", true) }
        }
    }

    fun importTags(path: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.fileTags.importFrom(File(path)) } }
                .onSuccess {
                    _tagSnapshot.value = it
                    message("Žymos importuotos")
                }
                .onFailure { message(it.message ?: "Žymų importuoti nepavyko", true) }
        }
    }

    fun refreshTags() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.fileTags.snapshot() } }
                .onSuccess { _tagSnapshot.value = it }
                .onFailure { message(it.message ?: "Žymų perskaityti nepavyko", true) }
        }
    }

    fun copySelection(panel: PanelId, move: Boolean) {
        val selected = panelFlow(panel).value.selectedPaths.toList()
        if (selected.isEmpty()) return
        _clipboard.value = ClipboardState(selected, if (move) ClipboardMode.MOVE else ClipboardMode.COPY)
        clearSelection(panel)
        message(if (move) "Paruošta perkelti: ${selected.size}" else "Nukopijuota į iškarpinę: ${selected.size}")
    }

    fun paste(
        panel: PanelId,
        conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        verification: TransferVerification = TransferVerification.SIZE,
        failurePolicy: TransferFailurePolicy = TransferFailurePolicy.STOP,
    ) {
        val clipboard = _clipboard.value ?: return
        val destination = panelFlow(panel).value.path
        val moving = clipboard.mode == ClipboardMode.MOVE
        viewModelScope.launch {
            graph.durableTransfers.createAndSubmit(
                sourcePaths = clipboard.paths,
                destinationPath = destination,
                move = moving,
                conflictPolicy = conflictPolicy,
                verification = verification,
                failurePolicy = if (moving) TransferFailurePolicy.STOP else failurePolicy,
            ).onSuccess {
                if (moving) _clipboard.value = null
                message("Patvarus planas išsaugotas ir įtrauktas į operacijų eilę")
            }.onFailure { message(it.message ?: "Operacijos plano sukurti nepavyko", true) }
        }
    }

    fun moveSelectionToTrash(panel: PanelId) {
        val selected = panelFlow(panel).value.selectedPaths.toList()
        if (selected.isEmpty()) return
        graph.operationManager.submit("Keliama į šiukšlinę") {
            graph.trash.moveToTrash(selected, this)
        }.onSuccess { clearSelection(panel) }
            .onFailure { message(it.message ?: "Operacijos pradėti nepavyko", true) }
    }

    fun createDirectory(panel: PanelId, name: String) {
        viewModelScope.launch {
            graph.localFiles.createDirectory(panelFlow(panel).value.path, name).fold(
                onSuccess = { refreshPanel(panel) },
                onFailure = { message(it.message ?: "Aplanko sukurti nepavyko", true) },
            )
        }
    }

    fun createFile(panel: PanelId, name: String) {
        viewModelScope.launch {
            graph.localFiles.createEmptyFile(panelFlow(panel).value.path, name).fold(
                onSuccess = { refreshPanel(panel) },
                onFailure = { message(it.message ?: "Failo sukurti nepavyko", true) },
            )
        }
    }

    fun rename(panel: PanelId, path: String, name: String) {
        viewModelScope.launch {
            graph.localFiles.rename(path, name).fold(
                onSuccess = { clearSelection(panel); refreshPanel(panel) },
                onFailure = { message(it.message ?: "Pervadinti nepavyko", true) },
            )
        }
    }

    fun beginBatchRename(paths: List<String>) {
        val sources = paths.distinct()
        if (sources.isEmpty()) return
        if (sources.size > com.affilemanager.app.operations.BatchRenameEngine.MAX_RENAME_ITEMS) {
            message("Vienu metu galima pervadinti iki ${com.affilemanager.app.operations.BatchRenameEngine.MAX_RENAME_ITEMS} elementų", true)
            return
        }
        _batchRename.value = BatchRenameUiState(open = true, sourcePaths = sources, running = true)
        scheduleBatchRenamePreview(immediate = true)
    }

    fun updateBatchRenameSpec(spec: BatchRenameSpec) {
        _batchRename.update { it.copy(spec = spec, preview = null, running = true, error = null) }
        scheduleBatchRenamePreview(immediate = false)
    }

    fun closeBatchRename() {
        batchRenamePreviewJob?.cancel()
        _batchRename.value = BatchRenameUiState()
    }

    fun executeBatchRename() {
        val preview = _batchRename.value.preview ?: return
        if (!preview.canExecute) return
        graph.operationManager.submit("Masinis pervadinimas") {
            val undo = graph.batchRename.execute(preview, this)
            _renameUndo.value = undo
            _leftPanel.update { it.copy(selectedPaths = emptySet()) }
            _rightPanel.update { it.copy(selectedPaths = emptySet()) }
            rerunSearchIfVisible()
            message(
                "Pervadinta: ${undo.items.size}",
                action = UiMessageAction.UNDO_BATCH_RENAME,
            )
        }.onSuccess {
            closeBatchRename()
        }.onFailure { message(it.message ?: "Pervadinimo pradėti nepavyko", true) }
    }

    fun undoBatchRename() {
        val undo = _renameUndo.value ?: return
        graph.operationManager.submit("Atšaukiamas masinis pervadinimas") {
            graph.batchRename.undo(undo, this)
            _renameUndo.value = null
            rerunSearchIfVisible()
            message("Pervadinimas atšauktas")
        }.onFailure { message(it.message ?: "Atšaukimo pradėti nepavyko", true) }
    }

    fun open(entry: FileEntry) {
        if (entry.isDirectory) {
            _section.value = AppSection.FILES
            navigate(_activePanel.value, entry.absolutePath)
            return
        }
        runCatching { _recents.value = graph.navigation.recordRecent(entry.absolutePath) }
            .onFailure { message(it.message ?: "Istorijos įrašyti nepavyko", true) }
        viewModelScope.launch {
            if (entry.extension == "afvault") {
                runCatching { graph.fileVault.inspect(entry.file) }
                    .onSuccess { _preview.value = PreviewTarget.Vault(entry, it) }
                    .onFailure { message(it.message ?: "Saugyklos perskaityti nepavyko", true) }
                return@launch
            }
            when (entry.kind) {
                com.affilemanager.app.model.EntryKind.ARCHIVE -> runCatching { graph.archives.list(entry.file) }
                    .onSuccess { _preview.value = PreviewTarget.Archive(entry, it) }
                    .onFailure { message(it.message ?: "Archyvo perskaityti nepavyko", true) }
                else -> _preview.value = PreviewTarget.LocalFile(entry)
            }
        }
    }

    fun closePreview() {
        _preview.value = null
    }

    fun openExternalUri(uri: Uri, mimeType: String?) {
        when (uri.scheme) {
            "file" -> {
                val path = uri.path
                if (path.isNullOrBlank()) {
                    message("Failo kelias nepateiktas", true)
                    return
                }
                runCatching { File(path).canonicalFile }
                    .onSuccess { file ->
                        if (file.isFile && file.canRead()) open(graph.localFiles.toEntry(file))
                        else message("Failas nepasiekiamas", true)
                    }
                    .onFailure { message(it.message ?: "Failas nepasiekiamas", true) }
            }
            "content" -> viewModelScope.launch {
                graph.contentFiles.describe(uri, mimeType).fold(
                    onSuccess = { _preview.value = PreviewTarget.ContentFile(it) },
                    onFailure = { message(it.message ?: "Failo atidaryti nepavyko", true) },
                )
            }
            else -> message("Šio tipo nuoroda nepalaikoma", true)
        }
    }

    fun extractArchive(file: FileEntry, password: CharArray? = null) {
        val destination = File(file.file.parentFile, file.name.substringBeforeLast('.').ifBlank { "išpakuota" })
        graph.operationManager.submit("Išpakuojamas ${file.name}") {
            graph.archives.extract(file.file, destination, password, this)
        }.onSuccess { closePreview() }
            .onFailure { message(it.message ?: "Išpakavimo pradėti nepavyko", true) }
    }

    fun createArchive(panel: PanelId, name: String, format: ArchiveFormat, password: CharArray? = null) {
        val state = panelFlow(panel).value
        val sources = state.selectedPaths.map(::File)
        if (sources.isEmpty()) return
        val suffix = when (format) {
            ArchiveFormat.ZIP -> ".zip"
            ArchiveFormat.SEVEN_Z -> ".7z"
            ArchiveFormat.TAR -> ".tar"
            ArchiveFormat.TAR_GZ -> ".tar.gz"
            else -> ""
        }
        val output = File(state.path, if (name.lowercase().endsWith(suffix)) name else name + suffix)
        graph.operationManager.submit("Kuriamas ${output.name}") {
            graph.archives.create(format, output, sources, password, this)
        }.onSuccess { clearSelection(panel) }
            .onFailure { message(it.message ?: "Archyvo kūrimo pradėti nepavyko", true) }
    }

    fun encryptFile(file: FileEntry, passphrase: CharArray) {
        val destination = File(file.file.parentFile, "${file.name}.afvault")
        graph.operationManager.submit("Šifruojamas ${file.name}") {
            graph.fileVault.encrypt(file.file, destination, passphrase, this)
        }.onFailure { message(it.message ?: "Šifravimo pradėti nepavyko", true) }
    }

    fun inspectVault(file: FileEntry) {
        viewModelScope.launch {
            runCatching { graph.fileVault.inspect(file.file) }
                .onSuccess { _preview.value = PreviewTarget.Vault(file, it) }
                .onFailure { message(it.message ?: "Saugyklos perskaityti nepavyko", true) }
        }
    }

    fun decryptVault(file: FileEntry, passphrase: CharArray) {
        graph.operationManager.submit("Iššifruojamas ${file.name}") {
            graph.fileVault.decrypt(file.file, file.file.parentFile ?: return@submit, passphrase, this)
        }.onSuccess { closePreview() }
            .onFailure { message(it.message ?: "Iššifravimo pradėti nepavyko", true) }
    }

    fun search(filters: SearchFilters, roots: List<String> = listOf(activePanelState().path)) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.value = SearchUiState(filters = filters, roots = roots, running = true)
            try {
                val taggedPaths = if (filters.tags.isEmpty()) null else graph.fileTags.pathsWithAll(filters.tags)
                val result = graph.search.search(roots, filters) { entry ->
                    taggedPaths == null || entry.absolutePath in taggedPaths
                }
                _searchState.value = SearchUiState(
                    filters = filters,
                    roots = roots,
                    results = result.entries,
                    scannedEntries = result.scannedEntries,
                    truncated = result.truncated,
                    running = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _searchState.value = SearchUiState(filters = filters, roots = roots, running = false, error = error.message)
            }
        }
    }

    fun toggleSearchSelection(path: String) {
        _searchState.update { state ->
            val selection = state.selectedPaths.toMutableSet()
            if (!selection.add(path)) selection.remove(path)
            state.copy(selectedPaths = selection)
        }
    }

    fun clearSearchSelection() {
        _searchState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun selectAllSearchResults() {
        _searchState.update { it.copy(selectedPaths = it.results.map(FileEntry::absolutePath).toSet()) }
    }

    fun copySearchSelection(move: Boolean) {
        val selected = orderedSearchSelection()
        if (selected.isEmpty()) return
        _clipboard.value = ClipboardState(selected, if (move) ClipboardMode.MOVE else ClipboardMode.COPY)
        clearSearchSelection()
        message(if (move) "Paruošta perkelti: ${selected.size}" else "Nukopijuota į iškarpinę: ${selected.size}")
    }

    fun trashSearchSelection() {
        val selected = orderedSearchSelection()
        if (selected.isEmpty()) return
        graph.operationManager.submit("Keliama į šiukšlinę") {
            graph.trash.moveToTrash(selected, this)
            clearSearchSelection()
            rerunSearchIfVisible()
        }.onFailure { message(it.message ?: "Operacijos pradėti nepavyko", true) }
    }

    fun trashDuplicateCopies(paths: List<String>, analysisRoot: String) {
        val selected = paths.distinct()
        if (selected.isEmpty()) return
        if (selected.size > 10_000) {
            message("Vienu metu galima tvarkyti iki 10 000 dublikatų", true)
            return
        }
        graph.operationManager.submit("Dublikatų kopijos keliamos į šiukšlinę") {
            graph.trash.moveToTrash(selected, this)
            viewModelScope.launch { analyze(analysisRoot) }
        }.onFailure { message(it.message ?: "Dublikatų tvarkymo pradėti nepavyko", true) }
    }

    fun batchRenameSearchSelection() {
        beginBatchRename(orderedSearchSelection())
    }

    fun revealSearchResult(entry: FileEntry) {
        val panel = _activePanel.value
        val parent = entry.file.parentFile ?: return
        _section.value = AppSection.FILES
        navigate(panel, parent.absolutePath)
        panelFlow(panel).update { it.copy(selectedPaths = setOf(entry.absolutePath)) }
    }

    fun analyze(path: String = activePanelState().path) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _analysisState.value = AnalysisUiState(running = true)
            try {
                val analysis = graph.search.analyze(listOf(path))
                val duplicates = graph.search.duplicates(listOf(path))
                _analysisState.value = AnalysisUiState(analysis, duplicates, running = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _analysisState.value = AnalysisUiState(running = false, error = error.message)
            }
        }
    }

    fun refreshTrash() {
        viewModelScope.launch {
            _trashItems.value = graph.trash.list()
            if (_trashBrowser.value.open) refreshTrashBrowser()
        }
    }

    fun openTrashBrowser() {
        _trashBrowser.value = TrashBrowserUiState(
            open = true,
            showThumbnails = savedThumbnailMode(trashDirectoryIdentity(null, "")),
        )
        refreshTrashBrowser()
    }

    fun closeTrashBrowser() {
        _trashBrowser.value = TrashBrowserUiState()
    }

    fun refreshTrashBrowser() {
        val snapshot = _trashBrowser.value
        if (!snapshot.open) return
        _trashBrowser.update { current ->
            if (current.itemId == snapshot.itemId && current.relativePath == snapshot.relativePath) {
                current.copy(loading = true, error = null)
            } else current
        }
        viewModelScope.launch {
            graph.trash.browse(snapshot.itemId, snapshot.relativePath).fold(
                onSuccess = { entries ->
                    _trashBrowser.update { current ->
                        if (current.itemId == snapshot.itemId && current.relativePath == snapshot.relativePath) {
                            current.copy(entries = entries, loading = false, error = null)
                        } else current
                    }
                },
                onFailure = { error ->
                    _trashBrowser.update { current ->
                        if (current.itemId == snapshot.itemId && current.relativePath == snapshot.relativePath) {
                            current.copy(entries = emptyList(), loading = false, error = error.message)
                        } else current
                    }
                },
            )
        }
    }

    fun openTrashEntry(entry: TrashBrowserEntry) {
        if (!entry.isDirectory) {
            _preview.value = PreviewTarget.TrashFile(entry.toFileEntry())
            return
        }
        val current = _trashBrowser.value
        val nextItemId = current.itemId ?: entry.itemId
        val nextRelativePath = if (current.itemId == null) "" else entry.relativePath
        val nextRootName = current.rootName ?: entry.name
        _trashBrowser.update {
            it.copy(
                itemId = nextItemId,
                relativePath = nextRelativePath,
                rootName = nextRootName,
                entries = emptyList(),
                showThumbnails = savedThumbnailMode(trashDirectoryIdentity(nextItemId, nextRelativePath)),
                error = null,
            )
        }
        refreshTrashBrowser()
    }

    fun navigateTrashBack(): Boolean {
        val current = _trashBrowser.value
        if (!current.open) return false
        if (current.itemId == null) {
            closeTrashBrowser()
            return true
        }
        val nextItemId = if (current.relativePath.isEmpty()) null else current.itemId
        val nextRelativePath = if (current.relativePath.isEmpty()) "" else TrashPathRules.parent(current.relativePath)
        _trashBrowser.update {
            it.copy(
                itemId = nextItemId,
                relativePath = nextRelativePath,
                rootName = if (nextItemId == null) null else it.rootName,
                entries = emptyList(),
                showThumbnails = savedThumbnailMode(trashDirectoryIdentity(nextItemId, nextRelativePath)),
                error = null,
            )
        }
        refreshTrashBrowser()
        return true
    }

    fun toggleTrashThumbnails() {
        val snapshot = _trashBrowser.value
        if (!snapshot.open) return
        val identity = trashDirectoryIdentity(snapshot.itemId, snapshot.relativePath)
        val enabled = !snapshot.showThumbnails
        runCatching { graph.navigation.setThumbnailsEnabled(identity, enabled) }
            .onSuccess {
                _trashBrowser.update { current ->
                    if (current.itemId == snapshot.itemId && current.relativePath == snapshot.relativePath) {
                        current.copy(showThumbnails = enabled)
                    } else current
                }
            }
            .onFailure { message(it.message ?: "Šiukšliadėžės vaizdo nustatymo išsaugoti nepavyko", true) }
    }

    fun emptyTrash() {
        if (_trashBrowser.value.emptying) return
        _trashBrowser.update { it.copy(emptying = true, error = null) }
        viewModelScope.launch {
            val result = graph.trash.emptyAll()
            _trashBrowser.update {
                it.copy(
                    itemId = null,
                    relativePath = "",
                    rootName = null,
                    entries = emptyList(),
                    showThumbnails = savedThumbnailMode(trashDirectoryIdentity(null, "")),
                    emptying = false,
                )
            }
            refreshTrash()
            when {
                result.failedItems > 0 -> message(
                    "Ištrinta: ${result.deletedItems}, nepavyko ištrinti: ${result.failedItems}",
                    true,
                )
                result.deletedItems > 0 -> message("Šiukšliadėžė išvalyta: ${result.deletedItems}")
                else -> message("Šiukšliadėžė jau tuščia")
            }
        }
    }

    fun refreshNavigationState() {
        runCatching {
            _favorites.value = graph.navigation.favorites()
            _recents.value = graph.navigation.recents()
            _savedSearches.value = graph.navigation.savedSearches()
        }.onFailure { message(it.message ?: "Naršymo nustatymų perskaityti nepavyko", true) }
    }

    fun toggleFavorite(path: String) {
        runCatching { _favorites.value = graph.navigation.toggleFavorite(path) }
            .onFailure { message(it.message ?: "Žymos pakeisti nepavyko", true) }
    }

    fun openQuickPath(path: String, panel: PanelId = _activePanel.value) {
        val file = File(path)
        if (file.isDirectory) navigate(panel, path)
        else if (file.isFile) open(graph.localFiles.toEntry(file))
        else message("Vieta nebeegzistuoja", true)
    }

    fun clearRecents() {
        runCatching { graph.navigation.clearRecents(); _recents.value = emptyList() }
            .onFailure { message(it.message ?: "Istorijos išvalyti nepavyko", true) }
    }

    fun saveSearch(name: String, roots: List<String>, filters: SearchFilters) {
        runCatching { _savedSearches.value = graph.navigation.saveSearch(name, roots, filters) }
            .onFailure { message(it.message ?: "Paieškos išsaugoti nepavyko", true) }
    }

    fun runSavedSearch(search: SavedSearch) {
        _section.value = AppSection.ANALYZE
        search(search.filters(), search.rootPaths)
    }

    fun removeSavedSearch(id: String) {
        runCatching { _savedSearches.value = graph.navigation.removeSearch(id) }
            .onFailure { message(it.message ?: "Paieškos pašalinti nepavyko", true) }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        runCatching { graph.appLock.setEnabled(enabled) }
            .onSuccess { message(if (enabled) "Programos užraktas įjungtas" else "Programos užraktas išjungtas") }
            .onFailure { message(it.message ?: "Užrakto nustatymo pakeisti nepavyko", true) }
    }

    fun restoreTrash(id: String) {
        viewModelScope.launch {
            graph.trash.restore(id).fold(
                onSuccess = { refreshTrash(); refreshPanel(PanelId.LEFT); refreshPanel(PanelId.RIGHT) },
                onFailure = { message(it.message ?: "Atkurti nepavyko", true) },
            )
        }
    }

    fun deleteTrashForever(id: String) {
        viewModelScope.launch {
            graph.trash.deleteForever(id).fold(
                onSuccess = { refreshTrash() },
                onFailure = { message(it.message ?: "Ištrinti nepavyko", true) },
            )
        }
    }

    fun refreshSafLocations() {
        viewModelScope.launch { _safLocations.value = graph.safFiles.locations() }
    }

    fun addSafLocation(uri: android.net.Uri, title: String) {
        viewModelScope.launch {
            graph.safFiles.addLocation(uri, title).fold(
                onSuccess = { refreshSafLocations() },
                onFailure = { message(it.message ?: "Vietos pridėti nepavyko", true) },
            )
        }
    }

    fun removeSafLocation(uri: String) {
        viewModelScope.launch {
            graph.safFiles.removeLocation(uri).fold(
                onSuccess = {
                    if (_safBrowser.value.location?.uri == uri) closeSafBrowser()
                    refreshSafLocations()
                },
                onFailure = { message(it.message ?: "Vietos pašalinti nepavyko", true) },
            )
        }
    }

    fun openSafLocation(location: SafLocation) {
        _safBrowser.value = SafBrowserUiState(location = location, currentUri = location.uri, title = location.title)
        refreshSafBrowser()
    }

    fun openSafEntry(entry: SafEntry) {
        if (entry.directory) {
            val state = _safBrowser.value
            val current = state.currentUri ?: return
            _safBrowser.update {
                it.copy(
                    currentUri = entry.uri,
                    title = entry.name,
                    backStack = (it.backStack + (current to it.title)).takeLast(64),
                    entries = emptyList(),
                )
            }
            refreshSafBrowser()
        } else {
            val application = getApplication<Application>()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(entry.uri), entry.mimeType ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { application.startActivity(Intent.createChooser(intent, "Atidaryti su").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { message(it.message ?: "Failo atidaryti nepavyko", true) }
        }
    }

    fun navigateSafBack(): Boolean {
        val state = _safBrowser.value
        val previous = state.backStack.lastOrNull() ?: return false
        _safBrowser.update {
            it.copy(currentUri = previous.first, title = previous.second, backStack = it.backStack.dropLast(1), entries = emptyList())
        }
        refreshSafBrowser()
        return true
    }

    fun refreshSafBrowser() {
        val uri = _safBrowser.value.currentUri ?: return
        viewModelScope.launch {
            _safBrowser.update { it.copy(loading = true, error = null) }
            graph.safFiles.list(uri).fold(
                onSuccess = { entries -> _safBrowser.update { it.copy(entries = entries, loading = false) } },
                onFailure = { error -> _safBrowser.update { it.copy(entries = emptyList(), loading = false, error = error.message) } },
            )
        }
    }

    fun createSafDirectory(name: String) = mutateSaf { uri -> graph.safFiles.createDirectory(uri, name).map { Unit } }

    fun createSafFile(name: String) = mutateSaf { uri -> graph.safFiles.createFile(uri, name).map { Unit } }

    fun renameSafEntry(entry: SafEntry, name: String) {
        viewModelScope.launch {
            graph.safFiles.rename(entry.uri, name).fold(
                onSuccess = { refreshSafBrowser() },
                onFailure = { message(it.message ?: "Pervadinti nepavyko", true) },
            )
        }
    }

    fun deleteSafEntry(entry: SafEntry) {
        viewModelScope.launch {
            graph.safFiles.delete(entry.uri).fold(
                onSuccess = { refreshSafBrowser() },
                onFailure = { message(it.message ?: "Ištrinti nepavyko", true) },
            )
        }
    }

    fun copyLocalToSaf(localPath: String) {
        val destinationUri = _safBrowser.value.currentUri ?: return
        val source = File(localPath)
        graph.operationManager.submit("Kopijuojama į Android vietą") {
            graph.safFiles.copyFromLocal(source, destinationUri, this).getOrThrow()
        }.onFailure { message(it.message ?: "Kopijavimo pradėti nepavyko", true) }
    }

    fun copySafToLocal(entry: SafEntry, destinationPanel: PanelId = _activePanel.value) {
        val destination = File(panelFlow(destinationPanel).value.path)
        graph.operationManager.submit("Kopijuojama iš Android vietos") {
            graph.safFiles.copyToLocal(entry.uri, destination, this).getOrThrow()
        }.onFailure { message(it.message ?: "Kopijavimo pradėti nepavyko", true) }
    }

    fun closeSafBrowser() {
        _safBrowser.value = SafBrowserUiState()
    }

    fun refreshProfiles() {
        viewModelScope.launch {
            runCatching { graph.networkProfiles.list() }
                .onSuccess { profiles -> _networkState.update { it.copy(profiles = profiles) } }
                .onFailure { message(it.message ?: "Profilių perskaityti nepavyko", true) }
        }
    }

    fun saveNetworkProfile(profile: NetworkProfile, password: CharArray?, privateKeyPem: CharArray? = null) {
        val editing = profile.id.isNotBlank()
        viewModelScope.launch {
            graph.networkProfiles.save(profile, password, privateKeyPem).fold(
                onSuccess = {
                    refreshProfiles()
                    message(if (editing) "Jungtis atnaujinta" else "Jungtis išsaugota")
                },
                onFailure = { message(it.message ?: "Profilio išsaugoti nepavyko", true) },
            )
        }
    }

    fun removeNetworkProfile(id: String) {
        viewModelScope.launch {
            graph.networkProfiles.remove(id).fold(
                onSuccess = { refreshProfiles() },
                onFailure = { message(it.message ?: "Profilio pašalinti nepavyko", true) },
            )
        }
    }

    fun connectNetwork(profile: NetworkProfile) {
        viewModelScope.launch {
            val normalizedProfile = NetworkProfileRules.normalize(profile)
            val profileProblem = runCatching { NetworkProfileRules.validate(normalizedProfile) }
                .exceptionOrNull()
                ?.message
            if (profileProblem != null) {
                _networkState.update {
                    it.copy(loading = false, error = RemoteErrorPresenter.invalidProfile(profileProblem))
                }
                return@launch
            }
            disconnectRemote()
            _networkState.update { it.copy(loading = true, error = null) }
            val result = runCatching {
                val initial = openRemoteConnection(normalizedProfile)
                ReconnectingRemoteClient(
                    initial = initial,
                    reconnect = {
                        val latest = graph.networkProfiles.list().firstOrNull { it.id == normalizedProfile.id }
                            ?: throw IllegalArgumentException("Profilis neberastas")
                        openRemoteConnection(latest)
                    },
                    onReconnected = { message("Nuotolinis ryšys automatiškai atkurtas") },
                )
            }
            result.onSuccess { client ->
                remoteClient = client
                if (client.verifiedHostFingerprint != null && normalizedProfile.expectedHostKeySha256 == null) {
                    graph.networkProfiles.updateSftpFingerprint(normalizedProfile.id, requireNotNull(client.verifiedHostFingerprint)).getOrThrow()
                    refreshProfiles()
                }
                val path = RemotePath.normalize(normalizedProfile.basePath)
                _networkState.update { it.copy(connectedProfile = normalizedProfile, path = path, loading = false) }
                _syncState.update { it.copy(remoteRoot = path, localRoot = activePanelState().path, preview = null, error = null) }
                refreshRemote(path)
            }.onFailure { error ->
                _networkState.update {
                    it.copy(
                        loading = false,
                        error = RemoteErrorPresenter.present(normalizedProfile.protocol, RemoteOperation.CONNECT, error),
                    )
                }
            }
        }
    }

    fun refreshRemote(path: String = _networkState.value.path) {
        val client = remoteClient ?: return
        val protocol = _networkState.value.connectedProfile?.protocol ?: return
        val normalizedPath = RemotePath.normalize(path)
        viewModelScope.launch {
            _networkState.update { state ->
                state.copy(
                    path = normalizedPath,
                    selectedPaths = if (state.path == normalizedPath) state.selectedPaths else emptySet(),
                    loading = true,
                    error = null,
                )
            }
            runCatching { client.list(normalizedPath) }
                .onSuccess { entries ->
                    val available = entries.mapTo(HashSet(entries.size), RemoteEntry::path)
                    _networkState.update { state ->
                        if (state.path != normalizedPath) state else state.copy(
                            entries = entries,
                            selectedPaths = RemoteSelectionRules.retainAvailable(state.selectedPaths, available),
                            loading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _networkState.update {
                        it.copy(loading = false, error = RemoteErrorPresenter.present(protocol, RemoteOperation.LIST, error))
                    }
                }
        }
    }

    fun toggleRemoteSelection(path: String) {
        var limitReached = false
        _networkState.update { state ->
            val result = RemoteSelectionRules.toggle(
                current = state.selectedPaths,
                availablePaths = state.entries.map(RemoteEntry::path),
                path = path,
                maximum = RemoteCopyEngine.MAX_SELECTED_ROOTS,
            )
            limitReached = result.limitReached
            state.copy(selectedPaths = result.selectedPaths)
        }
        if (limitReached) message("Vienu metu galima pasirinkti iki ${RemoteCopyEngine.MAX_SELECTED_ROOTS} failų ar aplankų", true)
    }

    fun selectAllRemote() {
        var limitReached = false
        _networkState.update { state ->
            val result = RemoteSelectionRules.selectAll(
                availablePaths = state.entries.map(RemoteEntry::path),
                maximum = RemoteCopyEngine.MAX_SELECTED_ROOTS,
            )
            limitReached = result.limitReached
            state.copy(selectedPaths = result.selectedPaths)
        }
        if (limitReached) message("Pasirinkti pirmi ${RemoteCopyEngine.MAX_SELECTED_ROOTS} elementų", true)
    }

    fun clearRemoteSelection() {
        _networkState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun remoteDownloadSelection(destinationPanel: PanelId = _activePanel.value) {
        val state = _networkState.value
        val entries = state.entries.filter { it.path in state.selectedPaths }
        if (entries.isEmpty()) return
        if (enqueueRemoteDownload(entries, destinationPanel)) clearRemoteSelection()
    }

    fun remoteDownload(entry: RemoteEntry, destinationPanel: PanelId = _activePanel.value) {
        enqueueRemoteDownload(listOf(entry), destinationPanel)
    }

    private fun enqueueRemoteDownload(entries: List<RemoteEntry>, destinationPanel: PanelId): Boolean {
        val client = remoteClient ?: return false
        if (entries.isEmpty() || entries.size > RemoteCopyEngine.MAX_SELECTED_ROOTS) return false
        val destination = File(panelFlow(destinationPanel).value.path)
        val title = if (entries.size == 1) {
            "Kopijuojama iš serverio: ${entries.first().name}"
        } else {
            "Kopijuojama iš serverio: ${entries.size}"
        }
        return graph.operationManager.submit(title) {
            val result = graph.remoteCopies.download(entries, destination, client, this)
            if (result.failures.isNotEmpty()) {
                completeWithErrors(
                    result.failures.size,
                    result.failures.joinToString("; ") { "${it.sourceName}: ${it.message}" },
                )
            } else {
                note("Nukopijuota į ${destination.absolutePath}")
            }
            if (result.copiedRoots > 0) refreshPanel(destinationPanel)
        }.onFailure { message(it.message ?: "Atsisiuntimo pradėti nepavyko", true) }.isSuccess
    }

    fun remoteUpload(localPaths: List<String>, remoteDirectory: String = _networkState.value.path): Boolean {
        val client = remoteClient ?: return false
        val sources = localPaths.distinct().map(::File)
        if (sources.isEmpty() || sources.size > RemoteCopyEngine.MAX_SELECTED_ROOTS) return false
        val normalizedRemoteDirectory = RemotePath.normalize(remoteDirectory)
        return graph.operationManager.submit("Kopijuojama į serverį: ${sources.size}") {
            val result = graph.remoteCopies.upload(sources, normalizedRemoteDirectory, client, this)
            if (result.failures.isNotEmpty()) {
                completeWithErrors(
                    result.failures.size,
                    result.failures.joinToString("; ") { "${it.sourceName}: ${it.message}" },
                )
            } else {
                note("Nukopijuota į $normalizedRemoteDirectory")
            }
            if (result.copiedRoots > 0) refreshRemote(normalizedRemoteDirectory)
        }.onFailure { message(it.message ?: "Įkėlimo pradėti nepavyko", true) }.isSuccess
    }

    fun remoteCreateDirectory(name: String) {
        val client = remoteClient ?: return
        val protocol = _networkState.value.connectedProfile?.protocol ?: return
        viewModelScope.launch {
            _networkState.update { it.copy(loading = true, error = null) }
            runCatching { client.createDirectory(RemotePath.join(_networkState.value.path, name)) }
                .onSuccess { refreshRemote() }
                .onFailure { error ->
                    _networkState.update {
                        it.copy(loading = false, error = RemoteErrorPresenter.present(protocol, RemoteOperation.CREATE_DIRECTORY, error))
                    }
                }
        }
    }

    fun remoteRename(entry: RemoteEntry, name: String) {
        val client = remoteClient ?: return
        val protocol = _networkState.value.connectedProfile?.protocol ?: return
        viewModelScope.launch {
            _networkState.update { it.copy(loading = true, error = null) }
            runCatching { client.rename(entry.path, RemotePath.join(_networkState.value.path, name)) }
                .onSuccess { refreshRemote() }
                .onFailure { error ->
                    _networkState.update {
                        it.copy(loading = false, error = RemoteErrorPresenter.present(protocol, RemoteOperation.RENAME, error))
                    }
                }
        }
    }

    fun remoteDelete(entry: RemoteEntry) {
        val client = remoteClient ?: return
        val protocol = _networkState.value.connectedProfile?.protocol ?: return
        viewModelScope.launch {
            _networkState.update { it.copy(loading = true, error = null) }
            runCatching { client.delete(entry.path, recursive = entry.directory) }
                .onSuccess { refreshRemote() }
                .onFailure { error ->
                    _networkState.update {
                        it.copy(loading = false, error = RemoteErrorPresenter.present(protocol, RemoteOperation.DELETE, error))
                    }
                }
        }
    }

    fun setSyncMode(mode: SyncMode) {
        _syncState.update { it.copy(mode = mode, preview = null, error = null) }
    }

    fun setSyncConflictPolicy(policy: SyncConflictPolicy) {
        _syncState.update { it.copy(conflictPolicy = policy, preview = null, error = null) }
    }

    fun previewSync() {
        val client = remoteClient ?: return
        val local = activePanelState().path
        val remote = _networkState.value.path
        val mode = _syncState.value.mode
        val policy = _syncState.value.conflictPolicy
        viewModelScope.launch {
            _syncState.update { it.copy(localRoot = local, remoteRoot = remote, running = true, preview = null, error = null) }
            runCatching { graph.sync.preview(File(local), remote, client, mode, policy) }
                .onSuccess { preview -> _syncState.update { it.copy(preview = preview, running = false) } }
                .onFailure { error -> _syncState.update { it.copy(running = false, error = error.message) } }
        }
    }

    fun executeSync() {
        val client = remoteClient ?: return
        val snapshot = _syncState.value
        val preview = snapshot.preview ?: return
        graph.operationManager.submit("Sinchronizuojama") {
            val fresh = graph.sync.preview(
                File(snapshot.localRoot),
                snapshot.remoteRoot,
                client,
                snapshot.mode,
                snapshot.conflictPolicy,
            )
            require(fresh == preview) { "Turinys pasikeitė po peržiūros; atnaujinkite sinchronizavimo planą" }
            require(fresh.actions.none { it.type == SyncActionType.CONFLICT }) { "Plane liko neišspręstų konfliktų" }
            graph.sync.execute(fresh, File(snapshot.localRoot), snapshot.remoteRoot, client, this)
        }.onSuccess {
            _syncState.update { it.copy(preview = null) }
        }.onFailure { message(it.message ?: "Sinchronizavimo pradėti nepavyko", true) }
    }

    fun refreshSyncSchedules() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.syncSchedules.list() }
                .onSuccess { _syncSchedules.value = it }
                .onFailure { message(it.message ?: "Tvarkaraščių perskaityti nepavyko", true) }
        }
    }

    fun scheduleCurrentSync(intervalHours: Long, unmeteredOnly: Boolean) {
        val profile = _networkState.value.connectedProfile ?: return
        val state = _syncState.value
        val schedule = SyncSchedule(
            id = "",
            profileId = profile.id,
            profileName = profile.name,
            localRoot = activePanelState().path,
            remoteRoot = _networkState.value.path,
            mode = state.mode,
            conflictPolicy = state.conflictPolicy,
            intervalHours = intervalHours,
            unmeteredOnly = unmeteredOnly,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.syncSchedules.save(schedule) }
                .onSuccess {
                    _syncSchedules.value = graph.syncSchedules.list()
                    message("Sinchronizavimo tvarkaraštis išsaugotas")
                }
                .onFailure { message(it.message ?: "Tvarkaraščio išsaugoti nepavyko", true) }
        }
    }

    fun removeSyncSchedule(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.syncSchedules.remove(id) }
                .onSuccess { _syncSchedules.value = graph.syncSchedules.list() }
                .onFailure { message(it.message ?: "Tvarkaraščio pašalinti nepavyko", true) }
        }
    }

    fun checkForUpdates() = graph.updates.check(automatic = false)

    fun downloadUpdate(release: AppRelease) = graph.updates.download(release)

    fun installUpdate() = graph.updates.installReady()

    fun disconnectNetwork() {
        viewModelScope.launch { disconnectRemote() }
    }

    fun cancelOperation(id: String) = graph.durableTransfers.cancel(id)
    fun pauseOperation(id: String) = graph.operationManager.pause(id)
    fun resumeOperation(id: String) = graph.operationManager.resume(id)
    fun retryOperation(id: String) = graph.durableTransfers.retry(id)
        .onFailure { message(it.message ?: "Operacijos pakartoti nepavyko", true) }
    fun dismissFinishedOperations() = graph.operationManager.dismissFinished()

    fun activePanelState(): PanelUiState = panelFlow(_activePanel.value).value

    override fun onCleared() {
        workspaceSaveRequests.trySend(currentWorkspace())
        workspaceSaveRequests.close()
        remoteClient?.let { client -> graph.applicationScope.launch { client.close() } }
        remoteClient = null
        super.onCleared()
    }

    private fun savedThumbnailMode(directoryIdentity: String): Boolean = runCatching {
        graph.navigation.thumbnailsEnabled(directoryIdentity)
    }.onFailure {
        message(it.message ?: "Katalogo vaizdo nustatymo perskaityti nepavyko", true)
    }.getOrDefault(false)

    private fun trashDirectoryIdentity(itemId: String?, relativePath: String): String = if (itemId == null) {
        "virtual:trash/root"
    } else {
        "virtual:trash/$itemId/${TrashPathRules.normalize(relativePath)}"
    }

    private fun WorkspaceTab.toPanelUiState(): PanelUiState = PanelUiState(
        path = path,
        sortMode = sortMode,
        sortDirection = sortDirection,
        includeHidden = includeHidden,
        grid = grid,
        showThumbnails = runCatching { graph.navigation.thumbnailsEnabled(path) }.getOrDefault(false),
        backHistory = backHistory,
        forwardHistory = forwardHistory,
    )

    private fun tabsFlow(panel: PanelId): MutableStateFlow<PanelWorkspace> = if (panel == PanelId.LEFT) _leftTabs else _rightTabs

    private fun syncActiveTab(panel: PanelId, persist: Boolean = true) {
        val state = panelFlow(panel).value
        tabsFlow(panel).update { workspace ->
            workspace.copy(
                tabs = workspace.tabs.map { tab ->
                    if (tab.id == workspace.activeTabId) {
                        tab.copy(
                            path = state.path,
                            backHistory = state.backHistory.takeLast(50),
                            forwardHistory = state.forwardHistory.takeLast(50),
                            sortMode = state.sortMode,
                            sortDirection = state.sortDirection,
                            includeHidden = state.includeHidden,
                            grid = state.grid,
                        )
                    } else tab
                },
            )
        }
        if (persist) persistWorkspace()
    }

    private fun currentWorkspace(): WorkspaceSession = WorkspaceSession(left = _leftTabs.value, right = _rightTabs.value)

    private fun persistWorkspace() {
        if (workspaceSaveRequests.trySend(currentWorkspace()).isFailure) {
            message("Darbo sesijos išsaugojimo eilė nepasiekiama", true)
        }
    }

    private fun panelFlow(panel: PanelId): MutableStateFlow<PanelUiState> = if (panel == PanelId.LEFT) _leftPanel else _rightPanel

    private fun panelRefreshJob(panel: PanelId): Job? = if (panel == PanelId.LEFT) leftPanelRefreshJob else rightPanelRefreshJob

    private fun setPanelRefreshJob(panel: PanelId, job: Job) {
        if (panel == PanelId.LEFT) leftPanelRefreshJob = job else rightPanelRefreshJob = job
    }

    private fun PanelUiState.matchesListingRequest(request: PanelUiState): Boolean =
        path == request.path && includeHidden == request.includeHidden && sortMode == request.sortMode && sortDirection == request.sortDirection

    private fun scheduleBatchRenamePreview(immediate: Boolean) {
        batchRenamePreviewJob?.cancel()
        val snapshot = _batchRename.value
        if (!snapshot.open) return
        batchRenamePreviewJob = viewModelScope.launch {
            if (!immediate) delay(150)
            try {
                val preview = graph.batchRename.preview(snapshot.sourcePaths, snapshot.spec)
                if (_batchRename.value.open && _batchRename.value.sourcePaths == snapshot.sourcePaths && _batchRename.value.spec == snapshot.spec) {
                    _batchRename.update { it.copy(preview = preview, running = false, error = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (_batchRename.value.open && _batchRename.value.sourcePaths == snapshot.sourcePaths && _batchRename.value.spec == snapshot.spec) {
                    _batchRename.update { it.copy(preview = null, running = false, error = error.message) }
                }
            }
        }
    }

    private fun orderedSearchSelection(): List<String> {
        val state = _searchState.value
        return state.results.asSequence()
            .map(FileEntry::absolutePath)
            .filter { it in state.selectedPaths }
            .toList()
    }

    private fun rerunSearchIfVisible() {
        val state = _searchState.value
        if (state.roots.isNotEmpty()) search(state.filters, state.roots)
    }

    private fun message(text: String, error: Boolean = false, action: UiMessageAction? = null) {
        _messages.tryEmit(UiMessage(text, error, action))
    }

    private fun mutateSaf(operation: suspend (String) -> Result<Unit>) {
        val uri = _safBrowser.value.currentUri ?: return
        viewModelScope.launch {
            operation(uri).fold(
                onSuccess = { refreshSafBrowser() },
                onFailure = { message(it.message ?: "Veiksmas nepavyko", true) },
            )
        }
    }

    private suspend fun disconnectRemote() {
        val client = remoteClient
        remoteClient = null
        runCatching { client?.close() }
        _networkState.update {
            it.copy(connectedProfile = null, entries = emptyList(), selectedPaths = emptySet(), loading = false, error = null)
        }
        _syncState.value = SyncUiState()
    }

    private suspend fun openRemoteConnection(profile: NetworkProfile): RemoteClient {
        val normalized = NetworkProfileRules.normalize(profile)
        NetworkProfileRules.validate(normalized)
        return graph.networkProfiles.secret(normalized.id).getOrThrow().use { secret ->
            graph.remoteClients.connect(normalized, secret)
        }
    }
}
