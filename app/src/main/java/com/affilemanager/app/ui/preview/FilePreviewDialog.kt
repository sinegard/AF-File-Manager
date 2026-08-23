package com.affilemanager.app.ui.preview

import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.localization.rememberLocalizedDateTimeFormat

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.times
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.affilemanager.app.archive.ArchiveEntryInfo
import com.affilemanager.app.archive.ArchiveBrowserIndex
import com.affilemanager.app.archive.ArchiveBrowserItem
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.editing.EditConflict
import com.affilemanager.app.editing.EditExistingPolicy
import com.affilemanager.app.editing.EditOrigin
import com.affilemanager.app.editing.EditSaveAsConflict
import com.affilemanager.app.editing.EditSession
import com.affilemanager.app.editing.EditabilityRules
import com.affilemanager.app.editing.FileRevision
import com.affilemanager.app.editing.LineEnding
import com.affilemanager.app.editing.TextEncoding
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryDisplayDefaults
import com.affilemanager.app.data.DirectoryGridStyle
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.ui.PreviewTarget
import com.affilemanager.app.ui.FileEditUiState
import com.affilemanager.app.ui.editor.EditSaveAsDialog
import com.affilemanager.app.ui.editor.FullTextEditor
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.DirectoryDisplayMenuItems
import com.affilemanager.app.ui.components.DirectoryDisplaySettingsDialog
import com.affilemanager.app.ui.components.DirectoryQuickSearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val pdfRenderPermits = Semaphore(1)

private data class PdfDocumentInfo(val pageAspectRatios: List<Float>) {
    val pageCount: Int get() = pageAspectRatios.size
}

@Composable
fun FilePreviewDialog(
    target: PreviewTarget,
    editState: FileEditUiState,
    archiveDisplayDefaults: DirectoryDisplayDefaults,
    onApplyArchiveDisplayToAll: (DirectoryDisplaySettings, SortMode?, SortDirection) -> Unit,
    onClose: () -> Unit,
    onPrepareEdit: () -> Unit,
    onEditTextChanged: (String) -> Unit,
    onEditEncodingChanged: (TextEncoding) -> Unit,
    onEditLineEndingChanged: (LineEnding) -> Unit,
    onSaveEdit: (Boolean) -> Unit,
    onSaveEditAs: (Uri) -> Unit,
    onSaveEditAsLocal: (String, String) -> Unit,
    onSaveEditAsRemote: (String, String) -> Unit,
    onResolveSaveAsConflict: (EditExistingPolicy) -> Unit,
    onDismissSaveAsConflict: () -> Unit,
    initialLocalSavePath: String,
    initialRemoteSavePath: String?,
    remoteConnectionName: String?,
    loadLocalSaveDirectory: suspend (String) -> Result<List<FileEntry>>,
    loadRemoteSaveDirectory: suspend (String) -> Result<List<RemoteEntry>>,
    onExternalEditorReturned: () -> Unit,
    onDismissEditConflict: () -> Unit,
    onMergeEditConflict: () -> Unit,
    onKeepEditing: () -> Unit,
    onDiscardEditAndClose: () -> Unit,
    onCopyArchiveEntry: (String) -> Unit,
    onExtract: (FileEntry, CharArray?) -> Unit,
    onDecrypt: (FileEntry, CharArray) -> Unit,
) {
    val context = LocalContext.current
    val summaryDateFormat = rememberLocalizedDateTimeFormat(DateFormat.SHORT, DateFormat.SHORT)
    val source = target.previewSource()
    var hash by remember(source.key) { mutableStateOf<String?>(null) }
    var hashRunning by remember { mutableStateOf(false) }
    var actionError by remember(source.key) { mutableStateOf<String?>(null) }
    var archivePath by remember(source.key) { mutableStateOf("") }
    var launchExternalEditorWhenReady by remember(source.key) { mutableStateOf(false) }
    var showSaveAs by remember(source.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val internalEditor = EditabilityRules.supportsInternalText(source.name, source.mimeType(context), source.kind)
    val externalEditorAvailable = remember(source.key) { canEditExternally(context, source) }
    val activeEditState = editState.takeIf { it.sourceKey == source.key }
    val editSession = activeEditState?.session
    val actionSource = editSession
        ?.let { PreviewSource.Working(source, it) }
        ?: source
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let(onSaveEditAs)
    }
    val externalEditorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onExternalEditorReturned()
    }
    val launchSystemSaveAs: () -> Unit = {
        val session = editSession
        if (session == null) {
            actionError = "Redaguojama kopija dar neparuošta"
        } else {
            saveAsLauncher.launch(createSaveAsIntent(session))
        }
    }
    val launchSaveAs: () -> Unit = {
        if (editSession == null) actionError = "Redaguojama kopija dar neparuošta"
        else showSaveAs = true
    }

    LaunchedEffect(source.key, internalEditor) {
        if (internalEditor) onPrepareEdit()
    }
    LaunchedEffect(
        launchExternalEditorWhenReady,
        editSession?.id,
        activeEditState?.preparing,
        activeEditState?.error,
    ) {
        if (!launchExternalEditorWhenReady) return@LaunchedEffect
        if (activeEditState?.error != null) {
            launchExternalEditorWhenReady = false
            return@LaunchedEffect
        }
        val session = editSession ?: return@LaunchedEffect
        launchExternalEditorWhenReady = false
        runCatching { externalEditorLauncher.launch(createExternalEditIntent(context, session)) }
            .onFailure { actionError = it.message ?: "Nepavyko atidaryti redaktoriaus pasirinkimo" }
    }
    val navigateBack: () -> Unit = {
        if ((target is PreviewTarget.Archive || target is PreviewTarget.RemoteArchive) && archivePath.isNotEmpty()) {
            archivePath = ArchiveBrowserIndex.parentOf(archivePath)
        } else {
            onClose()
        }
    }

    Dialog(onDismissRequest = navigateBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("file-preview-dialog"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = uiText(if (archivePath.isEmpty()) "Uždaryti" else "Grįžti į ankstesnį archyvo aplanką"),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(editSession?.displayName ?: source.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entrySummary(context, source, summaryDateFormat), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            runCatching { openWith(context, actionSource) }
                                .onFailure { actionError = it.message ?: "Programų pasirinkiklio atidaryti nepavyko" }
                        },
                        modifier = Modifier.testTag("open-with-action"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        LText("Atidaryti su kita programa")
                    }
                    if (externalEditorAvailable) {
                        FilledTonalButton(
                            onClick = {
                                if (editSession == null) {
                                    launchExternalEditorWhenReady = true
                                    onPrepareEdit()
                                } else {
                                    runCatching {
                                        externalEditorLauncher.launch(createExternalEditIntent(context, editSession))
                                    }.onFailure {
                                        actionError = it.message ?: "Nepavyko atidaryti redaktoriaus pasirinkimo"
                                    }
                                }
                            },
                            enabled = activeEditState?.preparing != true && activeEditState?.saving != true,
                            modifier = Modifier.testTag("edit-with-action"),
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            LText("Redaguoti su kita programa")
                        }
                    }
                    TextButton(
                        onClick = {
                            runCatching { shareFile(context, actionSource) }
                                .onFailure { actionError = it.message ?: "Dalijimosi programos atidaryti nepavyko" }
                        },
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        LText("Dalintis")
                    }
                    TextButton(
                        onClick = {
                            hashRunning = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { sha256(context, actionSource) } }
                                result.onSuccess { hash = it }.onFailure { actionError = it.message ?: "SHA-256 apskaičiuoti nepavyko" }
                                hashRunning = false
                            }
                        },
                    ) {
                        if (hashRunning) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Calculate, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        LText("SHA-256")
                    }
                }
                HorizontalDivider()
                hash?.let {
                    LText("SHA-256  $it", modifier = Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall)
                }
                actionError?.let {
                    LText(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                activeEditState?.let { state ->
                    FileEditActions(
                        state = state,
                        onSave = { onSaveEdit(false) },
                        onSaveAs = launchSaveAs,
                    )
                }

                when (target) {
                    is PreviewTarget.Archive -> ArchivePreview(
                        file = target.file,
                        entries = target.entries,
                        currentPath = archivePath,
                        onPathChanged = { archivePath = it },
                        onCopyEntry = onCopyArchiveEntry,
                        onExtract = onExtract,
                        initialDisplayDefaults = archiveDisplayDefaults,
                        onApplyDisplayToAll = onApplyArchiveDisplayToAll,
                    )
                    is PreviewTarget.RemoteArchive -> ArchivePreview(
                        file = target.file,
                        entries = target.entries,
                        currentPath = archivePath,
                        onPathChanged = { archivePath = it },
                        onCopyEntry = null,
                        onExtract = null,
                        initialDisplayDefaults = archiveDisplayDefaults,
                        onApplyDisplayToAll = onApplyArchiveDisplayToAll,
                    )
                    is PreviewTarget.Vault -> VaultPreview(target, onDecrypt)
                    is PreviewTarget.LocalFile,
                    is PreviewTarget.TrashFile,
                    is PreviewTarget.ContentFile,
                    is PreviewTarget.RemoteFile,
                    is PreviewTarget.PrivilegedFile,
                    -> FileContentPreview(
                        source = source,
                        editState = activeEditState,
                        onPrepareEdit = onPrepareEdit,
                        onEditTextChanged = onEditTextChanged,
                        onEditEncodingChanged = onEditEncodingChanged,
                        onEditLineEndingChanged = onEditLineEndingChanged,
                        onSave = { onSaveEdit(false) },
                        onSaveAs = launchSaveAs,
                    )
                }
            }
        }
    }

    activeEditState?.conflict?.let { conflict ->
        EditConflictDialog(
            conflict = conflict,
            workingRevision = editSession?.workingRevision ?: conflict.expected,
            saving = activeEditState.saving,
            canMerge = target is PreviewTarget.RemoteFile && editSession?.usesInternalTextEditor == true && conflict.current != null,
            onMerge = onMergeEditConflict,
            onOverwrite = { onSaveEdit(true) },
            onSaveAs = launchSaveAs,
            onKeepEditing = onDismissEditConflict,
        )
    }
    activeEditState?.saveAsConflict?.let { conflict ->
        EditSaveAsConflictDialog(
            conflict = conflict,
            saving = activeEditState.saving,
            onReplace = { onResolveSaveAsConflict(EditExistingPolicy.REPLACE) },
            onKeepBoth = { onResolveSaveAsConflict(EditExistingPolicy.KEEP_BOTH) },
            onChooseAnother = {
                onDismissSaveAsConflict()
                showSaveAs = true
            },
            onDismiss = onDismissSaveAsConflict,
        )
    }
    if (showSaveAs && editSession != null) {
        EditSaveAsDialog(
            initialFileName = editSession.displayName,
            initialLocalPath = if (target is PreviewTarget.LocalFile) {
                (editSession.origin as? EditOrigin.Local)
                    ?.path
                    ?.let(::File)
                    ?.parentFile
                    ?.absolutePath
                    ?: initialLocalSavePath
            } else {
                initialLocalSavePath
            },
            initialRemotePath = initialRemoteSavePath,
            remoteConnectionName = remoteConnectionName,
            loadLocalDirectory = loadLocalSaveDirectory,
            loadRemoteDirectory = loadRemoteSaveDirectory,
            onSaveLocal = { directory, name ->
                showSaveAs = false
                onSaveEditAsLocal(directory, name)
            },
            onSaveRemote = { directory, name ->
                showSaveAs = false
                onSaveEditAsRemote(directory, name)
            },
            onOpenSystemPicker = {
                showSaveAs = false
                launchSystemSaveAs()
            },
            onDismiss = { showSaveAs = false },
        )
    }
    if (activeEditState?.confirmDiscard == true) {
        AlertDialog(
            onDismissRequest = onKeepEditing,
            title = { LText("Atmesti neišsaugotus pakeitimus?") },
            text = { LText("Redaguojamoje kopijoje yra niekur neišsaugotų pakeitimų. Originalus failas nepakeistas.") },
            confirmButton = {
                Button(onClick = onDiscardEditAndClose, modifier = Modifier.testTag("discard-edit-confirm")) {
                    LText("Atmesti pakeitimus")
                }
            },
            dismissButton = {
                TextButton(onClick = onKeepEditing) { LText("Tęsti redagavimą") }
            },
        )
    }
}

@Composable
private fun FileContentPreview(
    source: PreviewSource,
    editState: FileEditUiState?,
    onPrepareEdit: () -> Unit,
    onEditTextChanged: (String) -> Unit,
    onEditEncodingChanged: (TextEncoding) -> Unit,
    onEditLineEndingChanged: (LineEnding) -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
) {
    val context = LocalContext.current
    val displayedSource = editState?.session
        ?.takeIf { it.sourceKey == source.key }
        ?.let { PreviewSource.Working(source, it) }
        ?: source
    when {
        source.extension == "afvault" -> PropertiesPreview(source, "Šifruotas AF File Manager failas. Jį galima iššifruoti tik atidarius iš vietinės saugyklos.")
        source.kind == EntryKind.IMAGE -> ImagePreview(displayedSource)
        source.extension == "pdf" || source.mimeType(context) == "application/pdf" -> PdfPreview(displayedSource)
        source.kind == EntryKind.VIDEO || source.kind == EntryKind.AUDIO -> MediaPreview(displayedSource)
        source.kind == EntryKind.APK && source.localFile != null -> ApkPreview(requireNotNull(source.localFile))
        EditabilityRules.supportsInternalText(source.name, source.mimeType(context), source.kind) -> TextPreview(
            state = editState,
            onPrepareEdit = onPrepareEdit,
            onTextChanged = onEditTextChanged,
            onEncodingChanged = onEditEncodingChanged,
            onLineEndingChanged = onEditLineEndingChanged,
            onSave = onSave,
            onSaveAs = onSaveAs,
        )
        else -> PropertiesPreview(displayedSource)
    }
}

@Composable
private fun ImagePreview(source: PreviewSource) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val result by produceState<Result<Bitmap>?>(initialValue = null, source.key) {
        value = withContext(Dispatchers.IO) { runCatching { decodeBoundedBitmap(context, source) } }
    }
    val metadata by produceState(initialValue = emptyList<Pair<String, String>>(), source.key, locale) {
        value = withContext(Dispatchers.IO) { runCatching { imageMetadata(context, source, locale) }.getOrDefault(emptyList()) }
    }
    var scale by remember(source.key) { mutableFloatStateOf(PreviewZoomRules.MIN_SCALE) }
    var offset by remember(source.key) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(source.key) { mutableStateOf(IntSize.Zero) }
    @Suppress("DEPRECATION")
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = PreviewZoomRules.clamp(scale * zoomChange, PreviewZoomRules.IMAGE_MAX_SCALE)
        scale = nextScale
        offset = if (nextScale <= PreviewZoomRules.MIN_SCALE) Offset.Zero
        else boundedOffset(offset + panChange, viewportSize, nextScale)
    }
    val updateScale: (Float) -> Unit = { requested ->
        scale = PreviewZoomRules.clamp(requested, PreviewZoomRules.IMAGE_MAX_SCALE)
        offset = if (scale <= PreviewZoomRules.MIN_SCALE) Offset.Zero else boundedOffset(offset, viewportSize, scale)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        ZoomControls(scale, PreviewZoomRules.IMAGE_MAX_SCALE, updateScale)
        when (val loaded = result) {
            null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            else -> {
                val bitmap = loaded.getOrNull()
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .testTag("image-zoom-viewport")
                            .onSizeChanged { viewportSize = it }
                            .transformable(
                                state = transformState,
                                canPan = { scale > PreviewZoomRules.MIN_SCALE },
                                lockRotationOnZoomPan = true,
                            ),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = source.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            contentScale = ContentScale.Fit,
                        )
                    }
                    LText("${bitmap.width} × ${bitmap.height} px", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                } else {
                    PreviewLoadError(requireNotNull(loaded.exceptionOrNull()))
                }
            }
        }
        metadata.forEach { (label, value) -> PropertyRow(label, value) }
    }
}

@Composable
private fun PdfPreview(source: PreviewSource) {
    val context = LocalContext.current
    val documentInfoResult by produceState<Result<PdfDocumentInfo>?>(initialValue = null, source.key) {
        value = withContext(Dispatchers.IO) { runCatching { pdfDocumentInfo(context, source) } }
    }
    var scale by remember(source.key) { mutableFloatStateOf(PreviewZoomRules.MIN_SCALE) }
    var renderScale by remember(source.key) { mutableFloatStateOf(PreviewZoomRules.MIN_SCALE) }
    val horizontalScroll = rememberScrollState()
    @Suppress("DEPRECATION")
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        scale = PreviewZoomRules.clamp(scale * zoomChange, PreviewZoomRules.PDF_MAX_SCALE)
    }
    val updateScale: (Float) -> Unit = { scale = PreviewZoomRules.clamp(it, PreviewZoomRules.PDF_MAX_SCALE) }
    LaunchedEffect(scale) {
        if (scale <= PreviewZoomRules.MIN_SCALE) horizontalScroll.scrollTo(0)
        delay(140)
        renderScale = scale
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ZoomControls(scale, PreviewZoomRules.PDF_MAX_SCALE, updateScale)
        when (val loaded = documentInfoResult) {
            null -> CircularProgressIndicator()
            else -> {
                val documentInfo = loaded.getOrNull()
                if (documentInfo == null) {
                    PreviewLoadError(requireNotNull(loaded.exceptionOrNull()))
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .transformable(
                                state = transformState,
                                canPan = { false },
                                lockRotationOnZoomPan = true,
                            ),
                    ) {
                        val viewportWidthPx = with(LocalDensity.current) { maxWidth.roundToPx().coerceAtLeast(1) }
                        val contentWidth = maxWidth * scale
                        Row(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(contentWidth)
                                    .testTag("pdf-continuous-pages"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                items(count = documentInfo.pageCount, key = { it }) { pageIndex ->
                                    PdfPageItem(
                                        context = context,
                                        source = source,
                                        pageIndex = pageIndex,
                                        pageAspectRatio = documentInfo.pageAspectRatios[pageIndex],
                                        viewportWidthPx = viewportWidthPx,
                                        renderScale = renderScale,
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

@Composable
private fun PdfPageItem(
    context: android.content.Context,
    source: PreviewSource,
    pageIndex: Int,
    pageAspectRatio: Float,
    viewportWidthPx: Int,
    renderScale: Float,
) {
    var bitmap by remember(source.key, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var renderError by remember(source.key, pageIndex) { mutableStateOf<Throwable?>(null) }
    var rendering by remember(source.key, pageIndex) { mutableStateOf(true) }
    LaunchedEffect(source.key, pageIndex, viewportWidthPx, renderScale) {
        rendering = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                pdfRenderPermits.withPermit {
                    renderPdfPage(context, source, pageIndex, viewportWidthPx, renderScale)
                }
            }
        }
        result.onSuccess {
            bitmap = it
            renderError = null
        }.onFailure { renderError = it }
        rendering = false
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        when (val loadedBitmap = bitmap) {
            null -> if (renderError == null || rendering) Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(pageAspectRatio),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() } else PreviewLoadError(requireNotNull(renderError))
            else -> Image(
                    bitmap = loadedBitmap.asImageBitmap(),
                    contentDescription = uiText("PDF puslapis ${pageIndex + 1}"),
                    modifier = Modifier.fillMaxWidth().aspectRatio(loadedBitmap.width.toFloat() / loadedBitmap.height),
                    contentScale = ContentScale.Fit,
                )
        }
    }
}

@Composable
private fun ZoomControls(scale: Float, maximum: Float, onScaleChanged: (Float) -> Unit) {
    val zoomOutDescription = uiText("Atitolinti")
    val zoomInDescription = uiText("Priartinti")
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onScaleChanged(PreviewZoomRules.zoomOut(scale, maximum)) },
            enabled = scale > PreviewZoomRules.MIN_SCALE,
            modifier = Modifier.semantics { contentDescription = zoomOutDescription },
        ) { LText("−", style = MaterialTheme.typography.headlineSmall) }
        LText(
            "${PreviewZoomRules.percent(scale)} %",
            modifier = Modifier.padding(horizontal = 12.dp).testTag("zoom-level"),
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(
            onClick = { onScaleChanged(PreviewZoomRules.zoomIn(scale, maximum)) },
            enabled = scale < maximum,
            modifier = Modifier.semantics { contentDescription = zoomInDescription },
        ) { LText("+", style = MaterialTheme.typography.headlineSmall) }
        TextButton(onClick = { onScaleChanged(PreviewZoomRules.MIN_SCALE) }, enabled = scale > PreviewZoomRules.MIN_SCALE) {
            LText("Atstatyti")
        }
    }
}

@Composable
private fun PreviewLoadError(error: Throwable) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        LText("Failo peržiūros sukurti nepavyko", style = MaterialTheme.typography.titleMedium)
        error.message?.takeIf(String::isNotBlank)?.let {
            LText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaPreview(source: PreviewSource) {
    val context = LocalContext.current
    val metadata by produceState(initialValue = emptyList<Pair<String, String>>(), source.key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    source.localFile?.let { retriever.setDataSource(it.absolutePath) }
                        ?: retriever.setDataSource(context, source.uri(context))
                    listOfNotNull(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { "Trukmė" to "${it / 1_000} s" },
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { "Tipas" to it },
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let { "Bitų sparta" to it },
                    )
                } finally {
                    retriever.release()
                }
            }.getOrDefault(emptyList())
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoURI(source.localFile?.let(Uri::fromFile) ?: source.uri(context))
                    setOnPreparedListener { seekTo(1) }
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            metadata.forEach { (label, value) -> PropertyRow(label, value) }
        }
    }
}

@Composable
private fun FileEditActions(
    state: FileEditUiState,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
) {
    val session = state.session
    val busy = state.preparing || state.saving
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                LText(
                    when {
                        state.preparing -> "Ruošiama saugi redaguojama kopija…"
                        state.saving -> "Išsaugoma ir patikrinama…"
                        state.hasUnsavedChanges -> "Neišsaugoti pakeitimai laikomi privačioje darbinėje kopijoje"
                        state.hasOriginChanges -> "Kopija išsaugota kitur; originalas vis dar skiriasi"
                        session != null -> "Redaguojama naudojant privačią darbinę kopiją"
                        else -> "Redagavimo paruošti nepavyko"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSave,
                    enabled = session != null && state.hasOriginChanges && session.origin.canWrite && !busy,
                    modifier = Modifier.testTag("save-edit-original"),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    LText("Išsaugoti")
                }
                OutlinedButton(
                    onClick = onSaveAs,
                    enabled = session != null && !busy,
                    modifier = Modifier.testTag("save-edit-as"),
                ) {
                    Icon(Icons.Rounded.SaveAs, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    LText("Išsaugoti kaip")
                }
            }
            if (session != null && !session.origin.canWrite) {
                LText(
                    "Originalas skirtas tik skaityti. Išsaugokite redaguotą failą kitoje vietoje.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            state.status?.let { LText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { LText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TextPreview(
    state: FileEditUiState?,
    onPrepareEdit: () -> Unit,
    onTextChanged: (String) -> Unit,
    onEncodingChanged: (TextEncoding) -> Unit,
    onLineEndingChanged: (LineEnding) -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
) {
    LaunchedEffect(state?.sourceKey) {
        if (state?.session == null && state?.preparing != true) onPrepareEdit()
    }
    when {
        state == null || state.preparing -> Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        state.session == null -> LText(
            state.error ?: "Nepavyko paruošti vidinio redaktoriaus",
            modifier = Modifier.fillMaxSize().padding(18.dp),
            color = MaterialTheme.colorScheme.error,
        )
        else -> FullTextEditor(
            sourceKey = state.sourceKey.orEmpty(),
            fileName = state.session.displayName,
            text = state.text.orEmpty(),
            readOnly = state.saving,
            encoding = state.encoding ?: TextEncoding.UTF8,
            lineEnding = state.lineEnding ?: LineEnding.LF,
            onTextChanged = onTextChanged,
            onEncodingChanged = onEncodingChanged,
            onLineEndingChanged = onLineEndingChanged,
            onSave = onSave,
            onSaveAs = onSaveAs,
        )
    }
}

@Composable
private fun EditConflictDialog(
    conflict: EditConflict,
    workingRevision: FileRevision,
    saving: Boolean,
    canMerge: Boolean,
    onMerge: () -> Unit,
    onOverwrite: () -> Unit,
    onSaveAs: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val dateFormat = rememberLocalizedDateTimeFormat(DateFormat.SHORT, DateFormat.SHORT)
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { LText("Originalus failas pasikeitė") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LText("AF File Manager jo neperrašė. Palyginkite versijas ir pasirinkite veiksmą.")
                Text(conflict.originLabel, style = MaterialTheme.typography.bodySmall)
                LText("Originalas atidarymo metu", fontWeight = FontWeight.SemiBold)
                Text(revisionSummary(conflict.expected, dateFormat), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                LText("Jūsų redaguojama kopija", fontWeight = FontWeight.SemiBold)
                Text(revisionSummary(workingRevision, dateFormat), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                LText("Dabartinis originalas", fontWeight = FontWeight.SemiBold)
                if (conflict.current == null) {
                    LText("Originalaus failo nebėra")
                } else {
                    Text(revisionSummary(conflict.current, dateFormat), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                LText(
                    if (canMerge) {
                        "„Sujungti“ palygins originalą, jūsų pakeitimus ir dabartinę serverio versiją. Neaiškios vietos bus aiškiai pažymėtos redaktoriuje."
                    } else {
                        "Perrašymas pakeis dabartinį originalą. „Išsaugoti kaip“ paliks abi versijas."
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = if (canMerge) onMerge else onOverwrite,
                enabled = !saving,
                modifier = Modifier.testTag(if (canMerge) "merge-edit-conflict" else "overwrite-edit-conflict"),
            ) { LText(if (canMerge) "Sujungti pakeitimus" else "Perrašyti originalą") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canMerge) {
                    TextButton(
                        onClick = onOverwrite,
                        enabled = !saving,
                        modifier = Modifier.testTag("overwrite-edit-conflict"),
                    ) { LText("Perrašyti originalą") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onSaveAs, enabled = !saving) { LText("Išsaugoti kaip") }
                    TextButton(onClick = onKeepEditing, enabled = !saving) { LText("Tęsti redagavimą") }
                }
            }
        },
    )
}

@Composable
private fun EditSaveAsConflictDialog(
    conflict: EditSaveAsConflict,
    saving: Boolean,
    onReplace: () -> Unit,
    onKeepBoth: () -> Unit,
    onChooseAnother: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = rememberLocalizedDateTimeFormat()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText("Paskirties failas jau yra") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LText("AF File Manager nieko neperrašė. Pasirinkite, kaip išspręsti konfliktą.")
                Text(conflict.destination.label, style = MaterialTheme.typography.bodySmall)
                PropertyRow("Esamo failo dydis", FileSystemRules.humanBytes(conflict.existing.sizeBytes))
                conflict.existing.modifiedAtMillis?.let { modified ->
                    PropertyRow("Esamas failas pakeistas", dateFormat.format(Date(modified)))
                }
                conflict.existing.sha256?.let { sha ->
                    PropertyRow("Esamo failo SHA-256", sha.take(16) + "…")
                }
                LText("„Perrašyti“ saugiai pakeis esamą failą. „Palikti abu“ parinks laisvą pavadinimą.")
            }
        },
        confirmButton = {
            Button(onClick = onReplace, enabled = !saving) { LText("Perrašyti") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                TextButton(onClick = onKeepBoth, enabled = !saving) { LText("Palikti abu") }
                TextButton(onClick = onChooseAnother, enabled = !saving) { LText("Rinktis kitur") }
            }
        },
    )
}

@Composable
private fun ApkPreview(file: File) {
    val context = LocalContext.current
    val info = remember(file.absolutePath) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES,
        )
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.InstallMobile, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        info?.packageName?.let {
            Text(it, style = MaterialTheme.typography.titleLarge)
        } ?: LText("APK informacija nepasiekiama", style = MaterialTheme.typography.titleLarge)
        info?.let { packageInfo ->
            @Suppress("DEPRECATION")
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
            PropertyRow("Versija", "${packageInfo.versionName ?: "?"} ($versionCode)")
            @Suppress("DEPRECATION")
            val certificate = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            }
            certificate?.let {
                PropertyRow("Parašo SHA-256", MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) })
            }
        }
        LText("Diegimą visada patvirtina Android sistema. Programa negali jo atlikti tyliai.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { installApk(context, file) }) {
            Icon(Icons.Rounded.InstallMobile, contentDescription = null)
            LText("Atidaryti diegimo lange", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ArchivePreview(
    file: FileEntry,
    entries: List<ArchiveEntryInfo>,
    currentPath: String,
    onPathChanged: (String) -> Unit,
    onCopyEntry: ((String) -> Unit)?,
    onExtract: ((FileEntry, CharArray?) -> Unit)?,
    initialDisplayDefaults: DirectoryDisplayDefaults,
    onApplyDisplayToAll: (DirectoryDisplaySettings, SortMode?, SortDirection) -> Unit,
) {
    var askPassword by remember { mutableStateOf(false) }
    var searchVisible by remember(file.absolutePath) { mutableStateOf(false) }
    var searchQuery by remember(file.absolutePath) { mutableStateOf("") }
    var menu by remember(file.absolutePath) { mutableStateOf(false) }
    var showDisplaySettings by remember(file.absolutePath) { mutableStateOf(false) }
    var displaySettings by remember(file.absolutePath) { mutableStateOf(initialDisplayDefaults.settings.copy(showThumbnails = false)) }
    var sortMode by remember(file.absolutePath) { mutableStateOf(initialDisplayDefaults.sortMode) }
    var sortDirection by remember(file.absolutePath) { mutableStateOf(initialDisplayDefaults.sortDirection) }
    LaunchedEffect(currentPath) {
        searchVisible = false
        searchQuery = ""
    }
    val browser = remember(entries) { ArchiveBrowserIndex.from(entries) }
    val children = remember(browser, currentPath) { browser.children(currentPath) }
    var visibleEntries by remember(file.absolutePath, currentPath) { mutableStateOf<List<ArchiveBrowserItem>>(emptyList()) }
    var transforming by remember(file.absolutePath, currentPath) { mutableStateOf(false) }
    LaunchedEffect(children, searchQuery, sortMode, sortDirection) {
        val requestedChildren = children
        val query = searchQuery.trim()
        transforming = true
        visibleEntries = emptyList()
        visibleEntries = withContext(Dispatchers.Default) {
            val ordered = orderArchiveEntries(requestedChildren, sortMode, sortDirection)
            if (query.isEmpty()) ordered else ordered.filter { it.name.contains(query, ignoreCase = true) }
        }
        transforming = false
    }
    val grid = displaySettings.layoutMode == DirectoryLayoutMode.GRID
    val goUp = { if (currentPath.isNotEmpty()) onPathChanged(ArchiveBrowserIndex.parentOf(currentPath)) }
    Column(modifier = Modifier.fillMaxSize()) {
        DirectoryBrowserToolbar(
            title = if (currentPath.isEmpty()) uiText("Archyvo pradžia") else ArchiveBrowserIndex.folderName(currentPath),
            path = if (currentPath.isEmpty()) file.name else "${file.name} / $currentPath",
            backEnabled = currentPath.isNotEmpty(),
            forwardEnabled = false,
            upEnabled = currentPath.isNotEmpty(),
            searchActive = searchVisible,
            grid = grid,
            testTagPrefix = "archive",
            onBack = goUp,
            onForward = {},
            onUp = goUp,
            onToggleSearch = {
                searchVisible = !searchVisible
                if (!searchVisible) searchQuery = ""
            },
            onToggleLayout = {
                displaySettings = displaySettings.copy(
                    layoutMode = if (grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
                )
            },
            onOpenSettings = { showDisplaySettings = true },
        ) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Aplanko veiksmai"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onExtract != null) {
                        DropdownMenuItem(
                            text = { LText("Išpakuoti") },
                            leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                            onClick = { menu = false; askPassword = true },
                        )
                    }
                    if (onCopyEntry != null && currentPath.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { LText("Kopijuoti archyvo aplanką") },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                            onClick = { menu = false; onCopyEntry(currentPath) },
                        )
                    }
                    if (onExtract != null || (onCopyEntry != null && currentPath.isNotEmpty())) HorizontalDivider()
                    DirectoryDisplayMenuItems(
                        grid = grid,
                        includeHidden = false,
                        hiddenFilesAvailable = false,
                        showThumbnails = false,
                        thumbnailsAvailable = false,
                        sortMode = sortMode,
                        sortDirection = sortDirection,
                        displaySettingsTestTag = "archive_display_settings",
                        onToggleHidden = {},
                        onToggleLayout = {
                            displaySettings = displaySettings.copy(
                                layoutMode = if (grid) DirectoryLayoutMode.LIST else DirectoryLayoutMode.GRID,
                            )
                        },
                        onToggleThumbnails = {},
                        onOpenSettings = { showDisplaySettings = true },
                        onSort = { sortMode = it },
                        onDismissMenu = { menu = false },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { LText("Atnaujinti") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
        if (searchVisible) {
            DirectoryQuickSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = { searchVisible = false; searchQuery = "" },
                modifier = Modifier.testTag("directory_search_field_archive"),
            )
        }
        when {
            transforming && visibleEntries.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visibleEntries.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LText(if (children.isEmpty()) "Aplankas tuščias" else "Atitikmenų nerasta")
            }
            grid -> LazyVerticalGrid(
                columns = GridCells.Fixed(displaySettings.gridColumns.coerceIn(1, 6)),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy((8f * displaySettings.spacingScalePercent / 100f).dp),
                verticalArrangement = Arrangement.spacedBy((8f * displaySettings.spacingScalePercent / 100f).dp),
            ) {
                gridItems(visibleEntries, key = { it.path }) { entry ->
                    ArchiveGridItem(entry, displaySettings.iconScalePercent, displaySettings.gridStyle, onPathChanged, onCopyEntry)
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visibleEntries, key = { it.path }) { entry ->
                    ArchiveListItem(
                        entry,
                        displaySettings.iconScalePercent,
                        displaySettings.spacingScalePercent,
                        onPathChanged,
                        onCopyEntry,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (showDisplaySettings) {
        DirectoryDisplaySettingsDialog(
            initialSettings = displaySettings,
            thumbnailsAvailable = false,
            initialSortMode = sortMode,
            initialSortDirection = sortDirection,
            onDismiss = { showDisplaySettings = false },
            onApply = {
                displaySettings = it.copy(showThumbnails = false)
                showDisplaySettings = false
            },
            onApplySort = { mode, direction ->
                sortMode = mode
                sortDirection = direction
            },
            onApplyToAll = { settings, mode, direction ->
                displaySettings = settings.copy(showThumbnails = false)
                mode?.let { sortMode = it }
                sortDirection = direction
                onApplyDisplayToAll(settings, mode, direction)
                showDisplaySettings = false
            },
        )
    }
    if (askPassword && onExtract != null) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { LText("Išpakuoti archyvą") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LText("Slaptažodį palikite tuščią, jei archyvas nešifruotas.")
                    OutlinedTextField(value = password, onValueChange = { password = it }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = { onExtract(file, password.takeIf(String::isNotBlank)?.toCharArray()); password = ""; askPassword = false }) {
                    LText("Išpakuoti")
                }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun ArchiveListItem(
    entry: ArchiveBrowserItem,
    iconScalePercent: Int,
    spacingScalePercent: Int,
    onPathChanged: (String) -> Unit,
    onCopyEntry: ((String) -> Unit)?,
) {
    val iconSize = (32f * iconScalePercent / 100f).dp
    val verticalPadding = (10f * spacingScalePercent / 100f).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.directory) { if (entry.directory) onPathChanged(entry.path) }
            .padding(horizontal = 14.dp, vertical = verticalPadding)
            .testTag("archive-entry-${entry.path}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
            contentDescription = null,
            tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        Text(entry.name, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!entry.directory && entry.sizeBytes >= 0) {
            Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
        }
        onCopyEntry?.let { copy ->
            IconButton(onClick = { copy(entry.path) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Pridėti archyvo įrašą į kopijavimo rinkinį"))
            }
        }
        if (entry.directory) Icon(Icons.Rounded.ChevronRight, contentDescription = uiText("Atidaryti aplanką"))
    }
}

@Composable
private fun ArchiveGridItem(
    entry: ArchiveBrowserItem,
    iconScalePercent: Int,
    gridStyle: DirectoryGridStyle,
    onPathChanged: (String) -> Unit,
    onCopyEntry: ((String) -> Unit)?,
) {
    val iconSize = (64f * iconScalePercent / 100f).dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.directory) { if (entry.directory) onPathChanged(entry.path) }
            .testTag("archive-entry-${entry.path}"),
        tonalElevation = if (gridStyle == DirectoryGridStyle.CLASSIC) 0.dp else 2.dp,
        color = if (gridStyle == DirectoryGridStyle.CLASSIC) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else MaterialTheme.colorScheme.surface,
        shape = if (gridStyle == DirectoryGridStyle.CLASSIC) androidx.compose.foundation.shape.RoundedCornerShape(4.dp) else MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = null,
                tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!entry.directory && entry.sizeBytes >= 0) {
                Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
            onCopyEntry?.let { copy ->
                IconButton(onClick = { copy(entry.path) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Pridėti archyvo įrašą į kopijavimo rinkinį"))
                }
            }
        }
    }
}

private fun orderArchiveEntries(
    entries: List<ArchiveBrowserItem>,
    mode: SortMode,
    direction: SortDirection,
): List<ArchiveBrowserItem> {
    val ascending = when (mode) {
        SortMode.NAME -> compareBy<ArchiveBrowserItem> { it.name.lowercase(Locale.ROOT) }
        SortMode.SIZE -> compareBy<ArchiveBrowserItem> { it.sizeBytes }.thenBy { it.name.lowercase(Locale.ROOT) }
        SortMode.MODIFIED -> compareBy<ArchiveBrowserItem> { it.modifiedAtMillis ?: Long.MAX_VALUE }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        SortMode.TYPE -> compareBy<ArchiveBrowserItem> { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    }
    val comparator = if (direction == SortDirection.ASCENDING) ascending else ascending.reversed()
    val (directories, files) = entries.partition(ArchiveBrowserItem::directory)
    return directories.sortedWith(comparator) + files.sortedWith(comparator)
}

@Composable
private fun VaultPreview(target: PreviewTarget.Vault, onDecrypt: (FileEntry, CharArray) -> Unit) {
    var askPassword by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(target.header.originalName, style = MaterialTheme.typography.titleLarge)
        Text(FileSystemRules.humanBytes(target.header.originalSize))
        LText("AES-256-GCM · PBKDF2-HMAC-SHA256", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { askPassword = true }) { LText("Iššifruoti šalia originalo") }
    }
    if (askPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { LText("Įveskite slaptafrazę") },
            text = { OutlinedTextField(value = password, onValueChange = { password = it }, visualTransformation = PasswordVisualTransformation(), singleLine = true) },
            confirmButton = {
                Button(onClick = { onDecrypt(target.file, password.toCharArray()); password = ""; askPassword = false }, enabled = password.isNotEmpty()) {
                    LText("Iššifruoti")
                }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun PropertiesPreview(source: PreviewSource, note: String? = null) {
    val dateFormat = rememberLocalizedDateTimeFormat()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        note?.let { LText(it, modifier = Modifier.padding(vertical = 10.dp)) }
        PropertyRow("Pavadinimas", source.name)
        PropertyRow("Vieta", source.locationLabel)
        PropertyRow("Dydis", source.sizeBytes?.let(FileSystemRules::humanBytes) ?: uiText("Nežinomas"))
        source.modifiedAtMillis?.let { PropertyRow("Pakeista", dateFormat.format(Date(it))) }
        PropertyRow("Skaitomas", uiText(if (source.isReadable) "Taip" else "Ne"))
        PropertyRow("Įrašomas", uiText(if (source.isWritable) "Taip" else "Ne"))
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        LText(label, modifier = Modifier.weight(0.35f), style = MaterialTheme.typography.labelMedium)
        Text(value, modifier = Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

private fun pdfDocumentInfo(context: android.content.Context, source: PreviewSource): PdfDocumentInfo =
    source.openFileDescriptor(context).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF neturi puslapių" }
            require(renderer.pageCount <= PdfRenderRules.MAX_PAGE_COUNT) { "PDF viršijo ${PdfRenderRules.MAX_PAGE_COUNT} puslapių saugos ribą" }
            PdfDocumentInfo(
                pageAspectRatios = List(renderer.pageCount) { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        page.width.toFloat() / page.height.coerceAtLeast(1)
                    }
                },
            )
        }
    }

private fun renderPdfPage(
    context: android.content.Context,
    source: PreviewSource,
    pageIndex: Int,
    viewportWidthPx: Int,
    zoom: Float,
): Bitmap {
    source.openFileDescriptor(context).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount in 1..PdfRenderRules.MAX_PAGE_COUNT) { "PDF puslapių skaičius nepalaikomas" }
            require(pageIndex in 0 until renderer.pageCount) { "PDF puslapis nepasiekiamas" }
            renderer.openPage(pageIndex).use { page ->
                val (width, height) = PdfRenderRules.pageSizeForViewport(
                    sourceWidth = page.width,
                    sourceHeight = page.height,
                    viewportWidthPx = viewportWidthPx,
                    zoom = zoom,
                )
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}

private fun decodeBoundedBitmap(context: android.content.Context, source: PreviewSource): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val imageSource = source.localFile?.let { ImageDecoder.createSource(it) }
            ?: ImageDecoder.createSource(context.contentResolver, source.uri(context))
        return ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / sample > 2_048 || info.size.height / sample > 2_048) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    source.openFileDescriptor(context).use { descriptor ->
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
    }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Vaizdo perskaityti nepavyko" }
    var sample = 1
    while (bounds.outWidth / sample > 2_048 || bounds.outHeight / sample > 2_048) sample *= 2
    return requireNotNull(source.openFileDescriptor(context).use { descriptor ->
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }) {
        "Vaizdo perskaityti nepavyko"
    }
}

private fun imageMetadata(context: android.content.Context, source: PreviewSource, locale: Locale): List<Pair<String, String>> {
    fun attributes(exif: ExifInterface): List<Pair<String, String>> {
        val takenAt = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL to ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED to ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_DATETIME to ExifInterface.TAG_OFFSET_TIME,
        ).firstNotNullOfOrNull { (dateTag, offsetTag) ->
            parseExifDateTimeMillis(exif.getAttribute(dateTag), exif.getAttribute(offsetTag))
        }
        return listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_MAKE)?.let { "Gamintojas" to it },
            exif.getAttribute(ExifInterface.TAG_MODEL)?.let { "Modelis" to it },
            takenAt?.let {
                "Fotografuota" to com.affilemanager.app.ui.localization.localizedDateTime(it, locale)
            },
            exif.getAttribute(ExifInterface.TAG_ORIENTATION)?.let { "Orientacija" to it },
        )
    }
    return source.localFile?.let { attributes(ExifInterface(it)) }
        ?: source.openFileDescriptor(context).use { attributes(ExifInterface(it.fileDescriptor)) }
}

private fun boundedOffset(candidate: Offset, viewport: IntSize, scale: Float): Offset {
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
}

private fun sha256(context: android.content.Context, source: PreviewSource): String {
    val digest = MessageDigest.getInstance("SHA-256")
    source.openInputStream(context).buffered().use { input ->
        val buffer = ByteArray(256 * 1_024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun shareFile(context: android.content.Context, source: PreviewSource) {
    val uri = source.uri(context)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = source.mimeType(context)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(source.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            context.getString(R.string.share_file_chooser_title),
        ),
    )
}

internal fun openWith(context: android.content.Context, source: PreviewSource) {
    val uri = source.uri(context)
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, source.mimeType(context))
        clipData = ClipData.newRawUri(source.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        viewIntent,
        context.getString(R.string.open_with_chooser_title, source.name),
    ).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
    context.startActivity(chooser)
}

internal fun canEditExternally(context: android.content.Context, source: PreviewSource): Boolean {
    if (!EditabilityRules.mayUseExternalEditor(source.kind, source.extension)) return false
    val uri = runCatching { source.uri(context) }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(uri, source.mimeType(context))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    @Suppress("DEPRECATION")
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .any { it.activityInfo?.packageName != context.packageName }
}

private fun createExternalEditIntent(context: android.content.Context, session: EditSession): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", session.workingFile)
    val editIntent = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(uri, session.mimeType)
        clipData = ClipData.newRawUri(session.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    return Intent.createChooser(
        editIntent,
        context.getString(R.string.edit_with_chooser_title, session.displayName),
    ).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
}

private fun createSaveAsIntent(session: EditSession): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = session.mimeType.ifBlank { "application/octet-stream" }
    putExtra(Intent.EXTRA_TITLE, session.displayName)
}

private fun revisionSummary(revision: FileRevision, dateFormat: DateFormat): String = buildString {
    append(FileSystemRules.humanBytes(revision.sizeBytes))
    revision.modifiedAtMillis?.let {
        append(" · ")
        append(dateFormat.format(Date(it)))
    }
    append(" · SHA-256 ")
    append(revision.sha256.take(12))
    append('…')
}

private fun installApk(context: android.content.Context, file: File) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.files", file),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

private fun entrySummary(context: android.content.Context, source: PreviewSource, dateFormat: DateFormat): String = listOfNotNull(
    source.sizeBytes?.let(FileSystemRules::humanBytes),
    source.modifiedAtMillis?.let { dateFormat.format(Date(it)) },
    source.mimeType(context).takeIf { source.modifiedAtMillis == null },
).joinToString(" · ")
