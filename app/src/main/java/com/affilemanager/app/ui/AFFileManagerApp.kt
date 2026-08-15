package com.affilemanager.app.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.IncomingViewRequest
import com.affilemanager.app.ui.preview.FilePreviewDialog
import com.affilemanager.app.ui.components.BatchRenameDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.screens.AnalyzeScreen
import com.affilemanager.app.ui.screens.ConnectionsScreen
import com.affilemanager.app.ui.screens.FilesScreen
import com.affilemanager.app.ui.screens.ToolsScreen
import com.affilemanager.app.ui.terminal.TerminalOverlay
import com.affilemanager.app.update.AppUpdateState
import java.io.File

private data class SectionDestination(
    val section: AppSection,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    SectionDestination(AppSection.FILES, "Failai", Icons.Rounded.Folder),
    SectionDestination(AppSection.ANALYZE, "Analizė", Icons.Rounded.Analytics),
    SectionDestination(AppSection.CONNECTIONS, "Ryšiai", Icons.Rounded.Storage),
    SectionDestination(AppSection.TOOLS, "Daugiau", Icons.Rounded.MoreHoriz),
)

@Composable
fun AFFileManagerApp(
    viewModel: MainViewModel = viewModel(),
    incomingViewRequest: IncomingViewRequest? = null,
    onIncomingViewRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val interfaceLanguage = LocalConfiguration.current.locales[0].language
    val lifecycleOwner = LocalLifecycleOwner.current
    val section by viewModel.section.collectAsStateWithLifecycle()
    val filesHomeVisible by viewModel.filesHomeVisible.collectAsStateWithLifecycle()
    val operations by viewModel.operations.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val fileEditState by viewModel.fileEditState.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val activePanel by viewModel.activePanel.collectAsStateWithLifecycle()
    val leftPanel by viewModel.leftPanel.collectAsStateWithLifecycle()
    val rightPanel by viewModel.rightPanel.collectAsStateWithLifecycle()
    val storageRoots by viewModel.roots.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    var hasAllFilesAccess by remember { mutableStateOf(hasFullFileAccess(context)) }
    var unlocked by remember(appLockEnabled) { mutableStateOf(!appLockEnabled) }
    var appStopped by remember { mutableStateOf(false) }
    var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activeOperations = operations.count {
        it.status == OperationStatus.RUNNING || it.status == OperationStatus.PAUSED || it.status == OperationStatus.QUEUED
    }
    val activePanelState = if (activePanel == PanelId.LEFT) leftPanel else rightPanel
    val systemBackAction = BackNavigationRules.decide(
        previewOpen = preview != null,
        section = section,
        filesHomeVisible = filesHomeVisible,
        selectedCount = activePanelState.selectedPaths.size,
        hasBackHistory = activePanelState.backHistory.isNotEmpty(),
        hasParent = File(activePanelState.path).parentFile != null &&
            !sameNormalizedPath(Environment.getExternalStorageDirectory().absolutePath, activePanelState.path) &&
            storageRoots.none { root -> sameNormalizedPath(root.path, activePanelState.path) },
        remoteSelectedCount = networkState.selectedPaths.size,
        remoteConnected = networkState.connectedProfile != null,
        remoteHasBackHistory = networkState.backHistory.isNotEmpty(),
        remoteHasParent = networkState.path != "/",
    )

    BackHandler(
        enabled = (!appLockEnabled || unlocked) && !terminalState.visible && systemBackAction != SystemBackAction.DEFER_TO_SYSTEM,
    ) {
        when (systemBackAction) {
            SystemBackAction.CLOSE_PREVIEW -> viewModel.closePreview()
            SystemBackAction.SHOW_FILES -> viewModel.setSection(AppSection.FILES)
            SystemBackAction.CLEAR_REMOTE_SELECTION -> viewModel.clearRemoteSelection()
            SystemBackAction.NAVIGATE_REMOTE_BACK -> viewModel.navigateRemoteBack()
            SystemBackAction.NAVIGATE_REMOTE_UP -> viewModel.navigateRemoteUp()
            SystemBackAction.CLEAR_SELECTION -> viewModel.clearSelection(activePanel)
            SystemBackAction.NAVIGATE_BACK -> viewModel.navigateBack(activePanel)
            SystemBackAction.NAVIGATE_UP -> viewModel.navigateUp(activePanel)
            SystemBackAction.DEFER_TO_SYSTEM -> Unit
        }
    }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addSafLocation(uri, uri.lastPathSegment?.substringAfterLast(':').orEmpty())
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAllFilesAccess = hasFullFileAccess(context)
        viewModel.refreshPermissionDependentState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = hasFullFileAccess(context)
                if (current != hasAllFilesAccess) {
                    hasAllFilesAccess = current
                    viewModel.refreshPermissionDependentState()
                }
                if (appStopped && appLockEnabled) unlocked = false
                appStopped = false
            } else if (event == Lifecycle.Event.ON_STOP) {
                appStopped = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = UiTranslator.translate(message.text, interfaceLanguage),
                actionLabel = when (message.action) {
                    UiMessageAction.UNDO_BATCH_RENAME -> UiTranslator.translate("Atšaukti", interfaceLanguage)
                    null -> null
                },
                duration = if (message.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && message.action == UiMessageAction.UNDO_BATCH_RENAME) {
                viewModel.undoBatchRename()
            }
        }
    }

    LaunchedEffect(incomingViewRequest) {
        incomingViewRequest?.let { request ->
            viewModel.openExternalUri(request.uri, request.mimeType)
            onIncomingViewRequestConsumed()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideNavigation = maxWidth >= 900.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!wideNavigation) {
                    NavigationBar {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                selected = section == destination.section,
                                onClick = { viewModel.setSection(destination.section) },
                                icon = {
                                    DestinationIcon(destination.icon, activeOperations.takeIf { destination.section == AppSection.TOOLS })
                                },
                                label = { LText(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (wideNavigation) {
                    NavigationRail {
                        destinations.forEach { destination ->
                            NavigationRailItem(
                                selected = section == destination.section,
                                onClick = { viewModel.setSection(destination.section) },
                                icon = {
                                    DestinationIcon(destination.icon, activeOperations.takeIf { destination.section == AppSection.TOOLS })
                                },
                                label = { LText(destination.label) },
                            )
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    when (section) {
                        AppSection.FILES -> FilesScreen(
                            viewModel = viewModel,
                            contentPadding = padding,
                            hasAllFilesAccess = hasAllFilesAccess,
                            onRequestAllFilesAccess = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                    context.startActivity(intent)
                                } else {
                                    legacyStorageLauncher.launch(
                                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                                    )
                                }
                            },
                        )
                        AppSection.ANALYZE -> AnalyzeScreen(viewModel, padding)
                        AppSection.CONNECTIONS -> ConnectionsScreen(viewModel, padding)
                        AppSection.TOOLS -> ToolsScreen(
                            viewModel = viewModel,
                            contentPadding = padding,
                            onAddSafLocation = { safLauncher.launch(null) },
                            onToggleAppLock = { enabled ->
                                if (enabled) {
                                    authenticate(
                                        context = context,
                                        title = UiTranslator.translate("Įjungti AF File Manager užraktą", interfaceLanguage),
                                        onSuccess = { viewModel.setAppLockEnabled(true); unlocked = true },
                                        onError = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
                                    )
                                } else {
                                    authenticate(
                                        context = context,
                                        title = UiTranslator.translate("Išjungti AF File Manager užraktą", interfaceLanguage),
                                        onSuccess = { viewModel.setAppLockEnabled(false); unlocked = true },
                                        onError = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    preview?.let { target ->
        FilePreviewDialog(
            target = target,
            editState = fileEditState,
            onClose = viewModel::closePreview,
            onPrepareEdit = viewModel::prepareFileEdit,
            onEditTextChanged = viewModel::updateEditText,
            onEditEncodingChanged = viewModel::updateEditEncoding,
            onEditLineEndingChanged = viewModel::updateEditLineEnding,
            onSaveEdit = viewModel::saveFileEdit,
            onSaveEditAs = viewModel::saveFileEditAs,
            onSaveEditAsLocal = { directory, name -> viewModel.saveFileEditAsLocal(directory, name) },
            onSaveEditAsRemote = { directory, name -> viewModel.saveFileEditAsRemote(directory, name) },
            onResolveSaveAsConflict = viewModel::resolveFileEditSaveAsConflict,
            onDismissSaveAsConflict = viewModel::dismissFileEditSaveAsConflict,
            initialLocalSavePath = activePanelState.path,
            initialRemoteSavePath = networkState.path.takeIf { networkState.connectedProfile != null },
            remoteConnectionName = networkState.connectedProfile?.name,
            loadLocalSaveDirectory = viewModel::listLocalDirectoryForUpload,
            loadRemoteSaveDirectory = viewModel::listRemoteDirectoryForEdit,
            onExternalEditorReturned = viewModel::refreshFileEditAfterExternalEditor,
            onDismissEditConflict = viewModel::dismissFileEditConflict,
            onKeepEditing = viewModel::keepEditing,
            onDiscardEditAndClose = viewModel::discardFileEditAndClose,
            onExtract = { file, password -> viewModel.extractArchive(file, password) },
            onDecrypt = { file, password -> viewModel.decryptVault(file, password) },
        )
    }

    BatchRenameDialog(viewModel)

    TerminalOverlay(
        state = terminalState,
        onRequestClose = viewModel::requestTerminalClose,
        onConfirmClose = viewModel::confirmTerminalClose,
        onDismissCloseConfirmation = viewModel::dismissTerminalCloseConfirmation,
        onPaste = viewModel::pasteIntoTerminal,
        onKey = viewModel::dispatchTerminalKey,
        onToggleCtrl = viewModel::toggleTerminalCtrl,
        onToggleAlt = viewModel::toggleTerminalAlt,
    )

    val offeredRelease = when (val update = updateState) {
        is AppUpdateState.Available -> update.release
        is AppUpdateState.Ready -> update.release
        else -> null
    }
    if (offeredRelease != null && offeredRelease.version != dismissedUpdateVersion) {
        val ready = updateState as? AppUpdateState.Ready
        AlertDialog(
            onDismissRequest = { dismissedUpdateVersion = offeredRelease.version },
            title = { LText(if (ready == null) "Nauja AF File Manager versija" else "Atnaujinimas paruoštas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LText("Versija ${offeredRelease.version}")
                    LText(
                        when {
                            ready?.installPermissionRequired == true -> "Android nustatymuose leiskite diegti iš šios programos, tada grįžkite ir dar kartą pasirinkite „Diegti“."
                            ready != null -> "APK SHA-256 ir pasirašymo sertifikatas patikrinti. Diegimą patvirtinsite Android sistemos lange."
                            else -> offeredRelease.notes.ifBlank { "Paskelbtas naujas stabilus leidimas." }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (ready == null) viewModel.downloadUpdate(offeredRelease) else viewModel.installUpdate()
                }) { LText(if (ready == null) "Atsisiųsti" else "Diegti") }
            },
            dismissButton = {
                TextButton(onClick = { dismissedUpdateVersion = offeredRelease.version }) { LText("Vėliau") }
            },
        )
    }

    if (appLockEnabled && !unlocked) {
        AppLockOverlay(
            onUnlock = {
                authenticate(
                    context = context,
                    title = UiTranslator.translate("Atrakinti AF File Manager", interfaceLanguage),
                    onSuccess = { unlocked = true },
                    onError = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
                )
            },
        )
    }
}

private fun hasFullFileAccess(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

private fun sameNormalizedPath(first: String, second: String): Boolean = runCatching {
    File(first).absoluteFile.toPath().normalize() == File(second).absoluteFile.toPath().normalize()
}.getOrElse {
    File(first).absolutePath == File(second).absolutePath
}

@Composable
private fun AppLockOverlay(onUnlock: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(84.dp), tint = MaterialTheme.colorScheme.primary)
                LText("AF File Manager užrakinta", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onUnlock, modifier = Modifier.padding(top = 18.dp)) { LText("Atrakinti") }
            }
        }
    }
}

private fun authenticate(
    context: android.content.Context,
    title: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val activity = context as? FragmentActivity ?: run {
        onError("Biometrinio lango atidaryti nepavyko")
        return
    }
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val available = BiometricManager.from(context).canAuthenticate(authenticators)
    if (available != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("Įrenginyje nenustatyta tinkama biometrija arba ekrano užraktas")
        return
    }
    val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onError(errString.toString())
                }
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Patvirtinkite tapatybę Android sistemos lange")
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}

@Composable
private fun DestinationIcon(icon: ImageVector, badgeCount: Int?) {
    if (badgeCount != null && badgeCount > 0) {
        BadgedBox(badge = { Badge { Text(badgeCount.coerceAtMost(99).toString()) } }) {
            Icon(icon, contentDescription = null)
        }
    } else {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
