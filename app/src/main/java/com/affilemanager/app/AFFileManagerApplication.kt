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
import com.affilemanager.app.data.WorkspaceSessionRepository
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
import com.affilemanager.app.operations.LocalFileOperator
import com.affilemanager.app.operations.BatchRenameEngine
import com.affilemanager.app.operations.DurableTransferCoordinator
import com.affilemanager.app.operations.DurableTransferRepository
import com.affilemanager.app.search.FileSearchEngine
import com.affilemanager.app.search.SimilarImageEngine
import com.affilemanager.app.security.CredentialVault
import com.affilemanager.app.security.AppLockRepository
import com.affilemanager.app.security.FileVaultEngine
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
        graph = AppGraph(this)
        graph.applicationScope.launch {
            graph.syncSchedules.restoreWork()
            graph.durableTransfers.restore()
            graph.workflows.restore()
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
    val fileSelectionInfo = FileSelectionInfoScanner()
    val contentFiles = ContentFileRepository(application)
    val navigation = NavigationRepository(application)
    val appearance = AppearanceRepository(application)
    val workspaceSession = WorkspaceSessionRepository(application)
    val fileTags = FileTagRepository.forApp(application)
    val editSessions = EditSessionStore(application.cacheDir)
    val remoteEdits = RemoteEditSaver(editSessions)
    val textMerge = ThreeWayTextMerge()
    val localFileOperator = LocalFileOperator()
    val batchRename = BatchRenameEngine()
    val operationManager = FileOperationManager(applicationScope)
    val durableTransferRepository = DurableTransferRepository(application)
    val durableTransfers = DurableTransferCoordinator(operationManager, durableTransferRepository)
    val trash = TrashRepository(application)
    val search = FileSearchEngine(localFiles)
    val similarImages = SimilarImageEngine()
    val archives = ArchiveEngine()
    val credentialVault = CredentialVault()
    val appLock = AppLockRepository(application)
    val networkProfiles = NetworkProfileStore(application, credentialVault)
    val remoteClients = RemoteClientFactory()
    val remoteCopies = RemoteCopyEngine()
    val safFiles = SafFileRepository(application)
    val fileVault = FileVaultEngine()
    val sync = SyncEngine()
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
