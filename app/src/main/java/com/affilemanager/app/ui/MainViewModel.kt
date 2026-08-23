package com.affilemanager.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.content.ContentResolver
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.R
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.advanced.AdvancedAccessState
import com.affilemanager.app.advanced.PrivilegedPathRules
import com.affilemanager.app.archive.ArchiveEntryInfo
import com.affilemanager.app.archive.ArchiveFormat
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.SafLocation
import com.affilemanager.app.data.SafEntry
import com.affilemanager.app.data.RecentItem
import com.affilemanager.app.data.RecentFileItem
import com.affilemanager.app.data.SavedSearch
import com.affilemanager.app.data.TrashBrowserEntry
import com.affilemanager.app.data.TrashItem
import com.affilemanager.app.data.TrashPathRules
import com.affilemanager.app.data.PanelWorkspace
import com.affilemanager.app.data.WorkspaceSession
import com.affilemanager.app.data.WorkspaceTab
import com.affilemanager.app.data.WorkspaceSessionRepository
import com.affilemanager.app.data.FileTagSnapshot
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.data.DirectoryDisplayDefaults
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryGridStyle
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.data.HomeCustomization
import com.affilemanager.app.data.HomeCustomizationRules
import com.affilemanager.app.data.HomeSection
import com.affilemanager.app.data.HomeShortcut
import com.affilemanager.app.data.HomeShortcutNavigationRules
import com.affilemanager.app.editing.EditConflict
import com.affilemanager.app.editing.EditDestination
import com.affilemanager.app.editing.EditDestinationRules
import com.affilemanager.app.editing.EditExistingPolicy
import com.affilemanager.app.editing.EditOrigin
import com.affilemanager.app.editing.EditSaveAsConflict
import com.affilemanager.app.editing.EditSaveAsResult
import com.affilemanager.app.editing.EditSaveResult
import com.affilemanager.app.editing.EditSession
import com.affilemanager.app.editing.EditabilityRules
import com.affilemanager.app.editing.FileRevision
import com.affilemanager.app.editing.LineEnding
import com.affilemanager.app.editing.TextEncoding
import com.affilemanager.app.model.ClipboardMode
import com.affilemanager.app.model.ClipboardSource
import com.affilemanager.app.model.ContentFileEntry
import com.affilemanager.app.model.ClipboardState
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.model.DuplicateGroup
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.model.SimilarImageGroup
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.StorageAnalysis
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProfileRules
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.RemoteClient
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.network.RemoteErrorInfo
import com.affilemanager.app.network.RemoteErrorPresenter
import com.affilemanager.app.network.RemoteOperation
import com.affilemanager.app.network.RemotePath
import com.affilemanager.app.network.ReconnectingRemoteClient
import com.affilemanager.app.network.RemoteCopyEngine
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.BatchRenamePreview
import com.affilemanager.app.operations.BatchRenameSpec
import com.affilemanager.app.operations.BatchRenameUndo
import com.affilemanager.app.operations.DurableTransferPlanner
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import com.affilemanager.app.security.VaultHeader
import com.affilemanager.app.sync.SyncActionType
import com.affilemanager.app.sync.SyncConflictPolicy
import com.affilemanager.app.sync.SyncMode
import com.affilemanager.app.sync.SyncPreview
import com.affilemanager.app.sync.SyncSchedule
import com.affilemanager.app.terminal.LocalPtyBackend
import com.affilemanager.app.terminal.ShellCommandRules
import com.affilemanager.app.terminal.SshTerminalBackend
import com.affilemanager.app.terminal.TerminalPasteResult
import com.affilemanager.app.update.AppRelease
import com.affilemanager.app.update.AppUpdateState
import com.affilemanager.app.workflow.AfAutomationRule
import com.affilemanager.app.workflow.AfAutomationSchedule
import com.affilemanager.app.workflow.AfDestinationRef
import com.affilemanager.app.workflow.AfLocationKind
import com.affilemanager.app.workflow.AfLocationRef
import com.affilemanager.app.workflow.AfOperationReceipt
import com.affilemanager.app.workflow.AfPlanDefinition
import com.affilemanager.app.workflow.AfPreflightSummary
import com.affilemanager.app.workflow.AfPreflightFingerprint
import com.affilemanager.app.workflow.AfSourceRef
import com.affilemanager.app.workflow.AfSourceKind
import com.affilemanager.app.workflow.AfUndoPreview
import com.affilemanager.app.workflow.AfWorkflowLimits
import com.affilemanager.app.workflow.AfWorkflowSnapshot
import com.affilemanager.app.workflow.identityKey
import com.affilemanager.app.ui.preview.RemotePreviewCache
import com.affilemanager.app.ui.preview.PreviewSource
import com.affilemanager.app.ui.preview.previewSource
import com.affilemanager.app.ui.theme.AppColorPalette
import com.affilemanager.app.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.io.FileNotFoundException
import java.util.UUID

private const val ADVANCED_STORAGE_SCROLL_OWNER = "advanced-storage"

enum class AppSection {
    FILES,
    ANALYZE,
    CONNECTIONS,
    SHARE,
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
    val iconScalePercent: Int = 100,
    val spacingScalePercent: Int = 100,
    val gridColumns: Int = 3,
    val gridStyle: DirectoryGridStyle = DirectoryGridStyle.CARDS,
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

data class RecentFilesUiState(
    val items: List<RecentFileItem> = emptyList(),
    val loading: Boolean = false,
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
    val duplicateCandidatesScanned: Int = 0,
    val duplicateScanTruncated: Boolean = false,
    val similarImages: List<SimilarImageGroup> = emptyList(),
    val rootPath: String? = null,
    val running: Boolean = false,
    val similarImagesRunning: Boolean = false,
    val similarImagesAnalyzed: Boolean = false,
    val similarImagesError: String? = null,
    val error: String? = null,
)

data class FileCategoryUiState(
    val open: Boolean = false,
    val category: FileCategory? = null,
    val entries: List<FileEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val grid: Boolean = false,
    val iconScalePercent: Int = 100,
    val spacingScalePercent: Int = 100,
    val gridColumns: Int = 3,
    val gridStyle: DirectoryGridStyle = DirectoryGridStyle.CARDS,
    val showThumbnails: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val scannedRows: Int = 0,
    val nextOffset: Int? = null,
    val truncated: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

data class AdvancedBrowserUiState(
    val open: Boolean = false,
    val title: String = "Apsaugoti Android failai",
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val backHistory: List<String> = emptyList(),
    val includeHidden: Boolean = false,
    val grid: Boolean = false,
    val iconScalePercent: Int = 100,
    val spacingScalePercent: Int = 100,
    val gridColumns: Int = 3,
    val gridStyle: DirectoryGridStyle = DirectoryGridStyle.CARDS,
    val sortMode: SortMode = SortMode.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val loading: Boolean = false,
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
    val backHistory: List<String> = emptyList(),
    val forwardHistory: List<String> = emptyList(),
    val includeHidden: Boolean = false,
    val grid: Boolean = false,
    val iconScalePercent: Int = 100,
    val spacingScalePercent: Int = 100,
    val gridColumns: Int = 3,
    val gridStyle: DirectoryGridStyle = DirectoryGridStyle.CARDS,
    val sortMode: SortMode = SortMode.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val openingPath: String? = null,
    val loading: Boolean = false,
    val error: RemoteErrorInfo? = null,
)

data class RemoteClipboardState(
    val profileId: String,
    val entries: List<RemoteEntry>,
)

data class AfClipboardState(
    val sources: List<AfSourceRef>,
    val moveAfterVerifiedCopies: Boolean = false,
)

enum class AfWorkflowTab { PLANS, TIMELINE, AUTOMATION }

data class AfWorkflowUiState(
    val open: Boolean = false,
    val tab: AfWorkflowTab = AfWorkflowTab.PLANS,
    val editingPlanId: String? = null,
    val name: String = "",
    val sources: List<AfSourceRef> = emptyList(),
    val destinations: List<AfDestinationRef> = emptyList(),
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    val verification: TransferVerification = TransferVerification.SIZE,
    val failurePolicy: TransferFailurePolicy = TransferFailurePolicy.STOP,
    val deleteSourcesAfterVerifiedCopies: Boolean = false,
    val preview: AfPreflightSummary? = null,
    val automationPreview: AfPreflightSummary? = null,
    val undoPreview: AfUndoPreview? = null,
    val selectedReceiptId: String? = null,
    val timelineQuery: String = "",
    val working: Boolean = false,
    val error: String? = null,
)

data class SafBrowserUiState(
    val location: SafLocation? = null,
    val currentUri: String? = null,
    val title: String = "",
    val entries: List<SafEntry> = emptyList(),
    val backStack: List<Pair<String, String>> = emptyList(),
    val grid: Boolean = false,
    val iconScalePercent: Int = 100,
    val spacingScalePercent: Int = 100,
    val gridColumns: Int = 3,
    val gridStyle: DirectoryGridStyle = DirectoryGridStyle.CARDS,
    val showThumbnails: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val loading: Boolean = false,
    val error: String? = null,
)

data class TrashBrowserUiState(
    val open: Boolean = false,
    val itemId: String? = null,
    val relativePath: String = "",
    val rootName: String? = null,
    val entries: List<TrashBrowserEntry> = emptyList(),
    val grid: Boolean = false,
    val iconScalePercent: Int = 100,
    val spacingScalePercent: Int = 100,
    val gridColumns: Int = 3,
    val gridStyle: DirectoryGridStyle = DirectoryGridStyle.CARDS,
    val showThumbnails: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
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

data class FileEditUiState(
    val sourceKey: String? = null,
    val temporaryDownload: Boolean = false,
    val session: EditSession? = null,
    val text: String? = null,
    val stagedText: String? = null,
    val encoding: TextEncoding? = null,
    val stagedEncoding: TextEncoding? = null,
    val lineEnding: LineEnding? = null,
    val stagedLineEnding: LineEnding? = null,
    val textChanged: Boolean = false,
    val preparing: Boolean = false,
    val saving: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val conflict: EditConflict? = null,
    val saveAsConflict: EditSaveAsConflict? = null,
    val confirmDiscard: Boolean = false,
    val closeAfterSave: Boolean = false,
) {
    val formatChanged: Boolean get() = encoding != stagedEncoding || lineEnding != stagedLineEnding
    val hasOriginChanges: Boolean get() = textChanged || formatChanged || session?.hasOriginChanges == true
    val hasUnsavedChanges: Boolean get() = textChanged || formatChanged || session?.hasUnsavedChanges == true
}

sealed interface PreviewTarget {
    data class LocalFile(val entry: FileEntry) : PreviewTarget
    data class TrashFile(val entry: FileEntry) : PreviewTarget
    data class ContentFile(val entry: ContentFileEntry) : PreviewTarget
    data class RemoteFile(
        val remote: RemoteEntry,
        val cachedFile: File,
        val profileId: String,
        val connectionName: String,
    ) : PreviewTarget
    data class PrivilegedFile(
        val entry: FileEntry,
        val cachedFile: File,
    ) : PreviewTarget
    data class Archive(val file: FileEntry, val entries: List<ArchiveEntryInfo>) : PreviewTarget
    data class RemoteArchive(
        val remote: RemoteEntry,
        val file: FileEntry,
        val profileId: String,
        val connectionName: String,
        val entries: List<ArchiveEntryInfo>,
    ) : PreviewTarget
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
    private val filesHomeDisplayIdentity = "virtual:files-home"
    private val remotePreviewCache = RemotePreviewCache(application.cacheDir)
    private val initialPrimaryPath = Environment.getExternalStorageDirectory().absolutePath
    private val initialDownloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .takeIf(File::isDirectory)?.absolutePath ?: initialPrimaryPath
    private val initialWorkspace = graph.workspaceSession.load(initialDownloadsPath, initialPrimaryPath)
    private val homeBuiltInShortcuts = listOf(
        HomeShortcut(
            id = "builtin.downloads",
            title = "Atsisiuntimai",
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
            builtIn = true,
        ),
        HomeShortcut(
            id = "builtin.documents",
            title = "Dokumentai",
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
            builtIn = true,
        ),
        HomeShortcut(
            id = "builtin.pictures",
            title = "Nuotraukos",
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath,
            builtIn = true,
        ),
        HomeShortcut(
            id = "builtin.videos",
            title = "Vaizdo įrašai",
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath,
            builtIn = true,
        ),
        HomeShortcut(
            id = "builtin.music",
            title = "Muzika",
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath,
            builtIn = true,
        ),
        HomeShortcut(
            id = "builtin.archives",
            title = "Archyvai",
            path = initialPrimaryPath,
            builtIn = true,
        ),
        HomeShortcut(
            id = "builtin.apps",
            title = "Programos",
            path = initialPrimaryPath,
            builtIn = true,
        ),
        HomeShortcut(
            id = "builtin.installed_apps",
            title = "Įdiegtos programos",
            path = initialPrimaryPath,
            builtIn = true,
        ),
    )

    private val _section = MutableStateFlow(AppSection.FILES)
    val section: StateFlow<AppSection> = _section.asStateFlow()

    val updateState: StateFlow<AppUpdateState> = graph.updates.state
    val appearanceSettings = graph.appearance.settings

    private val _filesHomeVisible = MutableStateFlow(true)
    val filesHomeVisible: StateFlow<Boolean> = _filesHomeVisible.asStateFlow()

    private val _filesHomeDisplaySettings = MutableStateFlow(
        runCatching { graph.navigation.directoryDisplaySettings(filesHomeDisplayIdentity) }.getOrNull()
            ?: DirectoryDisplaySettings(gridColumns = 4),
    )
    val filesHomeDisplaySettings: StateFlow<DirectoryDisplaySettings> = _filesHomeDisplaySettings.asStateFlow()

    private val _homeCustomization = MutableStateFlow(
        runCatching { graph.navigation.homeCustomization(homeBuiltInShortcuts) }
            .getOrElse { HomeCustomizationRules.normalize(HomeCustomization(), homeBuiltInShortcuts) },
    )
    val homeCustomization: StateFlow<HomeCustomization> = _homeCustomization.asStateFlow()

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

    private val _recentFiles = MutableStateFlow(RecentFilesUiState())
    val recentFiles: StateFlow<RecentFilesUiState> = _recentFiles.asStateFlow()

    private val _fileCategory = MutableStateFlow(FileCategoryUiState())
    val fileCategory: StateFlow<FileCategoryUiState> = _fileCategory.asStateFlow()

    private val _cleanupRequested = MutableStateFlow(false)
    val cleanupRequested: StateFlow<Boolean> = _cleanupRequested.asStateFlow()

    private val _clipboard = MutableStateFlow<ClipboardState?>(null)
    val clipboard: StateFlow<ClipboardState?> = _clipboard.asStateFlow()

    private val _remoteClipboard = MutableStateFlow<RemoteClipboardState?>(null)
    val remoteClipboard: StateFlow<RemoteClipboardState?> = _remoteClipboard.asStateFlow()

    private val _afClipboard = MutableStateFlow<AfClipboardState?>(null)
    val afClipboard: StateFlow<AfClipboardState?> = _afClipboard.asStateFlow()

    val afWorkflowSnapshot: StateFlow<AfWorkflowSnapshot> = graph.workflows.snapshot
    private val _afWorkflowUi = MutableStateFlow(AfWorkflowUiState())
    val afWorkflowUi: StateFlow<AfWorkflowUiState> = _afWorkflowUi.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _analysisState = MutableStateFlow(AnalysisUiState())
    val analysisState: StateFlow<AnalysisUiState> = _analysisState.asStateFlow()

    val advancedAccess: StateFlow<AdvancedAccessState> = graph.advancedAccess.state

    private val _advancedBrowser = MutableStateFlow(AdvancedBrowserUiState())
    val advancedBrowser: StateFlow<AdvancedBrowserUiState> = _advancedBrowser.asStateFlow()

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

    private val _fileEditState = MutableStateFlow(FileEditUiState())
    val fileEditState: StateFlow<FileEditUiState> = _fileEditState.asStateFlow()

    val terminalState: StateFlow<TerminalUiState> = graph.terminalSessions.state

    val operations = graph.operationManager.operations

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var remoteClient: RemoteClient? = null
    private var searchJob: Job? = null
    private var analysisJob: Job? = null
    private var similarImagesJob: Job? = null
    private var advancedBrowserJob: Job? = null
    private var advancedFileOpenJob: Job? = null
    private var recentFilesJob: Job? = null
    private var fileCategoryJob: Job? = null
    private var batchRenamePreviewJob: Job? = null
    private var remoteFileOpenJob: Job? = null
    private var remoteFileOpenRequestId = 0L
    private var fileEditJob: Job? = null
    private var fileEditRequestId = 0L
    private var leftPanelRefreshJob: Job? = null
    private var rightPanelRefreshJob: Job? = null
    private val handledOperations = ArrayDeque<String>()
    private val workspaceSaveRequests = Channel<WorkspaceSession>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            graph.terminalSessions.notices.collectLatest { message(it, true) }
        }
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
        refreshRecentFiles()
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
                    refreshRecentFiles()
                    if (_safBrowser.value.location != null) refreshSafBrowser()
                }
            }
        }
    }

    fun setSection(section: AppSection) {
        val previousSection = _section.value
        val filesDestinationReselected = SectionNavigationRules.shouldShowFilesHome(previousSection, section)
        if (filesDestinationReselected && _fileCategory.value.open) closeFileCategory()
        _section.value = section
        if (section == AppSection.FILES) {
            if (filesDestinationReselected) _filesHomeVisible.value = true
            refreshRecentFiles()
        }
        if (section == AppSection.TOOLS) {
            graph.advancedAccess.refreshCapabilities()
            refreshTrash()
            refreshSafLocations()
            refreshSyncSchedules()
        }
    }

    fun setAdvancedAccessMode(mode: AdvancedAccessMode) {
        graph.advancedAccess.setMode(mode)
        if (mode == AdvancedAccessMode.OFF) closeAdvancedBrowser()
    }

    fun requestShizukuAccess() = graph.advancedAccess.requestShizukuAccess()

    fun requestRootAccess() = graph.advancedAccess.requestRootAccess()

    fun refreshAdvancedAccess() = graph.advancedAccess.refreshCapabilities()

    fun openRootFromHome() {
        val rootBackend = graph.advancedAccess.state.value.activeBackend in setOf(
            AdvancedAccessBackend.ROOT,
            AdvancedAccessBackend.SHIZUKU_ROOT,
        )
        if (rootBackend) {
            openAdvancedBrowser("/")
        } else {
            setSection(AppSection.TOOLS)
            message("Įjunkite Root arba Shizuku root prieigą skiltyje Daugiau", true)
        }
    }

    fun showFilesHome() {
        if (_fileCategory.value.open) closeFileCategory()
        _section.value = AppSection.FILES
        _filesHomeVisible.value = true
        refreshRecentFiles()
    }

    fun openAdvancedBrowser(preferredPath: String? = null) {
        advancedBrowserJob?.cancel()
        val displayDefaults = runCatching { graph.navigation.directoryDisplayDefaults() }.getOrNull()
        val rootBackend = graph.advancedAccess.state.value.activeBackend in setOf(
            AdvancedAccessBackend.ROOT,
            AdvancedAccessBackend.SHIZUKU_ROOT,
        )
        val title = if (preferredPath == "/" || (preferredPath == null && rootBackend)) "Root" else "Apsaugoti Android failai"
        _advancedBrowser.value = AdvancedBrowserUiState(
            open = true,
            title = title,
            loading = true,
            sortMode = displayDefaults?.sortMode ?: SortMode.NAME,
            sortDirection = displayDefaults?.sortDirection ?: SortDirection.ASCENDING,
        )
        advancedBrowserJob = viewModelScope.launch {
            try {
                if (!rootBackend) {
                    val accessible = graph.privilegedFiles.probeAndroidData().getOrThrow()
                    require(accessible) { "Android/data šiuo privilegijuotu režimu nepasiekiamas" }
                }
                val roots = graph.privilegedFiles.availableRoots().getOrThrow()
                val target = preferredPath ?: roots.firstOrNull()?.path
                    ?: throw IllegalStateException("Privilegijuoti aplankai nepasiekiami")
                loadAdvancedDirectory(target, pushHistory = false, resetScroll = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _advancedBrowser.update { it.copy(loading = false, error = error.message ?: "Privilegijuotos vietos atidaryti nepavyko") }
            } finally {
                advancedBrowserJob = null
            }
        }
    }

    fun closeAdvancedBrowser() {
        advancedBrowserJob?.cancel()
        advancedBrowserJob = null
        _advancedBrowser.value = AdvancedBrowserUiState()
    }

    fun navigateAdvanced(path: String) {
        advancedBrowserJob?.cancel()
        advancedBrowserJob = viewModelScope.launch {
            try {
                loadAdvancedDirectory(path, pushHistory = true, resetScroll = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _advancedBrowser.update { it.copy(loading = false, error = error.message ?: "Aplanko atidaryti nepavyko") }
            } finally {
                advancedBrowserJob = null
            }
        }
    }

    fun navigateAdvancedBack(): Boolean {
        val state = _advancedBrowser.value
        val previous = state.backHistory.lastOrNull()
        if (previous == null) {
            closeAdvancedBrowser()
            return false
        }
        advancedBrowserJob?.cancel()
        advancedBrowserJob = viewModelScope.launch {
            _advancedBrowser.update { it.copy(backHistory = it.backHistory.dropLast(1), loading = true, selectedPaths = emptySet(), error = null) }
            runCatching { loadAdvancedDirectory(previous, pushHistory = false, resetScroll = false) }
                .onFailure { error -> _advancedBrowser.update { it.copy(loading = false, error = error.message ?: "Aplanko atidaryti nepavyko") } }
            advancedBrowserJob = null
        }
        return true
    }

    fun refreshAdvancedBrowser() {
        val state = _advancedBrowser.value
        if (!state.open || state.path.isBlank()) return
        advancedBrowserJob?.cancel()
        advancedBrowserJob = viewModelScope.launch {
            _advancedBrowser.update { it.copy(loading = true, error = null) }
            graph.privilegedFiles.list(state.path, state.includeHidden, state.sortMode, state.sortDirection).fold(
                onSuccess = { entries -> _advancedBrowser.update { it.copy(entries = entries, selectedPaths = it.selectedPaths.intersect(entries.map(FileEntry::absolutePath).toSet()), loading = false) } },
                onFailure = { error -> _advancedBrowser.update { it.copy(loading = false, error = error.message ?: "Aplanko atnaujinti nepavyko") } },
            )
            advancedBrowserJob = null
        }
    }

    fun toggleAdvancedHidden() {
        _advancedBrowser.update { it.copy(includeHidden = !it.includeHidden, selectedPaths = emptySet()) }
        refreshAdvancedBrowser()
    }

    fun toggleAdvancedLayout() {
        val state = _advancedBrowser.value
        setAdvancedDisplaySettings(
            DirectoryDisplaySettings(
                layoutMode = if (state.grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
                iconScalePercent = state.iconScalePercent,
                spacingScalePercent = state.spacingScalePercent,
                gridColumns = state.gridColumns,
                gridStyle = state.gridStyle,
                showThumbnails = false,
            ),
        )
    }

    fun advancedScrollPosition(path: String, grid: Boolean): FileScrollPosition =
        fileScrollPositions.read(FileScrollKey(ADVANCED_STORAGE_SCROLL_OWNER, path, grid))

    fun saveAdvancedScrollPosition(
        path: String,
        grid: Boolean,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        fileScrollPositions.write(
            FileScrollKey(ADVANCED_STORAGE_SCROLL_OWNER, path, grid),
            firstVisibleItemIndex,
            firstVisibleItemScrollOffset,
        )
    }

    fun setAdvancedDisplaySettings(settings: DirectoryDisplaySettings) {
        val state = _advancedBrowser.value
        if (state.path.isBlank()) return
        val normalized = settings.copy(showThumbnails = false)
        runCatching { graph.navigation.setDirectoryDisplaySettings("privileged:${state.path}", normalized) }
            .onSuccess {
                _advancedBrowser.update { current ->
                    current.copy(
                        grid = normalized.layoutMode == DirectoryLayoutMode.GRID,
                        iconScalePercent = normalized.iconScalePercent,
                        spacingScalePercent = normalized.spacingScalePercent,
                        gridColumns = normalized.gridColumns,
                        gridStyle = normalized.gridStyle,
                    )
                }
            }
            .onFailure { message(it.message ?: "Rodinio nustatymo išsaugoti nepavyko", true) }
    }

    fun setAdvancedSort(mode: SortMode, direction: SortDirection) {
        _advancedBrowser.update { it.copy(sortMode = mode, sortDirection = direction, selectedPaths = emptySet()) }
        refreshAdvancedBrowser()
    }

    fun toggleAdvancedSelection(path: String) {
        _advancedBrowser.update { state ->
            val selection = state.selectedPaths.toMutableSet()
            if (!selection.add(path)) selection.remove(path)
            state.copy(selectedPaths = selection)
        }
    }

    fun clearAdvancedSelection() {
        _advancedBrowser.update { it.copy(selectedPaths = emptySet()) }
    }

    fun toggleSelectAllAdvanced() {
        _advancedBrowser.update { state ->
            val all = state.entries.take(PrivilegedPathRules.MAX_SELECTED_ROOTS).map(FileEntry::absolutePath).toSet()
            state.copy(selectedPaths = if (all.isNotEmpty() && state.selectedPaths.containsAll(all)) emptySet() else all)
        }
    }

    fun copyAdvancedSelection(move: Boolean, append: Boolean = false) {
        val selected = _advancedBrowser.value.entries.asSequence()
            .map(FileEntry::absolutePath)
            .filter { it in _advancedBrowser.value.selectedPaths }
            .toList()
        if (selected.isEmpty()) return
        val existing = if (append) {
            val current = _clipboard.value
            if (current == null || current.source != ClipboardSource.PRIVILEGED || current.mode != ClipboardMode.COPY) {
                message("Nėra privilegijuoto kopijavimo rinkinio, kurį būtų galima papildyti", true)
                return
            }
            current.paths
        } else emptyList()
        val merged = ClipboardMergeRules.merge(
            existing = existing,
            additional = selected,
            maximum = PrivilegedPathRules.MAX_SELECTED_ROOTS,
            key = PrivilegedPathRules::normalizeAbsolute,
        )
        _clipboard.value = ClipboardState(
            paths = merged.items,
            mode = if (move) ClipboardMode.MOVE else ClipboardMode.COPY,
            source = ClipboardSource.PRIVILEGED,
        )
        _afClipboard.value = null
        _remoteClipboard.value = null
        clearAdvancedSelection()
        message(
            if (append) "Į privilegijuotą iškarpinę įtraukta: ${merged.addedCount} · iš viso: ${merged.items.size}"
            else if (move) "Paruošta perkelti: ${merged.items.size}"
            else "Nukopijuota į iškarpinę: ${merged.items.size}",
        )
    }

    fun pasteIntoAdvanced(conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH) {
        val clipboard = _clipboard.value ?: return
        val destination = _advancedBrowser.value.path
        if (destination.isBlank()) return
        val moving = clipboard.mode == ClipboardMode.MOVE
        graph.operationManager.submit("Kopijuojama į apsaugotą Android vietą") {
            val result = when (clipboard.source) {
                ClipboardSource.LOCAL -> graph.privilegedFiles.copyFromLocal(clipboard.paths, destination, moving, conflictPolicy, this)
                ClipboardSource.PRIVILEGED -> graph.privilegedFiles.copyWithin(clipboard.paths, destination, moving, conflictPolicy, this)
            }
            note("Nukopijuota: ${result.copiedRoots} · praleista: ${result.skippedRoots}")
            if (moving && result.skippedRoots == 0) _clipboard.value = null
            refreshAdvancedBrowser()
            if (clipboard.source == ClipboardSource.LOCAL && moving) {
                refreshPanel(PanelId.LEFT)
                refreshPanel(PanelId.RIGHT)
            }
        }.onFailure { message(it.message ?: "Įklijavimo pradėti nepavyko", true) }
    }

    fun createAdvancedDirectory(name: String) {
        val path = _advancedBrowser.value.path
        viewModelScope.launch {
            graph.privilegedFiles.createDirectory(path, name).fold(
                onSuccess = { refreshAdvancedBrowser() },
                onFailure = { message(it.message ?: "Aplanko sukurti nepavyko", true) },
            )
        }
    }

    fun createAdvancedFile(name: String) {
        val path = _advancedBrowser.value.path
        viewModelScope.launch {
            graph.privilegedFiles.createFile(path, name).fold(
                onSuccess = { refreshAdvancedBrowser() },
                onFailure = { message(it.message ?: "Failo sukurti nepavyko", true) },
            )
        }
    }

    fun renameAdvanced(path: String, name: String) {
        viewModelScope.launch {
            graph.privilegedFiles.rename(path, name).fold(
                onSuccess = { clearAdvancedSelection(); refreshAdvancedBrowser() },
                onFailure = { message(it.message ?: "Pervadinti nepavyko", true) },
            )
        }
    }

    fun deleteAdvancedSelectionPermanently() {
        val selected = _advancedBrowser.value.selectedPaths.toList()
        if (selected.isEmpty()) return
        graph.operationManager.submit("Šalinami pasirinkti apsaugoti failai") {
            graph.privilegedFiles.deletePermanently(selected, this)
            clearAdvancedSelection()
            refreshAdvancedBrowser()
        }.onFailure { message(it.message ?: "Trynimo pradėti nepavyko", true) }
    }

    fun openAdvancedEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            navigateAdvanced(entry.absolutePath)
            return
        }
        advancedFileOpenJob?.cancel()
        advancedFileOpenJob = viewModelScope.launch {
            graph.privilegedFiles.stageForPreview(entry).fold(
                onSuccess = { cached -> _preview.value = PreviewTarget.PrivilegedFile(entry, cached) },
                onFailure = { message(it.message ?: "Failo atidaryti nepavyko", true) },
            )
            advancedFileOpenJob = null
        }
    }

    private suspend fun loadAdvancedDirectory(path: String, pushHistory: Boolean, resetScroll: Boolean) {
        val before = _advancedBrowser.value
        if (resetScroll && before.path != path) fileScrollPositions.reset(ADVANCED_STORAGE_SCROLL_OWNER, path)
        val display = runCatching { graph.navigation.directoryDisplaySettings("privileged:$path") }.getOrNull()
            ?: DirectoryDisplaySettings()
        _advancedBrowser.update { it.copy(loading = true, error = null, selectedPaths = emptySet()) }
        val entries = graph.privilegedFiles.list(path, before.includeHidden, before.sortMode, before.sortDirection).getOrThrow()
        _advancedBrowser.update { state ->
            val history = if (pushHistory && before.path.isNotBlank() && before.path != path) {
                (before.backHistory + before.path).takeLast(128)
            } else before.backHistory
            state.copy(
                path = path,
                entries = entries,
                backHistory = history,
                grid = display.layoutMode == DirectoryLayoutMode.GRID,
                iconScalePercent = display.iconScalePercent,
                spacingScalePercent = display.spacingScalePercent,
                gridColumns = display.gridColumns,
                gridStyle = display.gridStyle,
                loading = false,
                error = null,
            )
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        runCatching { graph.appearance.setThemeMode(mode) }
            .onFailure { message(it.message ?: "Išvaizdos nustatymo išsaugoti nepavyko", true) }
    }

    fun setColorPalette(palette: AppColorPalette) {
        runCatching { graph.appearance.setColorPalette(palette) }
            .onFailure { message(it.message ?: "Išvaizdos nustatymo išsaugoti nepavyko", true) }
    }

    fun setAmoledBlack(enabled: Boolean) {
        runCatching { graph.appearance.setAmoledBlack(enabled) }
            .onFailure { message(it.message ?: "Išvaizdos nustatymo išsaugoti nepavyko", true) }
    }

    fun activatePanel(panel: PanelId) {
        _activePanel.value = panel
    }

    fun refreshRecentFiles() {
        recentFilesJob?.cancel()
        recentFilesJob = viewModelScope.launch {
            _recentFiles.update { it.copy(loading = true, error = null) }
            runCatching { graph.recentFiles.latest() }
                .onSuccess { items -> _recentFiles.value = RecentFilesUiState(items = items) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _recentFiles.value = RecentFilesUiState(error = error.message ?: "Naujausių failų įkelti nepavyko")
                }
        }
    }

    fun toggleFilesHomeLayout() {
        val current = _filesHomeDisplaySettings.value
        setFilesHomeDisplaySettings(
            current.copy(
                layoutMode = if (current.layoutMode == DirectoryLayoutMode.GRID) {
                    DirectoryLayoutMode.LIST
                } else {
                    DirectoryLayoutMode.GRID
                },
                showThumbnails = false,
            ),
        )
    }

    fun setFilesHomeDisplaySettings(settings: DirectoryDisplaySettings) {
        val homeSettings = settings.copy(showThumbnails = false)
        runCatching { graph.navigation.setDirectoryDisplaySettings(filesHomeDisplayIdentity, homeSettings) }
            .onSuccess { _filesHomeDisplaySettings.value = homeSettings }
            .onFailure { message(it.message ?: "Pradžios rodinio nustatymo išsaugoti nepavyko", true) }
    }

    fun applyDirectoryDisplaySettingsToAll(
        settings: DirectoryDisplaySettings,
        requestedSortMode: SortMode?,
        requestedSortDirection: SortDirection,
    ) {
        val normalized = DirectoryDisplaySettings(
            layoutMode = settings.layoutMode,
            iconScalePercent = settings.iconScalePercent,
            spacingScalePercent = settings.spacingScalePercent,
            gridColumns = settings.gridColumns,
            gridStyle = settings.gridStyle,
            showThumbnails = settings.showThumbnails,
        )
        val previousDefaults = runCatching { graph.navigation.directoryDisplayDefaults() }.getOrNull()
        val sortMode = requestedSortMode ?: previousDefaults?.sortMode ?: SortMode.NAME
        val sortDirection = requestedSortMode?.let { requestedSortDirection }
            ?: previousDefaults?.sortDirection
            ?: SortDirection.ASCENDING
        runCatching {
            graph.navigation.setDirectoryDisplayDefaults(
                DirectoryDisplayDefaults(normalized, sortMode, sortDirection),
            )
        }.onSuccess {
            _filesHomeDisplaySettings.value = normalized.copy(showThumbnails = false)
            _leftPanel.update { it.withDirectoryDisplaySettings(normalized).copy(sortMode = sortMode, sortDirection = sortDirection) }
            _rightPanel.update { it.withDirectoryDisplaySettings(normalized).copy(sortMode = sortMode, sortDirection = sortDirection) }
            _fileCategory.update {
                it.withFileCategoryDisplaySettings(normalized).copy(sortMode = sortMode, sortDirection = sortDirection)
            }
            _trashBrowser.update {
                it.withTrashDisplaySettings(normalized).copy(sortMode = sortMode, sortDirection = sortDirection)
            }
            _safBrowser.update {
                it.withSafDisplaySettings(normalized).copy(sortMode = sortMode, sortDirection = sortDirection)
            }
            _advancedBrowser.update {
                it.copy(
                    grid = normalized.layoutMode == DirectoryLayoutMode.GRID,
                    iconScalePercent = normalized.iconScalePercent,
                    spacingScalePercent = normalized.spacingScalePercent,
                    gridColumns = normalized.gridColumns,
                    gridStyle = normalized.gridStyle,
                    sortMode = sortMode,
                    sortDirection = sortDirection,
                )
            }
            _networkState.update {
                it.withDirectoryDisplaySettings(normalized.copy(showThumbnails = false))
                    .copy(sortMode = sortMode, sortDirection = sortDirection)
            }
            _leftTabs.update { workspace -> workspace.withGlobalDisplayDefaults(normalized, sortMode, sortDirection) }
            _rightTabs.update { workspace -> workspace.withGlobalDisplayDefaults(normalized, sortMode, sortDirection) }
            persistWorkspace()
            refreshPanel(PanelId.LEFT)
            refreshPanel(PanelId.RIGHT)
            if (_advancedBrowser.value.open) refreshAdvancedBrowser()
            if (_trashBrowser.value.open) refreshTrashBrowser()
            if (_safBrowser.value.location != null) refreshSafBrowser()
            message("Rodinio nustatymai pritaikyti visiems aplankams")
        }.onFailure { error ->
            message(error.message ?: "Bendrų rodinio nustatymų išsaugoti nepavyko", true)
        }
    }

    fun currentDirectoryDisplayDefaults(): DirectoryDisplayDefaults =
        runCatching { graph.navigation.directoryDisplayDefaults() }.getOrNull()
            ?: activePanelState().let { state ->
                DirectoryDisplayDefaults(state.directoryDisplaySettings(), state.sortMode, state.sortDirection)
            }

    fun moveHomeSection(section: HomeSection, offset: Int) {
        updateHomeCustomization { HomeCustomizationRules.moveSection(it, section, offset) }
    }

    fun moveHomeShortcut(id: String, offset: Int) {
        updateHomeCustomization { HomeCustomizationRules.moveShortcut(it, id, offset) }
    }

    fun setHomeShortcutVisible(id: String, visible: Boolean) {
        updateHomeCustomization { HomeCustomizationRules.setShortcutVisible(it, id, visible) }
    }

    fun removeHomeShortcut(id: String) {
        updateHomeCustomization { HomeCustomizationRules.removeShortcut(it, id) }
    }

    fun addHomeShortcut(title: String, path: String): Boolean {
        return runCatching {
            val file = File(path.trim()).canonicalFile
            require(file.exists()) { "Tokia failo ar aplanko vieta neegzistuoja" }
            val shortcutTitle = title.trim().ifEmpty { file.name.ifEmpty { file.absolutePath } }
            val shortcut = HomeShortcut(
                id = "custom.${UUID.randomUUID()}",
                title = shortcutTitle,
                path = file.absolutePath,
            )
            val updated = HomeCustomizationRules.addShortcut(_homeCustomization.value, shortcut)
            _homeCustomization.value = graph.navigation.setHomeCustomization(updated, homeBuiltInShortcuts)
        }.fold(
            onSuccess = { true },
            onFailure = {
                message(it.message ?: "Greitos vietos pridėti nepavyko", true)
                false
            },
        )
    }

    private fun updateHomeCustomization(transform: (HomeCustomization) -> HomeCustomization) {
        runCatching {
            graph.navigation.setHomeCustomization(transform(_homeCustomization.value), homeBuiltInShortcuts)
        }.onSuccess { _homeCustomization.value = it }
            .onFailure { message(it.message ?: "Pradžios ekrano nustatymų išsaugoti nepavyko", true) }
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
        val displaySettings = savedDirectoryDisplaySettings(target)
        val currentState = panelFlow(panel).value
        if (currentState.path != target) {
            fileScrollPositions.reset(tabsFlow(panel).value.activeTabId, target)
        }
        panelFlow(panel).update { state ->
            if (state.path == target) return@update state.copy(selectedPaths = emptySet())
            state.copy(
                path = target,
                entries = emptyList(),
                selectedPaths = emptySet(),
                loading = true,
                listingScannedEntries = 0,
                listingMetadataEntries = 0,
                listingTruncated = false,
                error = null,
                backHistory = if (rememberHistory) (state.backHistory + state.path).takeLast(50) else state.backHistory,
                forwardHistory = if (rememberHistory) emptyList() else state.forwardHistory,
            ).withDirectoryDisplaySettings(displaySettings)
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
        val displaySettings = savedDirectoryDisplaySettings(target)
        flow.update {
            it.copy(
                path = target,
                entries = emptyList(),
                selectedPaths = emptySet(),
                loading = true,
                listingScannedEntries = 0,
                listingMetadataEntries = 0,
                listingTruncated = false,
                error = null,
                backHistory = it.backHistory.dropLast(1),
                forwardHistory = (it.forwardHistory + it.path).takeLast(50),
            ).withDirectoryDisplaySettings(displaySettings)
        }
        syncActiveTab(panel)
        refreshPanel(panel)
        return true
    }

    fun navigateForward(panel: PanelId): Boolean {
        val flow = panelFlow(panel)
        val state = flow.value
        val target = state.forwardHistory.lastOrNull() ?: return false
        val displaySettings = savedDirectoryDisplaySettings(target)
        flow.update {
            it.copy(
                path = target,
                entries = emptyList(),
                selectedPaths = emptySet(),
                loading = true,
                listingScannedEntries = 0,
                listingMetadataEntries = 0,
                listingTruncated = false,
                error = null,
                backHistory = (it.backHistory + it.path).takeLast(50),
                forwardHistory = it.forwardHistory.dropLast(1),
            ).withDirectoryDisplaySettings(displaySettings)
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
        val current = panelFlow(panel).value
        setDirectoryDisplaySettings(
            panel,
            current.directoryDisplaySettings().copy(
                layoutMode = if (current.grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
            ),
        )
    }

    fun toggleThumbnails(panel: PanelId) {
        val snapshot = panelFlow(panel).value
        setDirectoryDisplaySettings(panel, snapshot.directoryDisplaySettings().copy(showThumbnails = !snapshot.showThumbnails))
    }

    fun setDirectoryDisplaySettings(panel: PanelId, settings: DirectoryDisplaySettings) {
        val snapshot = panelFlow(panel).value
        runCatching { graph.navigation.setDirectoryDisplaySettings(snapshot.path, settings) }
            .onSuccess {
                _leftPanel.update { current ->
                    if (current.path == snapshot.path) current.withDirectoryDisplaySettings(settings) else current
                }
                _rightPanel.update { current ->
                    if (current.path == snapshot.path) current.withDirectoryDisplaySettings(settings) else current
                }
                if (_leftPanel.value.path == snapshot.path) syncActiveTab(PanelId.LEFT)
                if (_rightPanel.value.path == snapshot.path) syncActiveTab(PanelId.RIGHT)
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

    fun setSort(panel: PanelId, mode: SortMode, direction: SortDirection) {
        panelFlow(panel).update { it.copy(sortMode = mode, sortDirection = direction) }
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

    fun selectPaths(panel: PanelId, paths: Collection<String>) {
        activatePanel(panel)
        panelFlow(panel).update { state ->
            val available = state.entries.mapTo(hashSetOf(), FileEntry::absolutePath)
            state.copy(selectedPaths = paths.filterTo(linkedSetOf(), available::contains))
        }
    }

    fun selectOnly(panel: PanelId, path: String) {
        selectPaths(panel, listOf(path))
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
        if (setLocalClipboard(selected, move = move, append = false)) clearSelection(panel)
    }

    fun addSelectionToClipboard(panel: PanelId) {
        val selected = panelFlow(panel).value.selectedPaths.toList()
        if (selected.isEmpty()) return
        if (setLocalClipboard(selected, move = false, append = true)) clearSelection(panel)
    }

    private fun setLocalClipboard(paths: List<String>, move: Boolean, append: Boolean): Boolean {
        val normalized = paths.mapNotNull { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }
        if (normalized.isEmpty()) return false
        val mode = if (move) ClipboardMode.MOVE else ClipboardMode.COPY
        val additional = normalized.map { path ->
            AfSourceRef(
                location = AfLocationRef.local(path),
                displayName = File(path).name.ifBlank { path },
            ).normalized()
        }
        val existing = if (append) {
            val current = _afClipboard.value
            if (current == null) {
                message("Nėra kopijavimo rinkinio, kurį būtų galima papildyti", true)
                return false
            }
            if (current.moveAfterVerifiedCopies) {
                message("Iškirptų elementų negalima maišyti su „Kopijuoti daugiau“. Pradėkite naują rinkinį.", true)
                return false
            }
            current.sources
        } else {
            emptyList()
        }
        val merged = ClipboardMergeRules.merge(
            existing = existing,
            additional = additional,
            maximum = AfWorkflowLimits.MAX_SOURCE_ROOTS,
            key = { source -> "${source.kind}:${source.location.identityKey()}:${source.archiveEntryPath.orEmpty()}" },
        )
        _afClipboard.value = AfClipboardState(merged.items, moveAfterVerifiedCopies = move)
        val allLocal = merged.items.all { it.kind == com.affilemanager.app.workflow.AfSourceKind.FILE_SYSTEM && it.location.kind == AfLocationKind.LOCAL }
        _clipboard.value = if (allLocal) {
            ClipboardState(merged.items.map { it.location.path }, mode, ClipboardSource.LOCAL)
        } else {
            null
        }
        _remoteClipboard.value = null
        if (append) {
            message(
                if (merged.addedCount == 0) {
                    "Visi pasirinkti elementai jau yra kopijavimo rinkinyje (iš viso ${merged.items.size})"
                } else {
                    "Pridėta ${merged.addedCount} · iš viso ${merged.items.size}"
                },
            )
        } else {
            message(if (move) "Paruošta perkelti: ${merged.items.size}" else "Nukopijuota į rinkinį: ${merged.items.size}")
        }
        if (merged.limitReached) {
            message("Pasiekta kopijavimo rinkinio riba: ${AfWorkflowLimits.MAX_SOURCE_ROOTS} elementų", true)
        }
        return true
    }

    fun openFileCategory(category: FileCategory, forceRefresh: Boolean = false) {
        fileCategoryJob?.cancel()
        val displayDefaults = runCatching { graph.navigation.directoryDisplayDefaults() }.getOrNull()
        val display = savedDirectoryDisplaySettings(fileCategoryIdentity(category))
        _fileCategory.value = FileCategoryUiState(
            open = true,
            category = category,
            loading = true,
            grid = display.layoutMode == DirectoryLayoutMode.GRID,
            iconScalePercent = display.iconScalePercent,
            spacingScalePercent = display.spacingScalePercent,
            gridColumns = display.gridColumns,
            gridStyle = display.gridStyle,
            showThumbnails = display.showThumbnails,
            sortMode = displayDefaults?.sortMode ?: SortMode.NAME,
            sortDirection = displayDefaults?.sortDirection ?: SortDirection.ASCENDING,
        )
        loadFileCategoryPage(reset = true, forceRefresh = forceRefresh)
    }

    private fun loadFileCategoryPage(reset: Boolean, forceRefresh: Boolean = false) {
        val snapshot = _fileCategory.value
        val category = snapshot.category ?: return
        val offset = if (reset) 0 else snapshot.nextOffset ?: return
        if (!reset && (snapshot.loading || snapshot.loadingMore)) return
        val sortMode = snapshot.sortMode
        val sortDirection = snapshot.sortDirection
        fileCategoryJob?.cancel()
        _fileCategory.update { current ->
            if (current.category != category) current else if (reset) {
                current.copy(
                    entries = emptyList(),
                    selectedPaths = emptySet(),
                    scannedRows = 0,
                    nextOffset = null,
                    truncated = false,
                    loading = true,
                    loadingMore = false,
                    error = null,
                )
            } else {
                current.copy(loadingMore = true, error = null)
            }
        }
        fileCategoryJob = viewModelScope.launch {
            try {
                var currentOffset = offset
                var refresh = forceRefresh
                var pageEntries: List<FileEntry>
                var pageScanned = 0
                var nextOffset: Int?
                var pageTruncated = false
                do {
                    val page = graph.fileCategories.loadPage(
                        category = category,
                        offset = currentOffset,
                        sortMode = sortMode,
                        sortDirection = sortDirection,
                        forceRefresh = refresh,
                    )
                    refresh = false
                    pageEntries = page.entries
                    pageScanned = Math.addExact(pageScanned, page.scannedRows)
                    nextOffset = page.nextOffset
                    pageTruncated = pageTruncated || page.truncated
                    if (pageEntries.isEmpty() && nextOffset != null) currentOffset = nextOffset
                } while (pageEntries.isEmpty() && nextOffset != null)

                val current = _fileCategory.value
                if (!current.open || current.category != category ||
                    current.sortMode != sortMode || current.sortDirection != sortDirection
                ) return@launch
                _fileCategory.update {
                    val merged = (if (reset) pageEntries else it.entries + pageEntries)
                        .distinctBy(FileEntry::absolutePath)
                        .take(com.affilemanager.app.data.FileCategoryRepository.MAX_RESULTS)
                    val reachedResultLimit = merged.size >= com.affilemanager.app.data.FileCategoryRepository.MAX_RESULTS
                    it.copy(
                        entries = merged,
                        scannedRows = (if (reset) pageScanned else it.scannedRows + pageScanned)
                            .coerceAtMost(com.affilemanager.app.data.FileCategoryRepository.MAX_QUERY_ROWS),
                        nextOffset = nextOffset.takeUnless { reachedResultLimit },
                        truncated = it.truncated || pageTruncated || (reachedResultLimit && nextOffset != null),
                        loading = false,
                        loadingMore = false,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _fileCategory.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = error.message ?: "Failų kategorijos atidaryti nepavyko",
                    )
                }
            }
        }
    }

    fun refreshFileCategory() {
        if (_fileCategory.value.category != null) loadFileCategoryPage(reset = true, forceRefresh = true)
    }

    fun loadMoreFileCategory() = loadFileCategoryPage(reset = false)

    @Suppress("DEPRECATION")
    fun openFileCategoryEntry(entry: FileEntry) {
        if (_fileCategory.value.category != FileCategory.INSTALLED_APPS) {
            open(entry)
            return
        }
        val application = getApplication<Application>()
        runCatching {
            val packageName = application.packageManager
                .getPackageArchiveInfo(entry.absolutePath, 0)
                ?.packageName
                ?: error("Programos paketo nustatyti nepavyko")
            val intent = application.packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            application.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { message(it.message ?: "Programos atidaryti nepavyko", true) }
    }

    fun toggleFileCategoryLayout() {
        val state = _fileCategory.value
        setFileCategoryDisplaySettings(
            state.fileCategoryDisplaySettings().copy(
                layoutMode = if (state.grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
            ),
        )
    }

    fun toggleFileCategoryThumbnails() {
        val state = _fileCategory.value
        setFileCategoryDisplaySettings(state.fileCategoryDisplaySettings().copy(showThumbnails = !state.showThumbnails))
    }

    fun setFileCategoryDisplaySettings(settings: DirectoryDisplaySettings) {
        val category = _fileCategory.value.category ?: return
        runCatching { graph.navigation.setDirectoryDisplaySettings(fileCategoryIdentity(category), settings) }
            .onSuccess {
                _fileCategory.update { current ->
                    if (current.category == category) current.withFileCategoryDisplaySettings(settings) else current
                }
            }
            .onFailure { message(it.message ?: "Kategorijos rodinio nustatymo išsaugoti nepavyko", true) }
    }

    fun setFileCategorySort(mode: SortMode, direction: SortDirection) {
        val state = _fileCategory.value
        if (state.category == null || (state.sortMode == mode && state.sortDirection == direction)) return
        _fileCategory.update { it.copy(sortMode = mode, sortDirection = direction, selectedPaths = emptySet()) }
        loadFileCategoryPage(reset = true)
    }

    fun closeFileCategory() {
        fileCategoryJob?.cancel()
        fileCategoryJob = null
        _fileCategory.value = FileCategoryUiState()
    }

    fun openTrashFromHome() {
        openTrashBrowser()
    }

    fun openCleanupFromHome() {
        _cleanupRequested.value = true
        _section.value = AppSection.ANALYZE
        val activePath = activePanelState().path
        val normalizedActivePath = runCatching { File(activePath).absoluteFile.toPath().normalize() }.getOrNull()
        val analysisRoot = _roots.value
            .filter { root ->
                val normalizedRootPath = runCatching { File(root.path).absoluteFile.toPath().normalize() }.getOrNull()
                normalizedActivePath != null && normalizedRootPath != null && normalizedActivePath.startsWith(normalizedRootPath)
            }
            .maxByOrNull { it.path.length }
            ?.path
            ?: activePath
        analyze(analysisRoot)
    }

    fun consumeCleanupRequest() {
        _cleanupRequested.value = false
    }

    fun openTagFromHome(tag: String) {
        _section.value = AppSection.ANALYZE
        val searchRoots = _roots.value.map(StorageRoot::path).distinct().ifEmpty { listOf(initialPrimaryPath) }
        search(SearchFilters(tags = setOf(tag)), searchRoots)
    }

    fun toggleFileCategorySelection(path: String) {
        _fileCategory.update { state ->
            val selected = state.selectedPaths.toMutableSet()
            if (!selected.add(path)) selected.remove(path)
            state.copy(selectedPaths = selected)
        }
    }

    fun toggleAllFileCategoryEntries(visiblePaths: Set<String>) {
        _fileCategory.update { state ->
            val allSelected = visiblePaths.isNotEmpty() && visiblePaths.all(state.selectedPaths::contains)
            state.copy(selectedPaths = if (allSelected) state.selectedPaths - visiblePaths else state.selectedPaths + visiblePaths)
        }
    }

    fun clearFileCategorySelection() {
        _fileCategory.update { it.copy(selectedPaths = emptySet()) }
    }

    fun copyFileCategorySelection(move: Boolean, append: Boolean = false) {
        val paths = _fileCategory.value.selectedPaths.toList()
        if (paths.isEmpty()) return
        if (setLocalClipboard(paths, move, append)) clearFileCategorySelection()
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
        if (clipboard.source == ClipboardSource.PRIVILEGED) {
            graph.operationManager.submit("Kopijuojama iš apsaugotos Android vietos") {
                val result = graph.privilegedFiles.copyToLocal(
                    sourcePaths = clipboard.paths,
                    destinationDirectory = File(destination),
                    move = moving,
                    conflictPolicy = conflictPolicy,
                    operation = this,
                )
                note("Nukopijuota: ${result.copiedRoots} · praleista: ${result.skippedRoots}")
                if (moving && result.skippedRoots == 0) {
                    _clipboard.value = null
                    _afClipboard.value = null
                }
                refreshPanel(panel)
                refreshRecentFiles()
                if (_advancedBrowser.value.open) refreshAdvancedBrowser()
            }.onFailure { message(it.message ?: "Operacijos pradėti nepavyko", true) }
            return
        }
        viewModelScope.launch {
            graph.durableTransfers.createAndSubmit(
                sourcePaths = clipboard.paths,
                destinationPath = destination,
                move = moving,
                conflictPolicy = conflictPolicy,
                verification = verification,
                failurePolicy = if (moving) TransferFailurePolicy.STOP else failurePolicy,
            ).onSuccess {
                if (moving) {
                    _clipboard.value = null
                    _afClipboard.value = null
                }
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
                onSuccess = { created ->
                    runCatching { graph.recentFiles.record(created.absolutePath) }
                        .onFailure { message(it.message ?: "Naujausių failų įrašo išsaugoti nepavyko", true) }
                    refreshPanel(panel)
                    refreshRecentFiles()
                },
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
            if (shouldOpenWithAdvancedAccess(entry.absolutePath)) {
                openAdvancedBrowser(entry.absolutePath)
                return
            }
            _section.value = AppSection.FILES
            navigate(_activePanel.value, entry.absolutePath)
            return
        }
        runCatching { _recents.value = graph.navigation.recordRecent(entry.absolutePath) }
            .onFailure { message(it.message ?: "Istorijos įrašyti nepavyko", true) }
        viewModelScope.launch {
            runCatching { graph.recentFiles.record(entry.absolutePath) }
                .onSuccess { refreshRecentFiles() }
                .onFailure { message(it.message ?: "Naujausių failų įrašo išsaugoti nepavyko", true) }
        }
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

    fun prepareFileEdit() {
        val target = _preview.value ?: return
        if (target is PreviewTarget.Archive || target is PreviewTarget.RemoteArchive || target is PreviewTarget.Vault) return
        val source = target.previewSource()
        val existing = _fileEditState.value
        if (existing.sourceKey == source.key && (existing.session != null || existing.preparing)) return

        val requestId = ++fileEditRequestId
        fileEditJob?.cancel()
        val previousSession = existing.session
        val temporaryDownload = source is PreviewSource.Remote || source is PreviewSource.Privileged
        _fileEditState.value = FileEditUiState(
            sourceKey = source.key,
            temporaryDownload = temporaryDownload,
            preparing = true,
        )
        fileEditJob = viewModelScope.launch {
            var prepared: EditSession? = null
            try {
                withContext(Dispatchers.IO) { graph.editSessions.discard(previousSession) }
                val context = getApplication<Application>()
                val mimeType = source.mimeType(context)
                val internalTextEditor = EditabilityRules.supportsInternalText(source.name, mimeType, source.kind)
                val origin = when (target) {
                    is PreviewTarget.LocalFile -> EditOrigin.Local(target.entry.absolutePath, target.entry.isWritable)
                    is PreviewTarget.TrashFile -> EditOrigin.Local(target.entry.absolutePath, target.entry.isWritable)
                    is PreviewTarget.ContentFile -> EditOrigin.Content(target.entry.uri, target.entry.isWritable)
                    is PreviewTarget.RemoteFile -> EditOrigin.Remote(
                        profileId = target.profileId,
                        connectionName = target.connectionName,
                        path = target.remote.path,
                    )
                    is PreviewTarget.PrivilegedFile -> EditOrigin.Privileged(
                        path = target.entry.absolutePath,
                        canWrite = target.entry.isWritable,
                    )
                }
                prepared = withContext(Dispatchers.IO) {
                    when (source) {
                        is PreviewSource.Content -> graph.editSessions.prepareFromStream(
                            sourceKey = source.key,
                            displayName = source.name,
                            mimeType = mimeType,
                            origin = origin,
                            expectedSizeBytes = source.sizeBytes,
                            modifiedAtMillis = source.modifiedAtMillis,
                            internalTextEditor = internalTextEditor,
                            openSource = { source.openInputStream(context) },
                        )
                        else -> graph.editSessions.prepareFromFile(
                            sourceKey = source.key,
                            displayName = source.name,
                            mimeType = mimeType,
                            sourceFile = requireNotNull(source.localFile) { "Source file is not available" },
                            origin = origin,
                            modifiedAtMillis = source.modifiedAtMillis,
                            internalTextEditor = internalTextEditor,
                        )
                    }
                }
                val document = if (internalTextEditor) withContext(Dispatchers.IO) {
                    graph.editSessions.readTextDocument(requireNotNull(prepared))
                } else null
                if (_preview.value !== target) {
                    withContext(Dispatchers.IO) { graph.editSessions.discard(prepared) }
                    return@launch
                }
                _fileEditState.value = FileEditUiState(
                    sourceKey = source.key,
                    temporaryDownload = temporaryDownload,
                    session = prepared,
                    text = document?.text,
                    stagedText = document?.text,
                    encoding = document?.encoding,
                    stagedEncoding = document?.encoding,
                    lineEnding = document?.lineEnding,
                    stagedLineEnding = document?.lineEnding,
                    status = if (internalTextEditor) "Paruošta redaguoti" else "Redaguojama kopija paruošta",
                )
                if (source is PreviewSource.Remote) {
                    val removed = withContext(Dispatchers.IO) { remotePreviewCache.discard(source.cachedFile) }
                    if (!removed) message("Laikinos pradinio failo kopijos pašalinti nepavyko", true)
                }
                if (source is PreviewSource.Privileged) {
                    val removed = withContext(Dispatchers.IO) { graph.privilegedFiles.discardPreview(source.cachedFile) }
                    if (!removed) message("Laikinos pradinio failo kopijos pašalinti nepavyko", true)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { graph.editSessions.discard(prepared) }
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.IO) { graph.editSessions.discard(prepared) }
                _fileEditState.value = FileEditUiState(
                    sourceKey = source.key,
                    temporaryDownload = temporaryDownload,
                    error = error.message ?: "Nepavyko paruošti redaguojamos kopijos",
                )
            } finally {
                if (fileEditRequestId == requestId) fileEditJob = null
            }
        }
    }

    fun updateEditText(text: String) {
        _fileEditState.update { state ->
            if (state.session?.usesInternalTextEditor != true || state.saving) state
            else state.copy(
                text = text,
                textChanged = text != state.stagedText,
                status = null,
                error = null,
                conflict = null,
                saveAsConflict = null,
            )
        }
    }

    fun updateEditEncoding(encoding: TextEncoding) {
        _fileEditState.update { state ->
            if (state.session?.usesInternalTextEditor != true || state.saving) state
            else state.copy(encoding = encoding, status = null, error = null, conflict = null, saveAsConflict = null)
        }
    }

    fun updateEditLineEnding(lineEnding: LineEnding) {
        _fileEditState.update { state ->
            if (state.session?.usesInternalTextEditor != true || state.saving) state
            else state.copy(lineEnding = lineEnding, status = null, error = null, conflict = null, saveAsConflict = null)
        }
    }

    fun saveFileEdit(forceOverwrite: Boolean = false) {
        val snapshot = _fileEditState.value
        if (snapshot.session == null || snapshot.saving || snapshot.preparing) return
        val requestId = ++fileEditRequestId
        fileEditJob?.cancel()
        _fileEditState.update { it.copy(saving = true, status = null, error = null, conflict = null, saveAsConflict = null) }
        fileEditJob = viewModelScope.launch {
            try {
                var session = requireNotNull(snapshot.session)
                if (session.usesInternalTextEditor && (snapshot.textChanged || snapshot.formatChanged)) {
                    val text = requireNotNull(snapshot.text) { "Editor content is unavailable" }
                    val encoding = requireNotNull(snapshot.encoding) { "Editor encoding is unavailable" }
                    val lineEnding = requireNotNull(snapshot.lineEnding) { "Editor line ending is unavailable" }
                    session = withContext(Dispatchers.IO) {
                        graph.editSessions.stageText(session, text, encoding, lineEnding)
                    }
                    _fileEditState.update { current ->
                        if (current.session?.id == session.id) {
                            current.copy(
                                session = session,
                                stagedText = text,
                                stagedEncoding = encoding,
                                stagedLineEnding = lineEnding,
                                textChanged = false,
                            )
                        } else current
                    }
                } else {
                    session = withContext(Dispatchers.IO) { graph.editSessions.refreshWorking(session) }
                }

                val result = when (session.origin) {
                    is EditOrigin.Local -> withContext(Dispatchers.IO) {
                        graph.editSessions.saveLocal(session, forceOverwrite)
                    }
                    is EditOrigin.Content -> withContext(Dispatchers.IO) {
                        saveContentOrigin(session, forceOverwrite)
                    }
                    is EditOrigin.Remote -> saveRemoteOrigin(session, forceOverwrite)
                    is EditOrigin.Privileged -> withContext(Dispatchers.IO) {
                        graph.privilegedFiles.saveOrigin(session, forceOverwrite)
                    }
                }
                when (result) {
                    is EditSaveResult.Conflict -> _fileEditState.update { current ->
                        if (current.session?.id == session.id) {
                            current.copy(
                                session = session,
                                saving = false,
                                conflict = result.details,
                                closeAfterSave = false,
                                status = null,
                                error = null,
                            )
                        } else current
                    }
                    is EditSaveResult.Saved -> {
                        val saved = withContext(Dispatchers.IO) {
                            graph.editSessions.markOriginSaved(session, result.revision)
                        }
                        val completionStatus = result.warning ?: "Išsaugota pradinėje vietoje"
                        var shouldClose = false
                        _fileEditState.update { current ->
                            if (current.session?.id == session.id) {
                                shouldClose = EditSessionLifecycleRules.closeAfterSuccessfulSave(
                                    temporaryDownload = current.temporaryDownload,
                                    closeRequested = current.closeAfterSave,
                                )
                                current.copy(
                                    session = saved,
                                    saving = false,
                                    textChanged = false,
                                    stagedEncoding = current.encoding,
                                    stagedLineEnding = current.lineEnding,
                                    conflict = null,
                                    saveAsConflict = null,
                                    closeAfterSave = false,
                                    status = completionStatus,
                                    error = null,
                                )
                            } else current
                        }
                        if (shouldClose) {
                            message(completionStatus)
                            fileEditJob = null
                            closePreviewImmediately()
                        }
                        when (session.origin) {
                            is EditOrigin.Local -> {
                                refreshPanel(PanelId.LEFT)
                                refreshPanel(PanelId.RIGHT)
                            }
                            is EditOrigin.Remote -> refreshRemote()
                            is EditOrigin.Privileged -> refreshAdvancedBrowser()
                            is EditOrigin.Content -> Unit
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _fileEditState.update {
                    it.copy(saving = false, closeAfterSave = false, error = error.message ?: "Failo išsaugoti nepavyko")
                }
            } finally {
                if (fileEditRequestId == requestId) fileEditJob = null
            }
        }
    }

    fun saveFileEditAs(destination: Uri) {
        val snapshot = _fileEditState.value
        if (snapshot.session == null || snapshot.saving || snapshot.preparing) return
        val requestId = ++fileEditRequestId
        fileEditJob?.cancel()
        _fileEditState.update { it.copy(saving = true, status = null, error = null, conflict = null, saveAsConflict = null) }
        fileEditJob = viewModelScope.launch {
            try {
                var session = requireNotNull(snapshot.session)
                if (session.usesInternalTextEditor && (snapshot.textChanged || snapshot.formatChanged)) {
                    val text = requireNotNull(snapshot.text) { "Editor content is unavailable" }
                    val encoding = requireNotNull(snapshot.encoding) { "Editor encoding is unavailable" }
                    val lineEnding = requireNotNull(snapshot.lineEnding) { "Editor line ending is unavailable" }
                    session = withContext(Dispatchers.IO) {
                        graph.editSessions.stageText(session, text, encoding, lineEnding)
                    }
                } else {
                    session = withContext(Dispatchers.IO) { graph.editSessions.refreshWorking(session) }
                }
                val verified = withContext(Dispatchers.IO) { writeContentDestination(session, destination) }
                require(session.workingRevision.hasSameContent(verified)) { "Išsaugotos kopijos patikra nepavyko" }
                val saved = withContext(Dispatchers.IO) {
                    graph.editSessions.rebaseOrigin(
                        session = session,
                        destination = EditDestination.Content(
                            destination.toString(),
                            contentDisplayName(destination) ?: session.displayName,
                        ),
                        savedRevision = verified,
                    )
                }
                val completionStatus = "Išsaugota pasirinktoje vietoje"
                var shouldClose = false
                _fileEditState.update { current ->
                    if (current.session?.id == session.id) {
                        shouldClose = EditSessionLifecycleRules.closeAfterSuccessfulSave(
                            temporaryDownload = current.temporaryDownload,
                            closeRequested = current.closeAfterSave,
                        )
                        current.copy(
                            session = saved,
                            saving = false,
                            stagedText = current.text,
                            stagedEncoding = current.encoding,
                            stagedLineEnding = current.lineEnding,
                            textChanged = false,
                            conflict = null,
                            saveAsConflict = null,
                            closeAfterSave = false,
                            status = "Išsaugota pasirinktoje vietoje; tai dabar aktyvus failas",
                            error = null,
                        )
                    } else current
                }
                if (shouldClose) {
                    message(completionStatus)
                    fileEditJob = null
                    closePreviewImmediately()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _fileEditState.update {
                    it.copy(saving = false, closeAfterSave = false, error = error.message ?: "Kopijos išsaugoti nepavyko")
                }
            } finally {
                if (fileEditRequestId == requestId) fileEditJob = null
            }
        }
    }

    fun saveFileEditAsLocal(
        directoryPath: String,
        requestedName: String,
        policy: EditExistingPolicy = EditExistingPolicy.ASK,
    ) {
        val destination = runCatching {
            val name = EditDestinationRules.validateFileName(requestedName)
            EditDestination.Local(File(File(directoryPath).canonicalFile, name).absolutePath)
        }.getOrElse { error ->
            _fileEditState.update { it.copy(error = error.message ?: "Netinkamas failo vardas") }
            return
        }
        saveFileEditAsDestination(destination, policy)
    }

    fun saveFileEditAsRemote(
        directoryPath: String,
        requestedName: String,
        policy: EditExistingPolicy = EditExistingPolicy.ASK,
    ) {
        val profile = _networkState.value.connectedProfile
        if (profile == null || remoteClient == null) {
            _fileEditState.update { it.copy(error = "Prieš išsaugodami serveryje prisijunkite prie serverio") }
            return
        }
        val destination = runCatching {
            val name = EditDestinationRules.validateFileName(requestedName)
            EditDestination.Remote(
                profileId = profile.id,
                connectionName = profile.name,
                path = RemotePath.join(directoryPath, name),
            )
        }.getOrElse { error ->
            _fileEditState.update { it.copy(error = error.message ?: "Netinkamas failo vardas") }
            return
        }
        saveFileEditAsDestination(destination, policy)
    }

    fun resolveFileEditSaveAsConflict(policy: EditExistingPolicy) {
        require(policy != EditExistingPolicy.ASK) { "Choose replace or keep both" }
        val destination = _fileEditState.value.saveAsConflict?.destination ?: return
        _fileEditState.update { it.copy(saveAsConflict = null) }
        saveFileEditAsDestination(destination, policy)
    }

    fun dismissFileEditSaveAsConflict() {
        _fileEditState.update { it.copy(saveAsConflict = null) }
    }

    suspend fun listRemoteDirectoryForEdit(path: String): Result<List<RemoteEntry>> {
        val client = remoteClient ?: return Result.failure(IllegalStateException("Server connection is not active"))
        return runCatching { graph.remoteEdits.listDirectories(client, path) }
    }

    private fun saveFileEditAsDestination(destination: EditDestination, policy: EditExistingPolicy) {
        val snapshot = _fileEditState.value
        if (snapshot.session == null || snapshot.saving || snapshot.preparing) return
        val requestId = ++fileEditRequestId
        fileEditJob?.cancel()
        _fileEditState.update {
            it.copy(saving = true, status = null, error = null, conflict = null, saveAsConflict = null)
        }
        fileEditJob = viewModelScope.launch {
            try {
                var session = requireNotNull(snapshot.session)
                if (session.usesInternalTextEditor && (snapshot.textChanged || snapshot.formatChanged)) {
                    val text = requireNotNull(snapshot.text) { "Editor content is unavailable" }
                    val encoding = requireNotNull(snapshot.encoding) { "Editor encoding is unavailable" }
                    val lineEnding = requireNotNull(snapshot.lineEnding) { "Editor line ending is unavailable" }
                    session = withContext(Dispatchers.IO) {
                        graph.editSessions.stageText(session, text, encoding, lineEnding)
                    }
                } else {
                    session = withContext(Dispatchers.IO) { graph.editSessions.refreshWorking(session) }
                }

                val result = when (destination) {
                    is EditDestination.Local -> withContext(Dispatchers.IO) {
                        val target = File(destination.path)
                        graph.editSessions.saveLocalAs(
                            session = session,
                            directoryPath = requireNotNull(target.parentFile).absolutePath,
                            requestedName = target.name,
                            policy = policy,
                        )
                    }
                    is EditDestination.Remote -> {
                        val profile = _networkState.value.connectedProfile
                        val client = remoteClient
                        require(profile?.id == destination.profileId && client != null) {
                            "Vėl prisijunkite prie ${destination.connectionName}"
                        }
                        graph.remoteEdits.saveAs(
                            session = session,
                            client = client,
                            profileId = destination.profileId,
                            connectionName = destination.connectionName,
                            directoryPath = RemotePath.normalize("${destination.path}/.."),
                            requestedName = destination.displayName,
                            policy = policy,
                        )
                    }
                    is EditDestination.Content -> error("Content destinations use the Android document picker")
                }

                when (result) {
                    is EditSaveAsResult.Conflict -> _fileEditState.update { current ->
                        if (current.session?.id == session.id) {
                            current.copy(
                                session = session,
                                saving = false,
                                stagedText = current.text,
                                stagedEncoding = current.encoding,
                                stagedLineEnding = current.lineEnding,
                                textChanged = false,
                                saveAsConflict = result.details,
                                closeAfterSave = false,
                            )
                        } else current
                    }
                    is EditSaveAsResult.Saved -> {
                        val saved = withContext(Dispatchers.IO) {
                            graph.editSessions.rebaseOrigin(session, result.destination, result.revision)
                        }
                        val completionStatus = result.warning ?: "Išsaugota kaip ${result.destination.label}"
                        var shouldClose = false
                        _fileEditState.update { current ->
                            if (current.session?.id == session.id) {
                                shouldClose = EditSessionLifecycleRules.closeAfterSuccessfulSave(
                                    temporaryDownload = current.temporaryDownload,
                                    closeRequested = current.closeAfterSave,
                                )
                                current.copy(
                                    session = saved,
                                    saving = false,
                                    stagedText = current.text,
                                    stagedEncoding = current.encoding,
                                    stagedLineEnding = current.lineEnding,
                                    textChanged = false,
                                    conflict = null,
                                    saveAsConflict = null,
                                    closeAfterSave = false,
                                    status = result.warning ?: "Išsaugota kaip ${result.destination.label}; tai dabar aktyvus failas",
                                    error = null,
                                )
                            } else current
                        }
                        when (result.destination) {
                            is EditDestination.Local -> {
                                refreshPanel(PanelId.LEFT)
                                refreshPanel(PanelId.RIGHT)
                            }
                            is EditDestination.Remote -> refreshRemote()
                            is EditDestination.Content -> Unit
                        }
                        if (shouldClose) {
                            message(completionStatus)
                            fileEditJob = null
                            closePreviewImmediately()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _fileEditState.update {
                    it.copy(saving = false, closeAfterSave = false, error = error.message ?: "Failo išsaugoti nepavyko")
                }
            } finally {
                if (fileEditRequestId == requestId) fileEditJob = null
            }
        }
    }

    fun refreshFileEditAfterExternalEditor() {
        val snapshot = _fileEditState.value
        val session = snapshot.session ?: return
        if (snapshot.saving || snapshot.preparing) return
        val requestId = ++fileEditRequestId
        fileEditJob?.cancel()
        _fileEditState.update { it.copy(preparing = true, status = null, error = null) }
        fileEditJob = viewModelScope.launch {
            try {
                val refreshed = withContext(Dispatchers.IO) { graph.editSessions.refreshWorking(session) }
                val refreshedDocument = if (session.usesInternalTextEditor) withContext(Dispatchers.IO) {
                    graph.editSessions.readTextDocument(refreshed)
                } else null
                val refreshedText = refreshedDocument?.text ?: snapshot.text
                _fileEditState.update { current ->
                    if (current.session?.id == session.id) {
                        current.copy(
                            session = refreshed,
                            text = refreshedText,
                            stagedText = refreshedText,
                            encoding = refreshedDocument?.encoding ?: current.encoding,
                            stagedEncoding = refreshedDocument?.encoding ?: current.stagedEncoding,
                            lineEnding = refreshedDocument?.lineEnding ?: current.lineEnding,
                            stagedLineEnding = refreshedDocument?.lineEnding ?: current.stagedLineEnding,
                            textChanged = false,
                            preparing = false,
                            status = if (refreshed.workingRevision.hasSameContent(session.workingRevision)) {
                                "Išorinis redaktorius uždarytas nepakeitus turinio"
                            } else {
                                "Išorinio redaktoriaus pakeitimus galima išsaugoti"
                            },
                            error = null,
                        )
                    } else current
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _fileEditState.update {
                    it.copy(preparing = false, error = error.message ?: "Redaguotos kopijos perskaityti nepavyko")
                }
            } finally {
                if (fileEditRequestId == requestId) fileEditJob = null
            }
        }
    }

    fun dismissFileEditConflict() {
        _fileEditState.update { it.copy(conflict = null) }
    }

    private fun shouldOpenWithAdvancedAccess(path: String): Boolean {
        if (!graph.advancedAccess.state.value.connected) return false
        val primary = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        return listOf("$primary/Android/data", "$primary/Android/obb").any { protectedRoot ->
            path == protectedRoot || path.startsWith("$protectedRoot/")
        }
    }

    fun mergeFileEditConflict() {
        val snapshot = _fileEditState.value
        val session = snapshot.session ?: return
        val origin = session.origin as? EditOrigin.Remote ?: run {
            message("Trijų versijų sujungimas dabar galimas nuotoliniams teksto failams", true)
            return
        }
        if (!session.usesInternalTextEditor || snapshot.conflict == null || snapshot.saving) return
        val profile = _networkState.value.connectedProfile
        val client = remoteClient
        if (profile?.id != origin.profileId || client == null) {
            message("Prieš sujungdami vėl prisijunkite prie ${origin.connectionName}", true)
            return
        }
        val requestId = ++fileEditRequestId
        fileEditJob?.cancel()
        _fileEditState.update { it.copy(saving = true, status = "Lyginamos trys versijos…", error = null) }
        fileEditJob = viewModelScope.launch {
            try {
                val encoding = snapshot.encoding ?: TextEncoding.UTF8
                val base = withContext(Dispatchers.IO) { graph.editSessions.readBaseTextDocument(session, encoding) }
                val yours = withContext(Dispatchers.IO) { graph.editSessions.readTextDocument(session, encoding) }
                val current = graph.remoteEdits.readCurrentText(session, client, encoding)
                    ?: throw IllegalStateException("The current remote file no longer exists")
                val merge = withContext(Dispatchers.Default) {
                    graph.textMerge.merge(base.text, yours.text, current.document.text)
                }
                val rebased = withContext(Dispatchers.IO) {
                    graph.editSessions.rebaseAfterMerge(session, current.revision, current.document)
                }
                _fileEditState.update { state ->
                    if (state.session?.id != session.id) state else state.copy(
                        session = rebased,
                        text = merge.text,
                        textChanged = merge.text != yours.text,
                        saving = false,
                        conflict = null,
                        status = if (merge.clean) {
                            "Pakeitimai sujungti. Peržiūrėkite ir išsaugokite rezultatą."
                        } else {
                            "Redaktoriuje pažymėta konfliktų: ${merge.conflicts.size}. Išspręskite žymeklius ir išsaugokite."
                        },
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _fileEditState.update {
                    it.copy(saving = false, error = error.message ?: "Trijų versijų sujungimas nepavyko")
                }
            } finally {
                if (fileEditRequestId == requestId) fileEditJob = null
            }
        }
    }

    fun openAfWorkflowCenter(tab: AfWorkflowTab = AfWorkflowTab.PLANS) {
        _afWorkflowUi.update { it.copy(open = true, tab = tab, error = null) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { graph.workflows.refresh() } }
    }

    fun closeAfWorkflowCenter() {
        _afWorkflowUi.value = AfWorkflowUiState()
    }

    fun startAfPlanFromClipboard(destinationPanel: PanelId? = null, remoteDestination: Boolean = false) {
        val clipboard = _afClipboard.value
        if (clipboard == null || clipboard.sources.isEmpty()) {
            message("Pirmiausia nukopijuokite failus, tada atverkite „Įklijuoti į kelias vietas“", true)
            return
        }
        val destinations = buildList {
            destinationPanel?.let { panel ->
                add(AfDestinationRef(AfLocationRef.local(panelFlow(panel).value.path)))
            }
            if (remoteDestination) {
                val profile = _networkState.value.connectedProfile
                if (profile != null) {
                    add(AfDestinationRef(AfLocationRef.remote(profile.id, profile.name, _networkState.value.path)))
                }
            }
        }
        _afWorkflowUi.value = AfWorkflowUiState(
            open = true,
            tab = AfWorkflowTab.PLANS,
            name = "AF Plan ${System.currentTimeMillis()}",
            sources = clipboard.sources,
            destinations = destinations,
            deleteSourcesAfterVerifiedCopies = clipboard.moveAfterVerifiedCopies,
        )
    }

    fun editAfPlan(plan: AfPlanDefinition) {
        _afWorkflowUi.value = AfWorkflowUiState(
            open = true,
            tab = AfWorkflowTab.PLANS,
            editingPlanId = plan.id,
            name = plan.name,
            sources = plan.sources,
            destinations = plan.destinations,
            conflictPolicy = plan.conflictPolicy,
            verification = plan.verification,
            failurePolicy = plan.failurePolicy,
            deleteSourcesAfterVerifiedCopies = plan.deleteSourcesAfterVerifiedCopies,
        )
    }

    fun newAfPlanDraft() {
        val sources = _afClipboard.value?.sources.orEmpty()
        _afWorkflowUi.update {
            AfWorkflowUiState(
                open = true,
                tab = AfWorkflowTab.PLANS,
                name = if (sources.isEmpty()) "" else "AF Plan ${System.currentTimeMillis()}",
                sources = sources,
                deleteSourcesAfterVerifiedCopies = _afClipboard.value?.moveAfterVerifiedCopies == true,
            )
        }
    }

    fun setAfWorkflowTab(tab: AfWorkflowTab) {
        _afWorkflowUi.update { it.copy(tab = tab, error = null, undoPreview = null) }
    }

    fun setAfPlanName(name: String) = _afWorkflowUi.update {
        it.copy(name = name.take(AfWorkflowLimits.MAX_NAME_LENGTH), preview = null, error = null)
    }

    fun setAfPlanConflictPolicy(policy: ConflictPolicy) = _afWorkflowUi.update {
        it.copy(
            conflictPolicy = policy,
            failurePolicy = if (policy == ConflictPolicy.REPLACE) TransferFailurePolicy.STOP else it.failurePolicy,
            preview = null,
            error = null,
        )
    }

    fun setAfPlanVerification(verification: TransferVerification) = _afWorkflowUi.update {
        it.copy(verification = verification, preview = null, error = null)
    }

    fun setAfPlanFailurePolicy(policy: TransferFailurePolicy) = _afWorkflowUi.update {
        it.copy(
            failurePolicy = if (it.conflictPolicy == ConflictPolicy.REPLACE) TransferFailurePolicy.STOP else policy,
            preview = null,
            error = null,
        )
    }

    fun setAfPlanDeleteSources(enabled: Boolean) = _afWorkflowUi.update {
        it.copy(
            deleteSourcesAfterVerifiedCopies = enabled,
            failurePolicy = if (enabled) TransferFailurePolicy.STOP else it.failurePolicy,
            preview = null,
            error = null,
        )
    }

    fun addActiveLocalAfDestination() {
        addAfDestination(AfDestinationRef(AfLocationRef.local(activePanelState().path)))
    }

    fun addCurrentRemoteAfDestination() {
        val profile = _networkState.value.connectedProfile
        if (profile == null) {
            message("Prieš pridėdami dabartinį serverio aplanką prisijunkite prie serverio", true)
            return
        }
        addAfDestination(AfDestinationRef(AfLocationRef.remote(profile.id, profile.name, _networkState.value.path)))
    }

    fun removeAfDestination(index: Int) = _afWorkflowUi.update { state ->
        if (index !in state.destinations.indices) state
        else state.copy(destinations = state.destinations.filterIndexed { itemIndex, _ -> itemIndex != index }, preview = null)
    }

    fun removeAfSource(index: Int) = _afWorkflowUi.update { state ->
        if (index !in state.sources.indices) state
        else state.copy(sources = state.sources.filterIndexed { itemIndex, _ -> itemIndex != index }, preview = null)
    }

    fun toggleAfDestinationRequired(index: Int) = _afWorkflowUi.update { state ->
        if (index !in state.destinations.indices) state else state.copy(
            destinations = state.destinations.mapIndexed { itemIndex, destination ->
                if (itemIndex == index) destination.copy(required = !destination.required) else destination
            },
            preview = null,
        )
    }

    fun previewAfPlan() {
        val snapshot = _afWorkflowUi.value
        if (snapshot.working) return
        val plan = runCatching { snapshot.toPlanDefinition() }.getOrElse { error ->
            _afWorkflowUi.update { it.copy(error = error.message ?: "AF planas neužbaigtas") }
            return
        }
        _afWorkflowUi.update { it.copy(working = true, preview = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.workflows.preview(plan) }
                .onSuccess { preview -> _afWorkflowUi.update { it.copy(working = false, preview = preview, error = null) } }
                .onFailure { error -> _afWorkflowUi.update { it.copy(working = false, error = error.message ?: "AF plano peržiūra nepavyko") } }
        }
    }

    fun saveAfPlan() {
        val snapshot = _afWorkflowUi.value
        if (snapshot.working) return
        val plan = runCatching { snapshot.toPlanDefinition() }.getOrElse { error ->
            _afWorkflowUi.update { it.copy(error = error.message ?: "AF planas neužbaigtas") }
            return
        }
        _afWorkflowUi.update { it.copy(working = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.workflows.savePlan(plan) }
                .onSuccess { saved ->
                    _afWorkflowUi.update { it.copy(working = false, editingPlanId = saved.id, error = null) }
                    message("AF planas išsaugotas")
                }
                .onFailure { error -> _afWorkflowUi.update { it.copy(working = false, error = error.message ?: "AF plano išsaugoti nepavyko") } }
        }
    }

    fun runAfPlan() {
        val snapshot = _afWorkflowUi.value
        val preview = snapshot.preview ?: run {
            _afWorkflowUi.update { it.copy(error = "Prieš vykdydami peržiūrėkite AF planą") }
            return
        }
        if (!preview.canRun || snapshot.working) return
        _afWorkflowUi.update { it.copy(working = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val saved = graph.workflows.savePlan(preview.plan)
                val fresh = graph.workflows.preview(saved)
                require(AfPreflightFingerprint.create(fresh) == AfPreflightFingerprint.create(preview)) {
                    "Po peržiūros failai pasikeitė; peržiūrėkite atnaujintą planą"
                }
                graph.workflows.submit(fresh)
            }.onSuccess {
                _afWorkflowUi.update { it.copy(working = false, editingPlanId = preview.plan.id, error = null) }
                message("AF planas įtrauktas į operacijų eilę")
            }.onFailure { error ->
                _afWorkflowUi.update { it.copy(working = false, error = error.message ?: "AF plano paleisti nepavyko") }
            }
        }
    }

    fun removeAfPlan(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.workflows.removePlan(id) }
                .onFailure { message(it.message ?: "AF plano pašalinti nepavyko", true) }
        }
    }

    fun setAfTimelineQuery(query: String) = _afWorkflowUi.update { it.copy(timelineQuery = query.take(240)) }

    fun selectAfReceipt(id: String?) = _afWorkflowUi.update {
        it.copy(selectedReceiptId = id, undoPreview = null, error = null)
    }

    fun previewAfUndo(receiptId: String) {
        _afWorkflowUi.update { it.copy(working = true, undoPreview = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.workflows.previewUndo(receiptId) }
                .onSuccess { preview -> _afWorkflowUi.update { it.copy(working = false, undoPreview = preview) } }
                .onFailure { error -> _afWorkflowUi.update { it.copy(working = false, error = error.message ?: "Atšaukimo peržiūra nepavyko") } }
        }
    }

    fun runAfUndo() {
        val state = _afWorkflowUi.value
        val receiptId = state.selectedReceiptId ?: return
        val preview = state.undoPreview ?: return
        graph.workflows.submitUndo(receiptId, preview)
            .onSuccess {
                _afWorkflowUi.update { it.copy(undoPreview = null, selectedReceiptId = null) }
                message("Saugus atšaukimas įtrauktas į operacijų eilę")
            }
            .onFailure { error -> _afWorkflowUi.update { it.copy(error = error.message ?: "Atšaukimo paleisti nepavyko") } }
    }

    fun createAfAutomation(
        plan: AfPlanDefinition,
        name: String,
        schedule: AfAutomationSchedule,
        unmeteredOnly: Boolean,
        chargingOnly: Boolean,
    ) {
        require(schedule != AfAutomationSchedule.MANUAL_ONLY) { "Pasirinkite automatinį tvarkaraštį" }
        val approved = _afWorkflowUi.value.automationPreview
        if (approved == null || approved.plan.id != plan.id || !approved.canRun) {
            _afWorkflowUi.update { it.copy(error = "Pirmiausia peržiūrėkite automatikos planą") }
            return
        }
        _afWorkflowUi.update { it.copy(working = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val preview = graph.workflows.preview(plan.copy(verification = TransferVerification.SHA256))
                require(com.affilemanager.app.workflow.AfAutomationPolicy.blocker(preview) == null) {
                    com.affilemanager.app.workflow.AfAutomationPolicy.blocker(preview)
                        ?: "AF plano peržiūroje yra kliūčių"
                }
                require(AfPreflightFingerprint.create(preview) == AfPreflightFingerprint.create(approved)) {
                    "Failai pasikeitė po peržiūros; peržiūrėkite automatikos planą iš naujo"
                }
                val rule = graph.workflows.saveAutomation(
                    AfAutomationRule(
                        name = name,
                        planId = plan.id,
                        enabled = true,
                        schedule = schedule,
                        unmeteredOnly = unmeteredOnly,
                        chargingOnly = chargingOnly,
                    ),
                )
                graph.workflows.approveAutomationPreview(rule.id, preview)
            }.onSuccess {
                _afWorkflowUi.update { it.copy(working = false, automationPreview = null, error = null) }
                message("Automatika išsaugota su dabartine peržiūra")
            }.onFailure { error -> _afWorkflowUi.update { it.copy(working = false, error = error.message ?: "Automatikos išsaugoti nepavyko") } }
        }
    }

    fun previewAfAutomation(plan: AfPlanDefinition) {
        if (_afWorkflowUi.value.working) return
        _afWorkflowUi.update { it.copy(working = true, automationPreview = null, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.workflows.preview(plan.copy(verification = TransferVerification.SHA256)) }
                .onSuccess { preview ->
                    val blocker = com.affilemanager.app.workflow.AfAutomationPolicy.blocker(preview)
                    val reviewed = if (blocker == null || blocker in preview.blockers) preview
                    else preview.copy(blockers = (preview.blockers + blocker).distinct())
                    _afWorkflowUi.update { it.copy(working = false, automationPreview = reviewed, error = null) }
                }
                .onFailure { error ->
                    _afWorkflowUi.update {
                        it.copy(working = false, error = error.message ?: "Automatikos plano peržiūra nepavyko")
                    }
                }
        }
    }

    fun clearAfAutomationPreview() = _afWorkflowUi.update {
        it.copy(automationPreview = null, error = null)
    }

    fun setAfAutomationEnabled(rule: AfAutomationRule, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.workflows.saveAutomation(rule.copy(enabled = enabled)) }
                .onFailure { message(it.message ?: "Automatikos atnaujinti nepavyko", true) }
        }
    }

    fun removeAfAutomation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.workflows.removeAutomation(id) }
                .onFailure { message(it.message ?: "Automatikos pašalinti nepavyko", true) }
        }
    }

    fun afReceiptJson(receiptId: String): String = graph.workflows.exportReceiptJson(receiptId)
    fun afReceiptText(receiptId: String): String = graph.workflows.exportReceiptText(receiptId)

    fun clearAfClipboard() {
        _afClipboard.value = null
        _clipboard.value = null
        _remoteClipboard.value = null
    }

    fun copyArchiveEntryToSet(entryPath: String) {
        val target = _preview.value as? PreviewTarget.Archive ?: run {
            message("Į AF planą galima pridėti tik vietinio archyvo įrašą", true)
            return
        }
        val source = runCatching {
            AfSourceRef(
                location = AfLocationRef.local(target.file.absolutePath),
                displayName = entryPath.trim('/').substringAfterLast('/').ifBlank {
                    target.file.name.substringBeforeLast('.', target.file.name)
                },
                kind = AfSourceKind.ARCHIVE_ENTRY,
                archiveEntryPath = entryPath,
            ).normalized()
        }.getOrElse { error ->
            message(error.message ?: "Archyvo įrašo pridėti nepavyko", true)
            return
        }
        val existing = _afClipboard.value
        if (existing?.moveAfterVerifiedCopies == true) {
            message("Archyvo įrašų negalima pridėti prie iškirpimo rinkinio", true)
            return
        }
        val merged = ClipboardMergeRules.merge(
            existing = existing?.sources.orEmpty(),
            additional = listOf(source),
            maximum = AfWorkflowLimits.MAX_SOURCE_ROOTS,
            key = { item -> "${item.kind}:${item.location.identityKey()}:${item.archiveEntryPath.orEmpty()}" },
        )
        _afClipboard.value = AfClipboardState(merged.items)
        _clipboard.value = null
        _remoteClipboard.value = null
        message(if (merged.addedCount > 0) "Archyvo įrašas pridėtas prie kopijavimo rinkinio" else "Archyvo įrašas jau yra kopijavimo rinkinyje")
    }

    private fun addAfDestination(destination: AfDestinationRef) {
        _afWorkflowUi.update { state ->
            if (state.destinations.any { it.location.identityKey() == destination.location.identityKey() }) {
                state.copy(error = "Ši paskirtis jau yra AF plane")
            } else if (state.destinations.size >= AfWorkflowLimits.MAX_DESTINATIONS) {
                state.copy(error = "Pasiekta AF plano paskirčių riba")
            } else {
                state.copy(destinations = state.destinations + destination, preview = null, error = null)
            }
        }
    }

    private fun AfWorkflowUiState.toPlanDefinition(): AfPlanDefinition = AfPlanDefinition(
        id = editingPlanId ?: UUID.randomUUID().toString(),
        name = name,
        sources = sources,
        destinations = destinations,
        conflictPolicy = conflictPolicy,
        verification = verification,
        failurePolicy = if (deleteSourcesAfterVerifiedCopies) TransferFailurePolicy.STOP else failurePolicy,
        deleteSourcesAfterVerifiedCopies = deleteSourcesAfterVerifiedCopies,
    ).normalized()

    fun closePreview() {
        if (_fileEditState.value.saving) {
            _fileEditState.update { it.copy(closeAfterSave = true) }
        } else if (_fileEditState.value.hasUnsavedChanges) {
            _fileEditState.update { it.copy(confirmDiscard = true) }
        } else {
            closePreviewImmediately()
        }
    }

    fun keepEditing() {
        _fileEditState.update { it.copy(confirmDiscard = false) }
    }

    fun discardFileEditAndClose() {
        closePreviewImmediately()
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
        val fallbackName = getApplication<Application>().getString(R.string.generated_extracted_name)
        val destination = File(file.file.parentFile, file.name.substringBeforeLast('.').ifBlank { fallbackName })
        graph.operationManager.submit("Išpakuojamas ${file.name}") {
            graph.archives.extract(
                archiveFile = file.file,
                destinationDirectory = destination,
                password = password,
                operation = this,
                fallbackExtractedName = fallbackName,
            )
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
                val pathPredicate: (FileEntry) -> Boolean = { entry ->
                    taggedPaths == null || entry.absolutePath in taggedPaths
                }
                val result = graph.fileCategories.searchIndexed(roots, filters, pathPredicate)
                    ?: graph.search.search(roots, filters, pathPredicate)
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

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        _searchState.update { it.copy(running = false, error = null) }
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
        if (setLocalClipboard(selected, move = move, append = false)) clearSearchSelection()
    }

    fun addSearchSelectionToClipboard() {
        val selected = orderedSearchSelection()
        if (selected.isEmpty()) return
        if (setLocalClipboard(selected, move = false, append = true)) clearSearchSelection()
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
        similarImagesJob?.cancel()
        _analysisState.value = AnalysisUiState(rootPath = path, running = true)
        analysisJob = viewModelScope.launch {
            try {
                val analysis = graph.search.analyze(listOf(path))
                val duplicateAnalysis = graph.search.duplicates(listOf(path))
                _analysisState.value = AnalysisUiState(
                    analysis = analysis,
                    duplicates = duplicateAnalysis.groups,
                    duplicateCandidatesScanned = duplicateAnalysis.scannedCandidates,
                    duplicateScanTruncated = duplicateAnalysis.truncated,
                    rootPath = path,
                    running = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _analysisState.value = AnalysisUiState(rootPath = path, running = false, error = error.message)
            }
        }
    }

    fun analyzeSimilarImages() {
        val analysis = _analysisState.value.analysis ?: return
        if (_analysisState.value.similarImagesRunning) return
        similarImagesJob?.cancel()
        similarImagesJob = viewModelScope.launch {
            _analysisState.update {
                it.copy(similarImagesRunning = true, similarImagesAnalyzed = false, similarImagesError = null)
            }
            try {
                val groups = graph.similarImages.find(analysis.similarImageCandidates)
                _analysisState.update {
                    it.copy(
                        similarImages = groups,
                        similarImagesRunning = false,
                        similarImagesAnalyzed = true,
                        similarImagesError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _analysisState.update {
                    it.copy(
                        similarImagesRunning = false,
                        similarImagesAnalyzed = true,
                        similarImagesError = error.message ?: "Panašių nuotraukų analizė nepavyko",
                    )
                }
            }
        }
    }

    fun trashAnalysisSelection(paths: Collection<String>) {
        val state = _analysisState.value
        val rootPath = state.rootPath ?: return
        val root = runCatching { File(rootPath).canonicalFile }.getOrElse {
            message("Analizės vieta nebepasiekiama", true)
            return
        }
        val selected = runCatching {
            val requested = paths.distinct()
            require(requested.size <= 10_000) { "Vienu metu galima tvarkyti iki 10 000 elementų" }
            requested.asSequence()
                .map(::File)
                .map { it.canonicalFile }
                .filter(File::exists)
                .onEach { candidate ->
                    require(candidate != root && FileSystemRules.isContained(root, candidate)) {
                        "Pasirinktas failas yra už analizuojamo aplanko ribų"
                    }
                }
                .sortedBy { it.absolutePath.length }
                .fold(mutableListOf<File>()) { accepted, candidate ->
                    if (accepted.none { parent -> FileSystemRules.isContained(parent, candidate) }) accepted += candidate
                    accepted
                }
                .toList()
        }.getOrElse {
            message(it.message ?: "Valymo pasirinkimas netinkamas", true)
            return
        }
        if (selected.isEmpty()) return
        fun scheduled(path: String): Boolean {
            val candidate = File(path)
            return selected.any { selectedRoot -> FileSystemRules.isContained(selectedRoot, candidate) }
        }
        val removedWholeDuplicateGroup = state.duplicates.any { group ->
            group.paths.isNotEmpty() && group.paths.all(::scheduled)
        }
        val removedWholeSimilarGroup = state.similarImages.any { group ->
            group.files.isNotEmpty() && group.files.all { scheduled(it.absolutePath) }
        }
        if (removedWholeDuplicateGroup || removedWholeSimilarGroup) {
            message("Iš kiekvienos vienodų ar panašių failų grupės palikite bent vieną failą", true)
            return
        }
        graph.operationManager.submit("Pasirinkti failai keliami į šiukšlinę") {
            graph.trash.moveToTrash(selected.map(File::getAbsolutePath), this)
            viewModelScope.launch { analyze(rootPath) }
        }.onFailure { message(it.message ?: "Valymo pradėti nepavyko", true) }
    }

    fun refreshTrash() {
        viewModelScope.launch {
            _trashItems.value = graph.trash.list()
            if (_trashBrowser.value.open) refreshTrashBrowser()
        }
    }

    fun openTrashBrowser() {
        val displayDefaults = runCatching { graph.navigation.directoryDisplayDefaults() }.getOrNull()
        val display = savedDirectoryDisplaySettings(trashDirectoryIdentity(null, ""))
        _trashBrowser.value = TrashBrowserUiState(
            open = true,
            sortMode = displayDefaults?.sortMode ?: SortMode.NAME,
            sortDirection = displayDefaults?.sortDirection ?: SortDirection.ASCENDING,
        ).withTrashDisplaySettings(display)
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
        val display = savedDirectoryDisplaySettings(trashDirectoryIdentity(nextItemId, nextRelativePath))
        _trashBrowser.update {
            it.copy(
                itemId = nextItemId,
                relativePath = nextRelativePath,
                rootName = nextRootName,
                entries = emptyList(),
                error = null,
            ).withTrashDisplaySettings(display)
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
        val display = savedDirectoryDisplaySettings(trashDirectoryIdentity(nextItemId, nextRelativePath))
        _trashBrowser.update {
            it.copy(
                itemId = nextItemId,
                relativePath = nextRelativePath,
                rootName = if (nextItemId == null) null else it.rootName,
                entries = emptyList(),
                error = null,
            ).withTrashDisplaySettings(display)
        }
        refreshTrashBrowser()
        return true
    }

    fun toggleTrashThumbnails() {
        val snapshot = _trashBrowser.value
        if (!snapshot.open) return
        setTrashDisplaySettings(snapshot.trashDisplaySettings().copy(showThumbnails = !snapshot.showThumbnails))
    }

    fun toggleTrashLayout() {
        val snapshot = _trashBrowser.value
        if (!snapshot.open) return
        setTrashDisplaySettings(
            snapshot.trashDisplaySettings().copy(
                layoutMode = if (snapshot.grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
            ),
        )
    }

    fun setTrashDisplaySettings(settings: DirectoryDisplaySettings) {
        val snapshot = _trashBrowser.value
        if (!snapshot.open) return
        val identity = trashDirectoryIdentity(snapshot.itemId, snapshot.relativePath)
        runCatching { graph.navigation.setDirectoryDisplaySettings(identity, settings) }
            .onSuccess {
                _trashBrowser.update { current ->
                    if (current.itemId == snapshot.itemId && current.relativePath == snapshot.relativePath) {
                        current.withTrashDisplaySettings(settings)
                    } else current
                }
            }
            .onFailure { message(it.message ?: "Šiukšliadėžės vaizdo nustatymo išsaugoti nepavyko", true) }
    }

    fun setTrashSort(mode: SortMode, direction: SortDirection) {
        _trashBrowser.update { it.copy(sortMode = mode, sortDirection = direction) }
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
                    emptying = false,
                ).withTrashDisplaySettings(savedDirectoryDisplaySettings(trashDirectoryIdentity(null, "")))
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
            _homeCustomization.value = graph.navigation.homeCustomization(homeBuiltInShortcuts)
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

    fun openHomeShortcut(shortcutId: String, path: String, panel: PanelId = _activePanel.value) {
        val category = HomeShortcutNavigationRules.categoryFor(shortcutId)
        if (category != null) openFileCategory(category) else openQuickPath(path, panel)
    }

    fun openStorageRoot(root: StorageRoot, panel: PanelId = _activePanel.value) {
        val target = runCatching { File(root.path).canonicalPath }.getOrElse {
            message(it.message ?: "Saugykla nepasiekiama", true)
            return
        }
        val knownRoot = _roots.value.firstOrNull { candidate ->
            runCatching { File(candidate.path).canonicalPath == target }.getOrDefault(false)
        }
        if (knownRoot == null || !File(target).isDirectory) {
            message("Saugykla nebeprijungta", true)
            return
        }

        _filesHomeVisible.value = false
        val displaySettings = savedDirectoryDisplaySettings(target)
        fileScrollPositions.reset(tabsFlow(panel).value.activeTabId, target)
        panelFlow(panel).update { state ->
            state.copy(
                path = target,
                entries = emptyList(),
                selectedPaths = emptySet(),
                loading = true,
                listingScannedEntries = 0,
                listingMetadataEntries = 0,
                listingTruncated = false,
                error = null,
                backHistory = emptyList(),
                forwardHistory = emptyList(),
            ).withDirectoryDisplaySettings(displaySettings)
        }
        syncActiveTab(panel)
        refreshPanel(panel)
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
        val displayDefaults = runCatching { graph.navigation.directoryDisplayDefaults() }.getOrNull()
        val display = savedDirectoryDisplaySettings(safDirectoryIdentity(location.uri))
        _safBrowser.value = SafBrowserUiState(
            location = location,
            currentUri = location.uri,
            title = location.title,
            sortMode = displayDefaults?.sortMode ?: SortMode.NAME,
            sortDirection = displayDefaults?.sortDirection ?: SortDirection.ASCENDING,
        ).withSafDisplaySettings(display)
        refreshSafBrowser()
    }

    fun openSafEntry(entry: SafEntry) {
        if (entry.directory) {
            val state = _safBrowser.value
            val current = state.currentUri ?: return
            val display = savedDirectoryDisplaySettings(safDirectoryIdentity(entry.uri))
            _safBrowser.update {
                it.copy(
                    currentUri = entry.uri,
                    title = entry.name,
                    backStack = (it.backStack + (current to it.title)).takeLast(64),
                    entries = emptyList(),
                ).withSafDisplaySettings(display)
            }
            refreshSafBrowser()
        } else {
            val application = getApplication<Application>()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(entry.uri), entry.mimeType ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                application.startActivity(
                    Intent.createChooser(intent, application.getString(R.string.open_with_chooser_short))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
                .onFailure { message(it.message ?: "Failo atidaryti nepavyko", true) }
        }
    }

    fun navigateSafBack(): Boolean {
        val state = _safBrowser.value
        val previous = state.backStack.lastOrNull() ?: return false
        val display = savedDirectoryDisplaySettings(safDirectoryIdentity(previous.first))
        _safBrowser.update {
            it.copy(
                currentUri = previous.first,
                title = previous.second,
                backStack = it.backStack.dropLast(1),
                entries = emptyList(),
            ).withSafDisplaySettings(display)
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

    fun toggleSafLayout() {
        val state = _safBrowser.value
        setSafDisplaySettings(
            state.safDisplaySettings().copy(
                layoutMode = if (state.grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
            ),
        )
    }

    fun toggleSafThumbnails() {
        val state = _safBrowser.value
        setSafDisplaySettings(state.safDisplaySettings().copy(showThumbnails = !state.showThumbnails))
    }

    fun setSafDisplaySettings(settings: DirectoryDisplaySettings) {
        val uri = _safBrowser.value.currentUri ?: return
        runCatching { graph.navigation.setDirectoryDisplaySettings(safDirectoryIdentity(uri), settings) }
            .onSuccess {
                _safBrowser.update { current ->
                    if (current.currentUri == uri) current.withSafDisplaySettings(settings) else current
                }
            }
            .onFailure { message(it.message ?: "Dokumentų vietos rodinio nustatymo išsaugoti nepavyko", true) }
    }

    fun setSafSort(mode: SortMode, direction: SortDirection) {
        _safBrowser.update { it.copy(sortMode = mode, sortDirection = direction) }
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

    fun openLocalTerminal(panel: PanelId = _activePanel.value) {
        val directory = File(panelFlow(panel).value.path)
        graph.terminalSessions.begin(
            location = TerminalLocation.PHONE,
            title = "Telefono terminalas",
            path = directory.absolutePath,
            openBackend = {
                val application = getApplication<Application>()
                LocalPtyBackend.open(
                    workingDirectory = directory,
                    homeDirectory = File(application.filesDir, "terminal-home"),
                    temporaryDirectory = File(application.cacheDir, "terminal-tmp"),
                )
            },
            errorInfo = { error ->
                TerminalFailureUi(
                    title = "Telefono terminalo atidaryti nepavyko",
                    detail = when (error) {
                        is SecurityException -> "Android saugos politika neleido terminalui pasiekti šio aplanko."
                        is IllegalArgumentException,
                        is IllegalStateException,
                        -> "Dabartinis aplankas terminalui nepasiekiamas arba nebegalioja."
                        else -> "Android terminalo proceso paleisti nepavyko."
                    },
                    suggestion = "Atverkite kitą telefono aplanką ir bandykite dar kartą.",
                    diagnosticCode = "LOCAL-PTY-${error.javaClass.simpleName.uppercase().filter(Char::isLetterOrDigit).take(24).ifBlank { "UNKNOWN" }}",
                )
            },
        )
    }

    fun openRemoteTerminal() {
        val snapshot = _networkState.value
        val connected = snapshot.connectedProfile ?: return
        if (connected.protocol != NetworkProtocol.SFTP) {
            message("Serverio terminalas pasiekiamas tik per SFTP/SSH jungtį", true)
            return
        }
        val profileId = connected.id
        val remotePath = snapshot.path
        val pathStyle = ShellCommandRules.inferPathStyle(
            path = remotePath,
            directoryNames = snapshot.entries.asSequence().filter(RemoteEntry::directory).map(RemoteEntry::name).toList(),
        )
        graph.terminalSessions.begin(
            location = TerminalLocation.SERVER,
            title = connected.name,
            path = remotePath,
            openBackend = {
                val latest = graph.networkProfiles.list().firstOrNull { it.id == profileId }
                    ?: throw IllegalArgumentException("Išsaugotas SFTP/SSH profilis nebeegzistuoja")
                val backend = graph.networkProfiles.secret(profileId).getOrThrow().use { secret ->
                    SshTerminalBackend.open(
                        profile = latest,
                        password = secret.password.copyOf(),
                        privateKeyPem = secret.privateKeyPem.copyOf(),
                        workingDirectory = remotePath,
                        pathStyle = pathStyle,
                    )
                }
                if (latest.expectedHostKeySha256 == null) {
                    graph.networkProfiles.updateSftpFingerprint(profileId, backend.trustedFingerprint).getOrThrow()
                    refreshProfiles()
                }
                backend
            },
            errorInfo = { error ->
                val info = RemoteErrorPresenter.present(NetworkProtocol.SFTP, RemoteOperation.CONNECT, error)
                TerminalFailureUi(
                    title = info.title,
                    detail = info.detail,
                    suggestion = info.suggestion,
                    diagnosticCode = info.diagnosticCode,
                )
            },
        )
    }

    fun requestTerminalClose() {
        graph.terminalSessions.requestClose()
    }

    fun dismissTerminalCloseConfirmation() {
        graph.terminalSessions.dismissCloseConfirmation()
    }

    fun confirmTerminalClose() {
        graph.terminalSessions.confirmClose()
    }

    fun pasteIntoTerminal(text: String) {
        if (text.isEmpty()) return
        when (graph.terminalSessions.paste(text)) {
            TerminalPasteResult.ACCEPTED -> Unit
            TerminalPasteResult.TOO_LARGE ->
                message("Iškarpinės turinys viršija 64 KiB terminalo įklijavimo ribą", true)
            TerminalPasteResult.BUSY ->
                message("Terminalo įvestis užimta; bandykite įklijuoti dar kartą", true)
        }
    }

    fun dispatchTerminalKey(key: Int) {
        graph.terminalSessions.dispatchKey(key)
    }

    fun toggleTerminalCtrl() {
        graph.terminalSessions.toggleCtrl()
    }

    fun toggleTerminalAlt() {
        graph.terminalSessions.toggleAlt()
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
                val displayDefaults = runCatching { graph.navigation.directoryDisplayDefaults() }.getOrNull()
                val displaySettings = savedDirectoryDisplaySettings(remoteDirectoryIdentity(normalizedProfile.id, path))
                _networkState.update {
                    it.copy(
                        connectedProfile = normalizedProfile,
                        path = path,
                        entries = emptyList(),
                        selectedPaths = emptySet(),
                        backHistory = emptyList(),
                        forwardHistory = emptyList(),
                        loading = false,
                        error = null,
                        sortMode = displayDefaults?.sortMode ?: SortMode.NAME,
                        sortDirection = displayDefaults?.sortDirection ?: SortDirection.ASCENDING,
                    ).withDirectoryDisplaySettings(displaySettings)
                }
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

    fun navigateRemote(path: String): Boolean {
        val target = RemotePath.normalize(path)
        val current = _networkState.value
        if (target == current.path) return false
        val profileId = current.connectedProfile?.id ?: return false
        val displaySettings = savedDirectoryDisplaySettings(remoteDirectoryIdentity(profileId, target))
        cancelRemoteFileOpen()
        _networkState.update {
            it.copy(
                path = target,
                entries = emptyList(),
                selectedPaths = emptySet(),
                backHistory = (it.backHistory + it.path).takeLast(50),
                forwardHistory = emptyList(),
                loading = true,
                error = null,
            ).withDirectoryDisplaySettings(displaySettings)
        }
        refreshRemote(target)
        return true
    }

    fun navigateRemoteBack(): Boolean {
        val current = _networkState.value
        val target = current.backHistory.lastOrNull() ?: return false
        val profileId = current.connectedProfile?.id ?: return false
        val displaySettings = savedDirectoryDisplaySettings(remoteDirectoryIdentity(profileId, target))
        cancelRemoteFileOpen()
        _networkState.update {
            it.copy(
                path = target,
                entries = emptyList(),
                selectedPaths = emptySet(),
                backHistory = it.backHistory.dropLast(1),
                forwardHistory = (it.forwardHistory + it.path).takeLast(50),
                loading = true,
                error = null,
            ).withDirectoryDisplaySettings(displaySettings)
        }
        refreshRemote(target)
        return true
    }

    fun navigateRemoteForward(): Boolean {
        val current = _networkState.value
        val target = current.forwardHistory.lastOrNull() ?: return false
        val profileId = current.connectedProfile?.id ?: return false
        val displaySettings = savedDirectoryDisplaySettings(remoteDirectoryIdentity(profileId, target))
        cancelRemoteFileOpen()
        _networkState.update {
            it.copy(
                path = target,
                entries = emptyList(),
                selectedPaths = emptySet(),
                backHistory = (it.backHistory + it.path).takeLast(50),
                forwardHistory = it.forwardHistory.dropLast(1),
                loading = true,
                error = null,
            ).withDirectoryDisplaySettings(displaySettings)
        }
        refreshRemote(target)
        return true
    }

    fun navigateRemoteUp(): Boolean {
        val current = _networkState.value.path
        if (current == "/") return false
        return navigateRemote(RemotePath.normalize("$current/.."))
    }

    fun openRemoteEntry(entry: RemoteEntry) {
        if (entry.directory) {
            navigateRemote(entry.path)
            return
        }
        val state = _networkState.value
        val profile = state.connectedProfile ?: return
        val client = remoteClient ?: return
        if (entry.sizeBytes !in 0..RemotePreviewCache.MAX_FILE_BYTES) {
            message("Nuotolinis failas per didelis greitajai peržiūrai. Naudokite „Kopijuoti į telefoną“.", true)
            return
        }

        cancelRemoteFileOpen()
        val requestId = ++remoteFileOpenRequestId
        remoteFileOpenJob = viewModelScope.launch {
            var destination: File? = null
            _networkState.update { it.copy(openingPath = entry.path) }
            try {
                destination = withContext(Dispatchers.IO) {
                    remotePreviewCache.createDestination(profile.id, entry)
                }
                client.download(
                    remotePath = entry.path,
                    localDestination = requireNotNull(destination),
                    operation = OperationContext.background(),
                    maxBytes = RemotePreviewCache.MAX_FILE_BYTES,
                )
                withContext(Dispatchers.IO) {
                    remotePreviewCache.validateCompleted(requireNotNull(destination))
                }
                if (remoteClient !== client || _networkState.value.connectedProfile?.id != profile.id) {
                    withContext(Dispatchers.IO) { remotePreviewCache.discard(destination) }
                    return@launch
                }

                val cachedFile = requireNotNull(destination)
                val kind = FileSystemRules.detectKind(entry.name, mimeType = null, isDirectory = false)
                if (kind == com.affilemanager.app.model.EntryKind.ARCHIVE) {
                    val previewEntry = FileEntry(
                        absolutePath = cachedFile.absolutePath,
                        name = entry.name,
                        kind = kind,
                        sizeBytes = cachedFile.length().coerceAtLeast(0),
                        modifiedAtMillis = entry.modifiedAtMillis ?: cachedFile.lastModified().coerceAtLeast(0),
                        isHidden = entry.name.startsWith('.'),
                        isReadable = cachedFile.canRead(),
                        isWritable = false,
                    )
                    val archiveEntries = graph.archives.list(cachedFile)
                    _preview.value = PreviewTarget.RemoteArchive(
                        remote = entry,
                        file = previewEntry,
                        profileId = profile.id,
                        connectionName = profile.name,
                        entries = archiveEntries,
                    )
                } else {
                    _preview.value = PreviewTarget.RemoteFile(
                        remote = entry,
                        cachedFile = cachedFile,
                        profileId = profile.id,
                        connectionName = profile.name,
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { remotePreviewCache.discard(destination) }
                throw cancelled
            } catch (_: Throwable) {
                withContext(Dispatchers.IO) { remotePreviewCache.discard(destination) }
                message("Nuotolinio failo atidaryti nepavyko", true)
            } finally {
                if (remoteFileOpenRequestId == requestId) {
                    _networkState.update { it.copy(openingPath = null) }
                    remoteFileOpenJob = null
                }
            }
        }
    }

    fun toggleRemoteHidden() {
        _networkState.update { state ->
            val updated = state.copy(includeHidden = !state.includeHidden)
            val available = visibleRemoteEntries(updated).map(RemoteEntry::path)
            updated.copy(selectedPaths = RemoteSelectionRules.retainAvailable(updated.selectedPaths, available))
        }
    }

    fun toggleRemoteGrid() {
        val current = _networkState.value
        setRemoteDirectoryDisplaySettings(
            current.directoryDisplaySettings().copy(
                layoutMode = if (current.grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
                showThumbnails = false,
            ),
        )
    }

    fun setRemoteDirectoryDisplaySettings(settings: DirectoryDisplaySettings) {
        val current = _networkState.value
        val profileId = current.connectedProfile?.id ?: return
        val remoteSettings = settings.copy(showThumbnails = false)
        val identity = remoteDirectoryIdentity(profileId, current.path)
        runCatching { graph.navigation.setDirectoryDisplaySettings(identity, remoteSettings) }
            .onSuccess { _networkState.update { it.withDirectoryDisplaySettings(remoteSettings) } }
            .onFailure { message(it.message ?: "Katalogo vaizdo nustatymo išsaugoti nepavyko", true) }
    }

    fun setRemoteSort(mode: SortMode) {
        _networkState.update {
            if (it.sortMode == mode) {
                it.copy(
                    sortDirection = if (it.sortDirection == SortDirection.ASCENDING) {
                        SortDirection.DESCENDING
                    } else {
                        SortDirection.ASCENDING
                    },
                )
            } else {
                it.copy(sortMode = mode, sortDirection = SortDirection.ASCENDING)
            }
        }
    }

    fun setRemoteSort(mode: SortMode, direction: SortDirection) {
        _networkState.update { it.copy(sortMode = mode, sortDirection = direction) }
    }

    fun refreshRemote(path: String = _networkState.value.path) {
        val client = remoteClient ?: return
        val protocol = _networkState.value.connectedProfile?.protocol ?: return
        val profileId = _networkState.value.connectedProfile?.id ?: return
        val normalizedPath = RemotePath.normalize(path)
        val displaySettings = savedDirectoryDisplaySettings(remoteDirectoryIdentity(profileId, normalizedPath))
        viewModelScope.launch {
            _networkState.update { state ->
                val updated = state.copy(
                    path = normalizedPath,
                    selectedPaths = if (state.path == normalizedPath) state.selectedPaths else emptySet(),
                    loading = true,
                    error = null,
                )
                if (state.path == normalizedPath) updated else updated.withDirectoryDisplaySettings(displaySettings)
            }
            runCatching { client.list(normalizedPath) }
                .onSuccess { entries ->
                    _networkState.update { state ->
                        if (state.path != normalizedPath) {
                            state
                        } else {
                            val updated = state.copy(entries = entries, loading = false)
                            val available = visibleRemoteEntries(updated).map(RemoteEntry::path)
                            updated.copy(
                                selectedPaths = RemoteSelectionRules.retainAvailable(updated.selectedPaths, available),
                            )
                        }
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
            val available = visibleRemoteEntries(state).map(RemoteEntry::path)
            val result = RemoteSelectionRules.toggle(
                current = state.selectedPaths,
                availablePaths = available,
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
                availablePaths = visibleRemoteEntries(state).map(RemoteEntry::path),
                maximum = RemoteCopyEngine.MAX_SELECTED_ROOTS,
            )
            limitReached = result.limitReached
            state.copy(selectedPaths = result.selectedPaths)
        }
        if (limitReached) message("Pasirinkti pirmi ${RemoteCopyEngine.MAX_SELECTED_ROOTS} elementų", true)
    }

    fun selectRemotePaths(paths: List<String>) {
        var limitReached = false
        _networkState.update { state ->
            val available = visibleRemoteEntries(state).map(RemoteEntry::path).toHashSet()
            val requested = paths.filter(available::contains).distinct()
            val result = RemoteSelectionRules.selectAll(requested, RemoteCopyEngine.MAX_SELECTED_ROOTS)
            limitReached = result.limitReached
            state.copy(selectedPaths = result.selectedPaths)
        }
        if (limitReached) message("Pasirinkti pirmi ${RemoteCopyEngine.MAX_SELECTED_ROOTS} elementų", true)
    }

    fun clearRemoteSelection() {
        _networkState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun copyRemoteSelection() {
        val state = _networkState.value
        val entries = state.entries.filter { it.path in state.selectedPaths }
        setRemoteClipboard(entries)
    }

    fun addRemoteSelectionToClipboard() {
        val state = _networkState.value
        val entries = state.entries.filter { it.path in state.selectedPaths }
        setRemoteClipboard(entries, append = true)
    }

    fun copyRemoteEntry(entry: RemoteEntry) {
        setRemoteClipboard(listOf(entry))
    }

    private fun setRemoteClipboard(entries: List<RemoteEntry>, append: Boolean = false) {
        val profile = _networkState.value.connectedProfile ?: return
        val additional = entries.map { entry ->
            AfSourceRef(
                location = AfLocationRef.remote(profile.id, profile.name, entry.path),
                displayName = entry.name,
            ).normalized()
        }
        val existing = if (append) {
            val current = _afClipboard.value
            if (current == null) {
                message("Nėra kopijavimo rinkinio, kurį būtų galima papildyti", true)
                return
            }
            if (current.moveAfterVerifiedCopies) {
                message("Iškirptų elementų negalima maišyti su „Kopijuoti daugiau“. Pradėkite naują rinkinį.", true)
                return
            }
            current.sources
        } else {
            emptyList()
        }
        val merged = ClipboardMergeRules.merge(
            existing = existing,
            additional = additional,
            maximum = AfWorkflowLimits.MAX_SOURCE_ROOTS,
            key = { source -> "${source.kind}:${source.location.identityKey()}:${source.archiveEntryPath.orEmpty()}" },
        )
        if (merged.items.isEmpty()) return
        _afClipboard.value = AfClipboardState(merged.items)
        val onlyThisProfile = merged.items.all {
            it.kind == com.affilemanager.app.workflow.AfSourceKind.FILE_SYSTEM &&
                it.location.kind == AfLocationKind.REMOTE &&
                it.location.profileId == profile.id
        }
        _remoteClipboard.value = if (onlyThisProfile) {
            val legacyExisting = if (append && _remoteClipboard.value?.profileId == profile.id) {
                _remoteClipboard.value?.entries.orEmpty()
            } else {
                emptyList()
            }
            val legacy = ClipboardMergeRules.merge(
                existing = legacyExisting,
                additional = entries,
                maximum = RemoteCopyEngine.MAX_SELECTED_ROOTS,
                key = { entry -> RemotePath.normalize(entry.path) },
            )
            RemoteClipboardState(profile.id, legacy.items)
        } else {
            null
        }
        _clipboard.value = null
        clearRemoteSelection()
        if (append) {
            message(
                if (merged.addedCount == 0) {
                    "Visi pasirinkti elementai jau yra kopijavimo rinkinyje (iš viso ${merged.items.size})"
                } else {
                    "Iš šio serverio pridėta: ${merged.addedCount} · iš viso ${merged.items.size}"
                },
            )
        } else {
            message("Nukopijuota iš serverio: ${merged.items.size}")
        }
        if (merged.limitReached) {
            message("Pasiekta kopijavimo rinkinio riba: ${AfWorkflowLimits.MAX_SOURCE_ROOTS} elementų", true)
        }
    }

    fun connectSavedNetworkProfile(profileId: String) {
        viewModelScope.launch {
            val profile = graph.networkProfiles.list().firstOrNull { it.id == profileId }
            if (profile == null) {
                _networkState.update { it.copy(loading = false, error = RemoteErrorPresenter.invalidProfile("Profilis neberastas")) }
            } else {
                connectNetwork(profile)
            }
        }
    }

    fun pasteRemoteClipboard(destinationPanel: PanelId = _activePanel.value): Boolean {
        val clipboard = _remoteClipboard.value ?: return false
        val profile = _networkState.value.connectedProfile
        if (profile == null || profile.id != clipboard.profileId || remoteClient == null) {
            message("Serverio iškarpinė nebegalioja; prisijunkite prie to paties serverio", true)
            return false
        }
        return enqueueRemoteDownload(clipboard.entries, destinationPanel)
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
            entries.asSequence()
                .filterNot(RemoteEntry::directory)
                .map { entry -> File(destination, entry.name) }
                .filter(File::isFile)
                .forEach { copied -> runCatching { graph.recentFiles.record(copied.absolutePath) } }
            if (result.copiedRoots > 0) refreshRecentFiles()
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

    fun pasteLocalClipboardToRemote(): Boolean {
        val clipboard = _clipboard.value ?: return false
        if (clipboard.source != ClipboardSource.LOCAL) {
            message("Į serverį pirmiausia nukopijuokite failą iš apsaugotos vietos į telefoną", true)
            return false
        }
        if (clipboard.mode != ClipboardMode.COPY) {
            message("Į serverį galima įklijuoti tik nukopijuotus, ne iškirptus elementus", true)
            return false
        }
        return remoteUpload(clipboard.paths)
    }

    suspend fun listLocalDirectoryForUpload(path: String): Result<List<FileEntry>> = graph.localFiles.list(
        directoryPath = path,
        includeHidden = false,
        sortMode = SortMode.NAME,
        sortDirection = SortDirection.ASCENDING,
    )

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
        remoteDelete(listOf(entry))
    }

    fun remoteDelete(entries: List<RemoteEntry>) {
        val client = remoteClient ?: return
        val protocol = _networkState.value.connectedProfile?.protocol ?: return
        val selected = entries.distinctBy { RemotePath.normalize(it.path) }.take(RemoteCopyEngine.MAX_SELECTED_ROOTS)
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _networkState.update { it.copy(loading = true, error = null) }
            var firstFailure: Throwable? = null
            var deleted = 0
            selected.forEach { entry ->
                runCatching { client.delete(entry.path, recursive = entry.directory) }
                    .onSuccess { deleted += 1 }
                    .onFailure { if (firstFailure == null) firstFailure = it }
            }
            clearRemoteSelection()
            val failure = firstFailure
            if (failure == null) {
                refreshRemote()
            } else if (deleted > 0) {
                refreshRemote()
                message("Ištrinta: $deleted, nepavyko ištrinti: ${selected.size - deleted}", true)
            } else {
                _networkState.update {
                    it.copy(
                        loading = false,
                        error = RemoteErrorPresenter.present(protocol, RemoteOperation.DELETE, failure),
                    )
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
    fun retryOperation(id: String) {
        graph.durableTransfers.retry(id).onFailure {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { graph.workflows.retryExecution(id) }
                    .onFailure { error -> message(error.message ?: "Operacijos pakartoti nepavyko", true) }
            }
        }
    }
    fun dismissFinishedOperations() = graph.operationManager.dismissFinished()

    fun activePanelState(): PanelUiState = panelFlow(_activePanel.value).value

    override fun onCleared() {
        workspaceSaveRequests.trySend(currentWorkspace())
        workspaceSaveRequests.close()
        recentFilesJob?.cancel()
        recentFilesJob = null
        cancelRemoteFileOpen()
        fileEditRequestId += 1
        fileEditJob?.cancel()
        val editSession = _fileEditState.value.session
        val previewTarget = _preview.value
        _fileEditState.value = FileEditUiState()
        _preview.value = null
        graph.applicationScope.launch {
            graph.editSessions.discard(editSession)
            remotePreviewCache.discard(previewTarget.remoteCachedFile())
        }
        remoteClient?.let { client -> graph.applicationScope.launch { client.close() } }
        remoteClient = null
        super.onCleared()
    }

    private fun savedThumbnailMode(directoryIdentity: String): Boolean = runCatching {
        graph.navigation.thumbnailsEnabled(directoryIdentity)
    }.onFailure {
        message(it.message ?: "Katalogo vaizdo nustatymo perskaityti nepavyko", true)
    }.getOrDefault(false)

    private fun savedDirectoryDisplaySettings(
        directoryIdentity: String,
        fallbackGrid: Boolean = false,
    ): DirectoryDisplaySettings {
        val saved = runCatching { graph.navigation.directoryDisplaySettings(directoryIdentity) }
            .onFailure { message(it.message ?: "Katalogo vaizdo nustatymo perskaityti nepavyko", true) }
            .getOrNull()
        return saved ?: DirectoryDisplaySettings(
            layoutMode = if (fallbackGrid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
            showThumbnails = savedThumbnailMode(directoryIdentity),
        )
    }

    private fun remoteDirectoryIdentity(profileId: String, path: String): String =
        "remote:$profileId:${RemotePath.normalize(path)}"

    private fun fileCategoryIdentity(category: FileCategory): String =
        "virtual:category/${category.name.lowercase()}"

    private fun safDirectoryIdentity(uri: String): String = "saf:$uri"

    private fun trashDirectoryIdentity(itemId: String?, relativePath: String): String = if (itemId == null) {
        "virtual:trash/root"
    } else {
        "virtual:trash/$itemId/${TrashPathRules.normalize(relativePath)}"
    }

    private fun WorkspaceTab.toPanelUiState(): PanelUiState {
        val saved = runCatching { graph.navigation.directoryDisplaySettings(path) }.getOrNull()
        val settings = saved ?: DirectoryDisplaySettings(
            layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
            showThumbnails = runCatching { graph.navigation.thumbnailsEnabled(path) }.getOrDefault(false),
        )
        return PanelUiState(
            path = path,
            sortMode = sortMode,
            sortDirection = sortDirection,
            includeHidden = includeHidden,
            backHistory = backHistory,
            forwardHistory = forwardHistory,
        ).withDirectoryDisplaySettings(settings)
    }

    private fun PanelUiState.directoryDisplaySettings(): DirectoryDisplaySettings = DirectoryDisplaySettings(
        layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
        iconScalePercent = iconScalePercent,
        spacingScalePercent = spacingScalePercent,
        gridColumns = gridColumns,
        gridStyle = gridStyle,
        showThumbnails = showThumbnails,
    )

    private fun FileCategoryUiState.fileCategoryDisplaySettings(): DirectoryDisplaySettings = DirectoryDisplaySettings(
        layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
        iconScalePercent = iconScalePercent,
        spacingScalePercent = spacingScalePercent,
        gridColumns = gridColumns,
        gridStyle = gridStyle,
        showThumbnails = showThumbnails,
    )

    private fun FileCategoryUiState.withFileCategoryDisplaySettings(
        settings: DirectoryDisplaySettings,
    ): FileCategoryUiState = copy(
        grid = settings.layoutMode == DirectoryLayoutMode.GRID,
        iconScalePercent = settings.iconScalePercent,
        spacingScalePercent = settings.spacingScalePercent,
        gridColumns = settings.gridColumns,
        gridStyle = settings.gridStyle,
        showThumbnails = settings.showThumbnails,
    )

    private fun TrashBrowserUiState.trashDisplaySettings(): DirectoryDisplaySettings = DirectoryDisplaySettings(
        layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
        iconScalePercent = iconScalePercent,
        spacingScalePercent = spacingScalePercent,
        gridColumns = gridColumns,
        gridStyle = gridStyle,
        showThumbnails = showThumbnails,
    )

    private fun TrashBrowserUiState.withTrashDisplaySettings(settings: DirectoryDisplaySettings): TrashBrowserUiState = copy(
        grid = settings.layoutMode == DirectoryLayoutMode.GRID,
        iconScalePercent = settings.iconScalePercent,
        spacingScalePercent = settings.spacingScalePercent,
        gridColumns = settings.gridColumns,
        gridStyle = settings.gridStyle,
        showThumbnails = settings.showThumbnails,
    )

    private fun SafBrowserUiState.safDisplaySettings(): DirectoryDisplaySettings = DirectoryDisplaySettings(
        layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
        iconScalePercent = iconScalePercent,
        spacingScalePercent = spacingScalePercent,
        gridColumns = gridColumns,
        gridStyle = gridStyle,
        showThumbnails = showThumbnails,
    )

    private fun SafBrowserUiState.withSafDisplaySettings(settings: DirectoryDisplaySettings): SafBrowserUiState = copy(
        grid = settings.layoutMode == DirectoryLayoutMode.GRID,
        iconScalePercent = settings.iconScalePercent,
        spacingScalePercent = settings.spacingScalePercent,
        gridColumns = settings.gridColumns,
        gridStyle = settings.gridStyle,
        showThumbnails = settings.showThumbnails,
    )

    private fun PanelUiState.withDirectoryDisplaySettings(settings: DirectoryDisplaySettings): PanelUiState = copy(
        grid = settings.layoutMode == DirectoryLayoutMode.GRID,
        iconScalePercent = settings.iconScalePercent,
        spacingScalePercent = settings.spacingScalePercent,
        gridColumns = settings.gridColumns,
        gridStyle = settings.gridStyle,
        showThumbnails = settings.showThumbnails,
    )

    private fun NetworkUiState.directoryDisplaySettings(): DirectoryDisplaySettings = DirectoryDisplaySettings(
        layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST,
        iconScalePercent = iconScalePercent,
        spacingScalePercent = spacingScalePercent,
        gridColumns = gridColumns,
        gridStyle = gridStyle,
        showThumbnails = false,
    )

    private fun NetworkUiState.withDirectoryDisplaySettings(settings: DirectoryDisplaySettings): NetworkUiState = copy(
        grid = settings.layoutMode == DirectoryLayoutMode.GRID,
        iconScalePercent = settings.iconScalePercent,
        spacingScalePercent = settings.spacingScalePercent,
        gridColumns = settings.gridColumns,
        gridStyle = settings.gridStyle,
    )

    private fun tabsFlow(panel: PanelId): MutableStateFlow<PanelWorkspace> = if (panel == PanelId.LEFT) _leftTabs else _rightTabs

    private fun PanelWorkspace.withGlobalDisplayDefaults(
        settings: DirectoryDisplaySettings,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): PanelWorkspace = copy(
        tabs = tabs.map { tab ->
            tab.copy(
                grid = settings.layoutMode == DirectoryLayoutMode.GRID,
                sortMode = sortMode,
                sortDirection = sortDirection,
            )
        },
    )

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

    private fun visibleRemoteEntries(state: NetworkUiState): List<RemoteEntry> = RemoteBrowserRules.displayEntries(
        entries = state.entries,
        includeHidden = state.includeHidden,
        sortMode = state.sortMode,
        sortDirection = state.sortDirection,
    )

    private fun closePreviewImmediately() {
        fileEditRequestId += 1
        fileEditJob?.cancel()
        fileEditJob = null
        val session = _fileEditState.value.session
        val target = _preview.value
        _fileEditState.value = FileEditUiState()
        _preview.value = null
        graph.applicationScope.launch {
            val editRemoved = graph.editSessions.discard(session)
            val previewRemoved = remotePreviewCache.discard(target.remoteCachedFile())
            val privilegedPreviewRemoved = graph.privilegedFiles.discardPreview(target.privilegedCachedFile())
            if (!editRemoved || !previewRemoved || !privilegedPreviewRemoved) {
                message("Laikinos redagavimo kopijos pašalinti nepavyko", true)
            }
        }
    }

    private fun PreviewTarget?.remoteCachedFile(): File? = when (this) {
        is PreviewTarget.RemoteFile -> cachedFile
        is PreviewTarget.RemoteArchive -> file.file
        else -> null
    }

    private fun PreviewTarget?.privilegedCachedFile(): File? = when (this) {
        is PreviewTarget.PrivilegedFile -> cachedFile
        else -> null
    }

    private fun saveContentOrigin(session: EditSession, forceOverwrite: Boolean): EditSaveResult {
        val origin = session.origin as? EditOrigin.Content ?: error("Edit session is not a content URI")
        val uri = Uri.parse(origin.uri)
        val current = currentContentRevision(uri)
        if (!forceOverwrite && !session.originRevision.hasSameContent(current)) {
            return EditSaveResult.Conflict(EditConflict(origin.label, session.originRevision, current))
        }
        require(origin.canWrite) { "Originalas skirtas tik skaityti; naudokite „Išsaugoti kaip“" }
        val verified = writeContentDestination(session, uri)
        require(session.workingRevision.hasSameContent(verified)) { "Išsaugoto failo patikra nepavyko" }
        return EditSaveResult.Saved(verified)
    }

    private fun currentContentRevision(uri: Uri): FileRevision? {
        val resolver = getApplication<Application>().contentResolver
        return try {
            graph.editSessions.revisionFromStream(modifiedAtMillis = null) {
                resolver.openInputStream(uri) ?: throw FileNotFoundException("Original content URI is unavailable")
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun contentDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun writeContentDestination(session: EditSession, destination: Uri): FileRevision {
        require(destination.scheme == "content") { "Pasirinkta vieta neįrašoma per Android dokumentų sistemą" }
        val resolver = getApplication<Application>().contentResolver
        graph.editSessions.writeWorkingCopy(session) {
            openContentOutput(resolver, destination)
        }
        return graph.editSessions.revisionFromStream(modifiedAtMillis = null) {
            resolver.openInputStream(destination) ?: throw FileNotFoundException("Išsaugoto failo patikrinti nepavyko")
        }
    }

    private fun openContentOutput(resolver: ContentResolver, uri: Uri): java.io.OutputStream {
        val readWriteTruncateOutput = runCatching { resolver.openOutputStream(uri, "rwt") }.getOrNull()
        return readWriteTruncateOutput
            ?: runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: throw FileNotFoundException("Pasirinkta vieta nesuteikė įrašomo srauto")
    }

    private suspend fun saveRemoteOrigin(session: EditSession, forceOverwrite: Boolean): EditSaveResult {
        val origin = session.origin as? EditOrigin.Remote ?: error("Edit session is not remote")
        val profile = _networkState.value.connectedProfile
        val client = remoteClient
        require(profile?.id == origin.profileId && client != null) {
            "Prieš išsaugodami pradiniame serveryje vėl prisijunkite prie ${origin.connectionName}"
        }
        return graph.remoteEdits.saveOrigin(session, client, forceOverwrite)
    }

    private suspend fun disconnectRemote() {
        cancelRemoteFileOpen()
        val client = remoteClient
        remoteClient = null
        runCatching { client?.close() }
        _networkState.update {
            it.copy(
                connectedProfile = null,
                path = "/",
                entries = emptyList(),
                selectedPaths = emptySet(),
                backHistory = emptyList(),
                forwardHistory = emptyList(),
                openingPath = null,
                loading = false,
                error = null,
            )
        }
        _remoteClipboard.value = null
        _syncState.value = SyncUiState()
    }

    private fun cancelRemoteFileOpen() {
        remoteFileOpenRequestId += 1
        remoteFileOpenJob?.cancel()
        remoteFileOpenJob = null
        _networkState.update { state ->
            if (state.openingPath == null) state else state.copy(openingPath = null)
        }
    }

    private suspend fun openRemoteConnection(profile: NetworkProfile): RemoteClient {
        val normalized = NetworkProfileRules.normalize(profile)
        NetworkProfileRules.validate(normalized)
        return graph.networkProfiles.secret(normalized.id).getOrThrow().use { secret ->
            graph.remoteClients.connect(normalized, secret)
        }
    }
}
