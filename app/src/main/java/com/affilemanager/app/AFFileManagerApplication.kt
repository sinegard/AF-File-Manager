package com.affilemanager.app

import android.app.Application
import com.affilemanager.app.advanced.AdvancedAccessManager
import com.affilemanager.app.advanced.PrivilegedFileRepository
import com.affilemanager.app.archive.ArchiveEngine
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.data.ContentFileRepository
import com.affilemanager.app.data.NavigationRepository
import com.affilemanager.app.data.RecentFileRepository
import com.affilemanager.app.data.SafFileRepository
import com.affilemanager.app.data.TrashRepository
import com.affilemanager.app.data.TrashRetentionSettings
import com.affilemanager.app.data.TrashRetentionScheduler
import com.affilemanager.app.data.UiPreferenceRepository
import com.affilemanager.app.data.WorkspaceSessionRepository
import com.affilemanager.app.cleanup.DeviceCleanupRepository
import com.affilemanager.app.cleanup.PrivilegedAppProcessRepository
import com.affilemanager.app.data.FileTagRepository
import com.affilemanager.app.data.FileCategoryRepository
import com.affilemanager.app.data.FileSelectionInfoScanner
import com.affilemanager.app.editing.EditSessionStore
import com.affilemanager.app.editing.RemoteEditSaver
import com.affilemanager.app.editing.ThreeWayTextMerge
import com.affilemanager.app.network.NetworkProfileStore
import com.affilemanager.app.network.RemoteClientFactory
import com.affilemanager.app.network.RemoteCopyEngine
import com.affilemanager.app.operations.FileOperationManager
import com.affilemanager.app.operations.FileOperationForegroundService
import com.affilemanager.app.operations.LocalFileOperator
import com.affilemanager.app.operations.BatchRenameEngine
import com.affilemanager.app.operations.DurableTransferCoordinator
import com.affilemanager.app.operations.DurableTransferRepository
import com.affilemanager.app.pdfsigning.PdfVisualSignatureEngine
import com.affilemanager.app.search.FileSearchEngine
import com.affilemanager.app.search.SimilarImageEngine
import com.affilemanager.app.security.CredentialVault
import com.affilemanager.app.security.AppLockRepository
import com.affilemanager.app.security.FileVaultEngine
import com.affilemanager.app.sharing.LocalShareManager
import com.affilemanager.app.transfer.NearbySourcePreparer
import com.affilemanager.app.sync.SyncEngine
import com.affilemanager.app.sync.SyncScheduleRepository
import com.affilemanager.app.update.AppUpdateManager
import com.affilemanager.app.workflow.AfAutomationRepository
import com.affilemanager.app.workflow.AfAutomationScheduler
import com.affilemanager.app.workflow.AfExecutionRepository
import com.affilemanager.app.workflow.AfPlanRepository
import com.affilemanager.app.workflow.AfStorageSessionFactory
import com.affilemanager.app.workflow.AfTimelineRepository
import com.affilemanager.app.workflow.AfWorkflowCoordinator
import com.affilemanager.app.ui.TerminalSessionStore
import com.affilemanager.app.ui.localization.UiTranslationCatalog
import com.affilemanager.app.ui.theme.AppearanceRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AFFileManagerApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        UiTranslationCatalog.initialize(this)
        PDFBoxResourceLoader.init(this)
        graph = AppGraph(this)
        graph.applicationScope.launch {
            graph.syncSchedules.restoreWork()
            graph.durableTransfers.restore()
            graph.workflows.restore()
            graph.trashRetentionScheduler.synchronize(graph.trashRetentionSettings.load())
        }
        graph.updates.check(automatic = true)
    }
}

class AppGraph(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val terminalSessions = TerminalSessionStore(application, applicationScope)
    val advancedAccess = AdvancedAccessManager(application)
    val privilegedFiles = PrivilegedFileRepository(application, advancedAccess)
    val localFiles = LocalFileRepository(application)
    val recentFiles = RecentFileRepository(application, localFiles)
    val fileCategories = FileCategoryRepository(application, localFiles)
    val deviceCleanup by lazy { DeviceCleanupRepository(application) }
    val privilegedApps by lazy { PrivilegedAppProcessRepository(application, advancedAccess) }
    val fileSelectionInfo by lazy { FileSelectionInfoScanner() }
    val contentFiles by lazy { ContentFileRepository(application) }
    val navigation = NavigationRepository(application)
    val uiPreferences = UiPreferenceRepository(application)
    val appearance = AppearanceRepository(application)
    val workspaceSession = WorkspaceSessionRepository(application)
    val fileTags = FileTagRepository.forApp(application)
    val editSessions by lazy { EditSessionStore(application.cacheDir) }
    val pdfSignatures by lazy { PdfVisualSignatureEngine(application.cacheDir) }
    val remoteEdits by lazy { RemoteEditSaver(editSessions) }
    val textMerge by lazy { ThreeWayTextMerge() }
    val localFileOperator by lazy { LocalFileOperator() }
    val batchRename by lazy { BatchRenameEngine() }
    val operationManager = FileOperationManager(applicationScope) {
        FileOperationForegroundService.start(application)
    }
    val durableTransferRepository = DurableTransferRepository(application)
    val durableTransfers = DurableTransferCoordinator(operationManager, durableTransferRepository)
    val trash = TrashRepository(application)
    val trashRetentionSettings = TrashRetentionSettings(application)
    val trashRetentionScheduler = TrashRetentionScheduler(application)
    val search = FileSearchEngine(localFiles)
    val similarImages by lazy { SimilarImageEngine() }
    val archives = ArchiveEngine()
    val localShare by lazy { LocalShareManager(application, archives) }
    val nearbySources by lazy { NearbySourcePreparer(application, fileCategories) }
    val credentialVault = CredentialVault()
    val appLock = AppLockRepository(application)
    val networkProfiles = NetworkProfileStore(application, credentialVault)
    val remoteClients = RemoteClientFactory()
    val remoteCopies by lazy { RemoteCopyEngine() }
    val safFiles = SafFileRepository(application)
    val fileVault by lazy { FileVaultEngine() }
    val sync by lazy { SyncEngine() }
    val syncSchedules = SyncScheduleRepository(application)
    val updates = AppUpdateManager(application)
    val workflows: AfWorkflowCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AfWorkflowCoordinator(
            scope = applicationScope,
            operations = operationManager,
            planRepository = AfPlanRepository(application),
            executionRepository = AfExecutionRepository(application),
            timelineRepository = AfTimelineRepository(application),
            automationRepository = AfAutomationRepository(application),
            automationScheduler = AfAutomationScheduler(application),
            storageFactory = AfStorageSessionFactory(application, archives, networkProfiles, remoteClients),
            stagingDirectory = java.io.File(application.cacheDir, "af-plan-execution"),
        )
    }
}
